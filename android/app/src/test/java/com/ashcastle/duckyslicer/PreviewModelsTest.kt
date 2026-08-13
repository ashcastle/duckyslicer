package com.ashcastle.duckyslicer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PreviewModelsTest {
    @Test
    fun nativePayloadKeepsMetadataSegmentsAndRolesWithoutJson() {
        val payload = floatArrayOf(
            17_491f, 2f, 0f, 1f, 2f, 0.2f, 0.4f, 2f, 2f,
            1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f,
            1f, 2f, 3f, 4f, 0.2f, 0f,
            3f, 4f, 5f, 6f, 0.4f, 9f,
            1f, 2f,
        )

        val preview = GcodeLayerPreview.fromNative(payload)

        assertEquals(0, preview.startLayer)
        assertEquals(1, preview.endLayer)
        assertEquals(2, preview.layerCount)
        assertArrayEquals(payload.copyOfRange(19, 31), preview.segments, 0f)
        assertEquals(1, preview.roleSegmentCounts[0])
        assertEquals(1, preview.roleSegmentCounts[9])
    }

    @Test
    fun trustedDirectPayloadKeepsExactMetadataAndRejectsInvalidBuffers() {
        val payload = floatArrayOf(
            17_491f, 2f, 0f, 1f, 2f, 0.2f, 0.4f, 2f, 2f,
            1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f,
            1f, 2f, 3f, 4f, 0.2f, 0f,
            3f, 4f, 5f, 6f, 0.4f, 9f,
            1f, 2f,
        )
        val direct = ByteBuffer.allocateDirect(payload.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        direct.asFloatBuffer().put(payload)

        val preview = GcodeLayerPreview.fromTrustedNative(direct, payload.size)

        assertEquals(2, preview.layerCount)
        assertArrayEquals(payload.copyOfRange(19, 31), preview.segments, 0f)
        assertEquals(1, preview.roleSegmentCounts[0])
        assertEquals(1, preview.roleSegmentCounts[9])
        assertThrows(IllegalStateException::class.java) {
            GcodeLayerPreview.fromTrustedNative(ByteBuffer.allocate(payload.size * 4), payload.size)
        }
        assertThrows(IllegalStateException::class.java) {
            GcodeLayerPreview.fromTrustedNative(direct, payload.size - 1)
        }
    }

    @Test
    fun nativePayloadRejectsNullTruncatedOrUnknownFormats() {
        assertThrows(IllegalStateException::class.java) {
            GcodeLayerPreview.fromNative(null)
        }
        assertThrows(IllegalStateException::class.java) {
            GcodeLayerPreview.fromNative(floatArrayOf(17_491f, 2f))
        }
        assertThrows(IllegalStateException::class.java) {
            GcodeLayerPreview.fromNative(floatArrayOf(99f, 2f, 0f, 0f, 0f, 0f, 0f))
        }
    }

    @Test
    fun nativePayloadRejectsNonFiniteCoordinatesAndInvalidRoles() {
        val valid = floatArrayOf(
            17_491f, 2f, 0f, 0f, 1f, 0.2f, 0.2f, 1f, 1f,
            1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
            1f, 2f, 3f, 4f, 0.2f, 0f,
            1f,
        )
        assertThrows(IllegalStateException::class.java) {
            GcodeLayerPreview.fromNative(valid.copyOf().apply { this[19] = Float.NaN })
        }
        assertThrows(IllegalStateException::class.java) {
            GcodeLayerPreview.fromNative(valid.copyOf().apply { this[24] = 1.5f })
        }
        assertThrows(IllegalStateException::class.java) {
            GcodeLayerPreview.fromNative(valid.copyOf().apply { this[24] = 10f })
        }
        assertThrows(IllegalStateException::class.java) {
            GcodeLayerPreview.fromNative(valid.copyOf().apply { this[9] = 0f })
        }
        assertThrows(IllegalStateException::class.java) {
            GcodeLayerPreview.fromNative(valid.copyOf().apply { this[25] = 0f })
        }
    }
}
