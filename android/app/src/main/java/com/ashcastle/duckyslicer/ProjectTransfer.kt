package com.ashcastle.duckyslicer

import android.app.Application
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

internal enum class ProjectTransferDirection {
    IMPORT,
    EXPORT,
}

internal enum class ProjectPersistenceMessage {
    STORAGE_UNAVAILABLE,
    SAVE_FAILED,
}

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
        !restored || busy || completion != null || history != expectedHistory ||
        sliceOptions != expectedOptions
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
    fun importProject(uri: Uri): Boolean {
        if (mutableState.value.busy || mutableState.value.completion != null) return false
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
        if (mutableState.value.busy || mutableState.value.completion != null) return false
        val operationId = ++nextOperationId
        mutableState.value = mutableState.value.copy(busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            val completion = try {
                getApplication<Application>().contentResolver.openOutputStream(uri).use { output ->
                    projectStore.exportArchive(
                        snapshot,
                        sliceOptions,
                        requireNotNull(output) { "output_unavailable" },
                    )
                }
                ProjectTransferCompletion.Exported(operationId, uri)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
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
                        (allowPendingCompletion || current.completion == null) &&
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
