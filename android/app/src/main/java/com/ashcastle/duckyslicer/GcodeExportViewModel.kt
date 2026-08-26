package com.ashcastle.duckyslicer

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.Serializable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class GcodeExportResult {
    SAVED,
    CANCELED,
    FAILED,
}

internal data class GcodeExportCompletion(
    val id: Long,
    val result: GcodeExportResult,
    val totalFiles: Int,
)

internal data class GcodeExportEntry(
    val displayName: String,
    val outcome: SliceOutcome,
) : Serializable {
    init {
        require(displayName == safeGcodeFileName(displayName)) {
            "Invalid G-code export name"
        }
    }
}

internal data class GcodeExportBatch(
    val entries: List<GcodeExportEntry>,
) : Serializable {
    init {
        require(entries.size in 2..MAX_PROJECT_PLATES) { "Invalid G-code export batch" }
        require(entries.map(GcodeExportEntry::displayName).toSet().size == entries.size) {
            "Duplicate G-code export name"
        }
    }
}

internal data class GcodeExportState(
    val activeId: Long? = null,
    val cancellationRequested: Boolean = false,
    val completedFiles: Int = 0,
    val totalFiles: Int = 0,
    val completion: GcodeExportCompletion? = null,
) {
    val busy: Boolean
        get() = activeId != null

    val currentFile: Int?
        get() = if (busy && totalFiles > 1) (completedFiles + 1).coerceAtMost(totalFiles) else null

    init {
        require(totalFiles in 0..MAX_PROJECT_PLATES) { "Invalid G-code export total" }
        require(completedFiles in 0..totalFiles) { "Invalid G-code export progress" }
        require(busy == (totalFiles > 0)) { "G-code export identity is incomplete" }
        require(!cancellationRequested || busy) { "Inactive G-code export cannot be canceling" }
    }
}

internal fun GcodeExportState.withStartedExport(
    operationId: Long,
    totalFiles: Int = 1,
): GcodeExportState? {
    if (busy || completion != null) return null
    if (totalFiles !in 1..MAX_PROJECT_PLATES) return null
    return copy(
        activeId = operationId,
        cancellationRequested = false,
        completedFiles = 0,
        totalFiles = totalFiles,
    )
}

internal fun GcodeExportState.withCancellationRequested(operationId: Long): GcodeExportState? {
    if (activeId != operationId || cancellationRequested || completion != null) return null
    return copy(cancellationRequested = true)
}

internal fun GcodeExportState.withCompletedExport(
    operationId: Long,
    result: GcodeExportResult,
): GcodeExportState? {
    if (activeId != operationId || completion != null) return null
    return copy(
        activeId = null,
        cancellationRequested = false,
        completedFiles = 0,
        totalFiles = 0,
        completion = GcodeExportCompletion(operationId, result, totalFiles),
    )
}

internal fun GcodeExportState.withExportProgress(
    operationId: Long,
    completedFiles: Int,
): GcodeExportState? {
    if (activeId != operationId || completion != null) return null
    if (completedFiles !in this.completedFiles..totalFiles) return null
    return copy(completedFiles = completedFiles)
}

private data class ActiveGcodeExport(
    val id: Long,
    val cancellation: DocumentTransferCancellation,
)

