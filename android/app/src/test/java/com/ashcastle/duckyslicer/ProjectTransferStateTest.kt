package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTransferStateTest {
    @Test
    fun retainedSessionMutationKeepsHistoryAndOptionsTogether() {
        val history = history()
        val nextHistory = history.updateSelectedTransform(ModelTransform(offsetXmm = 18f))
        val options = SliceOptions()
        val nextOptions = options.copy(fillDensity = 0.37f)

        val updated = ProjectTransferState(
            history = history,
            sliceOptions = options,
            restored = true,
            sessionRevision = 7,
        ).withUpdatedSession(history, nextHistory, options, nextOptions)

        assertNotNull(updated)
        requireNotNull(updated)
        assertEquals(18f, updated.history.current.selectedObject?.transform?.offsetXmm)
        assertTrue(updated.history.canUndo)
        assertFalse(updated.history.canRedo)
        assertEquals(0.37f, updated.sliceOptions.fillDensity)
        assertEquals(8L, updated.sessionRevision)
        assertEquals(0L, updated.persistedRevision)
    }

    @Test
    fun staleOrBusySessionMutationIsRejected() {
        val history = history()
        val options = SliceOptions()
        val nextHistory = history.updateSelectedTransform(ModelTransform(offsetYmm = 9f))
        val ready = ProjectTransferState(
            history = history,
            sliceOptions = options,
            restored = true,
        )

        assertNull(
            ready.copy(busy = true).withUpdatedSession(
                history,
                nextHistory,
                options,
                options,
            ),
        )
        assertNull(
            ready.withUpdatedSession(
                ProjectHistoryState(),
                nextHistory,
                options,
                options,
            ),
        )
        assertNull(ready.withUpdatedSession(history, history, options, options))
    }

    @Test
    fun retainedEditAppliesOneMatchingBaselineAndAdvancesTheSessionOnce() {
        val history = history()
        val options = SliceOptions()
        val operation = ActiveProjectEdit(41, ProjectEditKind.AUTO_LAY)
        val ready = ProjectTransferState(
            history = history,
            sliceOptions = options,
            restored = true,
            sessionRevision = 8,
        )
        val started = requireNotNull(ready.withStartedEdit(operation))
        val nextHistory = history.updateSelectedTransform(
            ModelTransform(rotationXdeg = 90f),
        )
        val completion = ProjectEditCompletion(
            id = operation.id,
            kind = operation.kind,
        )

        assertTrue(started.busy)
        assertEquals(operation, started.activeEdit)
        val completed = requireNotNull(
            started.withCompletedEdit(
                operation,
                history,
                options,
                nextHistory,
                completion,
            ),
        )
        assertFalse(completed.busy)
        assertNull(completed.activeEdit)
        assertTrue(requireNotNull(completed.editCompletion).sessionChanged)
        assertEquals(90f, completed.history.current.selectedObject?.transform?.rotationXdeg)
        assertEquals(9L, completed.sessionRevision)
        assertEquals(0L, completed.persistedRevision)

        assertNull(
            completed.withCompletedEdit(
                operation,
                history,
                options,
                nextHistory,
                completion,
            ),
        )
    }

    @Test
    fun retainedEditRejectsAStaleBaselineAndKeepsTheOperationPending() {
        val history = history()
        val options = SliceOptions()
        val operation = ActiveProjectEdit(7, ProjectEditKind.ARRANGE)
        val started = requireNotNull(
            ProjectTransferState(
                history = history,
                sliceOptions = options,
                restored = true,
            ).withStartedEdit(operation),
        )
        val staleHistory = history.updateSelectedTransform(ModelTransform(offsetXmm = 3f))

        assertNull(
            started.withCompletedEdit(
                operation,
                staleHistory,
                options,
                history,
                ProjectEditCompletion(operation.id, operation.kind),
            ),
        )
        assertTrue(started.busy)
        assertEquals(operation, started.activeEdit)
        assertNull(started.editCompletion)
    }

    private fun history(): ProjectHistoryState {
        val model = ModelInfo(
            fileName = "retained.stl",
            triangles = 1,
            dimensions = listOf(1.0, 1.0, 1.0),
            localPath = "/private/retained.stl",
            minMm = listOf(0.0, 0.0, 0.0),
            maxMm = listOf(1.0, 1.0, 1.0),
            previewTriangles = FloatArray(9),
        )
        return ProjectHistoryState().add(ProjectObject("retained", model))
    }
}
