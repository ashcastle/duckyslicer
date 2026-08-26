package com.ashcastle.duckyslicer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlateSliceResultsTest {
    @Test
    fun outcomesAreReplacedClearedAndRetainedByPlateIdentity() {
        val first = outcome("first.gcode", layers = 10)
        val replacement = outcome("first-new.gcode", layers = 12)
        val second = outcome("second.gcode", layers = 20)

        var results = PlateSliceResults()
            .put("plate-one", first)
            .put("plate-two", second)
            .put("plate-one", replacement)

        assertEquals(replacement, results.outcomeFor("plate-one"))
        assertEquals(second, results.outcomeFor("plate-two"))
        assertEquals(2, results.values.size)

        results = results.clear("plate-one")
        assertNull(results.outcomeFor("plate-one"))
        assertEquals(second, results.outcomeFor("plate-two"))

        results = results.put("plate-one", first).retain(setOf("plate-one"))
        assertEquals(first, results.outcomeFor("plate-one"))
        assertNull(results.outcomeFor("plate-two"))
    }

    @Test
    fun sliceInputContainsOnlyTheSelectedPlatesObjectsAndSettings() {
        val firstObject = projectObject("first-object")
        val secondObject = projectObject("second-object")
        val firstOptions = SliceOptions().copy(fillDensity = 0.15f)
        val secondOptions = SliceOptions().copy(fillDensity = 0.45f)
        val secondPauses = LayerPauseEvents().put(LayerPauseEvent(4.2f, "Check print"))
        val secondFilamentChanges = LayerFilamentChanges().put(
            LayerFilamentChange(printZMm = 6.4f, filamentSlot = 1),
        )
        val secondCustomGCode = LayerCustomGCodeEvents().put(
            LayerCustomGCodeEvent(printZMm = 8.6f, gcode = "M117 Inspect"),
        )
        val snapshot = ProjectSnapshot(
            selectedPlateId = "second-plate",
            plates = listOf(
                ProjectPlate(
                    "first-plate",
                    listOf(firstObject),
                    firstObject.id,
                    name = "Main body v0.2",
                ),
                ProjectPlate(
                    id = "second-plate",
                    objects = listOf(secondObject),
                    selectedObjectId = secondObject.id,
                    layerPauseEvents = secondPauses,
                    layerFilamentChanges = secondFilamentChanges,
                    layerCustomGCodeEvents = secondCustomGCode,
                ),
            ),
        )
        val options = mapOf(
            "first-plate" to firstOptions,
            "second-plate" to secondOptions,
        )

        val selected = requireNotNull(snapshot.sliceInput(options))

        assertEquals("second-plate", selected.plateId)
        assertEquals(listOf(secondObject), selected.objects)
        assertEquals(secondOptions, selected.options)
        assertEquals(secondPauses, selected.layerPauseEvents)
        assertEquals(secondFilamentChanges, selected.layerFilamentChanges)
        assertEquals(secondCustomGCode, selected.layerCustomGCodeEvents)
        assertNull(snapshot.sliceInput(options - "second-plate"))
        assertEquals(
            listOf("first-plate", "second-plate"),
            snapshot.sliceablePlateIds(options),
        )
        assertEquals(firstObject, snapshot.sliceInput("first-plate", options)?.objects?.single())
        assertNull(snapshot.sliceInput("missing", options))
        assertEquals(listOf("first-plate"), snapshot.sliceablePlateIds(options - "second-plate"))
    }

    @Test
    fun completeExportBatchFollowsPlateOrderAndUsesDistinctSafeNames() {
        val firstObject = projectObject("first-object")
        val secondObject = projectObject("second-object")
        val snapshot = ProjectSnapshot(
            selectedPlateId = "second-plate",
            plates = listOf(
                ProjectPlate(
                    "first-plate",
                    listOf(firstObject),
                    firstObject.id,
                    name = "Main body v0.2",
                ),
                ProjectPlate("empty-plate"),
                ProjectPlate("second-plate", listOf(secondObject), secondObject.id),
            ),
        )
        val first = outcome("first.gcode", 10).copy(suggestedName = "same/model.gcode")
        val second = outcome("second.gcode", 20).copy(suggestedName = "same/model.gcode")
        val results = PlateSliceResults()
            .put("second-plate", second)
            .put("first-plate", first)

        val batch = requireNotNull(results.completeExportBatch(snapshot))

        assertEquals(
            listOf("plate-01-Main body v0.2-model.gcode", "plate-03-model.gcode"),
            batch.entries.map(GcodeExportEntry::displayName),
        )
        assertEquals(listOf(first, second), batch.entries.map(GcodeExportEntry::outcome))
        assertNull(results.clear("second-plate").completeExportBatch(snapshot))
    }

    @Test
    fun exportBatchRequiresAtLeastTwoPrintablePlates() {
        val onlyObject = projectObject("only-object")
        val snapshot = ProjectSnapshot(
            plates = listOf(
                ProjectPlate("plate-1", listOf(onlyObject), onlyObject.id),
                ProjectPlate("plate-2"),
            ),
            selectedPlateId = "plate-1",
        )

        assertNull(
            PlateSliceResults()
                .put("plate-1", outcome("only.gcode", 10))
                .completeExportBatch(snapshot),
        )
    }

    private fun outcome(name: String, layers: Int) = SliceOutcome(
        output = File("/tmp/$name"),
        layers = layers,
        estimatedSeconds = layers.toFloat(),
        filamentMm = layers * 2f,
        filamentGrams = layers / 2f,
    )

    private fun projectObject(id: String) = ProjectObject(
        id = id,
        model = ModelInfo(
            fileName = "$id.stl",
            triangles = 1,
            dimensions = listOf(1.0, 1.0, 1.0),
            localPath = "/tmp/$id.stl",
            minMm = listOf(0.0, 0.0, 0.0),
            maxMm = listOf(1.0, 1.0, 1.0),
            previewTriangles = FloatArray(9),
        ),
    )
}
