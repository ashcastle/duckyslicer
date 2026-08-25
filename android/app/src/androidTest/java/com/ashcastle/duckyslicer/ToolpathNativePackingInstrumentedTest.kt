package com.ashcastle.duckyslicer

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolpathNativePackingInstrumentedTest {
    @Test
    fun rustPackingRejectsInvalidOutputBuffers() {
        val segments = floatArrayOf(0f, 0f, 10f, 0f, 0.2f, 0f, 0f)
        val pathStarts = intArrayOf(0)
        val pathEndsExclusive = intArrayOf(1)

        fun pack(output: ByteBuffer): Int = NativeEngine.packToolpathGeometry(
            segments = segments,
            pathStarts = pathStarts,
            pathEndsExclusive = pathEndsExclusive,
            bedOriginX = 0f,
            bedOriginY = 0f,
            minZMm = 0.2f,
            maxZMm = 0.2f,
            opacity = 1f,
            depthContrast = 0.5f,
            filamentColors = DefaultFilamentPreviewColors.toIntArray(),
            colorByFilament = false,
            reverseForEarlyZ = false,
            renderAsLines = false,
            output = output,
        )

        assertEquals(-1, pack(ByteBuffer.allocateDirect(1)))
        assertEquals(-1, pack(ByteBuffer.allocate(32)))
    }

    @Test
    fun rustPackingIsByteExactWithTheManagedFallback() {
        val preview = GcodeLayerPreview(
            startLayer = 0,
            endLayer = 1,
            layerCount = 2,
            minZMm = 0.2f,
            maxZMm = 0.4f,
            segments = floatArrayOf(
                10f, 20f, 20f, 20f, 0.2f, 0f, 0f,
                20f, 20f, 20f, 30f, 0.2f, 0f, 0f,
                20f, 30f, 20f, 30f, 0.2f, 0f, 0f, // degenerate segment is omitted
                15f, 25f, 25f, 35f, 0.4f, 6f, 1f,
                25f, 35f, 35f, 35f, 0.4f, 6f, 1f,
            ),
            roleSegmentCounts = intArrayOf(3, 0, 0, 0, 0, 0, 2, 0, 0, 0),
        )
        val base = ToolpathScene(
            preview = preview,
            bedSizeX = 220f,
            bedSizeY = 220f,
            opacity = 0.92f,
            depthContrast = 0.78f,
            detail = PreviewDetail.DETAIL,
            bedOriginX = 10f,
            bedOriginY = 20f,
        )
        listOf(
            base,
            base.copy(opacity = 0.5f, renderAsLines = true),
        ).forEach { scene ->
            val native = ToolpathMeshBuilder.build(scene, useNativePacking = true)
            val managed = ToolpathMeshBuilder.build(scene, useNativePacking = false)

            assertTrue("The Android renderer must use the bounded Rust packer", native.nativePackingUsed)
            assertEquals(managed.instanceCount, native.instanceCount)
            assertEquals(managed.lineVertexCount, native.lineVertexCount)
            assertArrayEquals(managed.toolpathInstances.bytes(), native.toolpathInstances.bytes())
            assertArrayEquals(managed.lineVertices.bytes(), native.lineVertices.bytes())
        }
    }

    private fun ByteBuffer.bytes(): ByteArray = duplicate().let { copy ->
        ByteArray(copy.remaining()).also(copy::get)
    }
}
