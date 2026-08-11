package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class ModelTransformTest {
    @Test
    fun nativeTransformJsonUsesRustOffsetZFieldName() {
        val json = JSONObject(
            ModelTransform(offsetZmm = 7f, scale = 1.2f, scaleY = 1.4f, scaleZ = 1.6f)
                .toJson(100f, 100f),
        )

        assertEquals(7.0, json.getDouble("offsetZMm"), 0.0001)
        assertFalse(json.has("offsetZmm"))
        assertEquals(1.2, json.getJSONArray("scaleAxes").getDouble(0), 0.0001)
        assertEquals(1.4, json.getJSONArray("scaleAxes").getDouble(1), 0.0001)
        assertEquals(1.6, json.getJSONArray("scaleAxes").getDouble(2), 0.0001)
    }

    @Test
    fun orcaOrientationPreservesPositionAndScaleWhileReplacingRotation() {
        val transform = ModelTransform(
            offsetXmm = 12f,
            offsetYmm = -8f,
            offsetZmm = 7f,
            rotationXdeg = 5f,
            rotationYdeg = 10f,
            rotationZdeg = 15f,
            scale = 1.5f,
            mirrorX = true,
        )
        val orientation = OrcaOrientation(
            doubleArrayOf(Math.PI / 2.0, -Math.PI / 6.0, Math.PI / 2.0),
        )

        assertEquals(
            ModelTransform(
                offsetXmm = 12f,
                offsetYmm = -8f,
                offsetZmm = 7f,
                rotationXdeg = 90f,
                rotationYdeg = -30f,
                rotationZdeg = 90f,
                scale = 1.5f,
                mirrorX = true,
            ),
            transform.withOrcaOrientation(orientation),
        )
    }

    @Test
    fun orcaOrientationCanonicalizesSignedZeroForDurableSessionEquality() {
        val transformed = ModelTransform().withOrcaOrientation(
            OrcaOrientation(doubleArrayOf(0.0, -0.0, 0.0)),
        )

        assertEquals(ModelTransform(), transformed)
        assertEquals(0f.toBits(), transformed.rotationYdeg.toBits())
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
            scaleY = 3f,
            scaleZ = 4f,
            mirrorX = true,
            mirrorZ = true,
        ).transformLocal(floatArrayOf(1f, 2f, 3f))

        assertEquals(-2f, transformed[0], 0.0001f)
        assertEquals(6f, transformed[1], 0.0001f)
        assertEquals(-12f, transformed[2], 0.0001f)
    }

    @Test
    fun axisScaleCanPreserveOrReleaseCurrentProportions() {
        val current = ModelTransform(scale = 1f, scaleY = 2f, scaleZ = 4f)
        val locked = current.withAxisScale(ModelScaleAxis.Y, 3f, true, 0.05f..10f)
        val unlocked = current.withAxisScale(ModelScaleAxis.Y, 3f, false, 0.05f..10f)

        assertEquals(ModelTransform(scale = 1.5f, scaleY = 3f, scaleZ = 6f), locked)
        assertEquals(ModelTransform(scale = 1f, scaleY = 3f, scaleZ = 4f), unlocked)
        assertFalse(current.hasUniformScale())
        assertTrue(ModelTransform(scale = 1.5f).hasUniformScale())
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
