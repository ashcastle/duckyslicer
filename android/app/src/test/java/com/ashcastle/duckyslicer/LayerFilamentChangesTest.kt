package com.ashcastle.duckyslicer

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerFilamentChangesTest {
    @Test
    fun changesAreOrderedReplaceableRemovableAndJsonStable() {
        val changes = LayerFilamentChanges()
            .put(LayerFilamentChange(8.2f, 1))
            .put(LayerFilamentChange(2.4f, 2))
            .put(LayerFilamentChange(8.2f, 3))

        assertEquals(listOf(2.4f, 8.2f), changes.values.map(LayerFilamentChange::printZMm))
        assertEquals(3, changes.changeAt(8.2f)?.filamentSlot)
        assertEquals(
            changes,
            JSONArray(changes.toProjectJson().toString()).toLayerFilamentChanges(),
        )
        assertEquals(listOf(8.2f), changes.remove(2.4f).values.map(LayerFilamentChange::printZMm))
        assertEquals(emptyList<LayerFilamentChange>(), changes.constrainedToSlotCount(1).values)
    }

    @Test
    fun malformedUnorderedOrUnavailableChangesFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            LayerFilamentChange(Float.NaN, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LayerFilamentChange(1f, MAX_FILAMENT_SLOTS)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LayerFilamentChanges(
                listOf(LayerFilamentChange(2f, 0), LayerFilamentChange(1f, 1)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            JSONArray("""[{"printZMm":1,"filamentSlot":1,"extra":true}]""")
                .toLayerFilamentChanges()
        }
    }

    @Test
    fun changesArePlateScopedUndoableAndConstrainedWithSlots() {
        var history = ProjectHistoryState().addPlate("second")
        history = history.putLayerFilamentChange(LayerFilamentChange(3.4f, 1))

        assertEquals(0, history.current.plates.first().layerFilamentChanges.values.size)
        assertEquals(1, history.current.activePlate.layerFilamentChanges.values.size)
        history = history.undo()
        assertEquals(0, history.current.activePlate.layerFilamentChanges.values.size)
        history = history.redo()
        assertEquals(1, history.current.activePlate.layerFilamentChanges.values.single().filamentSlot)
        history = history.constrainFilamentSlots(1)
        assertEquals(0, history.current.activePlate.layerFilamentChanges.values.size)
    }

    @Test
    fun availabilityRequiresAWholeModelUsingOneFilament() {
        val base = ProjectObject(
            id = "object",
            model = ModelInfo(
                fileName = "model.stl",
                triangles = 1,
                dimensions = listOf(1.0, 1.0, 1.0),
                localPath = "model.stl",
                minMm = listOf(0.0, 0.0, 0.0),
                maxMm = listOf(1.0, 1.0, 0.0),
                previewTriangles = floatArrayOf(
                    0f, 0f, 0f,
                    1f, 0f, 0f,
                    0f, 1f, 0f,
                ),
            ),
        )
        assertTrue(listOf(base).supportLayerFilamentChanges())
        assertFalse(
            listOf(
                base.copy(
                    volumes = listOf(
                        base.singleVolume,
                        base.singleVolume.copy(id = "second-volume", filamentSlot = 1),
                    ),
                ),
            ).supportLayerFilamentChanges(),
        )
        assertFalse(
            listOf(
                base.updateSingleVolume {
                    it.copy(multiColorPaint = MultiColorPaint(mapOf(0 to 1)))
                },
            ).supportLayerFilamentChanges(),
        )
    }
}
