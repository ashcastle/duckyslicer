package com.ashcastle.duckyslicer

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.Closeable
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

private class GcodeExportCancelledException : Exception("gcode_export_canceled")

/** Interrupts only the streams and provider open belonging to one G-code export. */
internal class GcodeExportCancellation {
    private val lock = Any()
    val providerSignal = CancellationSignal()

    @Volatile
    private var cancellationRequested = false
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var completed = false

    fun cancel(): Boolean {
        val resources = synchronized(lock) {
            if (completed || cancellationRequested) return false
            cancellationRequested = true
            output to input
        }
        runCatching { providerSignal.cancel() }
        resources.first.closeQuietly()
        resources.second.closeQuietly()
        return true
    }

    fun throwIfRequested() {
        if (cancellationRequested) throw GcodeExportCancelledException()
    }

    fun attachInput(value: InputStream) = attach(value, outputStream = false)

    fun attachOutput(value: OutputStream) = attach(value, outputStream = true)

    fun detachInput(value: InputStream) = detach(value, outputStream = false)

    fun detachOutput(value: OutputStream) = detach(value, outputStream = true)

    fun complete() {
        synchronized(lock) {
            if (cancellationRequested) throw GcodeExportCancelledException()
            check(!completed) { "gcode_export_lifecycle_invalid" }
            completed = true
            input = null
            output = null
        }
    }

    fun close() {
        synchronized(lock) {
            completed = true
            input = null
            output = null
        }
    }

    fun wasRequested(): Boolean = cancellationRequested

    private fun attach(value: Closeable, outputStream: Boolean) {
        val rejected = synchronized(lock) {
            if (cancellationRequested || completed) {
                true
            } else {
                if (outputStream) {
                    check(output == null) { "gcode_export_lifecycle_invalid" }
                    output = value as OutputStream
                } else {
                    check(input == null) { "gcode_export_lifecycle_invalid" }
                    input = value as InputStream
                }
                false
            }
        }
        if (rejected) {
            value.closeQuietly()
            throw GcodeExportCancelledException()
        }
    }

    private fun detach(value: Closeable, outputStream: Boolean) {
        synchronized(lock) {
            if (outputStream && output === value) output = null
            if (!outputStream && input === value) input = null
        }
    }

    private fun Closeable?.closeQuietly() {
        if (this != null) runCatching { close() }
    }
}

private data class ActiveGcodeExport(
    val id: Long,
    val cancellation: GcodeExportCancellation,
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
        val cancellation = GcodeExportCancellation()
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
                if (cancellation.wasRequested() || failure is GcodeExportCancelledException) {
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
        cancellation: GcodeExportCancellation,
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
            if (cancellation.wasRequested()) throw GcodeExportCancelledException()
            throw failure
        }
    }

    private fun copyCancellable(
        input: InputStream,
        output: OutputStream,
        cancellation: GcodeExportCancellation,
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
