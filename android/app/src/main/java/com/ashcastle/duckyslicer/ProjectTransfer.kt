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

internal enum class ProjectTransferDirection {
    IMPORT,
    EXPORT,
}

internal enum class ProjectPersistenceMessage {
    STORAGE_UNAVAILABLE,
    SAVE_FAILED,
}

internal enum class ProjectEditKind {
    MODEL_IMPORT,
    PRIMITIVE,
    AUTO_LAY,
    ARRANGE,
    SPLIT,
    CUT,
}

internal data class ActiveProjectEdit(
    val id: Long,
    val kind: ProjectEditKind,
)

internal enum class ProjectEditFailure {
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
    ) : ProjectTransferCompletion

    data class Failed(
        override val id: Long,
        override val uri: Uri,
        val direction: ProjectTransferDirection,
    ) : ProjectTransferCompletion
}

internal data class ProjectTransferState(
    val busy: Boolean = false,
    val completion: ProjectTransferCompletion? = null,
    val activeEdit: ActiveProjectEdit? = null,
    val editCompletion: ProjectEditCompletion? = null,
    val history: ProjectHistoryState = ProjectHistoryState(),
    val sliceOptions: SliceOptions = SliceOptions(),
    val restored: Boolean = false,
    val persistenceBlocked: Boolean = false,
    val persistenceMessage: ProjectPersistenceMessage? = null,
    val sessionRevision: Long = 0,
)

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
        persistenceMessage = ProjectPersistenceMessage.STORAGE_UNAVAILABLE.takeIf {
            persistenceBlocked
        },
        sessionRevision = sessionRevision + 1,
    )
}

internal fun ProjectTransferState.withStartedEdit(
    operation: ActiveProjectEdit,
): ProjectTransferState? {
    if (!restored || busy || completion != null || editCompletion != null) return null
    return copy(busy = true, activeEdit = operation)
}

internal fun ProjectTransferState.withCompletedEdit(
    operation: ActiveProjectEdit,
    expectedHistory: ProjectHistoryState,
    expectedOptions: SliceOptions,
    nextHistory: ProjectHistoryState?,
    completion: ProjectEditCompletion,
): ProjectTransferState? {
    require(completion.id == operation.id && completion.kind == operation.kind) {
        "Project edit completion does not match its operation"
    }
    require((completion.failure == null) == (nextHistory != null)) {
        "Successful project edits require a resulting history"
    }
    if (
        !busy || activeEdit != operation || history != expectedHistory ||
        sliceOptions != expectedOptions
    ) {
        return null
    }
    val appliedHistory = nextHistory ?: history
    val changed = appliedHistory != history
    return copy(
        busy = false,
        activeEdit = null,
        editCompletion = completion.copy(sessionChanged = changed),
        history = appliedHistory,
        persistenceMessage = ProjectPersistenceMessage.STORAGE_UNAVAILABLE.takeIf {
            changed && persistenceBlocked
        } ?: persistenceMessage,
        sessionRevision = sessionRevision + if (changed) 1 else 0,
    )
}

private data class ProjectEditBaseline(
    val operation: ActiveProjectEdit,
    val history: ProjectHistoryState,
    val options: SliceOptions,
)

