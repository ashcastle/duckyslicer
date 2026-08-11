package com.ashcastle.duckyslicer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

internal class CreatedDocumentWriteCancelledException :
    Exception("created_document_write_canceled")

/** Interrupts only the provider open and streams belonging to one created document. */
internal class CreatedDocumentWriteCancellation {
    private val lock = Any()
    val providerSignal = CancellationSignal()

    @Volatile
    private var cancellationRequested = false
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var completed = false

    fun cancel(): Boolean {
        val resources = synchronized(lock) {
            if (completed || cancellationRequested) return false
            cancellationRequested = true
            output to input
        }
        runCatching { providerSignal.cancel() }
        resources.first.closeQuietly()
        resources.second.closeQuietly()
        return true
    }

    fun throwIfRequested() {
        if (cancellationRequested) throw CreatedDocumentWriteCancelledException()
    }

    fun attachInput(value: InputStream) = attach(value, outputStream = false)

    fun attachOutput(value: OutputStream) = attach(value, outputStream = true)

    fun detachInput(value: InputStream) = detach(value, outputStream = false)

    fun detachOutput(value: OutputStream) = detach(value, outputStream = true)

    fun complete() {
        synchronized(lock) {
            if (cancellationRequested) throw CreatedDocumentWriteCancelledException()
            check(!completed) { "created_document_write_lifecycle_invalid" }
            completed = true
            input = null
            output = null
        }
    }

    fun close() {
        synchronized(lock) {
            completed = true
            input = null
            output = null
        }
    }

    fun wasRequested(): Boolean = cancellationRequested

    private fun attach(value: Closeable, outputStream: Boolean) {
        val rejected = synchronized(lock) {
            if (cancellationRequested || completed) {
                true
            } else {
                if (outputStream) {
                    check(output == null) { "created_document_write_lifecycle_invalid" }
                    output = value as OutputStream
                } else {
                    check(input == null) { "created_document_write_lifecycle_invalid" }
                    input = value as InputStream
                }
                false
            }
        }
        if (rejected) {
            value.closeQuietly()
            throw CreatedDocumentWriteCancelledException()
        }
    }

    private fun detach(value: Closeable, outputStream: Boolean) {
        synchronized(lock) {
            if (outputStream && output === value) output = null
            if (!outputStream && input === value) input = null
        }
    }

    private fun Closeable?.closeQuietly() {
        if (this != null) runCatching { close() }
    }
}

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
