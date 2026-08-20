package com.ashcastle.duckyslicer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class OrcaFacetPreviewTest {
    private val source = floatArrayOf(
        0f, 0f, 0f,
        2f, 0f, 0f,
        0f, 2f, 0f,
    )

    @Test
    fun oneEdgeSplitMatchesOrcaReverseChildTraversal() {
        val leaves = OrcaFacetPreviewTessellator.tessellate("841", source, 0, 8)

        assertEquals(listOf(1, 2), leaves.map { it.state })
        assertArrayEquals(
            floatArrayOf(1f, 1f, 0f, 0f, 2f, 0f, 0f, 0f, 0f),
            leaves[0].vertices,
            0f,
        )
        assertArrayEquals(
            floatArrayOf(0f, 0f, 0f, 2f, 0f, 0f, 1f, 1f, 0f),
            leaves[1].vertices,
            0f,
        )
    }

    @Test
    fun twoAndThreeEdgeSplitsKeepOnlyPaintedChildren() {
        val twoEdge = OrcaFacetPreviewTessellator.tessellate("0482", source, 0, 8)
        val threeEdge = OrcaFacetPreviewTessellator.tessellate("04843", source, 0, 8)

        assertEquals(listOf(2, 1), twoEdge.map { it.state })
        assertEquals(listOf(1, 2, 1), threeEdge.map { it.state })
        assertArrayEquals(
            floatArrayOf(2f, 0f, 0f, 0f, 2f, 0f, 0f, 1f, 0f),
            twoEdge[0].vertices,
            0f,
        )
        assertArrayEquals(
            floatArrayOf(1f, 0f, 0f, 2f, 0f, 0f, 0f, 1f, 0f),
            twoEdge[1].vertices,
            0f,
        )
        assertArrayEquals(
            floatArrayOf(1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f),
            threeEdge[0].vertices,
            0f,
        )
        assertArrayEquals(
            floatArrayOf(1f, 1f, 0f, 0f, 2f, 0f, 0f, 1f, 0f),
            threeEdge[1].vertices,
            0f,
        )
    }

    @Test
    fun wholeTriangleAndExtendedStatesAreDecoded() {
        assertEquals(2, OrcaFacetPreviewTessellator.rootLeafState("8"))
        assertEquals(16, OrcaFacetPreviewTessellator.rootLeafState("DC"))
    }

    @Test
    fun oversizedTreeFallsBackToDeterministicDominantPaintedState() {
        val leaves = OrcaFacetPreviewTessellator.tessellate("04483", source, 0, 1)

        assertEquals(1, leaves.size)
        assertEquals(1, leaves.single().state)
        assertArrayEquals(source, leaves.single().vertices, 0f)
    }
}
