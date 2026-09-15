package com.ashcastle.duckyslicer

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class OrcaProjectSettingsTest {
    private fun archive(entries: Map<String, String>, check: (File) -> Unit) {
        val file = Files.createTempFile("project-settings", ".3mf").toFile()
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                entries.forEach { (name, data) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(data.toByteArray())
                    zip.closeEntry()
                }
            }
            check(file)
        } finally { file.delete() }
    }

    @Test fun preservesExactSettingsAndMultimaterialVectors() = archive(mapOf(
        "Metadata/project_settings.config" to """{"layer_height":"0.235","filament_settings_id":["PLA","PETG"],"nozzle_temperature":["210","240"],"unknown_setting":"preserve"}""",
        "3D/3dmodel.model" to "geometry handled by engine",
    )) { file ->
        val settings = requireNotNull(readOrcaProjectSettings(file))
        assertEquals("0.235", settings.getString("layer_height"))
        assertEquals("240", settings.getJSONArray("nozzle_temperature").getString(1))
        assertEquals("preserve", settings.getString("unknown_setting"))
    }

    @Test fun geometryOnlyArchiveDoesNotInventSettings() = archive(mapOf(
        "3D/3dmodel.model" to "geometry",
    )) { assertNull(readOrcaProjectSettings(it)) }

    @Test fun malformedSettingsAreNotTreatedAsMissing() = archive(mapOf(
        "Metadata/project_settings.config" to "not json",
    )) { file -> assertThrows(Exception::class.java) { readOrcaProjectSettings(file) } }

    @Test fun cancellationStopsBeforeParsing() = archive(mapOf(
        "Metadata/project_settings.config" to "{}",
    )) { file -> assertThrows(ProjectEditCancelledException::class.java) {
        readOrcaProjectSettings(file) { true }
    } }
}
