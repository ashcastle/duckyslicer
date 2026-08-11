package com.ashcastle.duckyslicer

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class GcodeExportCompletion(
    val id: Long,
    val succeeded: Boolean,
)

internal data class GcodeExportState(
    val activeId: Long? = null,
    val completion: GcodeExportCompletion? = null,
) {
    val busy: Boolean
        get() = activeId != null
}

internal fun GcodeExportState.withStartedExport(operationId: Long): GcodeExportState? {
    if (busy || completion != null) return null
    return copy(activeId = operationId)
}

internal fun GcodeExportState.withCompletedExport(
    operationId: Long,
    succeeded: Boolean,
): GcodeExportState? {
    if (activeId != operationId || completion != null) return null
    return copy(
        activeId = null,
        completion = GcodeExportCompletion(operationId, succeeded),
    )
}

/** Owns one user-selected G-code copy independently of Activity configuration changes. */
internal class GcodeExportViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(GcodeExportState())
    val state: StateFlow<GcodeExportState> = mutableState.asStateFlow()
    private val supportEvents = SupportEventJournal(application)
    private var nextOperationId = 0L

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
        mutableState.value = started
        viewModelScope.launch(Dispatchers.IO) {
            val succeeded = try {
                copyArtifact(application.contentResolver, uri, outcome.output)
                true
            } catch (cancellation: CancellationException) {
                deleteFailedCreatedDocument(application, uri)
                throw cancellation
            } catch (failure: Exception) {
                if (BuildConfig.DEBUG) Log.e(LOG_TAG, "G-code export failed", failure)
                deleteFailedCreatedDocument(application, uri)
                supportEvents.record(SupportEvent.GCODE_EXPORT_FAILED)
                false
            }
            synchronized(this@GcodeExportViewModel) {
                mutableState.value.withCompletedExport(operationId, succeeded)?.let { completed ->
                    mutableState.value = completed
                }
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

    private fun copyArtifact(contentResolver: ContentResolver, uri: Uri, source: File) {
        SliceArtifactLease.acquire(source).use {
            contentResolver.openOutputStream(uri, "wt").use { output ->
                requireNotNull(output) { "G-code output is unavailable" }
                source.inputStream().use { input -> input.copyTo(output) }
                output.flush()
            }
        }
    }

    private companion object {
        const val LOG_TAG = "DuckyGcodeExport"
    }
}
