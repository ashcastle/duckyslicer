package com.ashcastle.duckyslicer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrcaFilamentConversionTest {
    private fun raw() = JSONObject().put("type", "filament").put("name", "Imported PLA")

    @Test fun importsArrayScalarsWithoutLosingTemperatureOrFlow() {
        val result = convertOrcaFilament(raw().put("nozzle_temperature", JSONArray().put("215"))
            .put("filament_flow_ratio", JSONArray().put("0.97"))
            .put("future_value", "preserve in review"), "user-import", FilamentProfile.GENERIC_PLA)
        assertEquals(215, result.profile.nozzleTemp)
        assertEquals(0.97f, result.profile.flowRatio, 0f)
        assertEquals(setOf("future_value"), result.unsupportedKeys)
        assertTrue("hot_plate_temp" in result.defaultedKeys)
    }

    @Test fun rejectsMultiToolAndFractionalTemperatureInsteadOfTruncating() {
        listOf(JSONArray().put("210").put("230"), JSONArray().put("215.5")).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                convertOrcaFilament(raw().put("nozzle_temperature", value), "user-import", FilamentProfile.GENERIC_PLA)
            }
        }
    }
}
