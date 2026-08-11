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

internal data class SupportReportExportCompletion(
    val id: Long,
    val succeeded: Boolean,
)

internal data class SupportReportExportState(
    val activeId: Long? = null,
    val completion: SupportReportExportCompletion? = null,
) {
    val busy: Boolean
        get() = activeId != null
}

internal fun SupportReportExportState.withStartedSupportReportExport(
    operationId: Long,
): SupportReportExportState? {
    if (busy) return null
    return copy(activeId = operationId, completion = null)
}

internal fun SupportReportExportState.withCompletedSupportReportExport(
    operationId: Long,
    succeeded: Boolean,
): SupportReportExportState? {
    if (activeId != operationId) return null
    return copy(
        activeId = null,
        completion = SupportReportExportCompletion(operationId, succeeded),
    )
}

/** Owns one user-selected support-report write across Activity recreation. */
internal class SupportReportExportViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(SupportReportExportState())
    val state: StateFlow<SupportReportExportState> = mutableState.asStateFlow()
    private val supportEvents = SupportEventJournal(application)
    private var nextOperationId = 0L

    @Synchronized
    fun export(uri: Uri, settings: AppSettings): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        val operationId = ++nextOperationId
        val started = mutableState.value.withStartedSupportReportExport(operationId) ?: return false
        mutableState.value = started
        val snapshot = settings.normalized()
        viewModelScope.launch(Dispatchers.IO) {
            val application = getApplication<Application>()
            val succeeded = try {
                val report = createSupportReport(application, snapshot)
                application.contentResolver.openOutputStream(uri, "wt").use { output ->
                    writeSupportReport(
                        requireNotNull(output) { "Support output is unavailable" },
                        report,
                    )
                }
                true
            } catch (cancellation: CancellationException) {
                deleteFailedCreatedDocument(application, uri)
                throw cancellation
            } catch (failure: Exception) {
                if (BuildConfig.DEBUG) Log.e(LOG_TAG, "Support report export failed", failure)
                deleteFailedCreatedDocument(application, uri)
                supportEvents.record(SupportEvent.SUPPORT_REPORT_EXPORT_FAILED)
                false
            }
            synchronized(this@SupportReportExportViewModel) {
                mutableState.value.withCompletedSupportReportExport(operationId, succeeded)
                    ?.let { completed -> mutableState.value = completed }
            }
        }
        return true
    }

    private companion object {
        const val LOG_TAG = "DuckySupportExport"
    }
}
