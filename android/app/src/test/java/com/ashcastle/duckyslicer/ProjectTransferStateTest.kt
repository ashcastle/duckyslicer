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
