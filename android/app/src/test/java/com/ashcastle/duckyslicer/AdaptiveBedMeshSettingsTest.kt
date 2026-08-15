package com.ashcastle.duckyslicer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveBedMeshSettingsTest {
    private val profile = PrinterProfile.CUSTOM_CARTESIAN.copy(
        bedMeshMinX = 10f,
        bedMeshMinY = 11f,
        bedMeshMaxX = 290f,
        bedMeshMaxY = 291f,
        bedMeshProbeDistanceX = 40f,
        bedMeshProbeDistanceY = 41f,
        adaptiveBedMeshMargin = 5f,
    )

    @Test
    fun adaptiveBedMeshRoundTripsAndReachesNativeContract() {
        val options = SliceOptions().copy(printerProfile = profile)
        val native = options.toNativeConfig()

        assertEquals(10f, native.bedMeshMinX)
        assertEquals(11f, native.bedMeshMinY)
        assertEquals(290f, native.bedMeshMaxX)
        assertEquals(291f, native.bedMeshMaxY)
        assertEquals(40f, native.bedMeshProbeDistanceX)
        assertEquals(41f, native.bedMeshProbeDistanceY)
        assertEquals(5f, native.adaptiveBedMeshMargin)

        val storedProfile = profile.toProfileJson()
        assertEquals(10.0, storedProfile.getDouble("bedMeshMinX"), 0.001)
        assertEquals(profile, storedProfile.toPrinterProfileOrNull())

        val storedProject = options.toProjectJson()
        assertEquals(91, storedProject.getInt("formatVersion"))
        val restored = requireNotNull(storedProject.toProjectSliceOptionsOrNull())
        assertEquals(10f, restored.printerProfile.bedMeshMinX)
        assertEquals(11f, restored.printerProfile.bedMeshMinY)
        assertEquals(290f, restored.printerProfile.bedMeshMaxX)
        assertEquals(291f, restored.printerProfile.bedMeshMaxY)
        assertEquals(40f, restored.printerProfile.bedMeshProbeDistanceX)
        assertEquals(41f, restored.printerProfile.bedMeshProbeDistanceY)
        assertEquals(5f, restored.printerProfile.adaptiveBedMeshMargin)
        assertEquals(41f, restored.toNativeConfig().bedMeshProbeDistanceY)
    }

    @Test
    fun legacyProfilesUseOrcaSentinelDefaults() {
        val legacyProfile = JSONObject(profile.toProfileJson().toString()).apply {
            remove("bedMeshMinX")
            remove("bedMeshMinY")
            remove("bedMeshMaxX")
            remove("bedMeshMaxY")
            remove("bedMeshProbeDistanceX")
            remove("bedMeshProbeDistanceY")
            remove("adaptiveBedMeshMargin")
        }
        val restoredProfile = requireNotNull(legacyProfile.toPrinterProfileOrNull())

        assertEquals(-99_999f, restoredProfile.bedMeshMinX)
        assertEquals(-99_999f, restoredProfile.bedMeshMinY)
        assertEquals(99_999f, restoredProfile.bedMeshMaxX)
        assertEquals(99_999f, restoredProfile.bedMeshMaxY)
        assertEquals(50f, restoredProfile.bedMeshProbeDistanceX)
        assertEquals(50f, restoredProfile.bedMeshProbeDistanceY)
        assertEquals(0f, restoredProfile.adaptiveBedMeshMargin)

        val legacyProject = SliceOptions().toProjectJson().apply {
            put("formatVersion", 87)
            getJSONObject("printer").apply {
                remove("bedMeshMinX")
                remove("bedMeshMinY")
                remove("bedMeshMaxX")
                remove("bedMeshMaxY")
                remove("bedMeshProbeDistanceX")
                remove("bedMeshProbeDistanceY")
                remove("adaptiveBedMeshMargin")
            }
        }
        assertEquals(
            -99_999f,
            requireNotNull(legacyProject.toProjectSliceOptionsOrNull()).printerProfile.bedMeshMinX,
        )
    }

    @Test
    fun validationRejectsUnsafeMeshBoundsAndProbeValues() {
        assertTrue(ProfileValidation.printer(profile))
        assertFalse(ProfileValidation.printer(profile.copy(bedMeshMinX = 300f)))
        assertFalse(ProfileValidation.printer(profile.copy(bedMeshProbeDistanceY = -1f)))
        assertFalse(ProfileValidation.printer(profile.copy(adaptiveBedMeshMargin = 100_001f)))
    }
}
