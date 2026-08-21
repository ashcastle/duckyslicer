package com.ashcastle.duckyslicer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelapseSettingsTest {
    @Test
    fun smoothTimelapseRoundTripsAndReachesTheNativeContract() {
        val options = SliceOptions().copy(
            printSequence = "by layer",
            gcodeSettings = GcodeSettings(timelapseType = "smooth"),
        )

        assertTrue(ProfileValidation.slicing(options.quality.copy(
            printSequence = options.printSequence,
            gcodeSettings = options.gcodeSettings,
        )))
        assertEquals("smooth", options.toNativeConfig().timelapseType)

        val stored = options.toProjectJson()
        assertEquals(95, stored.getInt("formatVersion"))
        assertEquals(
            "smooth",
            stored.getJSONObject("slicing").getString("timelapseType"),
        )
        val restored = requireNotNull(stored.toProjectSliceOptionsOrNull())
        assertEquals("smooth", restored.gcodeSettings.timelapseType)
        assertEquals("smooth", restored.toNativeConfig().timelapseType)
    }

    @Test
    fun legacyProjectsDefaultToTraditionalTimelapse() {
        val stored = SliceOptions().toProjectJson()
        val legacy = JSONObject(stored.toString()).apply {
            put("formatVersion", 85)
            getJSONObject("slicing").remove("timelapseType")
        }

        val restored = requireNotNull(legacy.toProjectSliceOptionsOrNull())

        assertEquals("traditional", restored.gcodeSettings.timelapseType)
        assertEquals("traditional", restored.toNativeConfig().timelapseType)
    }

    @Test
    fun invalidAndSequentialSmoothModesAreRejectedBeforeNativeSlicing() {
        assertFalse(
            ProfileValidation.slicing(
                QualityProfile.STANDARD.copy(
                    gcodeSettings = GcodeSettings(timelapseType = "unknown"),
                ),
            ),
        )
        assertFalse(
            ProfileValidation.slicing(
                QualityProfile.STANDARD.copy(
                    printSequence = "by object",
                    gcodeSettings = GcodeSettings(timelapseType = "smooth"),
                ),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            SliceOptions().copy(
                printSequence = "by object",
                gcodeSettings = GcodeSettings(timelapseType = "smooth"),
            ).toNativeConfig()
        }
    }

    @Test
    fun editorSelectionsNeverLeaveSmoothTimelapseInSequentialMode() {
        val smooth = SliceOptions().withTimelapseSelection("smooth")
        assertEquals("by layer", smooth.printSequence)
        assertEquals("smooth", smooth.gcodeSettings.timelapseType)
        assertTrue(smooth.wipeTowerEnabled)
        assertTrue(smooth.toNativeConfig().wipeTowerEnabled)

        val sequential = smooth.withPrintSequenceSelection("by object")
        assertEquals("by object", sequential.printSequence)
        assertEquals("traditional", sequential.gcodeSettings.timelapseType)
    }
}
