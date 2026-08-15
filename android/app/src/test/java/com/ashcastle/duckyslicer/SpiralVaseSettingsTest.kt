package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpiralVaseSettingsTest {
    @Test
    fun enablingSpiralModeAppliesRequiredCompanionSettings() {
        val enabled = SliceOptions(
            perimeters = 4,
            fillDensity = 0.24f,
            topSolidLayers = 6,
            supportEnabled = true,
            supportCoverage = SupportCoverageSettings(enforcedLayers = 8),
        ).withSpiralMode(true)

        assertTrue(enabled.spiralMode)
        assertEquals(1, enabled.perimeters)
        assertEquals(0f, enabled.fillDensity)
        assertEquals(0, enabled.topSolidLayers)
        assertFalse(enabled.supportEnabled)
        assertEquals(0, enabled.supportCoverage.enforcedLayers)
    }

    @Test
    fun disablingSpiralModePreservesCompanionSettings() {
        val enabled = SliceOptions(
            spiralMode = true,
            perimeters = 1,
            fillDensity = 0f,
            topSolidLayers = 0,
            supportEnabled = false,
        )

        val disabled = enabled.withSpiralMode(false)

        assertFalse(disabled.spiralMode)
        assertEquals(enabled.perimeters, disabled.perimeters)
        assertEquals(enabled.fillDensity, disabled.fillDensity)
        assertEquals(enabled.topSolidLayers, disabled.topSolidLayers)
        assertEquals(enabled.supportEnabled, disabled.supportEnabled)
    }
}
