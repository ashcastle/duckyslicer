package com.ashcastle.duckyslicer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportReportExportStateTest {
    @Test
    fun oneMatchingSupportExportCompletesAndASecondExportCanReplaceItsResult() {
        val started = requireNotNull(
            SupportReportExportState().withStartedSupportReportExport(12),
        )

        assertTrue(started.busy)
        assertNull(started.withStartedSupportReportExport(13))
        val completed = requireNotNull(
            started.withCompletedSupportReportExport(12, SupportReportExportOutcome.SAVED),
        )
        assertFalse(completed.busy)
        assertTrue(requireNotNull(completed.completion).succeeded)

        val retried = requireNotNull(completed.withStartedSupportReportExport(13))
        assertTrue(retried.busy)
        assertNull(retried.completion)
    }

    @Test
    fun staleSupportExportCannotCompleteTheActiveWrite() {
        val started = requireNotNull(
            SupportReportExportState().withStartedSupportReportExport(8),
        )

        assertNull(
            started.withCompletedSupportReportExport(7, SupportReportExportOutcome.SAVED),
        )
        assertTrue(started.busy)
        assertNull(started.completion)
    }

    @Test
    fun cancellationIsExactAndRejectsALateSuccessfulWrite() {
        val started = requireNotNull(
            SupportReportExportState().withStartedSupportReportExport(24),
        )

        assertNull(started.withSupportReportCancellationRequested(23))
        val canceling = requireNotNull(started.withSupportReportCancellationRequested(24))
        assertTrue(canceling.cancellationRequested)
        assertNull(canceling.withSupportReportCancellationRequested(24))
        val completed = requireNotNull(
            canceling.withCompletedSupportReportExport(24, SupportReportExportOutcome.SAVED),
        )

        assertFalse(completed.busy)
        assertFalse(completed.cancellationRequested)
        assertFalse(requireNotNull(completed.completion).succeeded)
        assertTrue(completed.completion.outcome == SupportReportExportOutcome.CANCELED)
    }
}
