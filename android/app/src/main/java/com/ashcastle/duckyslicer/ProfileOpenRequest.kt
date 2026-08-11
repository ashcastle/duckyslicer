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

internal data class ExternalProfileRequest(
    val id: Long,
    val uri: Uri,
    val startedOperationId: Long? = null,
)

internal fun profileBundleViewUriOrNull(intent: Intent): Uri? {
    if (intent.action != Intent.ACTION_VIEW) return null
    val uri = intent.data ?: return null
    if (!uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) return null
    val mimeType = intent.type
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?: return null
    if (mimeType == PROFILE_BUNDLE_MIME_TYPE) return uri
    val bundleByName = uri.lastPathSegment
        ?.endsWith(PROFILE_BUNDLE_FILE_EXTENSION, ignoreCase = true) == true
    return uri.takeIf {
        bundleByName && mimeType in PROFILE_BUNDLE_COMPATIBLE_MIME_TYPES
    }
}

/**
 * Retains one user-opened profile document without persisting an in-memory operation claim.
 *
 * A configuration change keeps this ViewModel and its exact operation binding. Process
 * restoration reconstructs only the URI request, so an interrupted additive import can be
 * retried safely; exact duplicate profiles are discarded by the atomic merge boundary.
 */
internal class ExternalProfileRequestViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private var nextRequestId = savedStateHandle[KEY_NEXT_REQUEST_ID] ?: 0L
    private val mutableRequest = MutableStateFlow(
        savedStateHandle.get<String>(KEY_REQUEST_URI)?.let { value ->
            ExternalProfileRequest(
                id = savedStateHandle[KEY_REQUEST_ID] ?: nextRequestId,
                uri = Uri.parse(value),
            )
        },
    )
    val request: StateFlow<ExternalProfileRequest?> = mutableRequest.asStateFlow()

    fun enqueue(intent: Intent): Boolean {
        val uri = profileBundleViewUriOrNull(intent) ?: return false
        nextRequestId += 1L
        savedStateHandle[KEY_NEXT_REQUEST_ID] = nextRequestId
        savedStateHandle[KEY_REQUEST_ID] = nextRequestId
        savedStateHandle[KEY_REQUEST_URI] = uri.toString()
        mutableRequest.value = ExternalProfileRequest(nextRequestId, uri)
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
        savedStateHandle[KEY_REQUEST_URI] = null
        mutableRequest.value = null
        return true
    }

    private companion object {
        const val KEY_NEXT_REQUEST_ID = "external_profile_next_request_id"
        const val KEY_REQUEST_ID = "external_profile_request_id"
        const val KEY_REQUEST_URI = "external_profile_request_uri"
    }
}

internal val PROFILE_BUNDLE_COMPATIBLE_MIME_TYPES = setOf(
    "application/json",
    "application/octet-stream",
)
