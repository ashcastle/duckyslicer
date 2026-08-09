package com.ashcastle.duckyslicer

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SliceArtifactStoreTest {
    @Test
    fun pruningEnforcesCountAndByteBudgetsOldestFirst() = withRoot { root ->
        val outputs = root.resolve("slices").apply { mkdirs() }
        val oldest = outputs.resolve("oldest.gcode").apply { writeBytes(ByteArray(5)) }
        val middle = outputs.resolve("middle.gcode").apply { writeBytes(ByteArray(5)) }
        val newest = outputs.resolve("newest.gcode").apply { writeBytes(ByteArray(5)) }
        oldest.setLastModified(1_000)
        middle.setLastModified(2_000)
        newest.setLastModified(3_000)
        val store = testStore(root, maximumRetainedBytes = 12, maximumRetainedOutputs = 3)

        store.pruneForTest()

        assertFalse(oldest.exists())
        assertTrue(middle.isFile)
        assertTrue(newest.isFile)
        assertEquals(10L, outputs.listFiles().orEmpty().sumOf(File::length))
    }

    @Test
    fun activeReaderLeasePreventsDeletionUntilItCloses() = withRoot { root ->
        val outputs = root.resolve("slices").apply { mkdirs() }
        val old = outputs.resolve("old.gcode").apply {
            writeBytes(ByteArray(5))
            setLastModified(1_000)
        }
        val current = outputs.resolve("current.gcode").apply {
            writeBytes(ByteArray(5))
            setLastModified(2_000)
        }
        val store = testStore(root, maximumOutputBytes = 5, maximumRetainedBytes = 5)

        SliceArtifactLease.acquire(old).use {
            store.pruneForTest(protected = setOf(current))
            assertTrue("Leased output must remain readable", old.isFile)
            assertTrue(current.isFile)
        }
        store.pruneForTest(protected = setOf(current))

        assertFalse("Closed oldest output should satisfy the byte budget", old.exists())
        assertTrue(current.isFile)
    }

    @Test
    fun oversizedNativeOutputIsRejectedAndRemoved() = withRoot { root ->
        val native = root.resolve(SliceArtifactStore.NATIVE_OUTPUT_NAME).apply {
            writeBytes(ByteArray(11))
        }
        val store = testStore(root, maximumOutputBytes = 10, maximumRetainedBytes = 20)

        assertThrows(IllegalStateException::class.java) { store.persist(native) }

        assertFalse("Rejected output must release its disk space", native.exists())
        assertTrue(root.resolve("slices").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun preparationRecoversStaleWorkAndFreesTheReserve() = withRoot { root ->
        val outputs = root.resolve("slices").apply { mkdirs() }
        val old = outputs.resolve("old.gcode").apply { writeBytes(ByteArray(20)) }
        val temporary = outputs.resolve(".partial.gcode.tmp").apply { writeBytes(ByteArray(7)) }
        val native = root.resolve(SliceArtifactStore.NATIVE_OUTPUT_NAME).apply { writeBytes(ByteArray(9)) }
        val usable = {
            100L - root.walkTopDown().filter(File::isFile).sumOf(File::length)
        }
        val store = testStore(
            root,
            maximumOutputBytes = 30,
            maximumRetainedBytes = 30,
            minimumFreeBytes = 90,
            emergencyFreeBytes = 10,
            usableSpace = usable,
        )

        store.prepareForSlice()

        assertFalse(old.exists())
        assertFalse(temporary.exists())
        assertFalse(native.exists())
        assertTrue(usable() >= 90)
    }

    @Test
    fun persistencePublishesOneCompleteFileAndPrunesPreviousOutput() = withRoot { root ->
        val outputs = root.resolve("slices").apply { mkdirs() }
        val previous = outputs.resolve("previous.gcode").apply {
            writeBytes(ByteArray(6) { 1 })
            setLastModified(1_000)
        }
        val native = root.resolve(SliceArtifactStore.NATIVE_OUTPUT_NAME).apply {
            writeBytes(ByteArray(7) { 2 })
        }
        val store = testStore(root, maximumOutputBytes = 10, maximumRetainedBytes = 10)

        val retained = store.persist(native)

        assertFalse(native.exists())
        assertFalse(previous.exists())
        assertTrue(retained.isFile)
        assertEquals(7L, retained.length())
        assertTrue(retained.readBytes().all { it == 2.toByte() })
        assertTrue(outputs.listFiles { file -> file.name.endsWith(".tmp") }.orEmpty().isEmpty())
    }

    @Test
    fun privateCacheOutputIsAcceptedAndRecovered() = withRoot { root ->
        val cache = root.resolve("cache").apply { mkdirs() }
        val store = testStore(root, transientRoots = listOf(root, cache))
        val native = cache.resolve(SliceArtifactStore.NATIVE_OUTPUT_NAME).apply {
            writeBytes(ByteArray(7) { 3 })
        }

        val retained = store.persist(native)

        assertEquals(7L, retained.length())
        assertFalse(native.exists())
        cache.resolve(SliceArtifactStore.NATIVE_OUTPUT_NAME).writeBytes(ByteArray(3))
        store.recover()
        assertFalse(cache.resolve(SliceArtifactStore.NATIVE_OUTPUT_NAME).exists())
    }

    @Test
    fun activeOutputGuardRequiresAFileAndDetectsSizeOrEmergencySpace() = withRoot { root ->
        var free = 100L
        val store = testStore(
            root,
            maximumOutputBytes = 10,
            maximumRetainedBytes = 20,
            minimumFreeBytes = 20,
            emergencyFreeBytes = 5,
            usableSpace = { free },
        )
        assertFalse(store.activeOutputIsUnsafe())

        val native = root.resolve(SliceArtifactStore.NATIVE_OUTPUT_NAME).apply {
            writeBytes(ByteArray(10))
        }
        assertFalse(store.activeOutputIsUnsafe())
        native.appendBytes(byteArrayOf(0))
        assertTrue(store.activeOutputIsUnsafe())
        native.writeBytes(ByteArray(1))
        free = 4
        assertTrue(store.activeOutputIsUnsafe())
    }

    private fun testStore(
        root: File,
        maximumOutputBytes: Long = 10,
        maximumRetainedBytes: Long = 12,
        minimumFreeBytes: Long = 2,
        emergencyFreeBytes: Long = 1,
        maximumRetainedOutputs: Int = 8,
        usableSpace: () -> Long = { 1_000L },
        transientRoots: List<File> = listOf(root),
    ) = SliceArtifactStore(
        filesRoot = root,
        maximumOutputBytes = maximumOutputBytes,
        maximumRetainedBytes = maximumRetainedBytes,
        minimumFreeBytes = minimumFreeBytes,
        emergencyFreeBytes = emergencyFreeBytes,
        maximumRetainedOutputs = maximumRetainedOutputs,
        usableSpace = usableSpace,
        transientRoots = transientRoots,
    )

    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("duckyslicer-artifacts-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
