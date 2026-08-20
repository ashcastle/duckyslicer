package com.ashcastle.duckyslicer

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GcodeThumbnailSettingsTest {
    private val definitions = "64x64/PNG,400x300/QOI"
    private val printer = PrinterProfile.CUSTOM_CARTESIAN.copy(
        gcodeThumbnails = definitions,
    )

    @Test
    fun validationMatchesOrcaThumbnailBounds() {
        assertTrue(gcodeThumbnailDefinitionsAreValid(""))
        assertTrue(gcodeThumbnailDefinitionsAreValid(definitions))
        assertTrue(gcodeThumbnailDefinitionsAreValid("230x110/BTT_TFT"))
        assertTrue(gcodeThumbnailDefinitionsAreValid("96x96/COLPIC"))
        assertEquals("64x48/PNG", canonicalGcodeThumbnailDefinitions(" 064X048/png "))
        assertFalse(gcodeThumbnailDefinitionsAreValid("0x64/PNG"))
        assertFalse(gcodeThumbnailDefinitionsAreValid("1000x64/PNG"))
        assertFalse(gcodeThumbnailDefinitionsAreValid("64x64/GIF"))
        assertFalse(gcodeThumbnailDefinitionsAreValid(List(9) { "16x16/PNG" }.joinToString()))
        assertTrue(ProfileValidation.printer(printer))
        assertFalse(ProfileValidation.printer(printer.copy(gcodeThumbnails = "64x64/GIF")))
    }

    @Test
    fun settingRoundTripsThroughProfilesProjectsAndNativeConfig() {
        val profileJson = printer.toProfileJson()
        assertEquals(definitions, profileJson.getString("gcodeThumbnails"))
        assertEquals(definitions, requireNotNull(profileJson.toPrinterProfileOrNull()).gcodeThumbnails)

        val projectJson = SliceOptions().selectPrinter(printer).toProjectJson()
        assertEquals(94, projectJson.getInt("formatVersion"))
        val restored = requireNotNull(projectJson.toProjectSliceOptionsOrNull())
        assertEquals(definitions, restored.printerProfile.gcodeThumbnails)
        assertEquals(definitions, restored.toNativeConfig().gcodeThumbnails)
    }

    @Test
    fun userProfilePersistenceAndLegacyDefaultRemainStable() {
        val directory = Files.createTempDirectory("duckyslicer-thumbnails-").toFile()
        try {
            val store = ProfileStore(directory.resolve("profiles.json"))
            val saved = store.savePrinter("Thumbnail printer", SliceOptions().selectPrinter(printer))
            assertEquals(
                definitions,
                store.load().printers.single { it.id == saved.id }.gcodeThumbnails,
            )

            val legacy = JSONObject(printer.toProfileJson().toString()).apply {
                remove("gcodeThumbnails")
            }
            assertEquals("", requireNotNull(legacy.toPrinterProfileOrNull()).gcodeThumbnails)
        } finally {
            directory.deleteRecursively()
        }
    }
}
