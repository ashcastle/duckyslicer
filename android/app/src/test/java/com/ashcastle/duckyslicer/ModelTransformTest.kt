package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTransformTest {
    @Test
    fun orcaOrientationPreservesPositionAndScaleWhileReplacingRotation() {
        val transform = ModelTransform(
            offsetXmm = 12f,
            offsetYmm = -8f,
            rotationXdeg = 5f,
            rotationYdeg = 10f,
            rotationZdeg = 15f,
            scale = 1.5f,
        )
        val orientation = OrcaOrientation(
            doubleArrayOf(Math.PI / 2.0, -Math.PI / 6.0, Math.PI / 2.0),
        )

        assertEquals(
            ModelTransform(
                offsetXmm = 12f,
                offsetYmm = -8f,
                rotationXdeg = 90f,
                rotationYdeg = -30f,
                rotationZdeg = 90f,
                scale = 1.5f,
            ),
            transform.withOrcaOrientation(orientation),
        )
    }

    @Test
    fun orcaOrientationRejectsMalformedNativeData() {
        val malformed = listOf(doubleArrayOf(), doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, Double.NaN, 0.0))

        malformed.forEach { rotations ->
            assertTrue(runCatching { OrcaOrientation(rotations) }.isFailure)
        }
    }

    @Test
    fun localPreviewTransformMirrorsBeforeRotation() {
        val transformed = ModelTransform(
            scale = 2f,
            mirrorX = true,
            mirrorZ = true,
        ).transformLocal(floatArrayOf(1f, 2f, 3f))

        assertEquals(-2f, transformed[0], 0.0001f)
        assertEquals(4f, transformed[1], 0.0001f)
        assertEquals(-6f, transformed[2], 0.0001f)
    }

    @Test
    fun orcaArrangementRejectsCountAndGeometryMismatches() {
        val malformed = listOf(
            { OrcaArrangement(floatArrayOf(), floatArrayOf(), floatArrayOf()) },
            { OrcaArrangement(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f), floatArrayOf(0f, 0f)) },
            {
                OrcaArrangement(
                    floatArrayOf(0f, Float.NaN),
                    floatArrayOf(1f, 1f, 1f),
                    floatArrayOf(0f, 0f),
                )
            },
            {
                OrcaArrangement(
                    floatArrayOf(0f, 0f),
                    floatArrayOf(1f, 0f, 1f),
                    floatArrayOf(0f, 0f),
                )
            },
            {
                OrcaArrangement(
                    floatArrayOf(0f, 0f),
                    floatArrayOf(1f, 1f, 1f),
                    floatArrayOf(Float.NaN, 0f),
                )
            },
        )

        malformed.forEach { create -> assertTrue(runCatching(create).isFailure) }
    }
}
