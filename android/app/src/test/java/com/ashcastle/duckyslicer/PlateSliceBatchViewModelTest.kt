package com.ashcastle.duckyslicer

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlateSliceBatchViewModelTest {
    @Test
    fun queueRunsInStableOrderAndReportsCompletion() {
        val model = PlateSliceBatchViewModel(SavedStateHandle())

        assertTrue(model.start(listOf("plate-a", "plate-b", "plate-c")))
        assertEquals("plate-a", model.claimNext())
        assertEquals("plate-a", model.claimNext())
        assertTrue(model.complete("plate-a"))
        assertEquals(1, model.state.value.completedCount)
        assertEquals("plate-b", model.claimNext())
        assertFalse(model.complete("plate-c"))
        assertTrue(model.complete("plate-b"))
        assertEquals("plate-c", model.claimNext())
        assertTrue(model.complete("plate-c"))

        assertFalse(model.state.value.active)
        assertEquals(
            PlateSliceBatchTerminalStatus.COMPLETED,
            model.state.value.terminalStatus,
        )
        assertTrue(model.consumeTerminal(PlateSliceBatchTerminalStatus.COMPLETED))
        assertEquals(PlateSliceBatchState(), model.state.value)
    }

    @Test
    fun boundedIdentityValidationRejectsInvalidQueues() {
        val model = PlateSliceBatchViewModel(SavedStateHandle())

        assertFalse(model.start(listOf("only-one")))
        assertFalse(model.start(listOf("duplicate", "duplicate")))
        assertFalse(model.start(listOf("valid", "")))
        assertFalse(model.state.value.active)
    }

    @Test
    fun activeQueueSurvivesViewModelRecreationWithoutGeometry() {
        val handle = SavedStateHandle()
        val original = PlateSliceBatchViewModel(handle)
        assertTrue(original.start(listOf("plate-a", "plate-b")))
        assertEquals("plate-a", original.claimNext())

        val restored = PlateSliceBatchViewModel(handle)

        assertEquals(listOf("plate-a", "plate-b"), restored.state.value.plateIds)
        assertEquals("plate-a", restored.state.value.currentPlateId)
        assertEquals(1, restored.state.value.currentNumber)
        assertEquals("plate-a", restored.claimNext())
    }

    @Test
    fun cancellationWaitsForClaimedPlateAndThenClearsTheQueue() {
        val model = PlateSliceBatchViewModel(SavedStateHandle())
        assertTrue(model.start(listOf("plate-a", "plate-b")))
        assertEquals("plate-a", model.claimNext())

        assertTrue(model.requestCancellation())
        assertTrue(model.state.value.cancellationRequested)
        assertNull(model.claimNext())
        assertTrue(model.cancel("plate-a"))
        assertEquals(PlateSliceBatchTerminalStatus.CANCELED, model.state.value.terminalStatus)
    }

    @Test
    fun failureMustBelongToTheClaimedPlate() {
        val model = PlateSliceBatchViewModel(SavedStateHandle())
        assertTrue(model.start(listOf("plate-a", "plate-b")))
        assertEquals("plate-a", model.claimNext())

        assertFalse(model.fail("plate-b"))
        assertTrue(model.state.value.active)
        assertTrue(model.fail("plate-a"))
        assertEquals(PlateSliceBatchTerminalStatus.FAILED, model.state.value.terminalStatus)
    }
}
