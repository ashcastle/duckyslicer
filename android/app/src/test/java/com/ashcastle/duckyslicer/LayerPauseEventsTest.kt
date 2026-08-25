package com.ashcastle.duckyslicer

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LayerPauseEventsTest {
    @Test
    fun eventsAreOrderedReplaceableRemovableAndJsonStable() {
        val events = LayerPauseEvents()
            .put(LayerPauseEvent(4.2f, "Second"))
            .put(LayerPauseEvent(1.6f, "First"))
            .put(LayerPauseEvent(4.2f, "Updated"))

        assertEquals(listOf(1.6f, 4.2f), events.values.map(LayerPauseEvent::printZMm))
        assertEquals("Updated", events.eventAt(4.2f)?.message)
        assertEquals(events, JSONArray(events.toProjectJson().toString()).toLayerPauseEvents())
        assertEquals(listOf(4.2f), events.remove(1.6f).values.map(LayerPauseEvent::printZMm))
    }

    @Test
    fun malformedOrUnorderedEventsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) { LayerPauseEvent(Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { LayerPauseEvent(1f, "bad\nmessage") }
        assertThrows(IllegalArgumentException::class.java) {
            LayerPauseEvents(listOf(LayerPauseEvent(2f), LayerPauseEvent(1f)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            JSONArray("""[{"printZMm":1,"message":"ok","extra":true}]""")
                .toLayerPauseEvents()
        }
    }

    @Test
    fun pauseHistoryIsPlateScopedAndUndoable() {
        var history = ProjectHistoryState().addPlate("second")
        history = history.putLayerPause(LayerPauseEvent(3.4f, "Inspect"))

        assertEquals(0, history.current.plates.first().layerPauseEvents.values.size)
        assertEquals(1, history.current.activePlate.layerPauseEvents.values.size)
        history = history.undo()
        assertEquals(0, history.current.activePlate.layerPauseEvents.values.size)
        history = history.redo()
        assertEquals("Inspect", history.current.activePlate.layerPauseEvents.values.single().message)
        history = history.removeLayerPause(3.4f)
        assertEquals(0, history.current.activePlate.layerPauseEvents.values.size)
    }
}
