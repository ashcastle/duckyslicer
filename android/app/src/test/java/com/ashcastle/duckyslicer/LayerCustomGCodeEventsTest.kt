package com.ashcastle.duckyslicer

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LayerCustomGCodeEventsTest {
    @Test
    fun eventsAreOrderedReplaceableRemovableAndJsonStable() {
        val events = LayerCustomGCodeEvents()
            .put(LayerCustomGCodeEvent(8.2f, "M117 Second"))
            .put(LayerCustomGCodeEvent(2.4f, "M117 First\nM106 S128"))
            .put(LayerCustomGCodeEvent(8.2f, "M117 Replaced"))

        assertEquals(listOf(2.4f, 8.2f), events.values.map(LayerCustomGCodeEvent::printZMm))
        assertEquals("M117 Replaced", events.eventAt(8.2f)?.gcode)
        assertEquals(
            events,
            JSONArray(events.toProjectJson().toString()).toLayerCustomGCodeEvents(),
        )
        assertEquals(listOf(8.2f), events.remove(2.4f).values.map(LayerCustomGCodeEvent::printZMm))
    }

    @Test
    fun printerTemplateEventsRoundTripAndLegacyEventsRemainCustom() {
        val template = LayerCustomGCodeEvents(
            listOf(
                LayerCustomGCodeEvent(
                    printZMm = 4.2f,
                    gcode = "",
                    kind = LayerCustomGCodeKind.PRINTER_TEMPLATE,
                ),
            ),
        )

        assertEquals(
            template,
            JSONArray(template.toProjectJson().toString()).toLayerCustomGCodeEvents(),
        )
        assertEquals(
            LayerCustomGCodeKind.CUSTOM,
            JSONArray("""[{"printZMm":1,"gcode":"M117 legacy"}]""")
                .toLayerCustomGCodeEvents()
                .values
                .single()
                .kind,
        )
    }

    @Test
    fun malformedOrUnboundedGCodeFailsClosed() {
        listOf("", " M117 padded", "M117 padded ", "M117 bad\rline", "M117 bad\u0000line")
            .forEach { gcode ->
                assertThrows(IllegalArgumentException::class.java) {
                    LayerCustomGCodeEvent(1f, gcode)
                }
            }
        assertThrows(IllegalArgumentException::class.java) {
            LayerCustomGCodeEvent(1f, "G1 X" + "1".repeat(300))
        }
        assertThrows(IllegalArgumentException::class.java) {
            LayerCustomGCodeEvents(
                listOf(
                    LayerCustomGCodeEvent(2f, "M117 second"),
                    LayerCustomGCodeEvent(1f, "M117 first"),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            JSONArray("""[{"printZMm":1,"gcode":"M117 hi","extra":true}]""")
                .toLayerCustomGCodeEvents()
        }
        assertThrows(IllegalArgumentException::class.java) {
            LayerCustomGCodeEvent(1f, "", LayerCustomGCodeKind.CUSTOM)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LayerCustomGCodeEvent(1f, "M117 invalid", LayerCustomGCodeKind.PRINTER_TEMPLATE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            JSONArray("""[{"printZMm":1,"gcode":"","kind":"unknown"}]""")
                .toLayerCustomGCodeEvents()
        }
    }

    @Test
    fun eventsArePlateScopedAndUndoable() {
        var history = ProjectHistoryState().addPlate("second")
        history = history.putLayerCustomGCode(LayerCustomGCodeEvent(3.4f, "M117 Inspect"))

        assertEquals(0, history.current.plates.first().layerCustomGCodeEvents.values.size)
        assertEquals(1, history.current.activePlate.layerCustomGCodeEvents.values.size)
        history = history.undo()
        assertEquals(0, history.current.activePlate.layerCustomGCodeEvents.values.size)
        history = history.redo()
        assertEquals("M117 Inspect", history.current.activePlate.layerCustomGCodeEvents.values.single().gcode)
        history = history.removeLayerCustomGCode(3.4f)
        assertEquals(0, history.current.activePlate.layerCustomGCodeEvents.values.size)
    }

    @Test
    fun pauseAndCustomGCodeAtSameHeightReplaceEachOtherAtomically() {
        val initial = ProjectHistoryState()
        val withPause = initial.putLayerPause(LayerPauseEvent(4.2f, "Inspect"))
        val withCustom = withPause.putLayerCustomGCode(
            LayerCustomGCodeEvent(4.2f, "M117 Inspect"),
        )

        assertEquals(0, withCustom.current.activePlate.layerPauseEvents.values.size)
        assertEquals(
            "M117 Inspect",
            withCustom.current.activePlate.layerCustomGCodeEvents.values.single().gcode,
        )

        val replacedWithPause = withCustom.putLayerPause(LayerPauseEvent(4.2f, "Resume"))
        assertEquals(0, replacedWithPause.current.activePlate.layerCustomGCodeEvents.values.size)
        assertEquals(
            "Resume",
            replacedWithPause.current.activePlate.layerPauseEvents.values.single().message,
        )
        assertEquals(withCustom.current, replacedWithPause.undo().current)
    }

    @Test
    fun plateRejectsPauseAndCustomGCodeAtSameHeight() {
        assertThrows(IllegalArgumentException::class.java) {
            ProjectPlate(
                id = "plate-1",
                layerPauseEvents = LayerPauseEvents(listOf(LayerPauseEvent(4.2f))),
                layerCustomGCodeEvents = LayerCustomGCodeEvents(
                    listOf(LayerCustomGCodeEvent(4.2f, "M117 Inspect")),
                ),
            )
        }
    }

    @Test
    fun lineEndingsAreNormalizedBeforeEditing() {
        assertEquals("M117 One\nM117 Two", normalizedLayerCustomGCode("\r\nM117 One\rM117 Two\r\n"))
    }
}