internal class ProjectTransferViewModel(application: Application) : AndroidViewModel(application) {
    private val projectStore = ProjectStore(application)
    private val supportEvents = SupportEventJournal(application)
    private val mutableState = MutableStateFlow(ProjectTransferState(busy = true))
    val state: StateFlow<ProjectTransferState> = mutableState.asStateFlow()
    private var nextOperationId = 0L
    private var persistenceJob: Job? = null

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
            mutableState.value = ProjectTransferState(
                history = ProjectHistoryState(current = restored.snapshot),
                sliceOptions = restored.sliceOptions ?: SliceOptions(),
                restored = true,
                persistenceBlocked = restored.storageUnavailable,
                persistenceMessage = ProjectPersistenceMessage.STORAGE_UNAVAILABLE.takeIf {
                    restored.storageUnavailable
                },
            )
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
            nextOptions = current.sliceOptions,
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
    fun autoLaySelectedModel(): Boolean {
        val target = mutableState.value.history.current.selectedObject ?: return false
        val baseline = startEditLocked(ProjectEditKind.AUTO_LAY) ?: return false
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val orientation = SlicerProcessClient.autoOrient(File(target.model.localPath))
                val nextHistory = baseline.history.updateTransform(
                    target.id,
                    target.transform.withOrcaOrientation(orientation),
                )
                completeEditSuccess(baseline, nextHistory)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                completeEditFailure(baseline, failure)
            }
        }
        return true
    }

    @Synchronized
    fun arrangeProjectObjects(): Boolean {
        if (mutableState.value.history.current.objects.size < 2) return false
        val baseline = startEditLocked(ProjectEditKind.ARRANGE) ?: return false
        val targets = baseline.history.current.objects
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val arrangement = OnDeviceSlicer.arrange(targets, baseline.options)
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
            }
        }
        return true
    }

    @Synchronized
    fun splitSelectedModel(): Boolean {
        val current = mutableState.value
        val target = current.history.current.selectedObject ?: return false
        val maximumObjects = ProjectStore.MAX_PROJECT_OBJECTS - current.history.current.objects.size + 1
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
            }
        }
        return true
    }

    @Synchronized
    fun cutSelectedModel(heightRatio: Float, placeOnCut: Boolean): Boolean {
        val current = mutableState.value
        val target = current.history.current.selectedObject ?: return false
        val maximumObjects = ProjectStore.MAX_PROJECT_OBJECTS - current.history.current.objects.size + 1
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
        val objectCount = mutableState.value.history.current.objects.size
        if (
            objectCount >= ProjectStore.MAX_PROJECT_OBJECTS || !sizeMm.isFinite() ||
            sizeMm !in MIN_PRIMITIVE_SIZE_MM..MAX_PRIMITIVE_SIZE_MM
        ) {
            return false
        }
        val baseline = startEditLocked(ProjectEditKind.PRIMITIVE) ?: return false
        viewModelScope.launch(Dispatchers.IO) {
            var installed = emptyList<ProjectObject>()
            try {
                val created = createOrcaPrimitive(primitive, sizeMm, displayName, projectStore)
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
            }
        }
        return true
    }

    @Synchronized
    fun importModels(uri: Uri): Boolean {
        val objectCount = mutableState.value.history.current.objects.size
        if (objectCount >= ProjectStore.MAX_PROJECT_OBJECTS) return false
        val baseline = startEditLocked(ProjectEditKind.MODEL_IMPORT) ?: return false
        viewModelScope.launch(Dispatchers.IO) {
            var installed = emptyList<ProjectObject>()
            try {
                val imported = importOrcaModels(
                    getApplication<Application>(),
                    uri,
                    projectStore,
                    baseline.options,
                )
                installed = imported
                require(objectCount + imported.size <= ProjectStore.MAX_PROJECT_OBJECTS) {
                    "Project has too many imported objects"
                }
                val distance = ((objectCount + 1) / 2) * 24f
                val offset = when {
                    objectCount == 0 -> 0f
                    objectCount % 2 == 1 -> distance
                    else -> -distance
                }
                val placed = imported.map { projectObject ->
                    projectObject.copy(
                        transform = projectObject.transform.copy(
                            offsetXmm = projectObject.transform.offsetXmm + offset,
                        ),
                    )
                }
                val nextHistory = baseline.history.addAll(placed)
                if (
                    !completeEditSuccess(
                        baseline,
                        nextHistory,
                        objectCount = placed.size,
                    )
                ) {
                    imported.deleteInstalledModels()
                }
            } catch (failure: CancellationException) {
                installed.deleteInstalledModels()
                throw failure
            } catch (failure: Exception) {
                installed.deleteInstalledModels()
                completeEditFailure(baseline, failure)
            }
        }
        return true
    }

    @Synchronized
    fun importProject(uri: Uri): Boolean {
        if (
            mutableState.value.busy || mutableState.value.completion != null ||
            mutableState.value.editCompletion != null
        ) return false
        persistenceJob?.cancel()
        val operationId = ++nextOperationId
        mutableState.value = mutableState.value.copy(busy = true, persistenceMessage = null)
        viewModelScope.launch(Dispatchers.IO) {
            val completion = try {
                val document = getApplication<Application>().contentResolver.openInputStream(uri).use { input ->
                    projectStore.importArchive(requireNotNull(input) { "input_unavailable" })
                }
                ProjectTransferCompletion.Imported(operationId, uri, document)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                ProjectTransferCompletion.Failed(
                    operationId,
                    uri,
                    ProjectTransferDirection.IMPORT,
                )
            }
            synchronized(this@ProjectTransferViewModel) {
                val current = mutableState.value
                mutableState.value = when (completion) {
                    is ProjectTransferCompletion.Imported -> current.copy(
                        busy = false,
                        completion = completion,
                        history = ProjectHistoryState(current = completion.document.snapshot),
                        sliceOptions = completion.document.sliceOptions ?: current.sliceOptions,
                        restored = true,
                        persistenceBlocked = false,
                        persistenceMessage = null,
                        sessionRevision = current.sessionRevision + 1,
                    )
                    else -> current.copy(busy = false, completion = completion)
                }
                if (completion !is ProjectTransferCompletion.Imported) {
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
        sliceOptions: SliceOptions,
    ): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        if (
            mutableState.value.busy || mutableState.value.completion != null ||
            mutableState.value.editCompletion != null
        ) return false
        val operationId = ++nextOperationId
        mutableState.value = mutableState.value.copy(busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            val application = getApplication<Application>()
            val completion = try {
                application.contentResolver.openOutputStream(uri, "wt").use { output ->
                    projectStore.exportArchive(
                        snapshot,
                        sliceOptions,
                        requireNotNull(output) { "output_unavailable" },
                    )
                }
                ProjectTransferCompletion.Exported(operationId, uri)
            } catch (failure: CancellationException) {
                deleteFailedCreatedDocument(application, uri)
                throw failure
            } catch (_: Exception) {
                deleteFailedCreatedDocument(application, uri)
                supportEvents.record(SupportEvent.PROJECT_ARCHIVE_EXPORT_FAILED)
                ProjectTransferCompletion.Failed(
                    operationId,
                    uri,
                    ProjectTransferDirection.EXPORT,
                )
            }
            synchronized(this@ProjectTransferViewModel) {
                mutableState.value = mutableState.value.copy(
                    busy = false,
                    completion = completion,
                )
                schedulePersistenceLocked(allowPendingCompletion = true)
            }
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

    private fun startEditLocked(kind: ProjectEditKind): ProjectEditBaseline? {
        val current = mutableState.value
        val operation = ActiveProjectEdit(++nextOperationId, kind)
        val started = current.withStartedEdit(operation) ?: return null
        persistenceJob?.cancel()
        mutableState.value = started
        return ProjectEditBaseline(operation, current.history, current.sliceOptions)
    }

    private fun completeEditSuccess(
        baseline: ProjectEditBaseline,
        nextHistory: ProjectHistoryState,
        objectCount: Int = 0,
        clearedObjectSettings: Boolean = false,
        displayName: String? = null,
    ): Boolean = synchronized(this) {
        val completion = ProjectEditCompletion(
            id = baseline.operation.id,
            kind = baseline.operation.kind,
            objectCount = objectCount,
            clearedObjectSettings = clearedObjectSettings,
            displayName = displayName,
        )
        val updated = mutableState.value.withCompletedEdit(
            operation = baseline.operation,
            expectedHistory = baseline.history,
            expectedOptions = baseline.options,
            nextHistory = nextHistory,
            completion = completion,
        ) ?: return@synchronized false
        mutableState.value = updated
        schedulePersistenceLocked(allowPendingCompletion = true)
        true
    }

    private fun completeEditFailure(
        baseline: ProjectEditBaseline,
        failure: Exception,
    ) {
        val reason = when (failure) {
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
        val accepted = synchronized(this) {
            val updated = mutableState.value.withCompletedEdit(
                operation = baseline.operation,
                expectedHistory = baseline.history,
                expectedOptions = baseline.options,
                nextHistory = null,
                completion = completion,
            ) ?: return@synchronized false
            mutableState.value = updated
            schedulePersistenceLocked(allowPendingCompletion = true)
            true
        }
        if (!accepted) return
        when (baseline.operation.kind) {
            ProjectEditKind.AUTO_LAY -> supportEvents.record(SupportEvent.AUTO_LAY_FAILED)
            ProjectEditKind.ARRANGE -> supportEvents.record(SupportEvent.ARRANGE_FAILED)
            ProjectEditKind.MODEL_IMPORT -> supportEvents.record(
                if (reason == ProjectEditFailure.MODEL_TOO_LARGE) {
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

    private fun schedulePersistenceLocked(allowPendingCompletion: Boolean = false) {
        persistenceJob?.cancel()
        val expectedRevision = mutableState.value.sessionRevision
        persistenceJob = viewModelScope.launch {
            delay(PROJECT_SAVE_DEBOUNCE_MILLIS)
            val document = synchronized(this@ProjectTransferViewModel) {
                mutableState.value.takeIf { current ->
                    current.restored && !current.busy &&
                        (
                            allowPendingCompletion ||
                                (current.completion == null && current.editCompletion == null)
                            ) &&
                        !current.persistenceBlocked && current.sessionRevision == expectedRevision
                }
            } ?: return@launch
            val failure = try {
                withContext(Dispatchers.IO) {
                    projectStore.save(document.history.current, document.sliceOptions)
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
                    if (current.persistenceMessage == ProjectPersistenceMessage.SAVE_FAILED) {
                        mutableState.value = current.copy(persistenceMessage = null)
                    }
                } else {
                    supportEvents.record(SupportEvent.PROJECT_SAVE_FAILED)
                    mutableState.value = current.copy(
                        persistenceMessage = ProjectPersistenceMessage.SAVE_FAILED,
                    )
                }
            }
        }
    }

    private companion object {
        const val PROJECT_SAVE_DEBOUNCE_MILLIS = 400L
    }
}

private fun List<ProjectObject>.deleteInstalledModels() {
    forEach { projectObject -> File(projectObject.model.localPath).delete() }
}
