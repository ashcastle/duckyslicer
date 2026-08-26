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

internal data class ExternalModelRequest(
    val id: Long,
    val uri: Uri,
    val startedOperationId: Long? = null,
)

internal fun modelDocumentUriOrNull(intent: Intent): Uri? {
    if (intent.action != Intent.ACTION_VIEW && intent.action != Intent.ACTION_SEND) return null
    val uri = when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> sharedDocumentUriOrNull(intent)
        else -> null
    } ?: return null
    if (!uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) return null
    val mimeType = intent.type
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?: return null
    val extension = uri.lastPathSegment
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase(Locale.ROOT)
    val explicitlySupported = mimeType in MODEL_DOCUMENT_MIME_TYPES
    val compatibleByName = when (extension) {
        "stl", "obj" -> mimeType == "application/octet-stream"
        "3mf" -> mimeType in MODEL_DOCUMENT_COMPATIBLE_MIME_TYPES
        else -> false
    }
    return uri.takeIf { explicitlySupported || compatibleByName }
}

@Suppress("DEPRECATION")
internal fun sharedDocumentUriOrNull(intent: Intent): Uri? {
    val clipData = intent.clipData
    val clipUri = when {
        clipData == null -> null
        clipData.itemCount != 1 -> return null
        else -> clipData.getItemAt(0).uri ?: return null
    }
    val extraUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
    if (clipUri != null && extraUri != null && clipUri != extraUri) return null
    return clipUri ?: extraUri
}

/** Retains one externally opened model and binds it to exactly one import operation. */
internal class ExternalModelRequestViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private var nextRequestId = savedStateHandle[KEY_NEXT_REQUEST_ID] ?: 0L
    private val mutableRequest = MutableStateFlow(
        savedStateHandle.get<String>(KEY_REQUEST_URI)?.let { value ->
            ExternalModelRequest(
                id = savedStateHandle[KEY_REQUEST_ID] ?: nextRequestId,
                uri = Uri.parse(value),
            )
        },
    )
    val request: StateFlow<ExternalModelRequest?> = mutableRequest.asStateFlow()

    fun enqueue(intent: Intent): Boolean {
        val uri = modelDocumentUriOrNull(intent) ?: return false
        nextRequestId += 1L
        savedStateHandle[KEY_NEXT_REQUEST_ID] = nextRequestId
        savedStateHandle[KEY_REQUEST_ID] = nextRequestId
        savedStateHandle[KEY_REQUEST_URI] = uri.toString()
        mutableRequest.value = ExternalModelRequest(nextRequestId, uri)
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
        const val KEY_NEXT_REQUEST_ID = "external_model_next_request_id"
        const val KEY_REQUEST_ID = "external_model_request_id"
        const val KEY_REQUEST_URI = "external_model_request_uri"
    }
}

internal val MODEL_DOCUMENT_MIME_TYPES =
    STL_MODEL_MIME_TYPES + THREE_MF_MODEL_MIME_TYPES + OBJ_MODEL_MIME_TYPES

internal val MODEL_DOCUMENT_COMPATIBLE_MIME_TYPES = setOf(
    "application/octet-stream",
    "application/zip",
    "application/x-zip-compressed",
)
