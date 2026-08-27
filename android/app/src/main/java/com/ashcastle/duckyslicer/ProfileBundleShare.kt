package com.ashcastle.duckyslicer

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.nio.file.Files

internal const val PROFILE_BUNDLE_SHARE_DISPLAY_NAME =
    "DuckySlicer-profiles$PROFILE_BUNDLE_FILE_EXTENSION"

internal data class PreparedProfileBundleShare(
    val file: File,
    val uri: Uri,
)

/** Creates one bounded app-private output that can be filled by the retained exporter. */
internal fun prepareProfileBundleShare(context: Context): PreparedProfileBundleShare? {
    var created: File? = null
    return try {
        val filesRoot = context.filesDir.canonicalFile
        val shareRoot = File(filesRoot, PROFILE_BUNDLE_SHARE_DIRECTORY).canonicalFile
        if (shareRoot.parentFile != filesRoot) return null
        Files.createDirectories(shareRoot.toPath())

        val existing = shareRoot.listFiles()?.toList() ?: return null
        if (existing.any { entry ->
                !entry.isFile || entry.canonicalFile.parentFile != shareRoot ||
                    !entry.name.startsWith(PROFILE_BUNDLE_SHARE_PREFIX) ||
                    !entry.name.endsWith(PROFILE_BUNDLE_FILE_EXTENSION)
            }
        ) {
            return null
        }
        val deleteCount = (existing.size - PROFILE_BUNDLE_SHARE_RETAINED_FILES + 1)
            .coerceAtLeast(0)
        existing
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .take(deleteCount)
            .forEach { stale -> if (!stale.delete()) return null }

        created = Files.createTempFile(
            shareRoot.toPath(),
            PROFILE_BUNDLE_SHARE_PREFIX,
            PROFILE_BUNDLE_FILE_EXTENSION,
        ).toFile().canonicalFile
        if (created.parentFile != shareRoot || created.length() != 0L) {
            created.delete()
            return null
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.profile-share",
            created,
            PROFILE_BUNDLE_SHARE_DISPLAY_NAME,
        )
        PreparedProfileBundleShare(created, uri)
    } catch (_: Exception) {
        created?.delete()
        null
    }
}

/** Removes only a file inside the dedicated profile-share directory. */
internal fun discardProfileBundleShare(context: Context, path: String?): Boolean {
    if (path.isNullOrBlank()) return false
    return runCatching {
        val filesRoot = context.filesDir.canonicalFile
        val shareRoot = File(filesRoot, PROFILE_BUNDLE_SHARE_DIRECTORY).canonicalFile
        val candidate = File(path).canonicalFile
        candidate.parentFile == shareRoot &&
            candidate.name.startsWith(PROFILE_BUNDLE_SHARE_PREFIX) &&
            candidate.name.endsWith(PROFILE_BUNDLE_FILE_EXTENSION) &&
            (!candidate.exists() || candidate.delete())
    }.getOrDefault(false)
}

/** Builds a read-only share Intent for one successfully exported profile bundle. */
internal fun profileBundleShareIntentOrNull(context: Context, uri: Uri?): Intent? {
    if (
        uri?.scheme != ContentResolver.SCHEME_CONTENT ||
        uri.authority != "${context.packageName}.profile-share"
    ) {
        return null
    }
    return Intent(Intent.ACTION_SEND).apply {
        type = PROFILE_BUNDLE_MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, PROFILE_BUNDLE_SHARE_DISPLAY_NAME)
        clipData = ClipData.newUri(
            context.contentResolver,
            PROFILE_BUNDLE_SHARE_DISPLAY_NAME,
            uri,
        )
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private const val PROFILE_BUNDLE_SHARE_DIRECTORY = "profile-shares"
private const val PROFILE_BUNDLE_SHARE_PREFIX = "profiles-"
private const val PROFILE_BUNDLE_SHARE_RETAINED_FILES = 3
