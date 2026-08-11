package com.ashcastle.duckyslicer

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class GcodeExportResult {
    SAVED,
    CANCELED,
    FAILED,
}

internal data class GcodeExportCompletion(
    val id: Long,
    val result: GcodeExportResult,
)

internal data class GcodeExportState(
    val activeId: Long? = null,
    val cancellationRequested: Boolean = false,
    val completion: GcodeExportCompletion? = null,
) {
    val busy: Boolean
        get() = activeId != null
}

internal fun GcodeExportState.withStartedExport(operationId: Long): GcodeExportState? {
    if (busy || completion != null) return null
    return copy(activeId = operationId, cancellationRequested = false)
}

internal fun GcodeExportState.withCancellationRequested(operationId: Long): GcodeExportState? {
    if (activeId != operationId || cancellationRequested || completion != null) return null
    return copy(cancellationRequested = true)
}

internal fun GcodeExportState.withCompletedExport(
    operationId: Long,
    result: GcodeExportResult,
): GcodeExportState? {
    if (activeId != operationId || completion != null) return null
    return copy(
        activeId = null,
        cancellationRequested = false,
        completion = GcodeExportCompletion(operationId, result),
    )
}

private data class ActiveGcodeExport(
    val id: Long,
    val cancellation: DocumentTransferCancellation,
)

/** Owns one user-selected G-code copy independently of Activity configuration changes. */
internal class GcodeExportViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(GcodeExportState())
    val state: StateFlow<GcodeExportState> = mutableState.asStateFlow()
    private val supportEvents = SupportEventJournal(application)
    private var nextOperationId = 0L
    private var activeExport: ActiveGcodeExport? = null

    @Synchronized
    fun export(uri: Uri, outcome: SliceOutcome): Boolean {
        val application = getApplication<Application>()
        if (
            uri.scheme != ContentResolver.SCHEME_CONTENT ||
            !outcome.isRestorableFrom(application.filesDir)
        ) {
            return false
        }
        val operationId = ++nextOperationId
        val started = mutableState.value.withStartedExport(operationId) ?: return false
        val cancellation = DocumentTransferCancellation()
        activeExport = ActiveGcodeExport(operationId, cancellation)
        mutableState.value = started
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                copyArtifact(application.contentResolver, uri, outcome.output, cancellation)
                cancellation.complete()
                GcodeExportResult.SAVED
            } catch (scopeCancellation: CancellationException) {
                cancellation.cancel()
                deleteFailedCreatedDocument(application, uri)
                GcodeExportResult.CANCELED
            } catch (failure: Exception) {
                if (
                    cancellation.wasRequested() ||
                    failure is DocumentTransferCancelledException
                ) {
                    deleteFailedCreatedDocument(application, uri)
                    GcodeExportResult.CANCELED
                } else {
                    if (BuildConfig.DEBUG) Log.e(LOG_TAG, "G-code export failed", failure)
                    deleteFailedCreatedDocument(application, uri)
                    supportEvents.record(SupportEvent.GCODE_EXPORT_FAILED)
                    GcodeExportResult.FAILED
                }
            } finally {
                cancellation.close()
            }
            synchronized(this@GcodeExportViewModel) {
                if (activeExport?.id == operationId) activeExport = null
                mutableState.value.withCompletedExport(operationId, result)?.let { completed ->
                    mutableState.value = completed
                }
            }
        }
        return true
    }

    fun cancelActiveExport(): Boolean {
        val operation = synchronized(this) { activeExport } ?: return false
        if (!operation.cancellation.cancel()) return false
        synchronized(this) {
            mutableState.value.withCancellationRequested(operation.id)?.let { canceling ->
                mutableState.value = canceling
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

    override fun onCleared() {
        val cancellation = synchronized(this) { activeExport?.cancellation }
        cancellation?.cancel()
        super.onCleared()
    }

    private fun copyArtifact(
        contentResolver: ContentResolver,
        uri: Uri,
        source: File,
        cancellation: DocumentTransferCancellation,
    ) {
        try {
            cancellation.throwIfRequested()
            SliceArtifactLease.acquire(source).use {
                source.inputStream().use { input ->
                    cancellation.attachInput(input)
                    try {
                        val descriptor = requireNotNull(
                            contentResolver.openAssetFileDescriptor(
                                uri,
                                "wt",
                                cancellation.providerSignal,
                            ),
                        ) { "G-code output is unavailable" }
                        descriptor.use {
                            descriptor.createOutputStream().use { output ->
                                cancellation.attachOutput(output)
                                try {
                                    copyCancellable(input, output, cancellation)
                                    output.flush()
                                    cancellation.throwIfRequested()
                                } finally {
                                    cancellation.detachOutput(output)
                                }
                            }
                        }
                    } finally {
                        cancellation.detachInput(input)
                    }
                }
            }
        } catch (failure: Exception) {
            if (cancellation.wasRequested()) throw DocumentTransferCancelledException()
            throw failure
        }
    }

    private fun copyCancellable(
        input: InputStream,
        output: OutputStream,
        cancellation: DocumentTransferCancellation,
    ) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            cancellation.throwIfRequested()
            val count = input.read(buffer)
            if (count < 0) return
            output.write(buffer, 0, count)
        }
    }

    private companion object {
        const val LOG_TAG = "DuckyGcodeExport"
        const val COPY_BUFFER_BYTES = 64 * 1_024
    }
}
