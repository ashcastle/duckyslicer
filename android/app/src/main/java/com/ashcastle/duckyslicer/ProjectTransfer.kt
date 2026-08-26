package com.ashcastle.duckyslicer

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal enum class ProjectTransferDirection {
    IMPORT,
    EXPORT,
}

internal enum class ProjectExportFormat {
    PROJECT_ARCHIVE,
    THREE_MF,
    STL,
}

internal data class ActiveProjectTransfer(
    val id: Long,
    val direction: ProjectTransferDirection,
    val exportFormat: ProjectExportFormat = ProjectExportFormat.PROJECT_ARCHIVE,
    val requestId: String = "transfer-$id",
)

internal enum class ProjectPersistenceMessage {
    STORAGE_UNAVAILABLE,
    SAVE_FAILED,
}

internal enum class ProjectEditKind {
    MODEL_IMPORT,
    PRIMITIVE,
    AUXILIARY_VOLUME,
    AUTO_LAY,
    ARRANGE,
    SPLIT,
    SPLIT_PARTS,
    CUT,
    SIMPLIFY,
}

internal data class ActiveProjectEdit(
    val id: Long,
    val kind: ProjectEditKind,
    val requestId: String = "project-$id",
    val cancellationRequested: Boolean = false,
)

internal enum class ProjectEditFailure {
    CANCELED,
    GENERIC,
    MODEL_TOO_LARGE,
    NOT_SPLITTABLE,
    NOT_CUTTABLE,
}

internal data class ProjectEditCompletion(
    val id: Long,
    val kind: ProjectEditKind,
    val failure: ProjectEditFailure? = null,
    val sessionChanged: Boolean = false,
    val objectCount: Int = 0,
    val clearedObjectSettings: Boolean = false,
    val triangleCount: Int = 0,
    val displayName: String? = null,
)

internal sealed interface ProjectTransferCompletion {
    val id: Long
    val uri: Uri

    data class Imported(
        override val id: Long,
        override val uri: Uri,
        val document: StoredProjectDocument,
    ) : ProjectTransferCompletion

    data class Exported(
        override val id: Long,
        override val uri: Uri,
        val format: ProjectExportFormat = ProjectExportFormat.PROJECT_ARCHIVE,
    ) : ProjectTransferCompletion

    data class Canceled(
        override val id: Long,
        override val uri: Uri,
        val direction: ProjectTransferDirection,
        val format: ProjectExportFormat = ProjectExportFormat.PROJECT_ARCHIVE,
    ) : ProjectTransferCompletion

    data class Failed(
        override val id: Long,
        override val uri: Uri,
        val direction: ProjectTransferDirection,
        val format: ProjectExportFormat = ProjectExportFormat.PROJECT_ARCHIVE,
    ) : ProjectTransferCompletion
}

internal data class ProjectTransferState(
    val busy: Boolean = false,
    val activeTransferId: Long? = null,
    val activeTransferDirection: ProjectTransferDirection? = null,
    val transferCancellationRequested: Boolean = false,
    val completion: ProjectTransferCompletion? = null,
    val activeEdit: ActiveProjectEdit? = null,
    val editCompletion: ProjectEditCompletion? = null,
    val history: ProjectHistoryState = ProjectHistoryState(),
    val sliceOptions: SliceOptions = SliceOptions(),
    val plateOptions: Map<String, SliceOptions> = mapOf(legacyProjectPlateId() to sliceOptions),
    val linkedDocument: LinkedProjectDocument? = null,
    val linkedDocumentDirty: Boolean = false,
    val restored: Boolean = false,
    val persistenceBlocked: Boolean = false,
    val persistenceMessage: ProjectPersistenceMessage? = null,
    val sessionRevision: Long = 0,
    val persistedRevision: Long = 0,
)

internal fun ProjectTransferState.withStartedTransfer(
    operation: ActiveProjectTransfer,
): ProjectTransferState? {
    if (!restored || busy || completion != null || editCompletion != null) return null
    return copy(
        busy = true,
        activeTransferId = operation.id,
        activeTransferDirection = operation.direction,
        transferCancellationRequested = false,
        persistenceMessage = persistenceMessage.takeUnless {
            operation.direction == ProjectTransferDirection.IMPORT
        },
    )
}

internal fun ProjectTransferState.withTransferCancellationRequested(
    operation: ActiveProjectTransfer,
): ProjectTransferState? {
    if (
        !busy || activeTransferId != operation.id ||
        activeTransferDirection != operation.direction ||
        transferCancellationRequested || completion != null
    ) {
        return null
    }
    return copy(transferCancellationRequested = true)
}

internal fun ProjectTransferState.withCompletedTransfer(
    operation: ActiveProjectTransfer,
    requestedCompletion: ProjectTransferCompletion,
): ProjectTransferState? {
    if (
        !busy || activeTransferId != operation.id ||
        activeTransferDirection != operation.direction ||
        requestedCompletion.id != operation.id || completion != null
    ) {
        return null
    }
    val completionDirection = when (requestedCompletion) {
        is ProjectTransferCompletion.Imported -> ProjectTransferDirection.IMPORT
        is ProjectTransferCompletion.Exported -> ProjectTransferDirection.EXPORT
        is ProjectTransferCompletion.Canceled -> requestedCompletion.direction
        is ProjectTransferCompletion.Failed -> requestedCompletion.direction
    }
    if (completionDirection != operation.direction) return null
    val completionFormat = when (requestedCompletion) {
        is ProjectTransferCompletion.Imported -> null
        is ProjectTransferCompletion.Exported -> requestedCompletion.format
        is ProjectTransferCompletion.Canceled -> requestedCompletion.format
        is ProjectTransferCompletion.Failed -> requestedCompletion.format
    }
    if (
        operation.direction == ProjectTransferDirection.EXPORT &&
        completionFormat != operation.exportFormat
    ) return null
    val successfulCompletion =
        requestedCompletion is ProjectTransferCompletion.Exported ||
            requestedCompletion is ProjectTransferCompletion.Imported
    val completion = if (transferCancellationRequested && successfulCompletion) {
        ProjectTransferCompletion.Canceled(
            operation.id,
            requestedCompletion.uri,
            operation.direction,
            operation.exportFormat,
        )
    } else {
        requestedCompletion
    }
    return copy(
        busy = false,
        activeTransferId = null,
        activeTransferDirection = null,
        transferCancellationRequested = false,
        completion = completion,
    )
}

internal fun ProjectTransferState.withUpdatedSession(
    expectedHistory: ProjectHistoryState,
    nextHistory: ProjectHistoryState,
    expectedOptions: SliceOptions,
    nextOptions: SliceOptions,
): ProjectTransferState? {
    if (
        !restored || busy || completion != null || editCompletion != null ||
        history != expectedHistory || sliceOptions != expectedOptions
    ) {
        return null
    }
    if (nextHistory == history && nextOptions == sliceOptions) return null
    return copy(
        history = nextHistory,
        sliceOptions = nextOptions,
        plateOptions = plateOptions + (nextHistory.current.selectedPlateId to nextOptions),
        linkedDocumentDirty = linkedDocument != null,
        persistenceMessage = ProjectPersistenceMessage.STORAGE_UNAVAILABLE.takeIf {
            persistenceBlocked
        },
        sessionRevision = sessionRevision + 1,
    )
}

