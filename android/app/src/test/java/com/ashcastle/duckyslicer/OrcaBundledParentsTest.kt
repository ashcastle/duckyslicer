package com.ashcastle.duckyslicer

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrcaBundledParentsTest {
    private fun record(name: String, parent: String = "", height: Double = 0.2) =
        JSONObject().put("type", "process").put("name", name).put("inherits", parent)
            .put("layer_height", height)

    private fun asset(vararg records: JSONObject) = ByteArrayOutputStream().also { output ->
        GZIPOutputStream(output).use { zip ->
            zip.write(records.joinToString("\n").toByteArray())
        }
    }.toByteArray().inputStream()

    @Test fun resolvesOnlyRequestedAncestors() {
        val incoming = listOf(record("Custom", "Standard"))
        val parents = readOrcaBundledParents(asset(
            record("Base"), record("Standard", "Base"), record("Unrelated"),
        ), incoming)
        assertEquals(setOf("Standard", "Base"), parents.map { it.getString("name") }.toSet())
        val reviewed = reviewOrcaPresets(incoming, parents, emptyMap(), emptySet()).single()
        assertNull(reviewed.problem)
    }

    @Test fun suppliedParentsTakePrecedenceOverBundledVersion() {
        val incoming = listOf(record("Custom", "Standard"), record("Standard", height = 0.15))
        assertTrue(readOrcaBundledParents(asset(record("Standard")), incoming).isEmpty())
    }

    @Test fun conflictingDefinitionsRemainAnError() {
        val incoming = listOf(record("Custom", "Base"))
        val parents = readOrcaBundledParents(asset(record("Base"), record("Base", height = 0.1)), incoming)
        assertEquals("ambiguous_parent:Base",
            reviewOrcaPresets(incoming, parents, emptyMap(), emptySet()).single().problem)
    }

    @Test fun cyclicBundledInheritanceTerminatesAndIsReported() {
        val incoming = listOf(record("Custom", "A"))
        val parents = readOrcaBundledParents(asset(record("A", "B"), record("B", "A")), incoming)
        assertEquals(2, parents.size)
        assertEquals("cyclic_preset_inheritance",
            reviewOrcaPresets(incoming, parents, emptyMap(), emptySet()).single().problem)
    }
}
