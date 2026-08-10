package com.ashcastle.duckyslicer

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class ProjectTransferDirection {
    IMPORT,
    EXPORT,
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
)

internal class ProjectTransferViewModel(application: Application) : AndroidViewModel(application) {
    private val projectStore = ProjectStore(application)
    private val mutableState = MutableStateFlow(ProjectTransferState(busy = true))
    val state: StateFlow<ProjectTransferState> = mutableState.asStateFlow()
    private var nextOperationId = 0L

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val projectRoot = java.io.File(application.filesDir, ProjectStore.PROJECT_DIRECTORY)
                ProjectStore.recoverAbandonedArchiveStaging(projectRoot)
                ProjectStore.recoverAbandonedModelImportStaging(projectRoot)
            }
            mutableState.value = ProjectTransferState()
        }
    }

    @Synchronized
    fun importProject(uri: Uri): Boolean {
        if (mutableState.value.busy || mutableState.value.completion != null) return false
        val operationId = ++nextOperationId
        mutableState.value = ProjectTransferState(busy = true)
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
            mutableState.value = ProjectTransferState(completion = completion)
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
        mutableState.value = ProjectTransferState(busy = true)
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
            mutableState.value = ProjectTransferState(completion = completion)
        }
        return true
    }

    @Synchronized
    fun consumeCompletion(operationId: Long) {
        if (mutableState.value.completion?.id != operationId) return
        mutableState.value = ProjectTransferState()
    }
}
