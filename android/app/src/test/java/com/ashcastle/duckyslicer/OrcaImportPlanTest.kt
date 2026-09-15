package com.ashcastle.duckyslicer

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrcaImportPlanTest {
    @Test fun archiveExtrasAreReportedAndCannotBeSelected() {
        val output = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(output).use { zip ->
            mapOf("preset.json" to """{"type":"process","name":"Fine"}""",
                "notes.txt" to "Not a profile").forEach { (name, data) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(data.toByteArray())
                zip.closeEntry()
            }
        }
        val items = prepareOrcaImport(output.toByteArray(), emptyList(), SliceOptions(), emptySet())
        assertEquals(2, items.size)
        assertEquals("notes.txt", items.last().review.name)
        assertEquals("archive-entry", items.last().review.kind)
        assertFalse(orcaImportSelectionReady(items, setOf(1), setOf(1)))
    }

    @Test fun selectedReviewedPresetFlowsIntoExistingBundleMerger() {
        val raw = JSONObject().put("type", "process").put("name", "Imported quality")
            .put("layer_height", "0.16").put("unknown_option", "1")
        val items = prepareOrcaImport(raw.toString().toByteArray(), emptyList(), SliceOptions(), emptySet())
        assertEquals(setOf("unknown_option"), items.single().review.unsupportedKeys)
        assertFalse(orcaImportSelectionReady(items, emptySet(), emptySet()))
        assertFalse(orcaImportSelectionReady(items, setOf(99), setOf(99)))
        assertFalse(orcaImportSelectionReady(items, setOf(0), emptySet()))
        assertTrue(orcaImportSelectionReady(items, setOf(0), setOf(0)))
        assertThrows(IllegalArgumentException::class.java) {
            selectedOrcaImportBundle(items, setOf(0), emptySet())
        }
        val bundle = selectedOrcaImportBundle(items, setOf(0), setOf(0))
        val merged = mergeProfileBundle(JSONObject(), bundle) { "user-new-id" }
        assertEquals(1, merged.result.importedSlicing)
        assertEquals(0.16, merged.root.getJSONArray("slicing").getJSONObject(0).getDouble("layerHeightMm"), 0.00001)
    }

    @Test fun missingParentCannotBeApprovedAway() {
        val raw = JSONObject().put("type", "process").put("name", "Child").put("inherits", "Missing")
        val items = prepareOrcaImport(raw.toString().toByteArray(), emptyList(), SliceOptions(), emptySet())
        assertNotNull(items.single().problem)
        assertThrows(IllegalArgumentException::class.java) {
            selectedOrcaImportBundle(items, setOf(0), setOf(0))
        }
    }
}
