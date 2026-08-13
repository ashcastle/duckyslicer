package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTransferStateTest {
    @Test
    fun importedPartAssignmentsExpandAvailableFilamentSlotsWithinPrinterCapacity() {
        val options = SliceOptions().copy(filamentSlots = listOf(FilamentProfile.PLA))

        val expanded = options.withMinimumFilamentSlots(2)

        assertEquals(2, expanded.resolvedFilamentSlots().size)
        assertEquals(FilamentProfile.PLA.id, expanded.resolvedFilamentSlots()[0].id)
        assertEquals(FilamentProfile.PLA.id, expanded.resolvedFilamentSlots()[1].id)
        assertEquals(
            options.printerProfile.extruderCount,
            options.withMinimumFilamentSlots(MAX_FILAMENT_SLOTS).resolvedFilamentSlots().size,
        )
    }

    @Test
    fun projectExportCancellationIsBoundToTheExactActiveTransfer() {
        val operation = ActiveProjectTransfer(91, ProjectTransferDirection.EXPORT)
        val started = requireNotNull(
            ProjectTransferState(restored = true).withStartedTransfer(operation),
        )

        assertTrue(started.busy)
        assertEquals(operation.id, started.activeTransferId)
        assertNull(
            started.withTransferCancellationRequested(operation.copy(id = operation.id - 1)),
        )
        val canceling = requireNotNull(
            started.withTransferCancellationRequested(operation),
        )
        assertTrue(canceling.transferCancellationRequested)
        assertNull(canceling.withTransferCancellationRequested(operation))
        assertEquals(ProjectTransferDirection.EXPORT, canceling.activeTransferDirection)
    }

    @Test
    fun projectImportCancellationIsBoundToTheExactActiveTransfer() {
        val operation = ActiveProjectTransfer(92, ProjectTransferDirection.IMPORT)
        val started = requireNotNull(
            ProjectTransferState(restored = true).withStartedTransfer(operation),
        )

        assertNull(
            started.withTransferCancellationRequested(
                operation.copy(direction = ProjectTransferDirection.EXPORT),
            ),
        )
        val canceling = requireNotNull(started.withTransferCancellationRequested(operation))
        assertTrue(canceling.transferCancellationRequested)
        assertEquals(ProjectTransferDirection.IMPORT, canceling.activeTransferDirection)
    }

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
    fun retainedModelImportAppliesRequiredFilamentSlotsAtomically() {
        val history = history()
        val options = SliceOptions()
        val nextOptions = options.copy(
            filamentSlots = listOf(FilamentProfile.PLA, FilamentProfile.PETG),
        )
        val operation = ActiveProjectEdit(42, ProjectEditKind.MODEL_IMPORT)
        val started = requireNotNull(
            ProjectTransferState(
                history = history,
                sliceOptions = options,
                restored = true,
                sessionRevision = 3,
            ).withStartedEdit(operation),
        )
        val nextHistory = history.updateSelectedTransform(ModelTransform(offsetXmm = 12f))

        val completed = requireNotNull(
            started.withCompletedEdit(
                operation = operation,
                expectedHistory = history,
                expectedOptions = options,
                nextHistory = nextHistory,
                completion = ProjectEditCompletion(operation.id, operation.kind),
                nextOptions = nextOptions,
            ),
        )

        assertEquals(12f, completed.history.current.selectedObject?.transform?.offsetXmm)
        assertEquals(2, completed.sliceOptions.resolvedFilamentSlots().size)
        assertEquals(
            nextOptions,
            completed.plateOptions[completed.history.current.selectedPlateId],
        )
        assertTrue(requireNotNull(completed.editCompletion).sessionChanged)
        assertEquals(4L, completed.sessionRevision)
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

    @Test
    fun requestedCancellationRejectsALateNativeSuccessAndKeepsTheProject() {
        val history = history()
        val options = SliceOptions()
        val operation = ActiveProjectEdit(
            id = 73,
            kind = ProjectEditKind.ARRANGE,
            requestId = "request-73",
        )
        val started = requireNotNull(
            ProjectTransferState(
                history = history,
                sliceOptions = options,
                restored = true,
                sessionRevision = 12,
            ).withStartedEdit(operation),
        )
        val canceling = requireNotNull(started.withEditCancellationRequested(operation.id))
        val lateHistory = history.updateSelectedTransform(ModelTransform(offsetXmm = 48f))

        assertTrue(requireNotNull(canceling.activeEdit).cancellationRequested)
        assertNull(canceling.withEditCancellationRequested(operation.id))
        val completed = requireNotNull(
            canceling.withCompletedEdit(
                operation,
                history,
                options,
                lateHistory,
                ProjectEditCompletion(operation.id, operation.kind),
            ),
        )

        assertFalse(completed.busy)
        assertNull(completed.activeEdit)
        assertEquals(ProjectEditFailure.CANCELED, completed.editCompletion?.failure)
        assertFalse(requireNotNull(completed.editCompletion).sessionChanged)
        assertEquals(history, completed.history)
        assertEquals(12L, completed.sessionRevision)
    }

    @Test
    fun switchingPlatesRestoresEachPlatesIndependentSliceOptions() {
        val firstHistory = history()
        val firstPlateId = firstHistory.current.selectedPlateId
        val firstOptions = SliceOptions().copy(fillDensity = 0.18f)
        var state = ProjectTransferState(
            history = firstHistory,
            sliceOptions = firstOptions,
            plateOptions = mapOf(firstPlateId to firstOptions),
            restored = true,
        )

        val addedHistory = state.history.addPlate("second-plate")
        state = requireNotNull(
            state.withUpdatedSession(
                state.history,
                addedHistory,
                state.sliceOptions,
                state.sliceOptions,
            ),
        )
        val secondOptions = firstOptions.copy(fillDensity = 0.44f)
        state = requireNotNull(
            state.withUpdatedSession(
                state.history,
                state.history,
                state.sliceOptions,
                secondOptions,
            ),
        )
        assertEquals(secondOptions, state.plateOptions.getValue("second-plate"))

        val firstSelected = state.history.selectPlate(firstPlateId)
        state = requireNotNull(
            state.withUpdatedSession(
                state.history,
                firstSelected,
                state.sliceOptions,
                state.plateOptions.getValue(firstPlateId),
            ),
        )

        assertEquals(firstPlateId, state.history.current.selectedPlateId)
        assertEquals(firstOptions, state.sliceOptions)
        assertEquals(firstOptions, state.plateOptions.getValue(firstPlateId))
        assertEquals(secondOptions, state.plateOptions.getValue("second-plate"))
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
