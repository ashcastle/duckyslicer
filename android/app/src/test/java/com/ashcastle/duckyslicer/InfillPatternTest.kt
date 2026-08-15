package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InfillPatternTest {
    @Test
    fun sparsePatternListExactlyMatchesPinnedOrcaEnum() {
        assertEquals(26, SPARSE_INFILL_PATTERNS.size)
        assertEquals(SPARSE_INFILL_PATTERNS.size, SPARSE_INFILL_PATTERNS.distinct().size)
        assertEquals(
            listOf(
                "rectilinear", "alignedrectilinear", "zigzag", "crosszag", "lockedzag",
                "line", "grid", "triangles", "tri-hexagon", "cubic", "adaptivecubic",
                "quartercubic", "supportcubic", "lightning", "honeycomb", "3dhoneycomb",
                "lateral-honeycomb", "lateral-lattice", "crosshatch", "tpmsd", "tpmsfk",
                "gyroid", "concentric", "hilbertcurve", "archimedeanchords",
                "octagramspiral",
            ),
            SPARSE_INFILL_PATTERNS,
        )
    }

    @Test
    fun multilineCompatibilityMatchesOrcaDesktopDependencies() {
        assertEquals(22, MULTILINE_INFILL_PATTERNS.size)
        assertTrue(MULTILINE_INFILL_PATTERNS.containsAll(listOf("gyroid", "tpmsd", "crosshatch")))
        for (pattern in listOf("zigzag", "crosszag", "lockedzag", "line")) {
            assertEquals(1, fillMultilineForPattern(pattern, 5))
        }
        assertEquals(4, fillMultilineForPattern("crosshatch", 4))
        assertEquals(5, fillMultilineForPattern("gyroid", 99))
        assertEquals(1, fillMultilineForPattern("gyroid", -1))
    }
}
