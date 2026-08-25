package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class PrepareSmoothNormalTest {
    @Test
    fun coplanarTrianglesShareAStableNormal() {
        val normals = buildPackedPrepareSmoothNormals(
            floatArrayOf(
                0f, 0f, 0f,
                1f, 0f, 0f,
                0f, 1f, 0f,
                1f, 0f, 0f,
                1f, 1f, 0f,
                0f, 1f, 0f,
            ),
        )

        repeat(normals.size / 3) { index ->
            val normal = normals.normalAt(index)
            assertEquals(0f, normal[0], NORMAL_TOLERANCE)
            assertEquals(0f, normal[1], NORMAL_TOLERANCE)
            assertEquals(1f, normal[2], NORMAL_TOLERANCE)
        }
    }

    @Test
    fun shallowCurveAveragesSharedVertexNormals() {
        val normals = buildPackedPrepareSmoothNormals(
            floatArrayOf(
                0f, 0f, 0f,
                1f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 0f,
                1f, 0f, 0.3f,
                0f, 1f, 0f,
            ),
        )

        val firstShared = normals.normalAt(0)
        val secondShared = normals.normalAt(3)
        assertTrue(firstShared[0] < -0.1f)
        assertTrue(firstShared[2] > 0.9f)
        assertEquals(firstShared[0], secondShared[0], NORMAL_TOLERANCE)
        assertEquals(firstShared[2], secondShared[2], NORMAL_TOLERANCE)
    }

    @Test
    fun rightAngleRetainsTheHardCrease() {
        val normals = buildPackedPrepareSmoothNormals(
            floatArrayOf(
                0f, 0f, 0f,
                1f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 1f,
            ),
        )

        val horizontal = normals.normalAt(0)
        val vertical = normals.normalAt(3)
        assertEquals(1f, horizontal[2], NORMAL_TOLERANCE)
        assertEquals(1f, vertical[0], NORMAL_TOLERANCE)
        assertEquals(0f, horizontal[0], NORMAL_TOLERANCE)
        assertEquals(0f, vertical[2], NORMAL_TOLERANCE)
    }

    @Test
    fun degenerateTriangleUsesFiniteFallbackNormal() {
        val normals = buildPackedPrepareSmoothNormals(FloatArray(9))

        repeat(3) { index ->
            assertEquals(listOf(0f, 0f, 1f), normals.normalAt(index).toList())
        }
    }

    @Test
    fun packedNormalsAddOnlyOneBytePerPositionComponent() {
        val vertices = FloatArray(18)

        assertEquals(vertices.size, buildPackedPrepareSmoothNormals(vertices).size)
        assertEquals(vertices.size.toLong() * 5L, prepareMeshGpuBytes(vertices))
    }

    private fun ByteArray.normalAt(vertexIndex: Int): FloatArray {
        val offset = vertexIndex * 3
        val normal = FloatArray(3) { component -> this[offset + component] / 127f }
        val length = sqrt(normal.sumOf { value -> (value * value).toDouble() }).toFloat()
        return if (length > 0f) FloatArray(3) { normal[it] / length } else normal
    }

    private companion object {
        const val NORMAL_TOLERANCE = 0.02f
    }
}
