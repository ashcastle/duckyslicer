package com.ashcastle.duckyslicer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** Best-effort rollback for a document returned by CreateDocument after a failed write. */
internal fun deleteFailedCreatedDocument(context: Context, uri: Uri) {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return
    val resolver = context.contentResolver
    runCatching {
        if (DocumentsContract.isDocumentUri(context, uri)) {
            DocumentsContract.deleteDocument(resolver, uri)
        } else {
            resolver.delete(uri, null, null)
        }
    }
}
