package com.ashcastle.duckyslicer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstLayerInspectionSettingsTest {
    @Test
    fun firstLayerInspectionRoundTripsAndReachesTheNativeContract() {
        val options = SliceOptions().copy(
            printerProfile = PrinterProfile.U1_04.copy(scanFirstLayer = true),
        )

        assertTrue(options.toNativeConfig().scanFirstLayer)
        val storedProfile = options.printerProfile.toProfileJson()
        assertTrue(storedProfile.getBoolean("scanFirstLayer"))
        assertTrue(requireNotNull(storedProfile.toPrinterProfileOrNull()).scanFirstLayer)

        val storedProject = options.toProjectJson()
        assertEquals(94, storedProject.getInt("formatVersion"))
        val restored = requireNotNull(storedProject.toProjectSliceOptionsOrNull())
        assertTrue(restored.printerProfile.scanFirstLayer)
        assertTrue(restored.toNativeConfig().scanFirstLayer)
    }

    @Test
    fun legacyProfilesDefaultToDisabledInspection() {
        val stored = PrinterProfile.U1_04.toProfileJson()
        stored.remove("scanFirstLayer")

        val restored = requireNotNull(stored.toPrinterProfileOrNull())

        assertFalse(restored.scanFirstLayer)
        assertFalse(SliceOptions().copy(printerProfile = restored).toNativeConfig().scanFirstLayer)
    }

    @Test
    fun projectMigrationDefaultsMissingInspectionToDisabled() {
        val stored = SliceOptions().toProjectJson()
        val legacy = JSONObject(stored.toString()).apply {
            put("formatVersion", 86)
            getJSONObject("printer").remove("scanFirstLayer")
        }

        val restored = requireNotNull(legacy.toProjectSliceOptionsOrNull())

        assertFalse(restored.printerProfile.scanFirstLayer)
    }
}
