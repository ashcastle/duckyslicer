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
}
