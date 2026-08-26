package com.ashcastle.duckyslicer

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

internal data class ExternalProjectRequest(
    val id: Long,
    val uri: Uri,
    val startedOperationId: Long? = null,
)

internal fun projectArchiveViewUriOrNull(intent: Intent): Uri? {
    if (intent.action != Intent.ACTION_VIEW) return null
    val uri = intent.data ?: return null
    if (!uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) return null
    val mimeType = intent.type
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?: return null
    if (mimeType == PROJECT_ARCHIVE_MIME_TYPE) return uri
    val archiveByName = uri.lastPathSegment
        ?.endsWith(PROJECT_ARCHIVE_FILE_EXTENSION, ignoreCase = true) == true
    return uri.takeIf {
        archiveByName && mimeType in PROJECT_ARCHIVE_COMPATIBLE_MIME_TYPES
    }
}

/**
 * Retains one externally opened project and binds it to exactly one import operation.
 *
 * A configuration change keeps the in-memory operation claim. Process restoration rebuilds
 * only the URI request, so a possibly interrupted archive import returns to explicit replacement
 * confirmation when the restored workspace is non-empty.
 */
internal class ExternalProjectRequestViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private var nextRequestId = savedStateHandle[KEY_NEXT_REQUEST_ID] ?: 0L
    private val mutableRequest = MutableStateFlow(
        savedStateHandle.get<String>(KEY_REQUEST_URI)?.let { value ->
            ExternalProjectRequest(
                id = savedStateHandle[KEY_REQUEST_ID] ?: nextRequestId,
                uri = Uri.parse(value),
            )
        },
    )
    val request: StateFlow<ExternalProjectRequest?> = mutableRequest.asStateFlow()

    fun enqueue(intent: Intent): Boolean {
        val uri = projectArchiveViewUriOrNull(intent) ?: return false
        nextRequestId += 1L
        savedStateHandle[KEY_NEXT_REQUEST_ID] = nextRequestId
        savedStateHandle[KEY_REQUEST_ID] = nextRequestId
        savedStateHandle[KEY_REQUEST_URI] = uri.toString()
        mutableRequest.value = ExternalProjectRequest(nextRequestId, uri)
        return true
    }

    fun markStarted(requestId: Long, operationId: Long): Boolean {
        val current = mutableRequest.value ?: return false
        if (current.id != requestId || current.startedOperationId != null || operationId <= 0L) {
            return false
        }
        mutableRequest.value = current.copy(startedOperationId = operationId)
        return true
    }

    fun consume(requestId: Long, operationId: Long): Boolean {
        val current = mutableRequest.value ?: return false
        if (current.id != requestId || current.startedOperationId != operationId) return false
        clear()
        return true
    }

    fun discardUnstarted(requestId: Long): Boolean {
        val current = mutableRequest.value ?: return false
        if (current.id != requestId || current.startedOperationId != null) return false
        clear()
        return true
    }

    private fun clear() {
        savedStateHandle[KEY_REQUEST_URI] = null
        mutableRequest.value = null
    }

    private companion object {
        const val KEY_NEXT_REQUEST_ID = "external_project_next_request_id"
        const val KEY_REQUEST_ID = "external_project_request_id"
        const val KEY_REQUEST_URI = "external_project_request_uri"
    }
}

internal val PROJECT_ARCHIVE_COMPATIBLE_MIME_TYPES = setOf(
    "application/zip",
    "application/x-zip-compressed",
    "application/octet-stream",
)
