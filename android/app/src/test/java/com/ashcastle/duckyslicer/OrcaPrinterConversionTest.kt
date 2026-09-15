package com.ashcastle.duckyslicer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrcaPrinterConversionTest {
    private fun input() = JSONObject().put("type", "machine").put("name", "Centered printer")

    @Test fun zeroLayerLimitsUseEngineDefaultsAndImportedNozzle() {
        val result = convertOrcaPrinter(input().put("nozzle_diameter", JSONArray(listOf("0.6")))
            .put("min_layer_height", JSONArray(listOf("0")))
            .put("max_layer_height", JSONArray(listOf("0"))),
            "user-auto", PrinterProfile.CUSTOM_CARTESIAN)
        assertEquals(0.07f, result.profile.minLayerHeight, 0f)
        assertEquals(0.45f, result.profile.maxLayerHeight, 0.00001f)
        assertThrows(IllegalArgumentException::class.java) {
            convertOrcaPrinter(input().put("max_layer_height", "-1"), "user-invalid", PrinterProfile.CUSTOM_CARTESIAN)
        }
    }

    @Test fun centeredBedKeepsItsOriginAndNormalizedPolygon() {
        val converted = convertOrcaPrinter(input()
            .put("printable_area", JSONArray(listOf("-100x-120", "100x-120", "100x120", "-100x120")))
            .put("printable_height", "300").put("nozzle_diameter", JSONArray(listOf("0.6"))),
            "user-centered", PrinterProfile.CUSTOM_CARTESIAN)
        assertEquals(-100f, converted.profile.bedOriginX, 0f)
        assertEquals(-120f, converted.profile.bedOriginY, 0f)
        assertEquals(200f, converted.profile.bedSizeX, 0f)
        assertEquals(240f, converted.profile.bedSizeY, 0f)
        assertEquals(300f, converted.profile.maxPrintHeight, 0f)
        assertEquals(0.6f, converted.profile.nozzleDiameter, 0f)
        assertEquals(listOf(0f, 0f, 200f, 0f, 200f, 240f, 0f, 240f), converted.profile.bedPolygon)
    }

    @Test fun malformedGeometryAndUnmappedMultipleNozzlesAreRejected() {
        listOf("0x0,1x1,2x2", "0x0,bad,100x100", "NaNx0,100x0,100x100").forEach {
            assertThrows(IllegalArgumentException::class.java) {
                convertOrcaPrinter(input().put("printable_area", it), "user-invalid", PrinterProfile.CUSTOM_CARTESIAN)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            convertOrcaPrinter(input().put("nozzle_diameter", JSONArray(listOf("0.4", "0.6"))),
                "user-dual", PrinterProfile.CUSTOM_CARTESIAN)
        }
    }
}
