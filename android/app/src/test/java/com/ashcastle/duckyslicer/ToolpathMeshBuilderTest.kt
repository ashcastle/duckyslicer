package com.ashcastle.duckyslicer

import android.content.ComponentCallbacks2
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolpathMeshBuilderTest {
    @Test
    fun gpuPreviewMemoryIsReleasedOnlyAfterTheUiBecomesHidden() {
        assertFalse(
            shouldReleaseToolpathGpuMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN - 1),
        )
        assertTrue(
            shouldReleaseToolpathGpuMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN),
        )
        assertTrue(
            shouldReleaseToolpathGpuMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND),
        )
    }

    @Test
    fun machineOriginIsNormalizedOnlyForPreviewRendering() {
        fun preview(xOffset: Float, yOffset: Float) = GcodeLayerPreview(
            startLayer = 0,
            endLayer = 0,
            layerCount = 1,
            minZMm = 0.2f,
            maxZMm = 0.2f,
            segments = floatArrayOf(
                10f + xOffset,
                20f + yOffset,
                30f + xOffset,
                20f + yOffset,
                0.2f,
                0f,
            ),
            roleSegmentCounts = intArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        )
        fun values(scene: ToolpathScene): FloatArray = ToolpathMeshBuilder.build(scene).let { buffer ->
            FloatArray(buffer.remaining()).also(buffer::get)
        }

        val normalized = values(
            ToolpathScene(preview(0f, 0f), 100f, 100f, 1f, 0.8f, PreviewDetail.BALANCED),
        )
        val machineCoordinates = values(
            ToolpathScene(
                preview = preview(-50f, -60f),
                bedSizeX = 100f,
                bedSizeY = 100f,
                opacity = 1f,
                depthContrast = 0.8f,
                detail = PreviewDetail.BALANCED,
                bedOriginX = -50f,
                bedOriginY = -60f,
            ),
        )

        assertArrayEquals(normalized, machineCoordinates, 0f)
    }

    @Test
    fun densePreviewKeepsWholeOuterWallPathsAcrossTheFullHeight() {
        val layerCount = 8
        val segmentsPerLayer = 10
        val segments = FloatArray(layerCount * segmentsPerLayer * GcodeLayerPreview.SEGMENT_STRIDE)
        val roleCounts = IntArray(GcodeLayerPreview.ROLE_COUNT)
        repeat(layerCount) { layer ->
            repeat(segmentsPerLayer) { line ->
                val segment = layer * segmentsPerLayer + line
                val offset = segment * GcodeLayerPreview.SEGMENT_STRIDE
                val pathLine = line % 5
                segments[offset] = pathLine.toFloat()
                segments[offset + 1] = layer.toFloat()
                segments[offset + 2] = pathLine + 1f
                segments[offset + 3] = layer.toFloat()
                segments[offset + 4] = 0.2f * (layer + 1)
                val role = if (line < 5) 0 else 2
                segments[offset + 5] = role.toFloat()
                roleCounts[role] += 1
            }
        }
        val preview = GcodeLayerPreview(0, 7, 8, 0.2f, 1.6f, segments, roleCounts)
        val plan = preview.buildRenderPlan(20)
        val selectedZ = plan.segmentOffsets.map { segments[it + 4] }
        val selectedRoles = plan.segmentOffsets.map { segments[it + 5].toInt() }

        assertTrue("The preview must retain geometry near the first layer", selectedZ.any { it == 0.2f })
        assertTrue("The preview must retain geometry near the last layer", selectedZ.any { it == 1.6f })
        assertEquals("Every visible toolpath role keeps a coherent representative path", setOf(0, 2), selectedRoles.toSet())
        assertTrue(
            "Outer walls must retain more of the mobile budget than internal infill",
            selectedRoles.count { it == 0 } > selectedRoles.count { it == 2 },
        )
        selectedZ.zip(selectedRoles).groupingBy { it }.eachCount().values.forEach { count ->
            assertEquals("LOD must retain complete five-segment paths", 5, count)
        }
        assertTrue("Every retained path segment must remain connected", plan.connectsToPrevious.count { it } >= 16)
    }

    @Test
    fun oneContinuousExtrusionPathIsNeverSampledIntoParticles() {
        val segmentCount = 30
        val segments = FloatArray(segmentCount * GcodeLayerPreview.SEGMENT_STRIDE)
        repeat(segmentCount) { index ->
            val offset = index * GcodeLayerPreview.SEGMENT_STRIDE
            segments[offset] = index.toFloat()
            segments[offset + 1] = 10f
            segments[offset + 2] = index + 1f
            segments[offset + 3] = 10f
            segments[offset + 4] = 0.2f
            segments[offset + 5] = 0f
        }
        val preview = GcodeLayerPreview(
            0,
            0,
            1,
            0.2f,
            0.2f,
            segments,
            intArrayOf(30, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        )

        val plan = preview.buildRenderPlan(segmentBudget = 5)

        assertEquals("A path may exceed LOD rather than becoming dotted", 30, plan.segmentOffsets.size)
        assertEquals(29, plan.connectsToPrevious.count { it })
    }

    @Test
    fun toolpathsBecomeFlatOutlinedRibbonsInsteadOfDisconnectedBoxes() {
        val preview = GcodeLayerPreview(
            startLayer = 0,
            endLayer = 1,
            layerCount = 2,
            minZMm = 0.2f,
            maxZMm = 0.4f,
            segments = floatArrayOf(
                10f, 10f, 20f, 10f, 0.2f, 0f,
                10f, 12f, 20f, 12f, 0.4f, 1f,
            ),
            roleSegmentCounts = intArrayOf(1, 1, 0, 0, 0, 0, 0, 0, 0),
        )
        val buffer = ToolpathMeshBuilder.build(
            ToolpathScene(preview, 100f, 100f, 1f, 0.8f, PreviewDetail.BALANCED),
        )
        val values = FloatArray(buffer.remaining()).also(buffer::get)
        val zValues = values.indices
            .filter { it % 8 == 2 }
            .map(values::get)
        val acrossValues = values.indices
            .filter { it % 8 == 7 }
            .map(values::get)

        assertTrue("The mesh must include the bed below Z=0", zValues.any { it < 0f })
        assertTrue("Toolpath ribbons must retain real separated heights", zValues.any { it > 0.4f })
        assertTrue("The shader must receive both ribbon edges", -1f in acrossValues && 1f in acrossValues)
        assertTrue("Every draw vertex must contain XYZ, RGBA, and lateral position", values.size % 8 == 0)
        assertTrue("GPU staging geometry must use direct native memory", buffer.isDirect)
    }

    @Test
    fun roleVisibilityCanExposeInnerToolpathsWithoutRemovingTheBed() {
        val preview = GcodeLayerPreview(
            startLayer = 0,
            endLayer = 0,
            layerCount = 1,
            minZMm = 0.2f,
            maxZMm = 0.2f,
            segments = floatArrayOf(
                10f, 10f, 20f, 10f, 0.2f, 0f,
                10f, 12f, 20f, 12f, 0.2f, 1f,
            ),
            roleSegmentCounts = intArrayOf(1, 1, 0, 0, 0, 0, 0, 0, 0),
        )
        val allRoles = ToolpathMeshBuilder.build(
            ToolpathScene(preview, 100f, 100f, 1f, 0.8f, PreviewDetail.BALANCED),
        )
        val onlyInnerWall = ToolpathMeshBuilder.build(
            ToolpathScene(
                preview = preview,
                bedSizeX = 100f,
                bedSizeY = 100f,
                opacity = 1f,
                depthContrast = 0.8f,
                detail = PreviewDetail.BALANCED,
                visibleRoles = setOf(1),
            ),
        )
        val noToolpaths = ToolpathMeshBuilder.build(
            ToolpathScene(
                preview = preview,
                bedSizeX = 100f,
                bedSizeY = 100f,
                opacity = 1f,
                depthContrast = 0.8f,
                detail = PreviewDetail.BALANCED,
                visibleRoles = emptySet(),
            ),
        )

        assertTrue("Hiding the outer wall must remove its geometry", onlyInnerWall.remaining() < allRoles.remaining())
        assertTrue("Selecting the inner wall must retain its geometry", onlyInnerWall.remaining() > noToolpaths.remaining())
        assertTrue("The bed must remain visible when every role is hidden", noToolpaths.remaining() > 0)
    }

    @Test
    fun balancedModeCapsDensePreviewGeometry() {
        val segmentCount = 30_000
        val segments = FloatArray(segmentCount * GcodeLayerPreview.SEGMENT_STRIDE)
        val roleCounts = IntArray(GcodeLayerPreview.ROLE_COUNT)
        repeat(segmentCount) { index ->
            val offset = index * GcodeLayerPreview.SEGMENT_STRIDE
            val role = index % GcodeLayerPreview.ROLE_COUNT
            segments[offset] = (index % 200).toFloat()
            segments[offset + 1] = (index / 200).toFloat()
            segments[offset + 2] = segments[offset] + 0.4f
            segments[offset + 3] = segments[offset + 1]
            segments[offset + 4] = 0.2f + (index / 1_000) * 0.2f
            segments[offset + 5] = role.toFloat()
            roleCounts[role] += 1
        }
        val preview = GcodeLayerPreview(0, 29, 30, 0.2f, 6f, segments, roleCounts)
        val buffer = ToolpathMeshBuilder.build(
            ToolpathScene(preview, 220f, 220f, 0.92f, 0.78f, PreviewDetail.BALANCED),
        )
        val vertexCount = buffer.remaining() / 8

        assertTrue("One flat ribbon must replace each former 36-vertex segment box", vertexCount < 190_000)
        assertTrue("Dense previews must retain every segment at this size", vertexCount > 180_000)
    }

    @Test
    fun unchangedSceneUploadsOnceUntilGeometryOrContextChanges() {
        val preview = GcodeLayerPreview(
            startLayer = 0,
            endLayer = 0,
            layerCount = 1,
            minZMm = 0.2f,
            maxZMm = 0.2f,
            segments = floatArrayOf(10f, 10f, 20f, 10f, 0.2f, 0f),
            roleSegmentCounts = intArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        )
        val scene = ToolpathScene(preview, 100f, 100f, 1f, 0.8f, PreviewDetail.BALANCED)
        val state = ToolpathGeometryUploadState()

        assertTrue("A new GL context must upload its first scene", state.needsUpload(scene))
        state.markUploaded(scene)
        assertFalse("Camera-only frames must reuse the GPU buffer", state.needsUpload(scene.copy()))

        val changed = scene.copy(visibleRoles = setOf(1))
        assertTrue("Role filtering must replace the GPU geometry", state.needsUpload(changed))
        state.markUploaded(changed)
        assertFalse("The changed scene must also upload only once", state.needsUpload(changed))

        state.invalidate()
        assertTrue("Context recreation must re-upload retained scene data", state.needsUpload(changed))
    }

    @Test
    fun twoSlotGeometryCacheEvictsTheLeastRecentlyUsedDetail() {
        val preview = GcodeLayerPreview(
            startLayer = 0,
            endLayer = 0,
            layerCount = 1,
            minZMm = 0.2f,
            maxZMm = 0.2f,
            segments = floatArrayOf(10f, 10f, 20f, 10f, 0.2f, 0f),
            roleSegmentCounts = intArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        )
        val requested = ToolpathScene(preview, 100f, 100f, 1f, 0.8f, PreviewDetail.BALANCED)
        val interaction = requested.copy(detail = PreviewDetail.PERFORMANCE)
        val changed = requested.copy(visibleRoles = setOf(1))
        val state = ToolpathGeometryUploadState(capacity = 2)

        assertNull(state.markUploaded(requested))
        assertNull(state.markUploaded(interaction))
        state.markUsed(requested)
        assertEquals(interaction, state.markUploaded(changed))
        assertFalse("The recently used requested VBO must remain cached", state.needsUpload(requested))
        assertTrue("The least recently used gesture VBO must be evicted", state.needsUpload(interaction))
        assertFalse("The replacement VBO must be cached", state.needsUpload(changed))
        state.remove(changed)
        assertTrue("Removing stale geometry must release its cache entry", state.needsUpload(changed))
    }
}
