package com.ashcastle.duckyslicer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PreviewModelsTest {
    @Test
    fun nativePayloadKeepsMetadataSegmentsAndRolesWithoutJson() {
        val payload = floatArrayOf(
            17_491f, 3f, 0f, 1f, 2f, 0.2f, 0.4f, 2f, 2f,
            1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f,
            1f, 2f, 3f, 4f, 0.2f, 0f, 0f,
            3f, 4f, 5f, 6f, 0.4f, 9f, 1f,
            1f, 2f,
        )

        val preview = GcodeLayerPreview.fromNative(payload)

        assertEquals(0, preview.startLayer)
        assertEquals(1, preview.endLayer)
        assertEquals(2, preview.layerCount)
        assertArrayEquals(payload.copyOfRange(19, 33), preview.segments, 0f)
        assertEquals(1, preview.toolSegmentCounts[0])
        assertEquals(1, preview.toolSegmentCounts[1])
        assertEquals(1, preview.roleSegmentCounts[0])
        assertEquals(1, preview.roleSegmentCounts[9])
    }

    @Test
    fun trustedDirectPayloadKeepsExactMetadataAndRejectsInvalidBuffers() {
        val payload = floatArrayOf(
            17_491f, 3f, 0f, 1f, 2f, 0.2f, 0.4f, 2f, 2f,
            1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f,
            1f, 2f, 3f, 4f, 0.2f, 0f, 0f,
            3f, 4f, 5f, 6f, 0.4f, 9f, 1f,
            1f, 2f,
        )
        val direct = ByteBuffer.allocateDirect(payload.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        direct.asFloatBuffer().put(payload)

        val preview = GcodeLayerPreview.fromTrustedNative(direct, payload.size)

        assertEquals(2, preview.layerCount)
        assertArrayEquals(payload.copyOfRange(19, 33), preview.segments, 0f)
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
            17_491f, 3f, 0f, 0f, 1f, 0.2f, 0.2f, 1f, 1f,
            1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
            1f, 2f, 3f, 4f, 0.2f, 0f, 0f,
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
            GcodeLayerPreview.fromNative(valid.copyOf().apply { this[25] = 1.5f })
        }
        assertThrows(IllegalStateException::class.java) {
            GcodeLayerPreview.fromNative(valid.copyOf().apply { this[25] = 16f })
        }
        assertThrows(IllegalStateException::class.java) {
            GcodeLayerPreview.fromNative(valid.copyOf().apply { this[9] = 0f })
        }
        assertThrows(IllegalStateException::class.java) {
            GcodeLayerPreview.fromNative(valid.copyOf().apply { this[26] = 0f })
        }
    }

    @Test
    fun memoryPressureDropsOnlyRebuildablePreviewCaches() {
        val preview = GcodeLayerPreview(
            startLayer = 0,
            endLayer = 1,
            layerCount = 2,
            minZMm = 0.2f,
            maxZMm = 0.4f,
            segments = floatArrayOf(
                0f, 0f, 10f, 0f, 0.2f, 0f, 0f,
                10f, 0f, 10f, 10f, 0.2f, 0f, 0f,
                0f, 0f, 0f, 10f, 0.4f, 1f, 0f,
                0f, 10f, 10f, 10f, 0.4f, 1f, 0f,
            ),
            roleSegmentCounts = intArrayOf(2, 2, 0, 0, 0, 0, 0, 0, 0, 0),
        )
        val authoritativeSegments = preview.segments.copyOf()
        val first = preview.buildRenderPlan(segmentBudget = 4)
        val firstOffsets = first.segmentOffsets.copyOf()
        val firstConnections = first.connectsToPrevious.copyOf()

        assertTrue(preview.derivedCacheStateForTest().indexedPathCount > 0)
        assertEquals(1, preview.derivedCacheStateForTest().renderPlanCount)

        preview.releaseDerivedMemoryForMemoryPressure()

        assertEquals(PreviewDerivedCacheState(0, 0), preview.derivedCacheStateForTest())
        assertArrayEquals(authoritativeSegments, preview.segments, 0f)
        val rebuilt = preview.buildRenderPlan(segmentBudget = 4)
        assertNotSame(first, rebuilt)
        assertArrayEquals(firstOffsets, rebuilt.segmentOffsets)
        assertArrayEquals(firstConnections, rebuilt.connectsToPrevious)
        assertTrue(preview.derivedCacheStateForTest().indexedPathCount > 0)
        assertEquals(1, preview.derivedCacheStateForTest().renderPlanCount)
    }

    @Test
    fun nativePreviewPoolRejectsLeasesReleasedAfterATrim() {
        NativePreviewBufferPool.trimForMemoryPressure()
        val retained = NativePreviewBufferPool.acquire()
        NativePreviewBufferPool.release(retained)
        assertEquals(1, NativePreviewBufferPool.retainedBufferCountForTest())

        val stale = NativePreviewBufferPool.acquire()
        NativePreviewBufferPool.trimForMemoryPressure()
        NativePreviewBufferPool.release(stale)
        assertEquals(0, NativePreviewBufferPool.retainedBufferCountForTest())

        val fresh = NativePreviewBufferPool.acquire()
        assertNotSame(stale.buffer, fresh.buffer)
        assertTrue(fresh.buffer.isDirect)
        assertEquals(ByteOrder.nativeOrder(), fresh.buffer.order())
        assertEquals(GcodeLayerPreview.MAX_PAYLOAD_BYTES, fresh.buffer.capacity())
        NativePreviewBufferPool.release(fresh)
        assertEquals(1, NativePreviewBufferPool.retainedBufferCountForTest())
        NativePreviewBufferPool.trimForMemoryPressure()
    }
}
