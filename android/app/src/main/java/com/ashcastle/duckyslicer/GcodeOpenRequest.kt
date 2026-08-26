package com.ashcastle.duckyslicer

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ExternalGcodeRequest(
    val id: Long,
    val uri: Uri,
    val startedOperationId: Long? = null,
)

internal fun gcodeDocumentUriOrNull(intent: Intent): Uri? {
    if (intent.action != Intent.ACTION_VIEW) return null
    val uri = intent.data ?: return null
    if (!uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) return null
    val mimeType = intent.type
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?: return null
    val extension = uri.lastPathSegment
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase(Locale.ROOT)
    val explicitlySupported = mimeType in GCODE_DOCUMENT_MIME_TYPES
    val compatibleByName = extension == "gcode" && mimeType in GCODE_COMPATIBLE_MIME_TYPES
    return uri.takeIf { explicitlySupported || compatibleByName }
}

/** Retains one externally opened G-code URI and binds it to one preview-import operation. */
internal class ExternalGcodeRequestViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private var nextRequestId = savedStateHandle[KEY_NEXT_REQUEST_ID] ?: 0L
    private val mutableRequest = MutableStateFlow(
        savedStateHandle.get<String>(KEY_REQUEST_URI)?.let { value ->
            ExternalGcodeRequest(
                id = savedStateHandle[KEY_REQUEST_ID] ?: nextRequestId,
                uri = Uri.parse(value),
                startedOperationId = savedStateHandle[KEY_OPERATION_ID],
            )
        },
    )
    val request: StateFlow<ExternalGcodeRequest?> = mutableRequest.asStateFlow()

    fun enqueue(intent: Intent): Boolean {
        val uri = gcodeDocumentUriOrNull(intent) ?: return false
        nextRequestId += 1L
        savedStateHandle[KEY_NEXT_REQUEST_ID] = nextRequestId
        savedStateHandle[KEY_REQUEST_ID] = nextRequestId
        savedStateHandle[KEY_REQUEST_URI] = uri.toString()
        savedStateHandle[KEY_OPERATION_ID] = null
        mutableRequest.value = ExternalGcodeRequest(nextRequestId, uri)
        return true
    }

    fun markStarted(requestId: Long, operationId: Long): Boolean {
        val current = mutableRequest.value ?: return false
        if (current.id != requestId || current.startedOperationId != null || operationId <= 0L) {
            return false
        }
        savedStateHandle[KEY_OPERATION_ID] = operationId
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
        savedStateHandle[KEY_OPERATION_ID] = null
        mutableRequest.value = null
    }

    private companion object {
        const val KEY_NEXT_REQUEST_ID = "external_gcode_next_request_id"
        const val KEY_REQUEST_ID = "external_gcode_request_id"
        const val KEY_REQUEST_URI = "external_gcode_request_uri"
        const val KEY_OPERATION_ID = "external_gcode_operation_id"
    }
}

internal val GCODE_DOCUMENT_MIME_TYPES = setOf(
    "text/x.gcode",
    "application/x-gcode",
    "application/gcode",
)

internal val GCODE_COMPATIBLE_MIME_TYPES = setOf(
    "application/octet-stream",
    "text/plain",
)
