package com.ashcastle.duckyslicer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GcodeExportStateTest {
    @Test
    fun oneMatchingExportCompletesExactlyOnce() {
        val started = requireNotNull(GcodeExportState().withStartedExport(17))

        assertTrue(started.busy)
        assertNull(started.withStartedExport(18))
        val completed = requireNotNull(
            started.withCompletedExport(17, GcodeExportResult.SAVED),
        )
        assertFalse(completed.busy)
        assertTrue(requireNotNull(completed.completion).result == GcodeExportResult.SAVED)
        assertNull(completed.withCompletedExport(17, GcodeExportResult.SAVED))
    }

    @Test
    fun staleExportCompletionCannotFinishCurrentCopy() {
        val started = requireNotNull(GcodeExportState().withStartedExport(8))

        assertNull(started.withCompletedExport(7, GcodeExportResult.SAVED))
        assertTrue(started.busy)
        assertNull(started.completion)
    }

    @Test
    fun cancellationIsBoundToTheExactActiveExport() {
        val started = requireNotNull(GcodeExportState().withStartedExport(23))

        assertNull(started.withCancellationRequested(22))
        val canceling = requireNotNull(started.withCancellationRequested(23))
        assertTrue(canceling.busy)
        assertTrue(canceling.cancellationRequested)
        assertNull(canceling.withCancellationRequested(23))

        val completed = requireNotNull(
            canceling.withCompletedExport(23, GcodeExportResult.CANCELED),
        )
        assertFalse(completed.busy)
        assertFalse(completed.cancellationRequested)
        assertTrue(requireNotNull(completed.completion).result == GcodeExportResult.CANCELED)
    }
}
