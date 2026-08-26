package com.ashcastle.duckyslicer

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider

internal const val GCODE_SHARE_MIME_TYPE = "text/x.gcode"

/** Builds a read-only share for one current app-owned slice artifact. */
internal fun gcodeShareIntentOrNull(context: Context, outcome: SliceOutcome): Intent? {
    if (!outcome.isRestorableFrom(context.filesDir)) return null
    val displayName = safeGcodeFileName(outcome.suggestedName)
    val uri = runCatching {
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.slice-share",
            outcome.output,
            displayName,
        )
    }.getOrNull() ?: return null
    return Intent(Intent.ACTION_SEND).apply {
        type = GCODE_SHARE_MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, displayName)
        clipData = ClipData.newUri(context.contentResolver, displayName, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
