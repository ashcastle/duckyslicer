package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportTypeTest {
    @Test
    fun legacyAutomaticSelectionsMigrateWithoutBecomingManual() {
        assertEquals("normal(auto)", normalizedSupportType("normal"))
        assertEquals("tree(auto)", normalizedSupportType("tree"))
        assertEquals("tree(auto)", normalizedSupportType("hybrid(auto)"))
        assertTrue("tree".isTreeSupportType())
        assertFalse("normal".isTreeSupportType())
    }

    @Test
    fun explicitManualSelectionsRemainManual() {
        assertEquals("normal(manual)", normalizedSupportType("normal(manual)"))
        assertEquals("tree(manual)", normalizedSupportType("tree(manual)"))
        assertEquals("unsupported", normalizedSupportType("UNSUPPORTED"))
    }
}
