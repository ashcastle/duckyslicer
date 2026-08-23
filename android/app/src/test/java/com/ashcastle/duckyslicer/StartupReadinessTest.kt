package com.ashcastle.duckyslicer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupReadinessTest {
    @Test
    fun fullyDrawnWaitsForEveryDurableWorkspaceSource() {
        assertFalse(initialWorkspaceReady(false, true, true))
        assertFalse(initialWorkspaceReady(true, false, true))
        assertFalse(initialWorkspaceReady(true, true, false))
        assertTrue(initialWorkspaceReady(true, true, true))
    }
}
