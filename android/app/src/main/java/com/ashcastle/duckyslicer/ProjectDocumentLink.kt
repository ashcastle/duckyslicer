package com.ashcastle.duckyslicer

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.net.URI

internal data class LinkedProjectDocument(
    val uri: String,
    val displayName: String,
) {
    init {
        require(isValidProjectDocumentUri(uri) && normalizedProjectDocumentName(displayName) == displayName) {
            "project_document_link_invalid"
        }
    }

    val contentUri: Uri
        get() = Uri.parse(uri)
}

internal fun List<LinkedProjectDocument>.withRecentProjectDocument(
    document: LinkedProjectDocument,
): List<LinkedProjectDocument> =
    (listOf(document) + filterNot { it.uri == document.uri })
        .take(MAX_RECENT_PROJECT_DOCUMENTS)

internal fun List<LinkedProjectDocument>.withoutRecentProjectDocument(
    uri: String,
): List<LinkedProjectDocument> = filterNot { it.uri == uri }

internal fun normalizedLinkedProjectDocument(
    uri: String,
    displayName: String,
): LinkedProjectDocument? {
    val normalizedUri = uri.trim().takeIf { it.length in 1..MAX_PROJECT_DOCUMENT_URI_LENGTH }
        ?: return null
    if (!isValidProjectDocumentUri(normalizedUri)) return null
    val normalizedName = normalizedProjectDocumentName(displayName) ?: return null
    return LinkedProjectDocument(normalizedUri, normalizedName)
}

private fun isValidProjectDocumentUri(value: String): Boolean {
    if (value.length !in 1..MAX_PROJECT_DOCUMENT_URI_LENGTH || value != value.trim()) return false
    val parsed = runCatching { URI(value) }.getOrNull() ?: return false
    return parsed.scheme == ContentResolver.SCHEME_CONTENT &&
        !parsed.rawAuthority.isNullOrBlank()
}

internal fun normalizedProjectDocumentName(value: String): String? = value
    .replace(Regex("[\\p{Cc}/\\\\]"), " ")
    .trim()
    .take(MAX_PROJECT_DOCUMENT_NAME_LENGTH)
    .trim()
    .takeIf(String::isNotBlank)

internal fun ContentResolver.retainProjectDocumentWritePermission(uri: Uri): Boolean {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
    if (hasProjectDocumentWritePermission(uri)) return true
    val requested = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    listOf(requested, Intent.FLAG_GRANT_WRITE_URI_PERMISSION).forEach { flags ->
        runCatching { takePersistableUriPermission(uri, flags) }
        if (hasProjectDocumentWritePermission(uri)) return true
    }
    return hasProjectDocumentWritePermission(uri)
}

internal fun ContentResolver.hasProjectDocumentWritePermission(uri: Uri): Boolean = runCatching {
    persistedUriPermissions.any { permission ->
        permission.uri == uri && permission.isWritePermission
    }
}.getOrDefault(false)

internal fun ContentResolver.releaseProjectDocumentPermission(uri: Uri): Boolean {
    val permission = runCatching {
        persistedUriPermissions.firstOrNull { it.uri == uri }
    }.getOrNull() ?: return true
    val flags =
        (Intent.FLAG_GRANT_READ_URI_PERMISSION.takeIf { permission.isReadPermission } ?: 0) or
            (Intent.FLAG_GRANT_WRITE_URI_PERMISSION.takeIf { permission.isWritePermission } ?: 0)
    if (flags == 0) return true
    return runCatching { releasePersistableUriPermission(uri, flags) }.isSuccess &&
        !hasProjectDocumentWritePermission(uri)
}

internal fun ContentResolver.linkedProjectDocument(uri: Uri): LinkedProjectDocument? {
    if (!hasProjectDocumentWritePermission(uri)) return null
    val queriedName = runCatching {
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            index.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString)
        }
    }.getOrNull()
    val fallback = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    val displayName = normalizedProjectDocumentName(queriedName.orEmpty())
        ?: normalizedProjectDocumentName(fallback)
        ?: DEFAULT_LINKED_PROJECT_NAME
    return normalizedLinkedProjectDocument(uri.toString(), displayName)
}

private const val MAX_PROJECT_DOCUMENT_URI_LENGTH = 4_096
private const val MAX_PROJECT_DOCUMENT_NAME_LENGTH = 200
internal const val MAX_RECENT_PROJECT_DOCUMENTS = 5
private const val DEFAULT_LINKED_PROJECT_NAME = "DuckySlicer-project.duckyproject"
