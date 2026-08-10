package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelMeshEdgeTest {
    @Test
    fun coplanarTrianglesHideTheirSharedDiagonal() {
        val edges = buildModelMeshEdges(
            floatArrayOf(
                0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f,
                0f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f,
            ),
        )

        assertEquals(5, edges.size)
        assertEquals(4, edges.count { it.adjacentTriangleIndex == null })
        val shared = edges.single { it.adjacentTriangleIndex != null }
        assertFalse("A flat triangulation edge must not become visible", shared.sharp)
    }

    @Test
    fun realFoldRemainsAVisibleFeatureEdge() {
        val edges = buildModelMeshEdges(
            floatArrayOf(
                0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f,
                0f, 0f, 0f, 0f, 0f, 1f, 1f, 0f, 0f,
            ),
        )

        val shared = edges.singleOrNull { it.adjacentTriangleIndex != null }
        assertNotNull(shared)
        assertTrue("A ninety-degree fold must remain visible", requireNotNull(shared).sharp)
    }

    @Test
    fun broadCurveDoesNotExposeItsTriangulation() {
        val diagonal = 0.70710677f
        val edges = buildModelMeshEdges(
            floatArrayOf(
                0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f, 0f, 0f, 0f, diagonal, diagonal,
            ),
        )

        val shared = edges.singleOrNull { it.adjacentTriangleIndex != null }
        assertNotNull(shared)
        assertFalse("A forty-five-degree bend should shade smoothly", requireNotNull(shared).sharp)
    }
}