/** Owns one user-selected G-code copy independently of Activity configuration changes. */
internal class GcodeExportViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(GcodeExportState())
    val state: StateFlow<GcodeExportState> = mutableState.asStateFlow()
    private val supportEvents = SupportEventJournal(application)
    private var nextOperationId = 0L
    private var activeExport: ActiveGcodeExport? = null

    @Synchronized
    fun export(uri: Uri, outcome: SliceOutcome): Boolean {
        val application = getApplication<Application>()
        if (
            uri.scheme != ContentResolver.SCHEME_CONTENT ||
            !outcome.isRestorableFrom(application.filesDir)
        ) {
            return false
        }
        return startExport(
            totalFiles = 1,
            export = { cancellation, onFileCompleted ->
                copyArtifact(application.contentResolver, uri, outcome.output, cancellation)
                onFileCompleted()
            },
            rollback = { deleteFailedCreatedDocument(application, uri) },
        )
    }

    @Synchronized
    fun exportAll(treeUri: Uri, batch: GcodeExportBatch): Boolean {
        val application = getApplication<Application>()
        if (
            treeUri.scheme != ContentResolver.SCHEME_CONTENT ||
            !DocumentsContract.isTreeUri(treeUri) ||
            batch.entries.any { !it.outcome.isRestorableFrom(application.filesDir) }
        ) {
            return false
        }
        val createdDocuments = ArrayList<Uri>(batch.entries.size)
        return startExport(
            totalFiles = batch.entries.size,
            export = { cancellation, onFileCompleted ->
                val resolver = application.contentResolver
                val parent = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri),
                )
                batch.entries.forEach { entry ->
                    cancellation.throwIfRequested()
                    val destination = requireNotNull(
                        DocumentsContract.createDocument(
                            resolver,
                            parent,
                            GCODE_DOCUMENT_MIME_TYPE,
                            entry.displayName,
                        ),
                    ) { "G-code document could not be created" }
                    createdDocuments += destination
                    copyArtifact(resolver, destination, entry.outcome.output, cancellation)
                    onFileCompleted()
                }
            },
            rollback = {
                createdDocuments.asReversed().forEach { uri ->
                    deleteFailedCreatedDocument(application, uri)
                }
            },
        )
    }

    private fun startExport(
        totalFiles: Int,
        export: (DocumentTransferCancellation, () -> Unit) -> Unit,
        rollback: () -> Unit,
    ): Boolean {
        val operationId = ++nextOperationId
        val started = mutableState.value.withStartedExport(operationId, totalFiles) ?: return false
        val cancellation = DocumentTransferCancellation()
        activeExport = ActiveGcodeExport(operationId, cancellation)
        mutableState.value = started
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                var completedFiles = 0
                export(cancellation) {
                    completedFiles += 1
                    synchronized(this@GcodeExportViewModel) {
                        mutableState.value.withExportProgress(operationId, completedFiles)
                            ?.let { progress -> mutableState.value = progress }
                    }
                }
                cancellation.complete()
                GcodeExportResult.SAVED
            } catch (scopeCancellation: CancellationException) {
                cancellation.cancel()
                runCatching(rollback)
                GcodeExportResult.CANCELED
            } catch (failure: Exception) {
                runCatching(rollback)
                if (
                    cancellation.wasRequested() ||
                    failure is DocumentTransferCancelledException
                ) {
                    GcodeExportResult.CANCELED
                } else {
                    if (BuildConfig.DEBUG) Log.e(LOG_TAG, "G-code export failed", failure)
                    supportEvents.record(SupportEvent.GCODE_EXPORT_FAILED)
                    GcodeExportResult.FAILED
                }
            } finally {
                cancellation.close()
            }
            synchronized(this@GcodeExportViewModel) {
                if (activeExport?.id == operationId) activeExport = null
                mutableState.value.withCompletedExport(operationId, result)?.let { completed ->
                    mutableState.value = completed
                }
            }
        }
        return true
    }

    fun cancelActiveExport(): Boolean {
        val operation = synchronized(this) { activeExport } ?: return false
        if (!operation.cancellation.cancel()) return false
        synchronized(this) {
            mutableState.value.withCancellationRequested(operation.id)?.let { canceling ->
                mutableState.value = canceling
            }
        }
        return true
    }

    @Synchronized
    fun consumeCompletion(operationId: Long) {
        val current = mutableState.value
        if (current.completion?.id != operationId) return
        mutableState.value = current.copy(completion = null)
    }

    override fun onCleared() {
        val cancellation = synchronized(this) { activeExport?.cancellation }
        cancellation?.cancel()
        super.onCleared()
    }

    private fun copyArtifact(
        contentResolver: ContentResolver,
        uri: Uri,
        source: File,
        cancellation: DocumentTransferCancellation,
    ) {
        try {
            cancellation.throwIfRequested()
            SliceArtifactLease.acquire(source).use {
                source.inputStream().use { input ->
                    cancellation.attachInput(input)
                    try {
                        val descriptor = requireNotNull(
                            contentResolver.openAssetFileDescriptor(
                                uri,
                                "wt",
                                cancellation.providerSignal,
                            ),
                        ) { "G-code output is unavailable" }
                        descriptor.use {
                            descriptor.createOutputStream().use { output ->
                                cancellation.attachOutput(output)
                                try {
                                    copyCancellable(input, output, cancellation)
                                    output.flush()
                                    cancellation.throwIfRequested()
                                } finally {
                                    cancellation.detachOutput(output)
                                }
                            }
                        }
                    } finally {
                        cancellation.detachInput(input)
                    }
                }
            }
        } catch (failure: Exception) {
            if (cancellation.wasRequested()) throw DocumentTransferCancelledException()
            throw failure
        }
    }

    private fun copyCancellable(
        input: InputStream,
        output: OutputStream,
        cancellation: DocumentTransferCancellation,
    ) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            cancellation.throwIfRequested()
            val count = input.read(buffer)
            if (count < 0) return
            output.write(buffer, 0, count)
        }
    }

    private companion object {
        const val LOG_TAG = "DuckyGcodeExport"
        const val COPY_BUFFER_BYTES = 64 * 1_024
    }
}
