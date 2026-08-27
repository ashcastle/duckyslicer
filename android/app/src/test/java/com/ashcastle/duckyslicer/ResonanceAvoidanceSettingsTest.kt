package com.ashcastle.duckyslicer

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResonanceAvoidanceSettingsTest {
    @Test
    fun validatesSpeedRange() {
        val profile = PrinterProfile.CUSTOM_CARTESIAN.copy(
            resonanceAvoidance = true,
            minResonanceAvoidanceSpeed = 40f,
            maxResonanceAvoidanceSpeed = 90f,
        )
        assertTrue(ProfileValidation.printer(profile))
        assertFalse(ProfileValidation.printer(profile.copy(minResonanceAvoidanceSpeed = 91f)))
        assertFalse(ProfileValidation.printer(profile.copy(maxResonanceAvoidanceSpeed = 2_001f)))
    }

    @Test
    fun persistsAndReachesNativeConfig() {
        val directory = Files.createTempDirectory("duckyslicer-resonance-").toFile()
        try {
            val printer = PrinterProfile.CUSTOM_CARTESIAN.copy(
                resonanceAvoidance = true,
                minResonanceAvoidanceSpeed = 40f,
                maxResonanceAvoidanceSpeed = 90f,
            )
            val options = SliceOptions().selectPrinter(printer)
            val saved = ProfileStore(directory.resolve("profiles.json"))
                .savePrinter("Resonance tuned", options)
            val restored = ProfileStore(directory.resolve("profiles.json"))
                .load().printers.single { it.id == saved.id }
            val native = SliceOptions().selectPrinter(restored).toNativeConfig()

            assertTrue(restored.resonanceAvoidance)
            assertEquals(40f, restored.minResonanceAvoidanceSpeed)
            assertEquals(90f, restored.maxResonanceAvoidanceSpeed)
            assertTrue(native.resonanceAvoidance)
            assertEquals(40f, native.minResonanceAvoidanceSpeed)
            assertEquals(90f, native.maxResonanceAvoidanceSpeed)
        } finally {
            directory.deleteRecursively()
        }
    }
}
