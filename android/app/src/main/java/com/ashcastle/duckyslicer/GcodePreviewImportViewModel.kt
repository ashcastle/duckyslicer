package com.ashcastle.duckyslicer

import android.app.Application
import android.content.ContentProviderClient
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class GcodePreviewImportStatus {
    NONE,
    SUCCEEDED,
    CANCELED,
    FAILED,
}

internal data class ImportedGcodePreview(
    val output: File,
    val displayName: String,
    val summary: PreviewSummary,
    val filamentColors: List<Int>,
    val preview: GcodeLayerPreview?,
)

internal data class GcodePreviewImportState(
    val document: ImportedGcodePreview? = null,
    val busy: Boolean = false,
    val importing: Boolean = false,
    val cancellationRequested: Boolean = false,
    val completionOperationId: Long? = null,
    val status: GcodePreviewImportStatus = GcodePreviewImportStatus.NONE,
)

/** Owns a read-only, app-private preview of a G-code document selected through SAF. */
internal class GcodePreviewImportViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val store = SliceArtifactStore(application.filesDir)
    private val mutableState = MutableStateFlow(GcodePreviewImportState())
    val state: StateFlow<GcodePreviewImportState> = mutableState.asStateFlow()
    private val nextOperationId = AtomicLong(savedStateHandle[KEY_NEXT_OPERATION_ID] ?: 0L)
    private val activeJob = AtomicReference<Job?>(null)
    private val providerCancellation = AtomicReference<CancellationSignal?>(null)
    private val cancellationRequested = AtomicBoolean(false)

    init {
        val activeUri = savedStateHandle.get<String>(KEY_ACTIVE_URI)
        restoreDocument(loadPreview = activeUri == null)
        activeUri?.let { value ->
            open(Uri.parse(value))
        }
    }

    fun open(uri: Uri): Long? {
        if (uri.scheme != "content" || mutableState.value.busy || activeJob.get()?.isActive == true) {
            return null
        }
        val operationId = nextOperationId.incrementAndGet()
        savedStateHandle[KEY_NEXT_OPERATION_ID] = operationId
        savedStateHandle[KEY_ACTIVE_URI] = uri.toString()
        val previous = mutableState.value.document
        mutableState.value = GcodePreviewImportState(
            document = previous,
            busy = true,
            importing = true,
        )
        cancellationRequested.set(false)
        val signal = CancellationSignal()
        providerCancellation.set(signal)
        val job = viewModelScope.launch {
            var imported: File? = null
            var finalState: GcodePreviewImportState? = null
            try {
                val document = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val provider = requireNotNull(
                        resolver.acquireContentProviderClient(uri),
                    ) { "G-code provider is unavailable" }
                    val displayName = provider.use {
                        val name = queryDisplayName(provider, uri, signal)
                        val descriptor = requireNotNull(
                            provider.openAssetFile(uri, "r", signal),
                        ) { "G-code document is unavailable" }
                        descriptor.use { asset ->
                            asset.createInputStream().use { input ->
                                imported = store.importDocument(
                                    input = input,
                                    protected = setOfNotNull(previous?.output),
                                    cancellationRequested = cancellationRequested::get,
                                )
                            }
                        }
                        name
                    }
                    val output = requireNotNull(imported)
                    val metadata = readGcodePreviewMetadata(output)
                    val preview = buildPreview(output, 0, Int.MAX_VALUE)
                    ImportedGcodePreview(
                        output = output,
                        displayName = displayName,
                        summary = metadata.summary,
                        filamentColors = metadata.filamentColors,
                        preview = preview,
                    )
                }
                if (cancellationRequested.get()) throw GcodeImportCanceledException()
                persistDocument(document)
                previous?.output?.takeIf { it != document.output }?.let(store::discard)
                finalState = GcodePreviewImportState(
                    document = document,
                    completionOperationId = operationId,
                    status = GcodePreviewImportStatus.SUCCEEDED,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: GcodeImportCanceledException) {
                imported?.let(store::discard)
                finalState = GcodePreviewImportState(
                    document = previous,
                    completionOperationId = operationId,
                    status = GcodePreviewImportStatus.CANCELED,
                )
            } catch (_: OperationCanceledException) {
                imported?.let(store::discard)
                finalState = GcodePreviewImportState(
                    document = previous,
                    completionOperationId = operationId,
                    status = GcodePreviewImportStatus.CANCELED,
                )
            } catch (failure: Exception) {
                if (BuildConfig.DEBUG) Log.e(LOG_TAG, "G-code preview import failed", failure)
                imported?.let(store::discard)
                finalState = GcodePreviewImportState(
                    document = previous,
                    completionOperationId = operationId,
                    status = GcodePreviewImportStatus.FAILED,
                )
            } finally {
                providerCancellation.compareAndSet(signal, null)
                cancellationRequested.set(false)
                savedStateHandle[KEY_ACTIVE_URI] = null
                activeJob.set(null)
                finalState?.let { mutableState.value = it }
            }
        }
        activeJob.set(job)
        return operationId
    }

    fun loadRange(startLayer: Int, endLayer: Int): Boolean {
        val current = mutableState.value
        val document = current.document ?: return false
        if (current.busy || activeJob.get()?.isActive == true) return false
        mutableState.value = current.copy(busy = true, status = GcodePreviewImportStatus.NONE)
        val job = viewModelScope.launch {
            try {
                val preview = withContext(Dispatchers.IO) {
                    buildPreview(document.output, startLayer, endLayer)
                }
                mutableState.value = GcodePreviewImportState(
                    document = document.copy(preview = preview),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (BuildConfig.DEBUG) Log.e(LOG_TAG, "Imported G-code range failed", failure)
                mutableState.value = GcodePreviewImportState(
                    document = document,
                    status = GcodePreviewImportStatus.FAILED,
                )
            } finally {
                activeJob.set(null)
            }
        }
        activeJob.set(job)
        return true
    }

    fun cancel() {
        val current = mutableState.value
        if (!current.importing || current.cancellationRequested) return
        cancellationRequested.set(true)
        providerCancellation.get()?.cancel()
        mutableState.value = current.copy(cancellationRequested = true)
    }

    fun consumeCompletion(operationId: Long): Boolean {
        val current = mutableState.value
        if (current.completionOperationId != operationId) return false
        mutableState.value = current.copy(
            completionOperationId = null,
            status = GcodePreviewImportStatus.NONE,
        )
        return true
    }

    fun clearDocument() {
        val current = mutableState.value
        if (current.busy) return
        current.document?.output?.let(store::discard)
        clearPersistedDocument()
        savedStateHandle[KEY_ACTIVE_URI] = null
        mutableState.value = GcodePreviewImportState()
    }

    private fun restoreDocument(loadPreview: Boolean) {
        val path = savedStateHandle.get<String>(KEY_DOCUMENT_PATH) ?: return
        val output = File(path)
        if (!isOwnedOutput(output)) {
            clearPersistedDocument()
            return
        }
        val displayName = savedStateHandle.get<String>(KEY_DISPLAY_NAME) ?: DEFAULT_DISPLAY_NAME
        val metadata = runCatching { readGcodePreviewMetadata(output) }.getOrElse {
            store.discard(output)
            clearPersistedDocument()
            return
        }
        val document = ImportedGcodePreview(
            output = output,
            displayName = displayName,
            summary = metadata.summary,
            filamentColors = metadata.filamentColors,
            preview = null,
        )
        if (!loadPreview) {
            mutableState.value = GcodePreviewImportState(document = document)
            return
        }
        mutableState.value = GcodePreviewImportState(document = document, busy = true)
        val job = viewModelScope.launch {
            try {
                val preview = withContext(Dispatchers.IO) { buildPreview(output, 0, Int.MAX_VALUE) }
                mutableState.value = GcodePreviewImportState(document = document.copy(preview = preview))
            } catch (failure: Exception) {
                if (BuildConfig.DEBUG) Log.e(LOG_TAG, "Saved G-code preview restore failed", failure)
                store.discard(output)
                clearPersistedDocument()
                mutableState.value = GcodePreviewImportState(status = GcodePreviewImportStatus.FAILED)
            } finally {
                activeJob.set(null)
            }
        }
        activeJob.set(job)
    }

    private fun buildPreview(file: File, startLayer: Int, endLayer: Int): GcodeLayerPreview =
        SliceArtifactLease.acquire(file).use {
            loadGcodePreview(file.absolutePath, startLayer, endLayer)
        }

    private fun queryDisplayName(
        provider: ContentProviderClient,
        uri: Uri,
        signal: CancellationSignal,
    ): String {
        val queried = try {
            provider.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
                signal,
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        } catch (failure: Exception) {
            if (signal.isCanceled) throw OperationCanceledException()
            null
        }
        return queried
            ?.let(::normalizedProjectDocumentName)
            ?: DEFAULT_DISPLAY_NAME
    }

    private fun persistDocument(document: ImportedGcodePreview) {
        savedStateHandle[KEY_DOCUMENT_PATH] = document.output.absolutePath
        savedStateHandle[KEY_DISPLAY_NAME] = document.displayName
    }

    private fun clearPersistedDocument() {
        savedStateHandle[KEY_DOCUMENT_PATH] = null
        savedStateHandle[KEY_DISPLAY_NAME] = null
    }

    private fun isOwnedOutput(file: File): Boolean = runCatching {
        file.isFile && file.extension == "gcode" &&
            file.canonicalFile.parentFile == File(
                getApplication<Application>().filesDir,
                SliceArtifactStore.OUTPUT_DIRECTORY,
            ).canonicalFile
    }.getOrDefault(false)

    override fun onCleared() {
        cancellationRequested.set(true)
        providerCancellation.get()?.cancel()
        super.onCleared()
    }

    private companion object {
        const val LOG_TAG = "DuckyGcodePreview"
        const val DEFAULT_DISPLAY_NAME = "G-code"
        const val KEY_NEXT_OPERATION_ID = "gcode_preview_next_operation_id"
        const val KEY_DOCUMENT_PATH = "gcode_preview_document_path"
        const val KEY_DISPLAY_NAME = "gcode_preview_display_name"
        const val KEY_ACTIVE_URI = "gcode_preview_active_uri"
    }
}
