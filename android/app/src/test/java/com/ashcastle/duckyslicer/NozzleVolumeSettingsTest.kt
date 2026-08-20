package com.ashcastle.duckyslicer

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NozzleVolumeSettingsTest {
    private val printer = PrinterProfile.CUSTOM_CARTESIAN.copy(nozzleVolume = 143f)

    @Test
    fun validationMatchesTheNativeSafetyBoundary() {
        assertTrue(ProfileValidation.printer(printer.copy(nozzleVolume = 0f)))
        assertTrue(ProfileValidation.printer(printer.copy(nozzleVolume = 1_000f)))
        assertFalse(ProfileValidation.printer(printer.copy(nozzleVolume = -0.1f)))
        assertFalse(ProfileValidation.printer(printer.copy(nozzleVolume = 1_000.1f)))
        assertFalse(ProfileValidation.printer(printer.copy(nozzleVolume = Float.NaN)))
        assertFalse(ProfileValidation.printer(printer.copy(nozzleVolume = Float.POSITIVE_INFINITY)))
    }

    @Test
    fun settingRoundTripsThroughProfilesProjectsAndNativeConfig() {
        val restoredPrinter = requireNotNull(printer.toProfileJson().toPrinterProfileOrNull())
        assertEquals(143f, restoredPrinter.nozzleVolume)

        val project = SliceOptions().selectPrinter(printer).toProjectJson()
        assertEquals(94, project.getInt("formatVersion"))
        val restored = requireNotNull(project.toProjectSliceOptionsOrNull())
        assertEquals(143f, restored.printerProfile.nozzleVolume)
        assertEquals(143f, restored.toNativeConfig().nozzleVolume)
    }

    @Test
    fun userProfilePersistenceAndLegacyDefaultRemainStable() {
        val directory = Files.createTempDirectory("duckyslicer-nozzle-volume-").toFile()
        val file = directory.resolve("profiles.json")
        try {
            val store = ProfileStore(file)
            val saved = store.savePrinter("Cutter-equipped printer", SliceOptions().selectPrinter(printer))
            assertEquals(
                143f,
                store.load().printers.single { it.id == saved.id }.nozzleVolume,
            )

            val legacy = JSONObject(printer.toProfileJson().toString()).apply {
                remove("nozzleVolume")
            }
            assertEquals(0f, requireNotNull(legacy.toPrinterProfileOrNull()).nozzleVolume)
        } finally {
            directory.deleteRecursively()
        }
    }
}
