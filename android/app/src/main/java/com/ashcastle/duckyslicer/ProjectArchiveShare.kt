package com.ashcastle.duckyslicer

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.nio.file.Files

internal const val PROJECT_ARCHIVE_SHARE_DISPLAY_NAME =
    "DuckySlicer-project$PROJECT_ARCHIVE_FILE_EXTENSION"

internal data class PreparedProjectArchiveShare(
    val file: File,
    val uri: Uri,
)

/** Creates one app-private archive output, replacing the previous shared project. */
internal fun prepareProjectArchiveShare(context: Context): PreparedProjectArchiveShare? {
    var created: File? = null
    return try {
        val filesRoot = context.filesDir.canonicalFile
        val shareRoot = File(filesRoot, PROJECT_ARCHIVE_SHARE_DIRECTORY).canonicalFile
        if (shareRoot.parentFile != filesRoot) return null
        Files.createDirectories(shareRoot.toPath())

        val existing = shareRoot.listFiles()?.toList() ?: return null
        if (existing.any { entry ->
                !entry.isFile || entry.canonicalFile.parentFile != shareRoot ||
                    !entry.name.startsWith(PROJECT_ARCHIVE_SHARE_PREFIX) ||
                    !entry.name.endsWith(PROJECT_ARCHIVE_FILE_EXTENSION)
            }
        ) {
            return null
        }
        existing.forEach { stale -> if (!stale.delete()) return null }

        created = Files.createTempFile(
            shareRoot.toPath(),
            PROJECT_ARCHIVE_SHARE_PREFIX,
            PROJECT_ARCHIVE_FILE_EXTENSION,
        ).toFile().canonicalFile
        if (created.parentFile != shareRoot || created.length() != 0L) {
            created.delete()
            return null
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.project-share",
            created,
            PROJECT_ARCHIVE_SHARE_DISPLAY_NAME,
        )
        PreparedProjectArchiveShare(created, uri)
    } catch (_: Exception) {
        created?.delete()
        null
    }
}

/** Removes only an exact output under the dedicated project-share directory. */
internal fun discardProjectArchiveShare(context: Context, path: String?): Boolean {
    if (path.isNullOrBlank()) return false
    return runCatching {
        val filesRoot = context.filesDir.canonicalFile
        val shareRoot = File(filesRoot, PROJECT_ARCHIVE_SHARE_DIRECTORY).canonicalFile
        val candidate = File(path).canonicalFile
        candidate.parentFile == shareRoot &&
            candidate.name.startsWith(PROJECT_ARCHIVE_SHARE_PREFIX) &&
            candidate.name.endsWith(PROJECT_ARCHIVE_FILE_EXTENSION) &&
            (!candidate.exists() || candidate.delete())
    }.getOrDefault(false)
}

/** Builds a read-only Android share Intent for one completed project archive. */
internal fun projectArchiveShareIntentOrNull(context: Context, uri: Uri?): Intent? {
    if (
        uri?.scheme != ContentResolver.SCHEME_CONTENT ||
        uri.authority != "${context.packageName}.project-share"
    ) {
        return null
    }
    return Intent(Intent.ACTION_SEND).apply {
        type = PROJECT_ARCHIVE_MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, PROJECT_ARCHIVE_SHARE_DISPLAY_NAME)
        clipData = ClipData.newUri(
            context.contentResolver,
            PROJECT_ARCHIVE_SHARE_DISPLAY_NAME,
            uri,
        )
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private const val PROJECT_ARCHIVE_SHARE_DIRECTORY = "project-shares"
private const val PROJECT_ARCHIVE_SHARE_PREFIX = "project-"