internal fun ProjectTransferState.withNewProject(): ProjectTransferState? {
    if (!restored || busy || completion != null || editCompletion != null) return null
    val nextHistory = ProjectHistoryState()
    val nextPlateOptions = mapOf(nextHistory.current.selectedPlateId to sliceOptions)
    if (history == nextHistory && plateOptions == nextPlateOptions && linkedDocument == null) {
        return null
    }
    return copy(
        history = nextHistory,
        plateOptions = nextPlateOptions,
        linkedDocument = null,
        linkedDocumentDirty = false,
        persistenceMessage = ProjectPersistenceMessage.STORAGE_UNAVAILABLE.takeIf {
            persistenceBlocked
        },
        sessionRevision = sessionRevision + 1,
    )
}

internal fun ProjectTransferState.withLinkedDocument(
    document: LinkedProjectDocument?,
): ProjectTransferState = if (linkedDocument == document && !linkedDocumentDirty) {
    this
} else {
    copy(
        linkedDocument = document,
        linkedDocumentDirty = false,
        sessionRevision = sessionRevision + 1,
    )
}

internal fun ProjectTransferState.withStartedEdit(
    operation: ActiveProjectEdit,
): ProjectTransferState? {
    if (!restored || busy || completion != null || editCompletion != null) return null
    return copy(busy = true, activeEdit = operation)
}

internal fun ProjectTransferState.withEditCancellationRequested(
    operationId: Long,
): ProjectTransferState? {
    val operation = activeEdit ?: return null
    if (!busy || operation.id != operationId || operation.cancellationRequested) return null
    return copy(activeEdit = operation.copy(cancellationRequested = true))
}

internal fun ProjectTransferState.withCompletedEdit(
    operation: ActiveProjectEdit,
    expectedHistory: ProjectHistoryState,
    expectedOptions: SliceOptions,
    nextHistory: ProjectHistoryState?,
    completion: ProjectEditCompletion,
    nextOptions: SliceOptions = expectedOptions,
): ProjectTransferState? {
    require(completion.id == operation.id && completion.kind == operation.kind) {
        "Project edit completion does not match its operation"
    }
    require((completion.failure == null) == (nextHistory != null)) {
        "Successful project edits require a resulting history"
    }
    val running = activeEdit
    if (
        !busy || running == null || !running.matches(operation) || history != expectedHistory ||
        sliceOptions != expectedOptions
    ) {
        return null
    }
    val canceled = running.cancellationRequested || completion.failure == ProjectEditFailure.CANCELED
    val appliedHistory = if (canceled) history else nextHistory ?: history
    val appliedOptions = if (canceled || completion.failure != null) sliceOptions else nextOptions
    val changed = appliedHistory != history || appliedOptions != sliceOptions
    return copy(
        busy = false,
        activeEdit = null,
        editCompletion = completion.copy(
            failure = ProjectEditFailure.CANCELED.takeIf { canceled } ?: completion.failure,
            sessionChanged = changed,
        ),
        history = appliedHistory,
        sliceOptions = appliedOptions,
        plateOptions = if (appliedOptions == sliceOptions) {
            plateOptions
        } else {
            plateOptions + (appliedHistory.current.selectedPlateId to appliedOptions)
        },
        linkedDocumentDirty = linkedDocument != null && (linkedDocumentDirty || changed),
        persistenceMessage = ProjectPersistenceMessage.STORAGE_UNAVAILABLE.takeIf {
            changed && persistenceBlocked
        } ?: persistenceMessage,
        sessionRevision = sessionRevision + if (changed) 1 else 0,
    )
}

private fun ActiveProjectEdit.matches(other: ActiveProjectEdit): Boolean =
    id == other.id && kind == other.kind && requestId == other.requestId

private data class ProjectEditBaseline(
    val operation: ActiveProjectEdit,
    val history: ProjectHistoryState,
    val options: SliceOptions,
)

internal fun SliceOptions.withMinimumFilamentSlots(requiredCount: Int): SliceOptions {
    val targetCount = requiredCount.coerceIn(
        1,
        printerProfile.extruderCount.coerceIn(1, MAX_FILAMENT_SLOTS),
    )
    val current = resolvedFilamentSlots()
    if (current.size >= targetCount) return this
    return copy(
        filamentSlots = current + List(targetCount - current.size) { filamentProfile },
        filamentColors = resolvedFilamentColors() +
            List(targetCount - current.size) { offset -> defaultFilamentColor(current.size + offset) },
    )
}

private data class ActiveProjectDocumentTransfer(
    val operation: ActiveProjectTransfer,
    val cancellation: DocumentTransferCancellation,
)

private data class ActiveModelImportTransfer(
    val operation: ActiveProjectEdit,
    val cancellation: DocumentTransferCancellation,
)

private data class FinalProjectOwnerCleanup(
    val transfer: ActiveProjectDocumentTransfer?,
    val modelImport: ActiveModelImportTransfer?,
    val pendingProject: ProjectTransferState?,
)

