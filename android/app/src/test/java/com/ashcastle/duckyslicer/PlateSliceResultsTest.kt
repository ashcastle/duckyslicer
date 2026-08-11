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
        val snapshot = ProjectSnapshot(
            selectedPlateId = "second-plate",
            plates = listOf(
                ProjectPlate("first-plate", listOf(firstObject), firstObject.id),
                ProjectPlate("second-plate", listOf(secondObject), secondObject.id),
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
        assertNull(snapshot.sliceInput(options - "second-plate"))
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
