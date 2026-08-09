package com.ashcastle.duckyslicer

import android.content.Context
import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONObject

internal enum class ForegroundSlicePhase {
    ACTIVE,
    COMPLETED,
    CANCELED,
    FAILED,
}

internal data class ForegroundSliceRecord(
    val requestId: String,
    val phase: ForegroundSlicePhase,
    val outcome: SliceOutcome? = null,
)

/** Cross-process checkpoint for the single user-visible foreground slice. */
internal object ForegroundSliceStore {
    fun begin(context: Context, requestId: String) {
        requireRequestId(requestId)
        check(load(context) == null) { "A foreground slice is already recoverable" }
        write(
            context,
            ForegroundSliceRecord(
                requestId = requestId,
                phase = ForegroundSlicePhase.ACTIVE,
            ),
        )
    }

    fun load(context: Context): ForegroundSliceRecord? {
        val sessionFile = sessionFile(context)
        if (!sessionFile.isFile) return null
        val record = runCatching {
            require(sessionFile.length() in 1..MAX_RECORD_BYTES) {
                "Foreground slice checkpoint is too large"
            }
            decode(context, sessionFile.readText(Charsets.UTF_8))
        }.getOrNull()
        if (record == null) sessionFile.delete()
        return record
    }

    fun complete(context: Context, requestId: String, outcome: SliceOutcome) {
        require(outcome.isRestorableFrom(context.filesDir)) {
            "Foreground slice result is not restorable"
        }
        update(
            context,
            requestId,
            ForegroundSliceRecord(
                requestId = requestId,
                phase = ForegroundSlicePhase.COMPLETED,
                outcome = outcome,
            ),
        )
    }

    fun mark(context: Context, requestId: String, phase: ForegroundSlicePhase) {
        require(phase == ForegroundSlicePhase.CANCELED || phase == ForegroundSlicePhase.FAILED)
        update(context, requestId, ForegroundSliceRecord(requestId, phase))
    }

    fun remove(context: Context, requestId: String) {
        val current = load(context)
        if (current?.requestId == requestId) sessionFile(context).delete()
        cleanupTemporaryFiles(context)
    }

    internal fun fileForTest(context: Context): File = sessionFile(context)

    private fun update(context: Context, requestId: String, replacement: ForegroundSliceRecord) {
        requireRequestId(requestId)
        val current = load(context) ?: return
        if (current.requestId != requestId) return
        write(context, replacement)
    }

    private fun write(context: Context, record: ForegroundSliceRecord) {
        val destination = sessionFile(context)
        val temporary = File(
            destination.parentFile,
            ".${destination.name}.${Process.myPid()}.${UUID.randomUUID()}.tmp",
        )
        val bytes = encode(record).toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_RECORD_BYTES) { "Foreground slice checkpoint is too large" }
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temporary.delete()
        }
    }

    private fun encode(record: ForegroundSliceRecord): String = JSONObject().apply {
        put("version", RECORD_VERSION)
        put("requestId", record.requestId)
        put("phase", record.phase.name)
        record.outcome?.let { outcome ->
            put("outputPath", outcome.output.canonicalPath)
            put("layers", outcome.layers)
            put("estimatedSeconds", outcome.estimatedSeconds.toDouble())
            put("filamentMm", outcome.filamentMm.toDouble())
            put("filamentGrams", outcome.filamentGrams.toDouble())
        }
    }.toString()

    private fun decode(context: Context, text: String): ForegroundSliceRecord {
        val value = JSONObject(text)
        require(value.getInt("version") == RECORD_VERSION) {
            "Unsupported foreground slice checkpoint"
        }
        val requestId = value.getString("requestId")
        requireRequestId(requestId)
        val phase = ForegroundSlicePhase.valueOf(value.getString("phase"))
        val outcome = if (phase == ForegroundSlicePhase.COMPLETED) {
            SliceOutcome(
                output = File(value.getString("outputPath")),
                layers = value.getInt("layers"),
                estimatedSeconds = value.getDouble("estimatedSeconds").toFloat(),
                filamentMm = value.getDouble("filamentMm").toFloat(),
                filamentGrams = value.getDouble("filamentGrams").toFloat(),
            ).also {
                require(it.isRestorableFrom(context.filesDir)) {
                    "Foreground slice result is unavailable"
                }
            }
        } else {
            null
        }
        return ForegroundSliceRecord(requestId, phase, outcome)
    }

    private fun cleanupTemporaryFiles(context: Context) {
        context.filesDir.listFiles { file ->
            file.isFile &&
                file.name.startsWith(".$SESSION_FILE_NAME.") &&
                file.name.endsWith(".tmp")
        }.orEmpty().forEach(File::delete)
    }

    private fun requireRequestId(requestId: String) {
        require(requestId.length in 1..MAX_REQUEST_ID_LENGTH)
        require(UUID.fromString(requestId).toString() == requestId)
    }

    private fun sessionFile(context: Context) = File(context.filesDir, SESSION_FILE_NAME)

    private const val RECORD_VERSION = 1
    private const val SESSION_FILE_NAME = "foreground-slice.session"
    private const val MAX_RECORD_BYTES = 4 * 1_024
    private const val MAX_REQUEST_ID_LENGTH = 128
}