internal class ProjectTransferViewModel(application: Application) : AndroidViewModel(application) {
    private val projectStore = ProjectStore(application)
    private val supportEvents = SupportEventJournal(application)
    private val mutableState = MutableStateFlow(ProjectTransferState(busy = true))
    val state: StateFlow<ProjectTransferState> = mutableState.asStateFlow()
    private var nextOperationId = 0L
    private var persistenceJob: Job? = null
    private var activeProjectDocumentTransfer: ActiveProjectDocumentTransfer? = null
    private var activeModelImportTransfer: ActiveModelImportTransfer? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val projectRoot = java.io.File(application.filesDir, ProjectStore.PROJECT_DIRECTORY)
                ProjectStore.recoverAbandonedArchiveStaging(projectRoot)
                ProjectStore.recoverAbandonedModelImportStaging(projectRoot)
            }
            val restored = try {
                projectStore.loadProject()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                StoredProjectDocument(storageUnavailable = true)
            }
            if (restored.storageUnavailable) {
                supportEvents.record(SupportEvent.PROJECT_STORAGE_UNAVAILABLE)
            }
            val linkedDocument = restored.linkedDocument?.takeIf { document ->
                application.contentResolver.hasProjectDocumentWritePermission(document.contentUri)
            }
            val droppedDocument = restored.linkedDocument != null && linkedDocument == null
            mutableState.value = ProjectTransferState(
                history = ProjectHistoryState(current = restored.snapshot),
                sliceOptions = restored.activeSliceOptions,
                plateOptions = restored.plateOptions,
                linkedDocument = linkedDocument,
                linkedDocumentDirty = linkedDocument != null && restored.linkedDocumentDirty,
                restored = true,
                persistenceBlocked = restored.storageUnavailable,
                persistenceMessage = ProjectPersistenceMessage.STORAGE_UNAVAILABLE.takeIf {
                    restored.storageUnavailable
                },
                sessionRevision = if (droppedDocument) 1 else 0,
            )
            if (droppedDocument && !restored.storageUnavailable) {
                synchronized(this@ProjectTransferViewModel) {
                    schedulePersistenceLocked(delayMillis = 0L)
                }
            }
        }
    }

    @Synchronized
    fun updateHistory(
        expected: ProjectHistoryState,
        next: ProjectHistoryState,
    ): Boolean {
        val current = mutableState.value
        return updateSessionLocked(
            current = current,
            expectedHistory = expected,
            nextHistory = next,
            expectedOptions = current.sliceOptions,
            nextOptions = current.plateOptions[next.current.selectedPlateId]
                ?: current.sliceOptions,
        )
    }

    @Synchronized
    fun updateSession(
        expectedHistory: ProjectHistoryState,
        nextHistory: ProjectHistoryState,
        expectedOptions: SliceOptions,
        nextOptions: SliceOptions,
    ): Boolean = updateSessionLocked(
        current = mutableState.value,
        expectedHistory = expectedHistory,
        nextHistory = nextHistory,
        expectedOptions = expectedOptions,
        nextOptions = nextOptions,
    )

    @Synchronized
    fun newProject(): Boolean {
        val previous = mutableState.value
        val updated = previous.withNewProject() ?: return false
        mutableState.value = updated
        schedulePersistenceLocked(
            delayMillis = 0L,
            obsoleteModelsAfterSave = previous.history.current,
        )
        return true
    }

    @Synchronized
    fun autoLaySelectedModel(): Boolean {
        val target = mutableState.value.history.current.selectedObject ?: return false
        val baseline = startEditLocked(ProjectEditKind.AUTO_LAY) ?: return false
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val orientation = SlicerProcessClient.autoOrient(
                    target.modelPartVolumes.map { volume -> File(volume.model.localPath) },
                    baseline.operation.requestId,
                )
                val nextHistory = baseline.history.updateTransform(
                    target.id,
                    target.transform.withOrcaOrientation(orientation),
                )
                completeEditSuccess(baseline, nextHistory)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                completeEditFailure(baseline, failure)
            } finally {
                SlicerProcessClient.releaseProjectRequest(baseline.operation.requestId)
            }
        }
        return true
    }

    @Synchronized
    fun arrangeProjectObjects(): Boolean {
        val currentObjects = mutableState.value.history.current.objects
        if (
            currentObjects.size < 2 ||
            currentObjects.sumOf { it.volumes.size } > ProjectStore.MAX_PROJECT_VOLUMES
        ) return false
        val baseline = startEditLocked(ProjectEditKind.ARRANGE) ?: return false
        val targets = baseline.history.current.objects
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val arrangement = OnDeviceSlicer.arrange(
                    targets,
                    baseline.options,
                    requestId = baseline.operation.requestId,
                )
                val nextHistory = baseline.history.applyOrcaArrangement(
                    arrangement,
                    baseline.options.bedSizeX,
                    baseline.options.bedSizeY,
                )
                completeEditSuccess(baseline, nextHistory)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                completeEditFailure(baseline, failure)
            } finally {
                SlicerProcessClient.releaseProjectRequest(baseline.operation.requestId)
            }
        }
        return true
    }

    @Synchronized
    fun splitSelectedModel(): Boolean {
        val current = mutableState.value
        val target = current.history.current.selectedObject ?: return false
        if (target.singleVolumeOrNull == null) return false
        val maximumObjects =
            ProjectStore.MAX_PROJECT_OBJECTS - current.history.current.allObjects.size + 1
        if (maximumObjects < 2) return false
        val baseline = startEditLocked(ProjectEditKind.SPLIT) ?: return false
        viewModelScope.launch(Dispatchers.IO) {
            var installed = emptyList<ProjectObject>()
            try {
                val result = splitProjectObject(
                    target,
                    projectStore,
                    baseline.options,
                    maximumObjects,
                    baseline.operation.requestId,
                )
                installed = result.objects
                val nextHistory = baseline.history.replaceSelected(result.objects)
                if (
                    !completeEditSuccess(
                        baseline,
                        nextHistory,
                        objectCount = result.objects.size,
                        clearedObjectSettings = result.clearedObjectSettings,
                    )
                ) {
                    result.objects.deleteInstalledModels()
                }
            } catch (failure: CancellationException) {
                installed.deleteInstalledModels()
                throw failure
            } catch (failure: Exception) {
                installed.deleteInstalledModels()
                completeEditFailure(baseline, failure)
            } finally {
                SlicerProcessClient.releaseProjectRequest(baseline.operation.requestId)
            }
        }
        return true
    }

    @Synchronized
    fun splitSelectedVolume(sourceVolumeId: String): Boolean {
        val current = mutableState.value
        val target = current.history.current.selectedObject ?: return false
        if (target.volumes.none {
                it.id == sourceVolumeId && it.role == ProjectVolumeRole.MODEL_PART
            }
        ) return false
        val otherVolumeCount = current.history.current.allObjects.sumOf { it.volumes.size } -
            target.volumes.size
        val maximumResultingVolumes = minOf(
            MAX_PROJECT_VOLUMES_PER_OBJECT,
            ProjectStore.MAX_PROJECT_VOLUMES - otherVolumeCount,
        )
        if (maximumResultingVolumes <= target.volumes.size) return false
        val baseline = startEditLocked(ProjectEditKind.SPLIT_PARTS) ?: return false
        viewModelScope.launch(Dispatchers.IO) {
            var installedPaths = emptyList<String>()
            try {
                val result = splitProjectObjectVolume(
                    projectObject = target,
                    sourceVolumeId = sourceVolumeId,
                    projectStore = projectStore,
                    maximumResultingVolumes = maximumResultingVolumes,
                    requestId = baseline.operation.requestId,
                )
                installedPaths = result.installedModelPaths
                val nextHistory = baseline.history.replaceSelected(listOf(result.projectObject))
                if (
                    !completeEditSuccess(
                        baseline,
                        nextHistory,
                        objectCount = result.createdPartCount,
                        clearedObjectSettings = result.clearedSurfacePaint,
                    )
                ) {
                    installedPaths.deleteInstalledModelPaths()
                }
            } catch (failure: CancellationException) {
                installedPaths.deleteInstalledModelPaths()
                throw failure
            } catch (failure: Exception) {
                installedPaths.deleteInstalledModelPaths()
                completeEditFailure(baseline, failure)
            } finally {
                SlicerProcessClient.releaseProjectRequest(baseline.operation.requestId)
            }
        }
        return true
    }

    @Synchronized
    fun cutSelectedModel(heightRatio: Float, placeOnCut: Boolean): Boolean {
        val current = mutableState.value
        val target = current.history.current.selectedObject ?: return false
        if (target.singleVolumeOrNull == null) return false
        val maximumObjects =
            ProjectStore.MAX_PROJECT_OBJECTS - current.history.current.allObjects.size + 1
        if (maximumObjects < 2 || !heightRatio.isFinite() || heightRatio !in 0.02f..0.98f) {
            return false
        }
        val baseline = startEditLocked(ProjectEditKind.CUT) ?: return false
        viewModelScope.launch(Dispatchers.IO) {
            var installed = emptyList<ProjectObject>()
            try {
                val result = cutProjectObject(
                    target,
                    projectStore,
                    baseline.options,
                    heightRatio,
                    placeOnCut,
                    maximumObjects,
                    baseline.operation.requestId,
                )
                installed = result.objects
                val nextHistory = baseline.history.replaceSelected(result.objects)
                if (
                    !completeEditSuccess(
                        baseline,
                        nextHistory,
                        objectCount = result.objects.size,
                        clearedObjectSettings = result.clearedObjectSettings,
                    )
                ) {
                    result.objects.deleteInstalledModels()
                }
            } catch (failure: CancellationException) {
                installed.deleteInstalledModels()
                throw failure
            } catch (failure: Exception) {
                installed.deleteInstalledModels()
                completeEditFailure(baseline, failure)
            } finally {
                SlicerProcessClient.releaseProjectRequest(baseline.operation.requestId)
            }
        }
        return true
    }

    @Synchronized
    fun simplifySelectedModel(keepPercent: Int): Boolean {
        val target = mutableState.value.history.current.selectedObject ?: return false
        val targetVolume = target.singleVolumeOrNull ?: return false
        if (
            targetVolume.model.triangles < MINIMUM_SIMPLIFIABLE_TRIANGLES ||
            keepPercent !in MINIMUM_SIMPLIFY_KEEP_PERCENT..MAXIMUM_SIMPLIFY_KEEP_PERCENT
        ) {
            return false
        }
        val baseline = startEditLocked(ProjectEditKind.SIMPLIFY) ?: return false
        viewModelScope.launch(Dispatchers.IO) {
            var installed: ProjectObject? = null
            try {
                val result = simplifyProjectObject(
                    target,
                    projectStore,
                    keepPercent,
                    baseline.operation.requestId,
                )
                installed = result.projectObject
                val nextHistory = baseline.history.replaceSelected(listOf(result.projectObject))
                if (
                    !completeEditSuccess(
                        baseline,
                        nextHistory,
                        clearedObjectSettings = result.clearedSurfacePaint,
                        triangleCount = result.projectObject.model.triangles,
                    )
                ) {
                    listOf(result.projectObject).deleteInstalledModels()
                }
            } catch (failure: CancellationException) {
                installed?.let { listOf(it).deleteInstalledModels() }
                throw failure
            } catch (failure: Exception) {
                installed?.let { listOf(it).deleteInstalledModels() }
                completeEditFailure(baseline, failure)
            } finally {
                SlicerProcessClient.releaseProjectRequest(baseline.operation.requestId)
            }
        }
        return true
    }

    @Synchronized
    fun createPrimitive(
        primitive: OrcaPrimitive,
        sizeMm: Float,
        displayName: String,
    ): Boolean {
        val snapshot = mutableState.value.history.current
        val objectCount = snapshot.objects.size
        if (
            snapshot.allObjects.size >= ProjectStore.MAX_PROJECT_OBJECTS || !sizeMm.isFinite() ||
            sizeMm !in MIN_PRIMITIVE_SIZE_MM..MAX_PRIMITIVE_SIZE_MM
        ) {
            return false
        }
        val baseline = startEditLocked(ProjectEditKind.PRIMITIVE) ?: return false
        viewModelScope.launch(Dispatchers.IO) {
            var installed = emptyList<ProjectObject>()
            try {
                val created = createOrcaPrimitive(
                    primitive,
                    sizeMm,
                    displayName,
                    projectStore,
                    baseline.operation.requestId,
                )
                installed = listOf(created)
                val distance = ((objectCount + 1) / 2) * 24f
                val offset = when {
                    objectCount == 0 -> 0f
                    objectCount % 2 == 1 -> distance
                    else -> -distance
                }
                val placed = created.copy(
                    transform = created.transform.copy(offsetXmm = offset),
                )
                val nextHistory = baseline.history.addAll(listOf(placed))
                if (
                    !completeEditSuccess(
                        baseline,
                        nextHistory,
                        displayName = displayName,
                    )
                ) {
                    listOf(created).deleteInstalledModels()
                }
            } catch (failure: CancellationException) {
                installed.deleteInstalledModels()
                throw failure
            } catch (failure: Exception) {
                installed.deleteInstalledModels()
                completeEditFailure(baseline, failure)
            } finally {
                SlicerProcessClient.releaseProjectRequest(baseline.operation.requestId)
            }
        }
        return true
    }

    @Synchronized
    fun createAuxiliaryPrimitive(
        draft: OrcaAuxiliaryPrimitiveDraft,
        displayName: String,
    ): Boolean {
        val snapshot = mutableState.value.history.current
        val target = snapshot.selectedObject ?: return false
        if (
            target.volumes.size >= MAX_PROJECT_VOLUMES_PER_OBJECT ||
            snapshot.allObjects.sumOf { it.volumes.size } >= ProjectStore.MAX_PROJECT_VOLUMES
        ) {
            return false
        }
        val baseline = startEditLocked(ProjectEditKind.PRIMITIVE) ?: return false
        viewModelScope.launch(Dispatchers.IO) {
            var installed: File? = null
            try {
                val created = createOrcaAuxiliaryPrimitive(
                    draft = draft,
                    displayName = displayName,
                    target = target,
                    projectStore = projectStore,
                    requestId = baseline.operation.requestId,
                )
                installed = File(created.model.localPath)
                val nextHistory = baseline.history.addAuxiliaryVolumeToSelected(created)
                if (
                    !completeEditSuccess(
                        baseline,
                        nextHistory,
                        displayName = displayName,
                    )
                ) {
                    installed.delete()
                }
            } catch (failure: CancellationException) {
                installed?.delete()
                throw failure
            } catch (failure: Exception) {
                installed?.delete()
                completeEditFailure(baseline, failure)
            } finally {
                SlicerProcessClient.releaseProjectRequest(baseline.operation.requestId)
            }
        }
        return true
    }

    @Synchronized
    fun editAuxiliaryVolume(
        draft: OrcaAuxiliaryVolumeEditDraft,
        displayName: String,
    ): Boolean {
        val target = mutableState.value.history.current.selectedObject ?: return false
        val source = target.volumes.firstOrNull { it.id == draft.volumeId } ?: return false
        if (source.role == ProjectVolumeRole.MODEL_PART) return false
        val baseline = startEditLocked(ProjectEditKind.AUXILIARY_VOLUME) ?: return false
        viewModelScope.launch(Dispatchers.IO) {
            var installed: File? = null
            try {
                val edited = editOrcaAuxiliaryVolume(
                    draft = draft,
                    target = target,
                    projectStore = projectStore,
                    requestId = baseline.operation.requestId,
                )
                installed = File(edited.model.localPath)
                val nextHistory = baseline.history.replaceSelectedAuxiliaryVolume(
                    draft.volumeId,
                    edited,
                )
                if (
                    !completeEditSuccess(
                        baseline,
                        nextHistory,
                        displayName = displayName,
                    )
                ) {
                    installed.delete()
                }
            } catch (failure: CancellationException) {
                installed?.delete()
                throw failure
            } catch (failure: Exception) {
                installed?.delete()
                completeEditFailure(baseline, failure)
            } finally {
                SlicerProcessClient.releaseProjectRequest(baseline.operation.requestId)
            }
        }
        return true
    }

    @Synchronized
    fun importModels(uri: Uri): Boolean = importModels(listOf(uri))

    @Synchronized
    fun importModels(uris: List<Uri>): Boolean {
        val requestedUris = uris.toList()
        if (
            requestedUris.isEmpty() ||
            requestedUris.any { it.scheme != ContentResolver.SCHEME_CONTENT }
        ) return false
        val snapshot = mutableState.value.history.current
        val objectCount = snapshot.objects.size
        val totalObjectCount = snapshot.allObjects.size
        val totalVolumeCount = snapshot.allObjects.sumOf { it.volumes.size }
        if (
            totalObjectCount >= ProjectStore.MAX_PROJECT_OBJECTS ||
            requestedUris.size > ProjectStore.MAX_PROJECT_OBJECTS - totalObjectCount
        ) return false
        val baseline = startEditLocked(ProjectEditKind.MODEL_IMPORT) ?: return false
        val cancellation = DocumentTransferCancellation()
        activeModelImportTransfer = ActiveModelImportTransfer(baseline.operation, cancellation)
        viewModelScope.launch(Dispatchers.IO) {
            var installed = emptyList<ProjectObject>()
            try {
                val placed = ArrayList<ProjectObject>()
                requestedUris.forEach { uri ->
                    val imported = importOrcaModels(
                        getApplication<Application>(),
                        uri,
                        projectStore,
                        baseline.options,
                        baseline.operation.requestId,
                        cancellation,
                    )
                    require(imported.isNotEmpty()) { "Model document contains no objects" }
                    installed = installed + imported
                    require(
                        totalObjectCount + installed.size <= ProjectStore.MAX_PROJECT_OBJECTS
                    ) { "Project has too many imported objects" }
                    require(
                        totalVolumeCount + installed.sumOf { it.volumes.size } <=
                            ProjectStore.MAX_PROJECT_VOLUMES
                    ) { "Project has too many imported volumes" }
                    val groupPosition = objectCount + placed.size
                    val distance = ((groupPosition + 1) / 2) * 24f
                    val offset = when {
                        groupPosition == 0 -> 0f
                        groupPosition % 2 == 1 -> distance
                        else -> -distance
                    }
                    placed += imported.map { projectObject ->
                        projectObject.copy(
                            transform = projectObject.transform.copy(
                                offsetXmm = projectObject.transform.offsetXmm + offset,
                            ),
                        )
                    }
                }
                val nextHistory = baseline.history.addAll(placed)
                val requiredFilamentSlots = placed
                    .flatMap(ProjectObject::volumes)
                    .maxOfOrNull(ProjectVolume::filamentSlot)
                    ?.plus(1)
                    ?: 1
                val nextOptions = baseline.options.withMinimumFilamentSlots(requiredFilamentSlots)
                cancellation.complete()
                if (
                    !completeEditSuccess(
                        baseline,
                        nextHistory,
                        nextOptions = nextOptions,
                        objectCount = placed.size,
                    )
                ) {
                    installed.deleteInstalledModels()
                }
            } catch (failure: CancellationException) {
                installed.deleteInstalledModels()
                if (cancellation.wasRequested()) {
                    completeEditFailure(baseline, ProjectEditCancelledException())
                }
                throw failure
            } catch (failure: Exception) {
                installed.deleteInstalledModels()
                completeEditFailure(
                    baseline,
                    if (
                        cancellation.wasRequested() ||
                        failure is DocumentTransferCancelledException
                    ) {
                        ProjectEditCancelledException()
                    } else {
                        failure
                    },
                )
            } finally {
                synchronized(this@ProjectTransferViewModel) {
                    if (activeModelImportTransfer?.operation?.matches(baseline.operation) == true) {
                        activeModelImportTransfer = null
                    }
                }
                cancellation.close()
                SlicerProcessClient.releaseProjectRequest(baseline.operation.requestId)
            }
        }
        return true
    }

    @Synchronized
    fun importProject(uri: Uri): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        val operation = ActiveProjectTransfer(
            id = ++nextOperationId,
            direction = ProjectTransferDirection.IMPORT,
        )
        val started = mutableState.value.withStartedTransfer(operation) ?: return false
        val pendingPersistence = persistenceJob
        pendingPersistence?.cancel()
        persistenceJob = null
        val cancellation = DocumentTransferCancellation()
        activeProjectDocumentTransfer = ActiveProjectDocumentTransfer(operation, cancellation)
        mutableState.value = started
        viewModelScope.launch(Dispatchers.IO) {
            pendingPersistence?.join()
            val application = getApplication<Application>()
            var linkedDocument: LinkedProjectDocument? = null
            val completion = try {
                cancellation.throwIfRequested()
                val provider = requireNotNull(
                    application.contentResolver.acquireContentProviderClient(uri),
                ) { "input_provider_unavailable" }
                val descriptor = requireNotNull(
                    provider.use {
                        provider.openAssetFile(uri, "r", cancellation.providerSignal)
                    },
                ) { "input_unavailable" }
                val document = descriptor.use {
                    descriptor.createInputStream().use { input ->
                        cancellation.attachInput(input)
                        try {
                            projectStore.importArchive(
                                input,
                                cancellation::throwIfRequested,
                                cancellation::complete,
                            )
                        } finally {
                            cancellation.detachInput(input)
                        }
                    }
                }
                linkedDocument = application.contentResolver
                    .takeIf { resolver -> resolver.retainProjectDocumentWritePermission(uri) }
                    ?.linkedProjectDocument(uri)
                ProjectTransferCompletion.Imported(operation.id, uri, document)
            } catch (_: CancellationException) {
                cancellation.cancel()
                ProjectTransferCompletion.Canceled(operation.id, uri, operation.direction)
            } catch (failure: Exception) {
                if (
                    cancellation.wasRequested() ||
                    failure is DocumentTransferCancelledException
                ) {
                    ProjectTransferCompletion.Canceled(operation.id, uri, operation.direction)
                } else {
                    ProjectTransferCompletion.Failed(
                        operation.id,
                        uri,
                        operation.direction,
                    )
                }
            } finally {
                cancellation.close()
            }
            synchronized(this@ProjectTransferViewModel) {
                if (activeProjectDocumentTransfer?.operation == operation) {
                    activeProjectDocumentTransfer = null
                }
                val current = mutableState.value
                val settled = current.withCompletedTransfer(operation, completion)
                    ?: return@synchronized
                val updated = when (completion) {
                    is ProjectTransferCompletion.Imported -> settled.copy(
                        history = ProjectHistoryState(current = completion.document.snapshot),
                        sliceOptions = completion.document.activeSliceOptions,
                        plateOptions = completion.document.plateOptions,
                        restored = true,
                        persistenceBlocked = false,
                        persistenceMessage = null,
                        linkedDocument = null,
                        linkedDocumentDirty = false,
                        sessionRevision = current.sessionRevision + 1,
                        persistedRevision = current.sessionRevision + 1,
                    ).withLinkedDocument(linkedDocument)
                    else -> settled
                }
                mutableState.value = updated
                if (
                    completion !is ProjectTransferCompletion.Imported ||
                    updated.hasUnpersistedSession()
                ) {
                    schedulePersistenceLocked(allowPendingCompletion = true)
                }
            }
        }
        return true
    }

    @Synchronized
    fun exportProject(
        uri: Uri,
        snapshot: ProjectSnapshot,
        plateOptions: Map<String, SliceOptions>,
        deleteFailedDocument: Boolean = true,
    ): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        val operation = ActiveProjectTransfer(
            id = ++nextOperationId,
            direction = ProjectTransferDirection.EXPORT,
        )
        val started = mutableState.value.withStartedTransfer(operation) ?: return false
        val cancellation = DocumentTransferCancellation()
        activeProjectDocumentTransfer = ActiveProjectDocumentTransfer(operation, cancellation)
        mutableState.value = started
        viewModelScope.launch(Dispatchers.IO) {
            val application = getApplication<Application>()
            var linkedDocument: LinkedProjectDocument? = null
            val completion = try {
                val descriptor = requireNotNull(
                    application.contentResolver.openAssetFileDescriptor(
                        uri,
                        "wt",
                        cancellation.providerSignal,
                    ),
                ) { "output_unavailable" }
                descriptor.use {
                    descriptor.createOutputStream().use { output ->
                        cancellation.attachOutput(output)
                        try {
                            projectStore.exportArchive(
                                snapshot,
                                plateOptions,
                                output,
                                cancellation::throwIfRequested,
                            )
                            output.flush()
                            cancellation.complete()
                        } finally {
                            cancellation.detachOutput(output)
                        }
                    }
                }
                linkedDocument = application.contentResolver
                    .takeIf { resolver -> resolver.retainProjectDocumentWritePermission(uri) }
                    ?.linkedProjectDocument(uri)
                ProjectTransferCompletion.Exported(operation.id, uri)
            } catch (_: CancellationException) {
                cancellation.cancel()
                if (deleteFailedDocument) deleteFailedCreatedDocument(application, uri)
                ProjectTransferCompletion.Canceled(operation.id, uri, operation.direction)
            } catch (failure: Exception) {
                if (
                    cancellation.wasRequested() ||
                    failure is DocumentTransferCancelledException
                ) {
                    if (deleteFailedDocument) deleteFailedCreatedDocument(application, uri)
                    ProjectTransferCompletion.Canceled(operation.id, uri, operation.direction)
                } else {
                    if (deleteFailedDocument) deleteFailedCreatedDocument(application, uri)
                    supportEvents.record(SupportEvent.PROJECT_ARCHIVE_EXPORT_FAILED)
                    ProjectTransferCompletion.Failed(
                        operation.id,
                        uri,
                        operation.direction,
                    )
                }
            } finally {
                cancellation.close()
            }
            synchronized(this@ProjectTransferViewModel) {
                if (activeProjectDocumentTransfer?.operation == operation) {
                    activeProjectDocumentTransfer = null
                }
                val settled = mutableState.value.withCompletedTransfer(operation, completion)
                    ?: return@synchronized
                mutableState.value = if (completion is ProjectTransferCompletion.Exported) {
                    settled.withLinkedDocument(linkedDocument)
                } else {
                    settled
                }
                schedulePersistenceLocked(allowPendingCompletion = true)
            }
        }
        return true
    }

    @Synchronized
    fun saveLinkedProject(
        snapshot: ProjectSnapshot,
        plateOptions: Map<String, SliceOptions>,
    ): Boolean {
        val current = mutableState.value
        val document = current.linkedDocument ?: return false
        val resolver = getApplication<Application>().contentResolver
        if (!resolver.hasProjectDocumentWritePermission(document.contentUri)) {
            mutableState.value = current.withLinkedDocument(null)
            schedulePersistenceLocked(delayMillis = 0L)
            return false
        }
        return exportProject(
            uri = document.contentUri,
            snapshot = snapshot,
            plateOptions = plateOptions,
            deleteFailedDocument = false,
        )
    }

    @Synchronized
    fun exportThreeMf(
        uri: Uri,
        snapshot: ProjectSnapshot,
        options: SliceOptions,
    ): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || snapshot.objects.isEmpty()) return false
        val operation = ActiveProjectTransfer(
            id = ++nextOperationId,
            direction = ProjectTransferDirection.EXPORT,
            exportFormat = ProjectExportFormat.THREE_MF,
        )
        val started = mutableState.value.withStartedTransfer(operation) ?: return false
        val cancellation = DocumentTransferCancellation()
        activeProjectDocumentTransfer = ActiveProjectDocumentTransfer(operation, cancellation)
        mutableState.value = started
        viewModelScope.launch(Dispatchers.IO) {
            val application = getApplication<Application>()
            val stagingDirectory = File(
                application.cacheDir,
                "$THREE_MF_EXPORT_DIRECTORY_PREFIX${UUID.randomUUID()}",
            )
            val stagedOutput = File(stagingDirectory, THREE_MF_EXPORT_FILE_NAME)
            val completion = try {
                require(stagingDirectory.mkdir()) { "export_staging_unavailable" }
                cancellation.throwIfRequested()
                OnDeviceSlicer.exportThreeMf(
                    objects = snapshot.objects,
                    options = options,
                    output = stagedOutput,
                    requestId = operation.requestId,
                    cancellationRequested = cancellation::wasRequested,
                )
                cancellation.throwIfRequested()
                val descriptor = requireNotNull(
                    application.contentResolver.openAssetFileDescriptor(
                        uri,
                        "wt",
                        cancellation.providerSignal,
                    ),
                ) { "output_unavailable" }
                descriptor.use {
                    stagedOutput.inputStream().use { input ->
                        descriptor.createOutputStream().use { output ->
                            cancellation.attachInput(input)
                            cancellation.attachOutput(output)
                            try {
                                copyThreeMfToDocument(input, output, cancellation::throwIfRequested)
                                output.flush()
                                cancellation.complete()
                            } finally {
                                cancellation.detachInput(input)
                                cancellation.detachOutput(output)
                            }
                        }
                    }
                }
                ProjectTransferCompletion.Exported(
                    operation.id,
                    uri,
                    operation.exportFormat,
                )
            } catch (_: CancellationException) {
                cancellation.cancel()
                deleteFailedCreatedDocument(application, uri)
                ProjectTransferCompletion.Canceled(
                    operation.id,
                    uri,
                    operation.direction,
                    operation.exportFormat,
                )
            } catch (failure: Exception) {
                deleteFailedCreatedDocument(application, uri)
                if (
                    cancellation.wasRequested() || failure is DocumentTransferCancelledException ||
                    failure is ProjectEditCancelledException || failure is SlicingCancelledException
                ) {
                    ProjectTransferCompletion.Canceled(
                        operation.id,
                        uri,
                        operation.direction,
                        operation.exportFormat,
                    )
                } else {
                    ProjectTransferCompletion.Failed(
                        operation.id,
                        uri,
                        operation.direction,
                        operation.exportFormat,
                    )
                }
            } finally {
                stagingDirectory.deleteRecursively()
                cancellation.close()
                SlicerProcessClient.releaseProjectRequest(operation.requestId)
            }
            synchronized(this@ProjectTransferViewModel) {
                if (activeProjectDocumentTransfer?.operation == operation) {
                    activeProjectDocumentTransfer = null
                }
                val settled = mutableState.value.withCompletedTransfer(operation, completion)
                    ?: return@synchronized
                mutableState.value = settled
                schedulePersistenceLocked(allowPendingCompletion = true)
            }
        }
        return true
    }

    @Synchronized
    fun exportStl(
        uri: Uri,
        projectObject: ProjectObject,
        options: SliceOptions,
    ): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        val operation = ActiveProjectTransfer(
            id = ++nextOperationId,
            direction = ProjectTransferDirection.EXPORT,
            exportFormat = ProjectExportFormat.STL,
        )
        val started = mutableState.value.withStartedTransfer(operation) ?: return false
        val cancellation = DocumentTransferCancellation()
        activeProjectDocumentTransfer = ActiveProjectDocumentTransfer(operation, cancellation)
        mutableState.value = started
        viewModelScope.launch(Dispatchers.IO) {
            val application = getApplication<Application>()
            val stagingDirectory = File(
                application.cacheDir,
                "$STL_EXPORT_DIRECTORY_PREFIX${UUID.randomUUID()}",
            )
            val completion = try {
                require(stagingDirectory.mkdir()) { "export_staging_unavailable" }
                cancellation.throwIfRequested()
                val stagedOutput = OnDeviceSlicer.exportStl(
                    projectObject = projectObject,
                    options = options,
                    stagingDirectory = stagingDirectory,
                    requestId = operation.requestId,
                    cancellationRequested = cancellation::wasRequested,
                )
                cancellation.throwIfRequested()
                validateBinaryStl(stagedOutput)
                val descriptor = requireNotNull(
                    application.contentResolver.openAssetFileDescriptor(
                        uri,
                        "wt",
                        cancellation.providerSignal,
                    ),
                ) { "output_unavailable" }
                descriptor.use {
                    stagedOutput.inputStream().use { input ->
                        descriptor.createOutputStream().use { output ->
                            cancellation.attachInput(input)
                            cancellation.attachOutput(output)
                            try {
                                copyStlToDocument(input, output, cancellation::throwIfRequested)
                                output.flush()
                                cancellation.complete()
                            } finally {
                                cancellation.detachInput(input)
                                cancellation.detachOutput(output)
                            }
                        }
                    }
                }
                ProjectTransferCompletion.Exported(operation.id, uri, operation.exportFormat)
            } catch (_: CancellationException) {
                cancellation.cancel()
                deleteFailedCreatedDocument(application, uri)
                ProjectTransferCompletion.Canceled(
                    operation.id,
                    uri,
                    operation.direction,
                    operation.exportFormat,
                )
            } catch (failure: Exception) {
                deleteFailedCreatedDocument(application, uri)
                if (
                    cancellation.wasRequested() || failure is DocumentTransferCancelledException ||
                    failure is ProjectEditCancelledException || failure is SlicingCancelledException
                ) {
                    ProjectTransferCompletion.Canceled(
                        operation.id,
                        uri,
                        operation.direction,
                        operation.exportFormat,
                    )
                } else {
                    ProjectTransferCompletion.Failed(
                        operation.id,
                        uri,
                        operation.direction,
                        operation.exportFormat,
                    )
                }
            } finally {
                stagingDirectory.deleteRecursively()
                cancellation.close()
                SlicerProcessClient.releaseProjectRequest(operation.requestId)
            }
            synchronized(this@ProjectTransferViewModel) {
                if (activeProjectDocumentTransfer?.operation == operation) {
                    activeProjectDocumentTransfer = null
                }
                val settled = mutableState.value.withCompletedTransfer(operation, completion)
                    ?: return@synchronized
                mutableState.value = settled
                schedulePersistenceLocked(allowPendingCompletion = true)
            }
        }
        return true
    }

    fun cancelProjectExport(): Boolean {
        return cancelProjectTransfer(ProjectTransferDirection.EXPORT)
    }

    fun cancelProjectImport(): Boolean {
        return cancelProjectTransfer(ProjectTransferDirection.IMPORT)
    }

    private fun cancelProjectTransfer(direction: ProjectTransferDirection): Boolean {
        val active = synchronized(this) {
            activeProjectDocumentTransfer?.takeIf { it.operation.direction == direction }
        } ?: return false
        if (!active.cancellation.cancel()) return false
        if (active.operation.exportFormat != ProjectExportFormat.PROJECT_ARCHIVE) {
            SlicerProcessClient.cancelProjectRequestAsync(active.operation.requestId)
        }
        synchronized(this) {
            mutableState.value.withTransferCancellationRequested(active.operation)
                ?.let { canceling -> mutableState.value = canceling }
        }
        return true
    }

    @Synchronized
    fun consumeCompletion(operationId: Long) {
        val current = mutableState.value
        if (current.completion?.id != operationId) return
        mutableState.value = current.copy(completion = null)
    }

    @Synchronized
    fun consumeEditCompletion(operationId: Long) {
        val current = mutableState.value
        if (current.editCompletion?.id != operationId) return
        mutableState.value = current.copy(editCompletion = null)
    }

    fun cancelActiveEdit(): Boolean {
        val (operation, modelImport) = synchronized(this) {
            val operation = mutableState.value.activeEdit ?: return false
            val updated = mutableState.value.withEditCancellationRequested(operation.id) ?: return false
            mutableState.value = updated
            operation to activeModelImportTransfer?.takeIf {
                it.operation.matches(operation)
            }
        }
        val providerCanceled = modelImport?.cancellation?.cancel() ?: false
        val nativeCanceled = SlicerProcessClient.cancelProjectRequestAsync(operation.requestId)
        return providerCanceled || nativeCanceled
    }

    @Synchronized
    internal fun startCancellationProbeForTest(onWorkerStarted: () -> Unit): Boolean {
        check(BuildConfig.DEBUG) { "Project cancellation probe is available only in debug builds" }
        val baseline = startEditLocked(ProjectEditKind.AUTO_LAY) ?: return false
        viewModelScope.launch(Dispatchers.IO) {
            try {
                SlicerProcessClient.cancellationProbeForTest(
                    onStarted = onWorkerStarted,
                    requestId = baseline.operation.requestId,
                )
                completeEditFailure(
                    baseline,
                    IllegalStateException("Cancellation probe completed unexpectedly"),
                )
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                completeEditFailure(baseline, failure)
            } finally {
                SlicerProcessClient.releaseProjectRequest(baseline.operation.requestId)
            }
        }
        return true
    }

    private fun startEditLocked(kind: ProjectEditKind): ProjectEditBaseline? {
        val current = mutableState.value
        val operation = ActiveProjectEdit(
            id = ++nextOperationId,
            kind = kind,
            requestId = UUID.randomUUID().toString(),
        )
        val started = current.withStartedEdit(operation) ?: return null
        mutableState.value = started
        return ProjectEditBaseline(operation, current.history, current.sliceOptions)
    }

    private fun completeEditSuccess(
        baseline: ProjectEditBaseline,
        nextHistory: ProjectHistoryState,
        nextOptions: SliceOptions = baseline.options,
        objectCount: Int = 0,
        clearedObjectSettings: Boolean = false,
        triangleCount: Int = 0,
        displayName: String? = null,
    ): Boolean = synchronized(this) {
        val completion = ProjectEditCompletion(
            id = baseline.operation.id,
            kind = baseline.operation.kind,
            objectCount = objectCount,
            clearedObjectSettings = clearedObjectSettings,
            triangleCount = triangleCount,
            displayName = displayName,
        )
        val updated = mutableState.value.withCompletedEdit(
            operation = baseline.operation,
            expectedHistory = baseline.history,
            expectedOptions = baseline.options,
            nextHistory = nextHistory,
            completion = completion,
            nextOptions = nextOptions,
        ) ?: return@synchronized false
        mutableState.value = updated
        schedulePersistenceLocked(allowPendingCompletion = true)
        updated.editCompletion?.failure != ProjectEditFailure.CANCELED
    }

    private fun completeEditFailure(
        baseline: ProjectEditBaseline,
        failure: Exception,
    ) {
        val reason = when (failure) {
            is ProjectEditCancelledException, is SlicingCancelledException ->
                ProjectEditFailure.CANCELED
            is ModelTooLargeException -> ProjectEditFailure.MODEL_TOO_LARGE
            is ModelNotSplittableException -> ProjectEditFailure.NOT_SPLITTABLE
            is ModelNotCuttableException -> ProjectEditFailure.NOT_CUTTABLE
            else -> ProjectEditFailure.GENERIC
        }
        val completion = ProjectEditCompletion(
            id = baseline.operation.id,
            kind = baseline.operation.kind,
            failure = reason,
        )
        val completedReason = synchronized(this) {
            val updated = mutableState.value.withCompletedEdit(
                operation = baseline.operation,
                expectedHistory = baseline.history,
                expectedOptions = baseline.options,
                nextHistory = null,
                completion = completion,
            ) ?: return@synchronized null
            mutableState.value = updated
            schedulePersistenceLocked(allowPendingCompletion = true)
            updated.editCompletion?.failure
        }
        if (completedReason == null || completedReason == ProjectEditFailure.CANCELED) return
        when (baseline.operation.kind) {
            ProjectEditKind.AUTO_LAY -> supportEvents.record(SupportEvent.AUTO_LAY_FAILED)
            ProjectEditKind.ARRANGE -> supportEvents.record(SupportEvent.ARRANGE_FAILED)
            ProjectEditKind.MODEL_IMPORT -> supportEvents.record(
                if (completedReason == ProjectEditFailure.MODEL_TOO_LARGE) {
                    SupportEvent.MODEL_TOO_LARGE
                } else {
                    SupportEvent.MODEL_IMPORT_FAILED
                },
            )
            else -> Unit
        }
    }

    private fun updateSessionLocked(
        current: ProjectTransferState,
        expectedHistory: ProjectHistoryState,
        nextHistory: ProjectHistoryState,
        expectedOptions: SliceOptions,
        nextOptions: SliceOptions,
    ): Boolean {
        val updated = current.withUpdatedSession(
            expectedHistory,
            nextHistory,
            expectedOptions,
            nextOptions,
        ) ?: return false
        mutableState.value = updated
        schedulePersistenceLocked()
        return true
    }

    @Synchronized
    fun flushPersistence(): Boolean {
        val current = mutableState.value
        if (!current.hasPersistableChanges(allowActiveTransfer = true)) return false
        schedulePersistenceLocked(
            allowPendingCompletion = true,
            delayMillis = 0L,
        )
        return true
    }

    private fun schedulePersistenceLocked(
        allowPendingCompletion: Boolean = false,
        delayMillis: Long = PROJECT_SAVE_DEBOUNCE_MILLIS,
        obsoleteModelsAfterSave: ProjectSnapshot? = null,
    ) {
        persistenceJob?.cancel()
        val expectedRevision = mutableState.value.sessionRevision
        persistenceJob = viewModelScope.launch {
            if (delayMillis > 0L) delay(delayMillis)
            val document = synchronized(this@ProjectTransferViewModel) {
                mutableState.value.takeIf { current ->
                    current.hasPersistableChanges(allowActiveTransfer = true) &&
                        (
                            allowPendingCompletion ||
                                (current.completion == null && current.editCompletion == null)
                            ) &&
                        !current.persistenceBlocked && current.sessionRevision == expectedRevision
                }
            } ?: return@launch
            val failure = try {
                withContext(Dispatchers.IO) {
                    projectStore.save(
                        document.history.current,
                        document.plateOptions,
                        document.linkedDocument,
                        document.linkedDocumentDirty,
                    )
                    if (obsoleteModelsAfterSave != null) {
                        runCatching { projectStore.deleteModelsReferencedBy(obsoleteModelsAfterSave) }
                    }
                }
                null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                error
            }
            synchronized(this@ProjectTransferViewModel) {
                val current = mutableState.value
                if (current.sessionRevision != expectedRevision) return@synchronized
                if (failure == null) {
                    mutableState.value = current.copy(
                        persistedRevision = maxOf(current.persistedRevision, expectedRevision),
                        persistenceMessage = current.persistenceMessage.takeUnless {
                            it == ProjectPersistenceMessage.SAVE_FAILED
                        },
                    )
                } else {
                    supportEvents.record(SupportEvent.PROJECT_SAVE_FAILED)
                    mutableState.value = current.copy(
                        persistenceMessage = ProjectPersistenceMessage.SAVE_FAILED,
                    )
                }
            }
        }
    }

    override fun onCleared() {
        val cleanup = synchronized(this) {
            persistenceJob?.cancel()
            persistenceJob = null
            val current = mutableState.value
            val activeEdit = current.activeEdit
            if (activeEdit != null) {
                mutableState.value.withEditCancellationRequested(activeEdit.id)?.let { canceling ->
                    mutableState.value = canceling
                }
                SlicerProcessClient.cancelProjectRequestAsync(activeEdit.requestId)
            }
            val cancelingCurrent = mutableState.value
            val pending = cancelingCurrent.takeIf { state ->
                state.hasPersistableChanges(allowActiveTransfer = true) ||
                    (
                        state.activeTransferDirection == ProjectTransferDirection.IMPORT &&
                            state.hasUnpersistedSession()
                        )
            }
            FinalProjectOwnerCleanup(
                activeProjectDocumentTransfer,
                activeModelImportTransfer,
                pending,
            )
        }
        cleanup.transfer?.cancellation?.cancel()
        cleanup.transfer?.operation
            ?.takeIf { it.exportFormat != ProjectExportFormat.PROJECT_ARCHIVE }
            ?.let { operation ->
                SlicerProcessClient.cancelProjectRequestAsync(operation.requestId)
            }
        cleanup.modelImport?.cancellation?.cancel()
        val pending = cleanup.pendingProject?.takeUnless {
            cleanup.transfer?.operation?.direction == ProjectTransferDirection.IMPORT &&
                cleanup.transfer.cancellation.completionWasClaimed()
        }
        try {
            if (pending != null) {
                try {
                    projectStore.save(
                        pending.history.current,
                        pending.plateOptions,
                        pending.linkedDocument,
                        pending.linkedDocumentDirty,
                    )
                } catch (_: Exception) {
                    supportEvents.record(SupportEvent.PROJECT_SAVE_FAILED)
                }
            }
        } finally {
            super.onCleared()
        }
    }

    private companion object {
        const val PROJECT_SAVE_DEBOUNCE_MILLIS = 400L
    }
}

