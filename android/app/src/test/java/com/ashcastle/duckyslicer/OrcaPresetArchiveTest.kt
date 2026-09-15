package com.ashcastle.duckyslicer

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class OrcaPresetArchiveTest {
    private val preset = """{"name":"Fine","type":"process","layer_height":"0.12"}""".toByteArray()

    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    @Test fun readsSingleJsonAndBundledJsonWithoutExtractingFiles() {
        assertEquals("Fine", readOrcaPresetArchive(preset).presets.single().getString("name"))
        val result = readOrcaPresetArchive(archive("process/Fine.json" to preset,
            "bundle_structure.json" to "{}".toByteArray(), "preview.png" to byteArrayOf(1)))
        assertEquals(1, result.presets.size)
        assertEquals(listOf("bundle_structure.json", "preview.png"), result.unrecognizedEntries)
    }

    @Test fun refusesUnsafePathsAndArchivesWithoutProfiles() {
        assertThrows(IllegalArgumentException::class.java) {
            readOrcaPresetArchive(archive("../Fine.json" to preset))
        }
        assertThrows(IllegalArgumentException::class.java) { readOrcaPresetArchive("{}".toByteArray()) }
    }

    @Test fun limitsExpandedDataEvenForUnrecognizedEntries() {
        val bytes = archive("huge.dat" to ByteArray(MAX_PROFILE_BUNDLE_BYTES + 1))
        assertThrows(IllegalArgumentException::class.java) { readOrcaPresetArchive(bytes) }
    }

    @Test fun limitsPayloadHiddenInsideDirectoryEntries() {
        val bytes = archive("directory/" to ByteArray(MAX_PROFILE_BUNDLE_BYTES + 1), "Fine.json" to preset)
        val error = assertThrows(IllegalArgumentException::class.java) { readOrcaPresetArchive(bytes) }
        assertEquals("preset_archive_expanded_size", error.message)
        assertEquals(1, readOrcaPresetArchive(archive("directory/" to byteArrayOf(), "Fine.json" to preset)).presets.size)
    }
}
