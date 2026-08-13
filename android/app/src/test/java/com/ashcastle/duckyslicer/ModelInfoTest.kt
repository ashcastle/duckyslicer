package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelInfoTest {
    @Test
    fun nativePayloadDecodesBoundedGeometryAndSourceFacetMapping() {
        val payload = validPayload()

        val model = ModelInfo.fromNative(payload, "/private/project/duck.stl")

        assertEquals("duck.stl", model.fileName)
        assertEquals(5, model.triangles)
        assertEquals(listOf(20.0, 30.0, 40.0), model.dimensions)
        assertTrue(payload.copyOfRange(10, 28).contentEquals(model.previewTriangles))
        assertTrue(intArrayOf(0, 4).contentEquals(model.previewTriangleIndices))
    }

    @Test
    fun nativePayloadRejectsMissingOrUnknownEnvelope() {
        assertInvalid(null)
        assertInvalid(validPayload().also { it[0] = 0f })
        assertInvalid(validPayload().also { it[1] = 2f })
        assertInvalid(validPayload().copyOf(9))
    }

    @Test
    fun nativePayloadRejectsNonFiniteOrInconsistentGeometry() {
        assertInvalid(validPayload().also { it[6] = -20f })
        assertInvalid(validPayload().also { it[12] = Float.NaN })
        assertInvalid(validPayload().also { it[9] = 1.5f })
        assertInvalid(validPayload().copyOf(validPayload().size - 1))
    }

    @Test
    fun nativePayloadRejectsInvalidSourceTriangleIndices() {
        assertInvalid(validPayload().also { it[it.lastIndex] = 5f })
        assertInvalid(validPayload().also { it[it.lastIndex] = -1f })
        assertInvalid(validPayload().also { it[it.lastIndex] = 1.5f })
    }

    private fun assertInvalid(payload: FloatArray?) {
        assertThrows(IllegalStateException::class.java) {
            ModelInfo.fromNative(payload, "/private/project/invalid.stl")
        }
    }

    private fun validPayload(): FloatArray = floatArrayOf(
        17_492f, 1f, 5f,
        -10f, -20f, -30f,
        10f, 10f, 10f,
        2f,
        -10f, -20f, -30f,
        10f, -20f, -30f,
        10f, 10f, -30f,
        -10f, -20f, 10f,
        10f, 10f, 10f,
        -10f, 10f, 10f,
        0f, 4f,
    )
}