private fun copyThreeMfToDocument(
    input: java.io.InputStream,
    output: java.io.OutputStream,
    checkCancellation: () -> Unit,
) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        checkCancellation()
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        total += count
        require(total <= 512L * 1024L * 1024L) { "3mf_export_too_large" }
        output.write(buffer, 0, count)
    }
    require(total > 0L) { "3mf_export_empty" }
}

internal fun validateBinaryStl(file: File) {
    require(file.isFile && file.length() in 84L..512L * 1024L * 1024L) {
        "stl_export_invalid"
    }
    val triangleCount = java.io.DataInputStream(file.inputStream().buffered()).use { input ->
        val header = ByteArray(80)
        input.readFully(header)
        Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()))
    }
    require(84L + triangleCount * 50L == file.length()) { "stl_export_invalid" }
}

private fun copyStlToDocument(
    input: java.io.InputStream,
    output: java.io.OutputStream,
    checkCancellation: () -> Unit,
) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        checkCancellation()
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        total += count
        require(total <= 512L * 1024L * 1024L) { "stl_export_too_large" }
        output.write(buffer, 0, count)
    }
    require(total >= 84L) { "stl_export_empty" }
}

private fun ProjectTransferState.hasPersistableChanges(
    allowActiveTransfer: Boolean,
): Boolean = hasUnpersistedSession() &&
    (
        !busy || activeEdit != null ||
            (allowActiveTransfer && activeTransferDirection == ProjectTransferDirection.EXPORT)
    )

private fun ProjectTransferState.hasUnpersistedSession(): Boolean =
    restored && !persistenceBlocked && sessionRevision != persistedRevision

private fun List<ProjectObject>.deleteInstalledModels() {
    flatMap(ProjectObject::volumes)
        .map { volume -> File(volume.model.localPath) }
        .distinctBy { file -> runCatching { file.canonicalPath }.getOrDefault(file.absolutePath) }
        .forEach(File::delete)
}

private fun List<String>.deleteInstalledModelPaths() {
    map(::File)
        .distinctBy { file -> runCatching { file.canonicalPath }.getOrDefault(file.absolutePath) }
        .forEach(File::delete)
}
