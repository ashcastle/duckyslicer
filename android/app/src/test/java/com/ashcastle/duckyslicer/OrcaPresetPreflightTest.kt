package com.ashcastle.duckyslicer

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrcaPresetPreflightTest {
    private fun preset(name: String, parent: String = "") = JSONObject()
        .put("type", "process").put("name", name).put("inherits", parent)

    @Test fun inheritsValuesReportsUnsupportedAndDoesNotMutateInputs() {
        val parent = preset("Base").put("layer_height", "0.2").put("future_option", "on")
        val child = preset("Fine", "Base").put("layer_height", "0.12")
        val review = reviewOrcaPresets(listOf(child), listOf(parent),
            mapOf("process" to setOf("layer_height")), setOf("process" to "Fine")).single()
        assertNull(review.problem)
        assertEquals("0.12", review.resolved!!.getString("layer_height"))
        assertEquals(setOf("future_option"), review.unsupportedKeys)
        assertTrue(review.conflictsWithSavedName)
        assertEquals("0.2", parent.getString("layer_height"))
        assertFalse(child.has("future_option"))
    }

    @Test fun missingParentIsNotReplacedWithDefaults() {
        val review = reviewOrcaPresets(listOf(preset("Fine", "Absent")), emptyList(), emptyMap(), emptySet()).single()
        assertNull(review.resolved)
        assertEquals("missing_parent:Absent", review.problem)
    }

    @Test fun cyclesAndAmbiguousNamesBlockResolution() {
        val cycle = reviewOrcaPresets(listOf(preset("A", "B"), preset("B", "A")), emptyList(), emptyMap(), emptySet())
        assertTrue(cycle.all { it.problem == "cyclic_preset_inheritance" })
        val duplicate = reviewOrcaPresets(listOf(preset("A"), preset("A")), emptyList(), emptyMap(), emptySet())
        assertTrue(duplicate.all { it.problem == "ambiguous_preset_name" })
    }
}
