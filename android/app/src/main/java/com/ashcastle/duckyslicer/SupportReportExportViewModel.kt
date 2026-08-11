package com.ashcastle.duckyslicer

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class SupportReportExportOutcome {
    SAVED,
    CANCELED,
    FAILED,
}

internal data class SupportReportExportCompletion(
    val id: Long,
    val outcome: SupportReportExportOutcome,
) {
    val succeeded: Boolean
        get() = outcome == SupportReportExportOutcome.SAVED
}

internal data class SupportReportExportState(
    val activeId: Long? = null,
    val cancellationRequested: Boolean = false,
    val completion: SupportReportExportCompletion? = null,
) {
    val busy: Boolean
        get() = activeId != null
}

internal fun SupportReportExportState.withStartedSupportReportExport(
    operationId: Long,
): SupportReportExportState? {
    if (busy) return null
    return copy(
        activeId = operationId,
        cancellationRequested = false,
        completion = null,
    )
}

internal fun SupportReportExportState.withSupportReportCancellationRequested(
    operationId: Long,
): SupportReportExportState? {
    if (activeId != operationId || cancellationRequested) return null
    return copy(cancellationRequested = true)
}

internal fun SupportReportExportState.withCompletedSupportReportExport(
    operationId: Long,
    requestedOutcome: SupportReportExportOutcome,
): SupportReportExportState? {
    if (activeId != operationId) return null
    val outcome = if (
        cancellationRequested && requestedOutcome == SupportReportExportOutcome.SAVED
    ) {
        SupportReportExportOutcome.CANCELED
    } else {
        requestedOutcome
    }
    return copy(
        activeId = null,
        cancellationRequested = false,
        completion = SupportReportExportCompletion(operationId, outcome),
    )
}

private data class ActiveSupportReportExport(
    val id: Long,
    val cancellation: DocumentTransferCancellation,
)

/** Owns one user-selected support-report write across Activity recreation. */
internal class SupportReportExportViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(SupportReportExportState())
    val state: StateFlow<SupportReportExportState> = mutableState.asStateFlow()
    private val supportEvents = SupportEventJournal(application)
    private var nextOperationId = 0L
    private var activeExport: ActiveSupportReportExport? = null

    @Synchronized
    fun export(uri: Uri, settings: AppSettings): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        val operationId = ++nextOperationId
        val started = mutableState.value.withStartedSupportReportExport(operationId) ?: return false
        val cancellation = DocumentTransferCancellation()
        activeExport = ActiveSupportReportExport(operationId, cancellation)
        mutableState.value = started
        val snapshot = settings.normalized()
        viewModelScope.launch(Dispatchers.IO) {
            val application = getApplication<Application>()
            val outcome = try {
                val report = createSupportReport(application, snapshot)
                val descriptor = requireNotNull(
                    application.contentResolver.openAssetFileDescriptor(
                        uri,
                        "wt",
                        cancellation.providerSignal,
                    ),
                ) { "Support output is unavailable" }
                descriptor.use {
                    descriptor.createOutputStream().use { output ->
                        cancellation.attachOutput(output)
                        try {
                            writeSupportReport(output, report)
                            output.flush()
                            cancellation.complete()
                        } finally {
                            cancellation.detachOutput(output)
                        }
                    }
                }
                SupportReportExportOutcome.SAVED
            } catch (scopeCancellation: CancellationException) {
                cancellation.cancel()
                deleteFailedCreatedDocument(application, uri)
                throw scopeCancellation
            } catch (failure: Exception) {
                deleteFailedCreatedDocument(application, uri)
                if (
                    cancellation.wasRequested() ||
                    failure is DocumentTransferCancelledException
                ) {
                    SupportReportExportOutcome.CANCELED
                } else {
                    if (BuildConfig.DEBUG) Log.e(LOG_TAG, "Support report export failed", failure)
                    supportEvents.record(SupportEvent.SUPPORT_REPORT_EXPORT_FAILED)
                    SupportReportExportOutcome.FAILED
                }
            } finally {
                cancellation.close()
            }
            synchronized(this@SupportReportExportViewModel) {
                if (activeExport?.id == operationId) activeExport = null
                mutableState.value.withCompletedSupportReportExport(operationId, outcome)
                    ?.let { completed -> mutableState.value = completed }
            }
        }
        return true
    }

    fun cancel(): Boolean {
        val active = synchronized(this) { activeExport } ?: return false
        if (!active.cancellation.cancel()) return false
        synchronized(this) {
            mutableState.value.withSupportReportCancellationRequested(active.id)
                ?.let { canceling -> mutableState.value = canceling }
        }
        return true
    }

    override fun onCleared() {
        val active = synchronized(this) { activeExport }
        active?.cancellation?.cancel()
        super.onCleared()
    }

    private companion object {
        const val LOG_TAG = "DuckySupportExport"
    }
}
