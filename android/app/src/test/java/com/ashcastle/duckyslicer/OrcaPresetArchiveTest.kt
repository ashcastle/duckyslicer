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

    @Test fun readsOrcaPrinterBundleWithUntypedUserPresetsAndTrailingManifest() {
        val result = readOrcaPresetArchive(archive(
            "printer/My printer.json" to """{"name":"My printer","inherits":"Base"}""".toByteArray(),
            "filament/PLA.json" to """{"name":"PLA","filament_settings_id":["PLA"]}""".toByteArray(),
            "process/Fine.json" to """{"name":"Fine","print_settings_id":"Fine","layer_height":"0.12"}""".toByteArray(),
            "bundle_structure.json" to """{"bundle_type":"printer config bundle","printer_config":["printer/My printer.json"],"filament_config":["filament/PLA.json"],"process_config":["process/Fine.json"]}""".toByteArray(),
        ))
        assertEquals(listOf("machine", "filament", "process"), result.presets.map { it.getString("type") })
        assertTrue(result.unrecognizedEntries.isEmpty())
    }

    @Test fun readsOrcaFilamentBundleAndStandaloneUserJson() {
        val filament = """{"name":"PLA","filament_settings_id":["PLA"],"nozzle_temperature":["215"]}""".toByteArray()
        assertEquals("filament", readOrcaPresetArchive(filament).presets.single().getString("type"))
        val result = readOrcaPresetArchive(archive(
            "Vendor/PLA.json" to filament,
            "bundle_structure.json" to """{"bundle_type":"filament config bundle","printer_vendor":[{"vendor":"Vendor","filament_path":["Vendor/PLA.json"]}]}""".toByteArray(),
        ))
        assertEquals("filament", result.presets.single().getString("type"))
        assertTrue(result.unrecognizedEntries.isEmpty())
    }

    @Test fun automaticallyRecognizesAllThreeFlatPresetZipExports() {
        for ((key, kind) in listOf("printer_settings_id" to "machine",
            "filament_settings_id" to "filament", "print_settings_id" to "process")) {
            val json = """{"name":"Exported preset","$key":"Exported preset","from":"User"}""".toByteArray()
            val result = readOrcaPresetArchive(archive("Exported preset.json" to json))
            assertEquals(kind, result.presets.single().getString("type"))
        }
    }

    @Test fun rejectsConflictingTypesAndMissingManifestEntries() {
        for (entry in listOf("Fine.json", "missing.json")) {
            assertThrows(IllegalArgumentException::class.java) {
                readOrcaPresetArchive(archive("Fine.json" to preset,
                    "bundle_structure.json" to """{"bundle_type":"printer config bundle","printer_config":["$entry"]}""".toByteArray()))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            readOrcaPresetArchive("""{"name":"Mixed","print_settings_id":"X","printer_settings_id":"Y"}""".toByteArray())
        }
    }

    @Test fun readsSingleJsonAndBundledJsonWithoutExtractingFiles() {
        assertEquals("Fine", readOrcaPresetArchive(preset).presets.single().getString("name"))
        val result = readOrcaPresetArchive(archive("process/Fine.json" to preset,
            "bundle_structure.json" to "{}".toByteArray(), "preview.png" to byteArrayOf(1)))
        assertEquals(1, result.presets.size)
        assertEquals(setOf("bundle_structure.json", "preview.png"), result.unrecognizedEntries.toSet())
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
