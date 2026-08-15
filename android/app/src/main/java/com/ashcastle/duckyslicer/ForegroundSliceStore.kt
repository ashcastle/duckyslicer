package com.ashcastle.duckyslicer

import android.content.Context
import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONArray
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
    val plateId: String? = null,
)

/** Cross-process checkpoint for the single user-visible foreground slice. */
internal object ForegroundSliceStore {
    private val localLock = Any()

    fun begin(context: Context, requestId: String, plateId: String) {
        requireRequestId(requestId)
        requirePlateId(plateId)
        withStoreLock(context) {
            check(loadUnlocked(context) == null) { "A foreground slice is already recoverable" }
            writeUnlocked(
                context,
                ForegroundSliceRecord(
                    requestId = requestId,
                    phase = ForegroundSlicePhase.ACTIVE,
                    plateId = plateId,
                ),
            )
        }
    }

    fun load(context: Context): ForegroundSliceRecord? = withStoreLock(context) {
        loadUnlocked(context)
    }

    private fun loadUnlocked(context: Context): ForegroundSliceRecord? {
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
        withStoreLock(context) {
            val current = loadUnlocked(context)
            if (current?.requestId == requestId) sessionFile(context).delete()
            cleanupTemporaryFiles(context)
        }
    }

    internal fun fileForTest(context: Context): File = sessionFile(context)

    private fun update(context: Context, requestId: String, replacement: ForegroundSliceRecord) {
        requireRequestId(requestId)
        withStoreLock(context) {
            val current = loadUnlocked(context) ?: return@withStoreLock
            if (current.requestId != requestId) return@withStoreLock
            writeUnlocked(context, replacement.copy(plateId = current.plateId))
        }
    }

    private fun writeUnlocked(context: Context, record: ForegroundSliceRecord) {
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

    private inline fun <T> withStoreLock(context: Context, action: () -> T): T =
        synchronized(localLock) {
            RandomAccessFile(File(context.filesDir, LOCK_FILE_NAME), "rw").use { lockFile ->
                lockFile.channel.use { channel ->
                    val fileLock = channel.lock()
                    try {
                        action()
                    } finally {
                        fileLock.release()
                    }
                }
            }
        }

    private fun encode(record: ForegroundSliceRecord): String = JSONObject().apply {
        put("version", RECORD_VERSION)
        put("requestId", record.requestId)
        put("phase", record.phase.name)
        record.plateId?.let { put("plateId", it) }
        record.outcome?.let { outcome ->
            put("outputPath", outcome.output.canonicalPath)
            put("layers", outcome.layers)
            put("estimatedSeconds", outcome.estimatedSeconds.toDouble())
            put("filamentMm", outcome.filamentMm.toDouble())
            put("filamentGrams", outcome.filamentGrams.toDouble())
            put("suggestedName", outcome.suggestedName)
            put("warningCodes", JSONArray(outcome.warnings.map(SliceWarningCode::storageValue)))
        }
    }.toString()

    private fun decode(context: Context, text: String): ForegroundSliceRecord {
        val value = JSONObject(text)
        val version = value.getInt("version")
        require(version in MIN_RECORD_VERSION..RECORD_VERSION) {
            "Unsupported foreground slice checkpoint"
        }
        val requestId = value.getString("requestId")
        requireRequestId(requestId)
        val plateId = if (version >= 2) {
            value.getString("plateId").also(::requirePlateId)
        } else {
            null
        }
        val phase = ForegroundSlicePhase.valueOf(value.getString("phase"))
        val outcome = if (phase == ForegroundSlicePhase.COMPLETED) {
            SliceOutcome(
                output = File(value.getString("outputPath")),
                layers = value.getInt("layers"),
                estimatedSeconds = value.getDouble("estimatedSeconds").toFloat(),
                filamentMm = value.getDouble("filamentMm").toFloat(),
                filamentGrams = value.getDouble("filamentGrams").toFloat(),
                suggestedName = if (version >= 3) {
                    safeGcodeFileName(value.getString("suggestedName"))
                } else {
                    "model.gcode"
                },
                warnings = if (version >= 4) {
                    val warningCodes = requireNotNull(value.optJSONArray("warningCodes")) {
                        "Slice warnings are unavailable"
                    }
                    require(warningCodes.length() <= MAX_SLICE_WARNING_CODES) {
                        "Too many slice warnings"
                    }
                    parseSliceWarningCodes(
                        List(warningCodes.length(), warningCodes::getString),
                    )
                } else {
                    emptySet()
                },
            ).also {
                require(it.isRestorableFrom(context.filesDir)) {
                    "Foreground slice result is unavailable"
                }
            }
        } else {
            null
        }
        return ForegroundSliceRecord(requestId, phase, outcome, plateId)
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

    private fun requirePlateId(plateId: String) {
        require(plateId.length in 1..ProjectStore.MAX_ID_LENGTH)
    }

    private fun sessionFile(context: Context) = File(context.filesDir, SESSION_FILE_NAME)

    private const val MIN_RECORD_VERSION = 1
    private const val RECORD_VERSION = 4
    private const val SESSION_FILE_NAME = "foreground-slice.session"
    private const val LOCK_FILE_NAME = "foreground-slice.lock"
    private const val MAX_RECORD_BYTES = 4 * 1_024
    private const val MAX_REQUEST_ID_LENGTH = 128
}
