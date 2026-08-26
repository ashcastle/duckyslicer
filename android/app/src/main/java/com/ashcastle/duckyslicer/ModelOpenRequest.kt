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
    val uris: List<Uri>,
    val startedOperationId: Long? = null,
)

internal fun modelDocumentUrisOrNull(intent: Intent): List<Uri>? {
    val uris = when (intent.action) {
        Intent.ACTION_VIEW -> intent.data?.let(::listOf)
        Intent.ACTION_SEND -> sharedDocumentUriOrNull(intent)?.let(::listOf)
        Intent.ACTION_SEND_MULTIPLE -> sharedDocumentUrisOrNull(intent)
        else -> null
    } ?: return null
    if (uris.isEmpty() || uris.size > ProjectStore.MAX_PROJECT_OBJECTS) return null
    if (uris.distinct().size != uris.size) return null
    val mimeType = intent.type
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?: return null
    val explicitlySupported = mimeType in MODEL_DOCUMENT_MIME_TYPES
    return uris.takeIf { documents ->
        documents.all { uri ->
            if (!uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) {
                return@all false
            }
            val extension = uri.lastPathSegment
                ?.substringAfterLast('.', missingDelimiterValue = "")
                ?.lowercase(Locale.ROOT)
            val compatibleByName = when (extension) {
                "stl", "obj" -> mimeType == "application/octet-stream"
                "3mf" -> mimeType in MODEL_DOCUMENT_COMPATIBLE_MIME_TYPES
                else -> false
            }
            explicitlySupported || compatibleByName
        }
    }
}

internal fun modelDocumentUriOrNull(intent: Intent): Uri? =
    modelDocumentUrisOrNull(intent)?.singleOrNull()

@Suppress("DEPRECATION")
internal fun sharedDocumentUriOrNull(intent: Intent): Uri? {
    val clipData = intent.clipData
    val clipUri = when {
        clipData == null -> null
        clipData.itemCount != 1 -> return null
        else -> clipData.getItemAt(0).uri ?: return null
    }
    val extraUri = if (intent.hasExtra(Intent.EXTRA_STREAM)) {
        val extra = try {
            intent.extras?.get(Intent.EXTRA_STREAM)
        } catch (_: RuntimeException) {
            return null
        }
        extra as? Uri ?: return null
    } else {
        null
    }
    if (clipUri != null && extraUri != null && clipUri != extraUri) return null
    return clipUri ?: extraUri
}

@Suppress("DEPRECATION")
internal fun sharedDocumentUrisOrNull(intent: Intent): List<Uri>? {
    val clipData = intent.clipData
    val clipUris = when {
        clipData == null -> null
        clipData.itemCount !in 1..ProjectStore.MAX_PROJECT_OBJECTS -> return null
        else -> {
            val values = ArrayList<Uri>(clipData.itemCount)
            for (index in 0 until clipData.itemCount) {
                values += clipData.getItemAt(index).uri ?: return null
            }
            values
        }
    }
    val extraUris = if (intent.hasExtra(Intent.EXTRA_STREAM)) {
        val extra = try {
            intent.extras?.get(Intent.EXTRA_STREAM)
        } catch (_: RuntimeException) {
            return null
        }
        if (extra !is ArrayList<*> || extra.any { it !is Uri }) return null
        extra.filterIsInstance<Uri>()
    } else {
        null
    }
    if (extraUris != null && extraUris.size !in 1..ProjectStore.MAX_PROJECT_OBJECTS) return null
    if (clipUris != null && extraUris != null && clipUris != extraUris) return null
    return clipUris ?: extraUris
}

/** Retains one externally opened model batch and binds it to exactly one import operation. */
internal class ExternalModelRequestViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private var nextRequestId = savedStateHandle[KEY_NEXT_REQUEST_ID] ?: 0L
    private val mutableRequest = MutableStateFlow(
        savedStateHandle.get<ArrayList<String>>(KEY_REQUEST_URIS)?.let { values ->
            ExternalModelRequest(
                id = savedStateHandle[KEY_REQUEST_ID] ?: nextRequestId,
                uris = values.map(Uri::parse),
            )
        },
    )
    val request: StateFlow<ExternalModelRequest?> = mutableRequest.asStateFlow()

    fun enqueue(intent: Intent): Boolean {
        val uris = modelDocumentUrisOrNull(intent) ?: return false
        nextRequestId += 1L
        savedStateHandle[KEY_NEXT_REQUEST_ID] = nextRequestId
        savedStateHandle[KEY_REQUEST_ID] = nextRequestId
        savedStateHandle[KEY_REQUEST_URIS] = ArrayList(uris.map(Uri::toString))
        mutableRequest.value = ExternalModelRequest(nextRequestId, uris)
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
        savedStateHandle[KEY_REQUEST_URIS] = null
        mutableRequest.value = null
    }

    private companion object {
        const val KEY_NEXT_REQUEST_ID = "external_model_next_request_id"
        const val KEY_REQUEST_ID = "external_model_request_id"
        const val KEY_REQUEST_URIS = "external_model_request_uris"
    }
}

internal val MODEL_DOCUMENT_MIME_TYPES =
    STL_MODEL_MIME_TYPES + THREE_MF_MODEL_MIME_TYPES + OBJ_MODEL_MIME_TYPES

internal val MODEL_DOCUMENT_COMPATIBLE_MIME_TYPES = setOf(
    "application/octet-stream",
    "application/zip",
    "application/x-zip-compressed",
)
