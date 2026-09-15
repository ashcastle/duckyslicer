package com.ashcastle.duckyslicer

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrcaProjectPresetSplitTest {
    @Test fun malformedColorIsRejectedAndEmptyColorMeansUnspecified() {
        fun raw(color: String) = JSONObject().put("type", "filament").put("name", "Color")
            .put("default_filament_colour", color)
        assertThrows(IllegalArgumentException::class.java) {
            convertOrcaFilament(raw("yellow"), "bad-color", FilamentProfile.PLA)
        }
        assertEquals(NO_FILAMENT_COLOR,
            convertOrcaFilament(raw(""), "no-color", FilamentProfile.PLA).profile.defaultColor)
    }

    @Test fun preservesEachMaterialAndReportsUnmappedGlobalSettings() {
        val result = splitOrcaProjectSettings(JSONObject("""{
            "filament_settings_id":["PLA","PETG"],
            "nozzle_temperature":["210","240"],
            "filament_flow_ratio":["0.98","0.95"],
            "nozzle_diameter":["0.4"], "layer_height":"0.235",
            "filament_colour":["#FFFF00","#00FFFF"]
        }"""))
        assertEquals(2, result.filaments.size)
        assertEquals("240", result.filaments[1].getString("nozzle_temperature"))
        assertEquals("0.98", result.filaments[0].getString("filament_flow_ratio"))
        assertEquals(1, result.printer.getJSONArray("nozzle_diameter").length())
        assertEquals("0.235", result.process.getString("layer_height"))
        assertTrue(result.unsupportedKeys.isEmpty())
        val colors = result.filaments.mapIndexed { index, raw ->
            convertOrcaFilament(raw, "color-$index", FilamentProfile.PLA).profile.defaultColor
        }
        assertEquals(listOf(0xffff00, 0x00ffff), colors)
    }

    @Test fun rejectsTruncatedMaterialVectorsInsteadOfRepeatingFirstValue() {
        assertThrows(IllegalArgumentException::class.java) {
            splitOrcaProjectSettings(JSONObject("""{
                "filament_settings_id":["A","B"],"nozzle_temperature":["210"]
            }"""))
        }
    }

    @Test fun splitValuesReachExistingTypedConverters() {
        val result = splitOrcaProjectSettings(JSONObject("""{
            "filament_settings_id":["PLA","PETG"],"nozzle_temperature":["210","240"]
        }"""))
        val profiles = result.filaments.mapIndexed { index, raw ->
            convertOrcaFilament(raw, "import-$index", FilamentProfile.PLA).profile
        }
        assertEquals(listOf(210, 240), profiles.map { it.nozzleTemp })
    }
}
