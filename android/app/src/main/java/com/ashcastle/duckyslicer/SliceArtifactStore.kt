package com.ashcastle.duckyslicer

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class GcodeImportCanceledException : Exception()

internal class SliceArtifactStore(
    private val filesRoot: File,
    private val maximumOutputBytes: Long = MAXIMUM_OUTPUT_BYTES,
    private val maximumRetainedBytes: Long = MAXIMUM_RETAINED_BYTES,
    private val minimumFreeBytes: Long = MINIMUM_FREE_BYTES,
    private val emergencyFreeBytes: Long = EMERGENCY_FREE_BYTES,
    private val maximumRetainedOutputs: Int = MAXIMUM_RETAINED_OUTPUTS,
    private val usableSpace: () -> Long = { filesRoot.usableSpace },
    transientRoots: List<File> = listOf(filesRoot),
) {
    private val outputRoot = File(filesRoot, OUTPUT_DIRECTORY)
    private val transientRoots = transientRoots.map(File::getCanonicalFile).distinct()
    private val nativeOutputs = this.transientRoots.map { File(it, NATIVE_OUTPUT_NAME) }

    fun recover() {
        requirePolicy()
        check(filesRoot.isDirectory || filesRoot.mkdirs()) { "G-code storage is unavailable" }
        check(outputRoot.isDirectory || outputRoot.mkdirs()) { "G-code storage is unavailable" }
        cleanupTemporaryFiles()
        nativeOutputs.forEach { nativeOutput ->
            check(deleteUnlocked(nativeOutput)) { "Stale G-code output is still in use" }
        }
        prune()
    }

    fun prepareForSlice() {
        recover()
        check(usableSpace() >= minimumFreeBytes) { "Not enough free space for slicing" }
    }

    fun persist(source: File): File {
        requirePolicy()
        val canonicalSource = source.canonicalFile
        val sourceParent = requireNotNull(canonicalSource.parentFile) {
            "G-code output has no private parent"
        }
        require(
            sourceParent in transientRoots &&
                canonicalSource.name == NATIVE_OUTPUT_NAME,
        ) {
            "G-code output is outside private transient storage"
        }
        val sourceBytes = canonicalSource.length()
        if (!canonicalSource.isFile || sourceBytes !in 1..maximumOutputBytes) {
            deleteUnlocked(canonicalSource)
            error("G-code output size is invalid")
        }
        FileOutputStream(canonicalSource, true).use { output -> output.fd.sync() }
        check(outputRoot.isDirectory || outputRoot.mkdirs()) { "G-code storage is unavailable" }
        cleanupTemporaryFiles()
        prune()

        val output = File(outputRoot, "${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}.gcode")
        val temporary = File(outputRoot, ".${output.name}.tmp")
        try {
            if (!move(canonicalSource, output)) {
                check(usableSpace() >= saturatingAdd(sourceBytes, minimumFreeBytes)) {
                    "Not enough free space to retain G-code"
                }
                copyBounded(canonicalSource, temporary, sourceBytes)
                check(move(temporary, output)) { "G-code could not be finalized" }
                deleteUnlocked(canonicalSource)
            }
            check(output.isFile && output.length() == sourceBytes) { "G-code output is unavailable" }
            prune(protected = setOf(output.canonicalFile))
            if (usableSpace() < minimumFreeBytes) {
                deleteUnlocked(output)
                error("Not enough free space to retain G-code")
            }
            return output
        } catch (failure: Exception) {
            deleteUnlocked(output)
            throw failure
        } finally {
            deleteUnlocked(temporary)
        }
    }

    /** Copies an external G-code stream into the same bounded, private artifact store. */
    fun importDocument(
        input: InputStream,
        protected: Set<File> = emptySet(),
        cancellationRequested: () -> Boolean = { false },
    ): File {
        requirePolicy()
        check(filesRoot.isDirectory || filesRoot.mkdirs()) { "G-code storage is unavailable" }
        check(outputRoot.isDirectory || outputRoot.mkdirs()) { "G-code storage is unavailable" }
        cleanupTemporaryFiles()
        prune(protected)
        check(usableSpace() >= minimumFreeBytes) { "Not enough free space to import G-code" }

        val output = File(outputRoot, "${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}.gcode")
        val temporary = File(outputRoot, ".${output.name}.tmp")
        try {
            var copied = 0L
            FileOutputStream(temporary).use { destination ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    if (cancellationRequested()) throw GcodeImportCanceledException()
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied = saturatingAdd(copied, count.toLong())
                    require(copied <= maximumOutputBytes) { "G-code document is too large" }
                    destination.write(buffer, 0, count)
                }
                destination.flush()
                destination.fd.sync()
            }
            if (cancellationRequested()) throw GcodeImportCanceledException()
            require(copied > 0L) { "G-code document is empty" }
            check(move(temporary, output)) { "G-code document could not be finalized" }
            check(output.isFile && output.length() == copied) { "G-code document is unavailable" }
            prune(protected + output)
            if (usableSpace() < minimumFreeBytes) {
                deleteUnlocked(output)
                error("Not enough free space to retain G-code")
            }
            return output
        } catch (failure: Exception) {
            deleteUnlocked(output)
            throw failure
        } finally {
            deleteUnlocked(temporary)
        }
    }

    /** Deletes only a complete direct child owned by this store. */
    fun discard(file: File): Boolean {
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return false
        val canonicalRoot = runCatching { outputRoot.canonicalFile }.getOrNull() ?: return false
        if (canonical.parentFile != canonicalRoot || canonical.extension != "gcode") return false
        return deleteUnlocked(canonical)
    }

    fun activeOutputIsUnsafe(): Boolean {
        return nativeOutputs.any { nativeOutput ->
            nativeOutput.isFile &&
                (nativeOutput.length() > maximumOutputBytes || usableSpace() < emergencyFreeBytes)
        }
    }

    internal fun pruneForTest(protected: Set<File> = emptySet()) = prune(protected)

    private fun prune(protected: Set<File> = emptySet()) {
        if (!outputRoot.isDirectory) return
        val protectedPaths = protected.mapNotNullTo(HashSet()) {
            runCatching { it.canonicalPath }.getOrNull()
        }
        val candidates = outputRoot.listFiles { file ->
            file.isFile && file.extension == "gcode"
        }.orEmpty().sortedWith(compareByDescending<File>(File::lastModified).thenByDescending(File::getName))
        var retainedCount = candidates.size
        var retainedBytes = candidates.fold(0L) { total, file -> saturatingAdd(total, file.length()) }
        candidates.asReversed().forEach { candidate ->
            val overBudget = retainedCount > maximumRetainedOutputs ||
                retainedBytes > maximumRetainedBytes || usableSpace() < minimumFreeBytes
            if (!overBudget) return
            val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return@forEach
            if (canonical.parentFile != outputRoot.canonicalFile || canonical.path in protectedPaths) {
                return@forEach
            }
            val length = canonical.length()
            if (deleteUnlocked(canonical)) {
                retainedCount -= 1
                retainedBytes = (retainedBytes - length).coerceAtLeast(0L)
            }
        }
    }

    private fun cleanupTemporaryFiles() {
        outputRoot.listFiles { file -> file.isFile && file.name.endsWith(".tmp") }
            .orEmpty()
            .forEach(::deleteUnlocked)
    }

    private fun copyBounded(source: File, destination: File, expectedBytes: Long) {
        var copied = 0L
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    require(copied <= maximumOutputBytes && copied <= expectedBytes) {
                        "G-code changed while being retained"
                    }
                    output.write(buffer, 0, count)
                }
                output.flush()
                output.fd.sync()
            }
        }
        check(copied == expectedBytes) { "G-code changed while being retained" }
    }

    private fun move(source: File, destination: File): Boolean = try {
        Files.move(
            source.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
        )
        true
    } catch (_: AtomicMoveNotSupportedException) {
        runCatching { Files.move(source.toPath(), destination.toPath()) }.isSuccess
    } catch (_: Exception) {
        false
    }

    private fun deleteUnlocked(file: File): Boolean {
        if (!file.isFile) return true
        return try {
            RandomAccessFile(file, "rw").use { randomAccess ->
                val lock = try {
                    randomAccess.channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (lock == null) return false
                lock.use { file.delete() }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun requirePolicy() {
        require(maximumOutputBytes > 0L)
        require(maximumRetainedBytes >= maximumOutputBytes)
        require(minimumFreeBytes > emergencyFreeBytes && emergencyFreeBytes > 0L)
        require(maximumRetainedOutputs > 0)
    }

    companion object {
        const val OUTPUT_DIRECTORY = "slices"
        const val NATIVE_OUTPUT_NAME = "output.gcode"
        const val MAXIMUM_OUTPUT_BYTES = 1L * 1_024 * 1_024 * 1_024
        const val MAXIMUM_RETAINED_BYTES = 1L * 1_024 * 1_024 * 1_024
        const val MINIMUM_FREE_BYTES = 512L * 1_024 * 1_024
        const val EMERGENCY_FREE_BYTES = 64L * 1_024 * 1_024
        const val MAXIMUM_RETAINED_OUTPUTS = 8
        private const val COPY_BUFFER_BYTES = 64 * 1_024
    }
}

internal class SliceArtifactLease private constructor(
    private val canonicalPath: String,
) : Closeable {
    override fun close() = SliceArtifactLeases.release(canonicalPath)

    companion object {
        fun acquire(file: File): SliceArtifactLease =
            SliceArtifactLease(SliceArtifactLeases.acquire(file))
    }
}

private object SliceArtifactLeases {
    private data class Entry(
        val stream: FileInputStream,
        val lock: FileLock,
        var references: Int,
    )

    private val entries = HashMap<String, Entry>()

    @Synchronized
    fun acquire(file: File): String {
        val canonical = file.canonicalFile
        require(canonical.isFile && canonical.length() > 0L) { "G-code output is unavailable" }
        entries[canonical.path]?.let { existing ->
            existing.references += 1
            return canonical.path
        }
        val stream = FileInputStream(canonical)
        try {
            val lock = stream.channel.lock(0L, Long.MAX_VALUE, true)
            if (!canonical.isFile || canonical.length() <= 0L) {
                lock.release()
                error("G-code output was removed during lease acquisition")
            }
            entries[canonical.path] = Entry(stream, lock, 1)
            return canonical.path
        } catch (failure: Exception) {
            stream.close()
            throw failure
        }
    }

    @Synchronized
    fun release(canonicalPath: String) {
        val entry = entries[canonicalPath] ?: return
        entry.references -= 1
        if (entry.references > 0) return
        entries.remove(canonicalPath)
        runCatching { entry.lock.release() }
        runCatching { entry.stream.close() }
    }
}

private fun saturatingAdd(left: Long, right: Long): Long =
    if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
