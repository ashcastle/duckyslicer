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
        assertTrue(requireNotNull(completed.completion).totalFiles == 1)
        assertNull(completed.withCompletedExport(17, GcodeExportResult.SAVED))
    }

    @Test
    fun batchProgressIsMonotonicAndBoundToTheActiveExport() {
        val started = requireNotNull(GcodeExportState().withStartedExport(31, totalFiles = 3))

        assertTrue(started.busy)
        assertTrue(started.currentFile == 1)
        assertNull(started.withExportProgress(30, 1))
        val first = requireNotNull(started.withExportProgress(31, 1))
        assertTrue(first.currentFile == 2)
        assertNull(first.withExportProgress(31, 0))
        val finishedCopying = requireNotNull(first.withExportProgress(31, 3))
        assertTrue(finishedCopying.currentFile == 3)

        val completed = requireNotNull(
            finishedCopying.withCompletedExport(31, GcodeExportResult.SAVED),
        )
        assertFalse(completed.busy)
        assertTrue(completed.totalFiles == 0)
        assertTrue(completed.completedFiles == 0)
        assertNull(completed.currentFile)
        assertTrue(requireNotNull(completed.completion).totalFiles == 3)
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
