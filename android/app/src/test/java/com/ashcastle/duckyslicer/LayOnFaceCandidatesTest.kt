package com.ashcastle.duckyslicer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LayOnFaceCandidatesTest {
    @Test
    fun adjacentCoplanarTrianglesBecomeOneSelectableFace() {
        val candidates = detectLayOnFaceCandidates(
            floatArrayOf(
                0f, 0f, 0f, 10f, 0f, 0f, 10f, 10f, 0f,
                0f, 0f, 0f, 10f, 10f, 0f, 0f, 10f, 0f,
            ),
        )

        assertEquals(1, candidates.size)
        assertArrayEquals(intArrayOf(0, 1), candidates.single().previewTriangleIndices)
        assertEquals(100f, candidates.single().areaMm2, 0.001f)
    }

    @Test
    fun disconnectedPlanesRemainSeparateAndLargestComesFirst() {
        val candidates = detectLayOnFaceCandidates(
            floatArrayOf(
                0f, 0f, 0f, 4f, 0f, 0f, 0f, 4f, 0f,
                10f, 10f, 0f, 13.5f, 10f, 0f, 10f, 13.5f, 0f,
            ),
        )

        assertEquals(2, candidates.size)
        assertEquals(8f, candidates[0].areaMm2, 0.001f)
        assertEquals(6.125f, candidates[1].areaMm2, 0.001f)
    }

    @Test
    fun tinyAndDegenerateFacesAreNotOffered() {
        val candidates = detectLayOnFaceCandidates(
            floatArrayOf(
                0f, 0f, 0f, 0.5f, 0f, 0f, 0f, 0.5f, 0f,
                0f, 0f, 0f, 1f, 1f, 1f, 2f, 2f, 2f,
            ),
        )

        assertEquals(emptyList<LayOnFaceCandidate>(), candidates)
    }
}
