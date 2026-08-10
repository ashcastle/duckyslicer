package com.ashcastle.duckyslicer

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableJsonFileTest {
    @Test
    fun validPrimaryCreatesBackupAndCorruptionRecoversIt() = withStore { file, store ->
        file.writeText("""{"version":1,"value":"duck"}""")

        val first = store.read(::versionOne)
        assertEquals(DurableJsonStatus.PRIMARY, first.status)
        assertEquals("duck", first.value?.getString("value"))
        assertTrue(store.backup.isFile)

        file.writeText("{broken")
        val recovered = store.read(::versionOne)
        assertEquals(DurableJsonStatus.RECOVERED_BACKUP, recovered.status)
        assertEquals("duck", recovered.value?.getString("value"))
        assertEquals(store.backup.readText(), file.readText())
    }

    @Test
    fun writesKeepPreviousGenerationAndRecoverAfterInterruptedPrimary() = withStore { file, store ->
        store.write(JSONObject().put("version", 1).put("value", "first"), ::versionOne)
        store.write(JSONObject().put("version", 1).put("value", "second"), ::versionOne)

        assertEquals("second", JSONObject(file.readText()).getString("value"))
        assertEquals("first", JSONObject(store.backup.readText()).getString("value"))
        file.writeText("")
        val recovered = store.read(::versionOne)
        assertEquals(DurableJsonStatus.RECOVERED_BACKUP, recovered.status)
        assertEquals("first", recovered.value?.getString("value"))
    }

    @Test
    fun unreadableGenerationsAreNeverOverwritten() = withStore { file, store ->
        file.writeText("{broken-primary")
        store.backup.writeText("{broken-backup")
        val primaryBytes = file.readBytes()
        val backupBytes = store.backup.readBytes()

        assertEquals(DurableJsonStatus.UNREADABLE, store.read(::versionOne).status)
        assertThrows(IllegalStateException::class.java) {
            store.write(JSONObject().put("version", 1), ::versionOne)
        }
        assertTrue(primaryBytes.contentEquals(file.readBytes()))
        assertTrue(backupBytes.contentEquals(store.backup.readBytes()))
    }

    @Test
    fun futurePrimaryDoesNotFallBackToOrOverwriteOlderBackup() = withStore { file, store ->
        file.writeText("""{"version":2,"value":"future"}""")
        store.backup.writeText("""{"version":1,"value":"old"}""")
        val primaryBytes = file.readBytes()
        val backupBytes = store.backup.readBytes()
        val compatible: (JSONObject) -> Boolean = { it.optInt("version", 0) <= 1 }

        val read = store.read(::versionOne, compatible)

        assertEquals(DurableJsonStatus.INCOMPATIBLE, read.status)
        assertThrows(IllegalStateException::class.java) {
            store.write(JSONObject().put("version", 1), ::versionOne, compatible)
        }
        assertTrue(primaryBytes.contentEquals(file.readBytes()))
        assertTrue(backupBytes.contentEquals(store.backup.readBytes()))
    }

    @Test
    fun validPrimaryRemainsReadableWhenBackupCannotBeRefreshed() = withStore { file, store ->
        file.writeText("""{"version":1,"value":"primary"}""")
        assertTrue(store.backup.mkdir())
        File(store.backup, "unexpected-entry").writeText("keep")
        val primaryBytes = file.readBytes()

        val read = store.read(::versionOne)

        assertEquals(DurableJsonStatus.PRIMARY_WITHOUT_BACKUP, read.status)
        assertEquals("primary", read.value?.getString("value"))
        assertTrue(!read.status.mutationSafe)
        assertThrows(IllegalStateException::class.java) {
            store.write(JSONObject().put("version", 1).put("value", "replacement"), ::versionOne)
        }
        assertTrue(primaryBytes.contentEquals(file.readBytes()))
        assertTrue(File(store.backup, "unexpected-entry").isFile)
    }

    @Test
    fun validBackupRemainsReadableWhenPrimaryCannotBeRepaired() = withStore { file, store ->
        assertTrue(file.mkdir())
        File(file, "unexpected-entry").writeText("keep")
        store.backup.writeText("""{"version":1,"value":"backup"}""")
        val backupBytes = store.backup.readBytes()

        val read = store.read(::versionOne)

        assertEquals(DurableJsonStatus.BACKUP_ONLY, read.status)
        assertEquals("backup", read.value?.getString("value"))
        assertTrue(!read.status.mutationSafe)
        assertThrows(IllegalStateException::class.java) {
            store.write(JSONObject().put("version", 1).put("value", "replacement"), ::versionOne)
        }
        assertTrue(backupBytes.contentEquals(store.backup.readBytes()))
        assertTrue(File(file, "unexpected-entry").isFile)
    }

    @Test
    fun oversizedMalformedUtf8DeepAndFutureJsonAreRejected() = withStore(maximumBytes = 128) { file, store ->
        file.writeBytes(ByteArray(129) { 'x'.code.toByte() })
        assertEquals(DurableJsonStatus.UNREADABLE, store.read(::versionOne).status)

        file.writeBytes(byteArrayOf('{'.code.toByte(), 0xc3.toByte(), 0x28, '}'.code.toByte()))
        assertEquals(DurableJsonStatus.UNREADABLE, store.read(::versionOne).status)

        file.writeText("""{"version":2}""")
        assertEquals(DurableJsonStatus.UNREADABLE, store.read(::versionOne).status)

        val deep = "{\"value\":" + "[".repeat(65) + "0" + "]".repeat(65) + "}"
        assertThrows(IllegalArgumentException::class.java) {
            parseBoundedJsonObject(deep.toByteArray(), 4_096)
        }
    }

    private fun versionOne(root: JSONObject): JSONObject? =
        root.takeIf { it.optInt("version", 0) == 1 }

    private fun withStore(
        maximumBytes: Int = 4_096,
        block: (File, DurableJsonFile) -> Unit,
    ) {
        val directory = Files.createTempDirectory("duckyslicer-durable-json-").toFile()
        try {
            val file = File(directory, "state.json")
            block(file, DurableJsonFile(file, maximumBytes))
        } finally {
            directory.deleteRecursively()
        }
    }
}
