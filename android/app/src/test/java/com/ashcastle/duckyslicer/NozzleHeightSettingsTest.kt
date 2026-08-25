package com.ashcastle.duckyslicer

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NozzleHeightSettingsTest {
    private val printer = PrinterProfile.CUSTOM_CARTESIAN.copy(nozzleHeight = 4.76f)

    @Test
    fun validationMatchesTheNativeSafetyBoundary() {
        assertTrue(ProfileValidation.printer(printer.copy(nozzleHeight = 0.1f)))
        assertTrue(ProfileValidation.printer(printer.copy(nozzleHeight = 100f)))
        assertFalse(ProfileValidation.printer(printer.copy(nozzleHeight = 0.09f)))
        assertFalse(ProfileValidation.printer(printer.copy(nozzleHeight = 100.1f)))
        assertFalse(ProfileValidation.printer(printer.copy(nozzleHeight = Float.NaN)))
        assertFalse(ProfileValidation.printer(printer.copy(nozzleHeight = Float.POSITIVE_INFINITY)))
    }

    @Test
    fun settingRoundTripsThroughProfilesProjectsAndNativeConfig() {
        val restoredPrinter = requireNotNull(printer.toProfileJson().toPrinterProfileOrNull())
        assertEquals(4.76f, restoredPrinter.nozzleHeight)

        val project = SliceOptions().selectPrinter(printer).toProjectJson()
        assertEquals(99, project.getInt("formatVersion"))
        val restored = requireNotNull(project.toProjectSliceOptionsOrNull())
        assertEquals(4.76f, restored.printerProfile.nozzleHeight)
        assertEquals(4.76f, restored.toNativeConfig().nozzleHeight)
    }

    @Test
    fun userProfilePersistenceAndLegacyDefaultRemainStable() {
        val directory = Files.createTempDirectory("duckyslicer-nozzle-height-").toFile()
        val file = directory.resolve("profiles.json")
        try {
            val store = ProfileStore(file)
            val saved = store.savePrinter("Tall nozzle", SliceOptions().selectPrinter(printer))
            assertEquals(
                4.76f,
                store.load().printers.single { it.id == saved.id }.nozzleHeight,
            )

            val legacy = JSONObject(printer.toProfileJson().toString()).apply {
                remove("nozzleHeight")
            }
            assertEquals(2.5f, requireNotNull(legacy.toPrinterProfileOrNull()).nozzleHeight)
        } finally {
            directory.deleteRecursively()
        }
    }
}
