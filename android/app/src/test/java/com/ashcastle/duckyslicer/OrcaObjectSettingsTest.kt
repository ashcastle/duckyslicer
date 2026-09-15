package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OrcaObjectSettingsTest {
    @Test fun exactValuesAndPercentUnitsArePreserved() {
        val result = orcaObjectOverrides(mapOf("layer_height" to "0.235",
            "sparse_infill_density" to "1%", "wall_loops" to "4", "enable_support" to "0"))
        assertEquals(0.235f, result.layerHeightMm)
        assertEquals(1f, result.sparseInfillDensityPercent)
        assertEquals(4, result.wallLoops)
        assertEquals(false, result.supportEnabled)
        assertEquals(null, result.innerWallSpeedMmS)
    }

    @Test fun heightMaterialUsesNativeOneBasedIndices() {
        val range = orcaHeightRange(5f, 15f, mapOf("wall_filament" to "2",
            "sparse_infill_filament" to "2", "solid_infill_filament" to "2"))
        assertEquals(1, range.filamentSlot)
        assertEquals(true, range.overrides.isEmpty)
    }

    @Test fun invalidAndUnrepresentableSettingsAreNotSilentlyChanged() {
        listOf("NaN", "Infinity", "120%", "-2").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                orcaObjectOverrides(mapOf("outer_wall_speed" to value))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            orcaHeightRange(0f, 10f, mapOf("wall_filament" to "2", "layer_height" to "0.2"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            orcaHeightRange(0f, 10f, mapOf("layer_height" to "0.2", "unknown_option" to "1"))
        }
    }
}
