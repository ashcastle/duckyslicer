package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OrcaModelSimplifyTest {
    @Test
    fun targetTriangleCountUsesBoundedKeepPercentage() {
        assertEquals(100, simplificationTargetTriangleCount(1_000, 10))
        assertEquals(500, simplificationTargetTriangleCount(1_000, 50))
        assertEquals(900, simplificationTargetTriangleCount(1_000, 90))
        assertEquals(4, simplificationTargetTriangleCount(8, 10))

        assertThrows(IllegalArgumentException::class.java) {
            simplificationTargetTriangleCount(7, 50)
        }
        assertThrows(IllegalArgumentException::class.java) {
            simplificationTargetTriangleCount(1_000, 91)
        }
    }

    @Test
    fun simplifiedReplacementPreservesNonFacetProjectStateAndClearsPaint() {
        val transform = ModelTransform(
            offsetXmm = 12f,
            offsetYmm = -8f,
            rotationZdeg = 35f,
            scale = 1.4f,
        )
        val variableLayers = VariableLayerHeights(
            listOf(VariableLayerRange(0.2f, 0.5f, 0.12f)),
        )
        val overrides = ObjectProcessOverrides(wallLoops = 4, supportEnabled = true)
        val original = ProjectObject(
            id = "selected",
            model = modelInfo("original.stl", 1_000),
            transform = transform,
            supportPaint = SupportPaint(mapOf(2 to SupportPaintState.ENFORCE)),
            seamPaint = SeamPaint(mapOf(3 to SeamPaintState.BLOCK)),
            multiColorPaint = MultiColorPaint(mapOf(4 to 1)),
            variableLayerHeights = variableLayers,
            processOverrides = overrides,
            filamentSlot = 2,
        )

        val result = original.withSimplifiedModel(modelInfo("simplified.stl", 450))
        val simplified = result.projectObject

        assertTrue(result.clearedSurfacePaint)
        assertEquals(original.id, simplified.id)
        assertEquals(transform, simplified.transform)
        assertEquals(variableLayers, simplified.variableLayerHeights)
        assertEquals(overrides, simplified.processOverrides)
        assertEquals(2, simplified.filamentSlot)
        assertTrue(simplified.supportPaint.facets.isEmpty())
        assertTrue(simplified.seamPaint.facets.isEmpty())
        assertTrue(simplified.multiColorPaint.facets.isEmpty())

        var history = ProjectHistoryState().add(original)
        history = history.replaceSelected(listOf(simplified))
        assertEquals(450, history.current.selectedObject?.model?.triangles)
        history = history.undo()
        assertEquals(1_000, history.current.selectedObject?.model?.triangles)
        history = history.redo()
        assertEquals(450, history.current.selectedObject?.model?.triangles)
    }

    @Test
    fun unpaintedReplacementDoesNotReportPaintClearing() {
        val original = ProjectObject("selected", modelInfo("original.stl", 100))
        val result = original.withSimplifiedModel(modelInfo("simplified.stl", 50))

        assertFalse(result.clearedSurfacePaint)
    }

    @Test
    fun failedOrCanceledSimplificationCannotReplaceTheProject() {
        val original = ProjectObject("selected", modelInfo("original.stl", 100))
        assertThrows(IllegalArgumentException::class.java) {
            original.withSimplifiedModel(modelInfo("not-reduced.stl", 100))
        }

        val history = ProjectHistoryState().add(original)
        val options = SliceOptions()
        val operation = ActiveProjectEdit(9, ProjectEditKind.SIMPLIFY, "simplify-9")
        val started = requireNotNull(
            ProjectTransferState(
                history = history,
                sliceOptions = options,
                restored = true,
                sessionRevision = 4,
            ).withStartedEdit(operation),
        )
        val canceling = requireNotNull(started.withEditCancellationRequested(operation.id))
        val lateSuccess = history.replaceSelected(
            listOf(original.withSimplifiedModel(modelInfo("late.stl", 50)).projectObject),
        )
        val completed = requireNotNull(
            canceling.withCompletedEdit(
                operation,
                history,
                options,
                lateSuccess,
                ProjectEditCompletion(operation.id, operation.kind, triangleCount = 50),
            ),
        )

        assertNull(completed.activeEdit)
        assertEquals(ProjectEditFailure.CANCELED, completed.editCompletion?.failure)
        assertEquals(history, completed.history)
        assertEquals(4L, completed.sessionRevision)
    }

    private fun modelInfo(name: String, triangles: Int) = ModelInfo(
        fileName = name,
        triangles = triangles,
        dimensions = listOf(20.0, 20.0, 20.0),
        localPath = "/tmp/$name",
        minMm = listOf(0.0, 0.0, 0.0),
        maxMm = listOf(20.0, 20.0, 20.0),
        previewTriangles = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f),
    )
}
