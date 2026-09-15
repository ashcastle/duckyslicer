package com.ashcastle.duckyslicer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrcaPresetCompatibilityTest {
    @Test fun processKeepsImportedPrinterRestrictionsRatherThanBaselinePrinter() {
        val raw = JSONObject().put("type", "process").put("name", "Vendor process")
            .put("compatible_printers", JSONArray(listOf("Vendor 0.4", "Vendor 0.4")))
            .put("compatible_printers_condition", "nozzle_diameter[0]==0.4")
        val result = convertOrcaProcess(raw, "user-vendor", QualityProfile.STANDARD)
        assertEquals(listOf("Vendor 0.4"), result.profile.compatiblePrinters)
        assertTrue("compatible_printers" in result.appliedKeys)
        assertTrue("compatible_printers_condition" in result.unsupportedKeys)
    }

    @Test fun malformedCompatibilityIsNotMadeUniversal() {
        listOf<Any>("Vendor", JSONArray(listOf("")), JSONArray(listOf(1))).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                copyOrcaPresetCompatibility(JSONObject().put("compatible_printers", value), JSONObject())
            }
        }
    }
}
