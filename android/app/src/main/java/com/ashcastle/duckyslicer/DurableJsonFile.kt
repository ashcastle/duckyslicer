package com.ashcastle.duckyslicer

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

internal enum class DurableJsonStatus {
    MISSING,
    PRIMARY,
    RECOVERED_BACKUP,
    INCOMPATIBLE,
    UNREADABLE,
}

internal data class DurableJsonRead<T>(
    val value: T?,
    val status: DurableJsonStatus,
)

/**
 * Stores bounded JSON with a last-known-good backup.
 *
 * Existing unreadable data is never replaced. A valid primary refreshes the backup,
 * while a valid backup repairs a missing or corrupt primary before returning.
 */
internal class DurableJsonFile(
    private val primary: File,
    private val maximumBytes: Int,
) {
    internal val backup = File(primary.parentFile, "${primary.name}.bak")
    private val temporary = File(primary.parentFile, "${primary.name}.tmp")
    private val backupTemporary = File(primary.parentFile, "${primary.name}.bak.tmp")

    @Synchronized
    fun <T : Any> read(
        parser: (JSONObject) -> T?,
        compatible: (JSONObject) -> Boolean = { true },
    ): DurableJsonRead<T> {
        when (val primaryCandidate = readCandidate(primary, parser, compatible)) {
            is CandidateResult.Valid -> {
                refreshBackup(primaryCandidate.bytes)
                return DurableJsonRead(primaryCandidate.value, DurableJsonStatus.PRIMARY)
            }
            CandidateResult.Incompatible -> {
                return DurableJsonRead(null, DurableJsonStatus.INCOMPATIBLE)
            }
            CandidateResult.Invalid -> Unit
        }
        when (val backupCandidate = readCandidate(backup, parser, compatible)) {
            is CandidateResult.Valid -> {
                install(primary, temporary, backupCandidate.bytes)
                return DurableJsonRead(backupCandidate.value, DurableJsonStatus.RECOVERED_BACKUP)
            }
            CandidateResult.Incompatible -> {
                return DurableJsonRead(null, DurableJsonStatus.INCOMPATIBLE)
            }
            CandidateResult.Invalid -> Unit
        }
        val status = if (!primary.exists() && !backup.exists()) {
            DurableJsonStatus.MISSING
        } else {
            DurableJsonStatus.UNREADABLE
        }
        return DurableJsonRead(null, status)
    }

    @Synchronized
    fun <T : Any> write(
        root: JSONObject,
        parser: (JSONObject) -> T?,
        compatible: (JSONObject) -> Boolean = { true },
    ) {
        val existing = read(parser, compatible)
        check(existing.status !in setOf(DurableJsonStatus.UNREADABLE, DurableJsonStatus.INCOMPATIBLE)) {
            "saved_data_unreadable"
        }
        val bytes = root.toString(2).toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..maximumBytes) { "saved_data_too_large" }
        check(runCatching {
            val parsed = parseBoundedJsonObject(bytes, maximumBytes)
            compatible(parsed) && parser(parsed) != null
        }.getOrDefault(false)) {
            "saved_data_invalid"
        }
        install(primary, temporary, bytes)
    }

    private fun <T : Any> readCandidate(
        file: File,
        parser: (JSONObject) -> T?,
        compatible: (JSONObject) -> Boolean,
    ): CandidateResult<T> {
        if (!file.isFile || file.length() !in 1..maximumBytes.toLong()) {
            return CandidateResult.Invalid
        }
        return try {
            val bytes = file.readBytes()
            val root = parseBoundedJsonObject(bytes, maximumBytes)
            if (!compatible(root)) return CandidateResult.Incompatible
            val value = parser(root) ?: return CandidateResult.Invalid
            CandidateResult.Valid(bytes, value)
        } catch (_: Exception) {
            CandidateResult.Invalid
        }
    }

    private fun refreshBackup(bytes: ByteArray) {
        val current = backup.takeIf(File::isFile)?.let { runCatching { it.readBytes() }.getOrNull() }
        if (current?.contentEquals(bytes) == true) return
        install(backup, backupTemporary, bytes)
    }

    private fun install(destination: File, staging: File, bytes: ByteArray) {
        val parent = destination.parentFile
        check(parent?.isDirectory == true || parent?.mkdirs() == true) {
            "saved_data_directory_unavailable"
        }
        FileOutputStream(staging).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        try {
            Files.move(
                staging.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                staging.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            staging.delete()
        }
    }

    private sealed interface CandidateResult<out T> {
        data class Valid<T>(val bytes: ByteArray, val value: T) : CandidateResult<T>
        data object Invalid : CandidateResult<Nothing>
        data object Incompatible : CandidateResult<Nothing>
    }
}
