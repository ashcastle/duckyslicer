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
        val completed = requireNotNull(started.withCompletedExport(17, succeeded = true))
        assertFalse(completed.busy)
        assertTrue(requireNotNull(completed.completion).succeeded)
        assertNull(completed.withCompletedExport(17, succeeded = true))
    }

    @Test
    fun staleExportCompletionCannotFinishCurrentCopy() {
        val started = requireNotNull(GcodeExportState().withStartedExport(8))

        assertNull(started.withCompletedExport(7, succeeded = true))
        assertTrue(started.busy)
        assertNull(started.completion)
    }
}
