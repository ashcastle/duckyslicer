package com.ashcastle.duckyslicer

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrcaProcessConversionTest {
    @Test fun preservesSupportAndShellSettings() {
        val result = convertOrcaProcess(input().put("enable_support", "1")
            .put("support_type", "tree(auto)").put("support_style", "organic")
            .put("support_top_z_distance", "0.15").put("top_shell_layers", "7")
            .put("sparse_infill_pattern", "gyroid"), "user-support", QualityProfile.STANDARD)
        assertTrue(result.profile.supportEnabled)
        assertEquals("tree(auto)", result.profile.supportType)
        assertEquals("organic", result.profile.supportStyle)
        assertEquals(0.15f, result.profile.supportTopZDistance, 0f)
        assertEquals(7, result.profile.topSolidLayers)
        assertTrue(result.unsupportedKeys.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            convertOrcaProcess(input().put("support_type", "invented"), "user-bad", QualityProfile.STANDARD)
        }
    }

    private fun input() = JSONObject().put("type", "process").put("name", "Imported")

    @Test fun relativeSpeedsUseImportedOuterWallSpeed() {
        val result = convertOrcaProcess(input().put("inner_wall_speed", "150%")
            .put("sparse_infill_speed", "125%").put("outer_wall_speed", "80"),
            "user-relative", QualityProfile.STANDARD)
        assertEquals(120f, result.profile.innerWallSpeed, 0f)
        assertEquals(100f, result.profile.sparseInfillSpeed, 0f)
        assertTrue(result.unsupportedKeys.isEmpty())
        val inherited = convertOrcaProcess(input().put("inner_wall_speed", "50%"),
            "user-inherited", QualityProfile.STANDARD)
        assertEquals(QualityProfile.STANDARD.printSpeed / 2f, inherited.profile.innerWallSpeed, 0f)
        assertTrue("outer_wall_speed" in inherited.defaultedKeys)
    }

    @Test fun preservesNumericValuesAndReportsUnmappedSettings() {
        val result = convertOrcaProcess(input().put("layer_height", "0.12")
            .put("sparse_infill_density", "22.5%").put("wall_loops", "3")
            .put("future_setting", "true"), "user-import", QualityProfile.STANDARD)
        assertEquals(0.12f, result.profile.layerHeightMm, 0f)
        assertEquals(0.225f, result.profile.fillDensity, 0f)
        assertEquals(3, result.profile.perimeters)
        assertEquals(setOf("future_setting"), result.unsupportedKeys)
        assertTrue("outer_wall_speed" in result.defaultedKeys)
    }

    @Test fun doesNotSilentlyCoerceInvalidOrRelativeValues() {
        listOf("NaN", "Infinity", "80%", "not-a-number").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                convertOrcaProcess(input().put("outer_wall_speed", value), "user-import", QualityProfile.STANDARD)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            convertOrcaProcess(input().put("wall_loops", "2.5"), "user-import", QualityProfile.STANDARD)
        }
    }
}
