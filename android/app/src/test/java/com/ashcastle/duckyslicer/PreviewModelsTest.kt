package com.ashcastle.duckyslicer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PreviewModelsTest {
    @Test
    fun nativePayloadKeepsMetadataSegmentsAndRolesWithoutJson() {
        val payload = floatArrayOf(
            17_491f, 1f, 0f, 1f, 2f, 0.2f, 0.4f,
            1f, 2f, 3f, 4f, 0.2f, 0f,
            3f, 4f, 5f, 6f, 0.4f, 9f,
        )

        val preview = GcodeLayerPreview.fromNative(payload)

        assertEquals(0, preview.startLayer)
        assertEquals(1, preview.endLayer)
        assertEquals(2, preview.layerCount)
        assertArrayEquals(payload.copyOfRange(7, payload.size), preview.segments, 0f)
        assertEquals(1, preview.roleSegmentCounts[0])
        assertEquals(1, preview.roleSegmentCounts[9])
    }

    @Test
    fun nativePayloadRejectsNullTruncatedOrUnknownFormats() {
        assertThrows(IllegalStateException::class.java) {
            GcodeLayerPreview.fromNative(null)
        }
        assertThrows(IllegalStateException::class.java) {
            GcodeLayerPreview.fromNative(floatArrayOf(17_491f, 1f))
        }
        assertThrows(IllegalStateException::class.java) {
            GcodeLayerPreview.fromNative(floatArrayOf(99f, 1f, 0f, 0f, 0f, 0f, 0f))
        }
    }

    @Test
    fun nativePayloadRejectsNonFiniteCoordinatesAndInvalidRoles() {
        val valid = floatArrayOf(
            17_491f, 1f, 0f, 0f, 1f, 0.2f, 0.2f,
            1f, 2f, 3f, 4f, 0.2f, 0f,
        )
        assertThrows(IllegalStateException::class.java) {
            GcodeLayerPreview.fromNative(valid.copyOf().apply { this[7] = Float.NaN })
        }
        assertThrows(IllegalStateException::class.java) {
            GcodeLayerPreview.fromNative(valid.copyOf().apply { this[12] = 1.5f })
        }
        assertThrows(IllegalStateException::class.java) {
            GcodeLayerPreview.fromNative(valid.copyOf().apply { this[12] = 10f })
        }
    }
}
