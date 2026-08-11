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
            started.withCompletedSupportReportExport(12, succeeded = true),
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

        assertNull(started.withCompletedSupportReportExport(7, succeeded = true))
        assertTrue(started.busy)
        assertNull(started.completion)
    }
}
