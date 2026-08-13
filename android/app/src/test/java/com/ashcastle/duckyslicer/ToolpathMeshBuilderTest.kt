package com.ashcastle.duckyslicer

import android.content.ComponentCallbacks2
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class ToolpathMeshBuilderTest {
    @Test
    fun pendingLodCanReuseOnlyGeometryFromTheSameVisualScene() {
        val preview = twoLayerPreview()
        val current = ToolpathScene(
            preview = preview,
            bedSizeX = 100f,
            bedSizeY = 100f,
            opacity = 0.92f,
            depthContrast = 0.8f,
            detail = PreviewDetail.PERFORMANCE,
            segmentBudgetOverride = 4,
            renderAsLines = true,
        )

        assertTrue(
            current.canReuseGeometryWhileBuilding(
                current.copy(
                    detail = PreviewDetail.DETAIL,
                    segmentBudgetOverride = 120_000,
                    renderAsLines = false,
                ),
            ),
        )
        assertFalse(current.canReuseGeometryWhileBuilding(current.copy(opacity = 0.5f)))
        assertFalse(current.canReuseGeometryWhileBuilding(current.copy(preview = twoLayerPreview())))
        assertFalse(current.canReuseGeometryWhileBuilding(current.copy(visibleRoles = setOf(0))))
    }

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
        fun values(scene: ToolpathScene): Pair<FloatArray, ByteArray> =
            ToolpathMeshBuilder.build(scene).let { payload ->
                val bed = FloatArray(payload.bedVertices.remaining()).also(payload.bedVertices::get)
                val instances = ByteArray(payload.toolpathInstances.remaining())
                    .also(payload.toolpathInstances::get)
                bed to instances
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

        assertArrayEquals(normalized.first, machineCoordinates.first, 0f)
        assertArrayEquals(normalized.second, machineCoordinates.second)
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
    fun performancePreviewRepresentsEveryLayerInTheMaximumDensePayload() {
        val layerCount = 500
        val segmentsPerLayer = GcodeLayerPreview.MAX_SEGMENTS / layerCount
        val outerWallSegments = 10
        val segments = FloatArray(GcodeLayerPreview.MAX_SEGMENTS * GcodeLayerPreview.SEGMENT_STRIDE)
        val roleCounts = IntArray(GcodeLayerPreview.ROLE_COUNT)
        repeat(layerCount) { layer ->
            repeat(segmentsPerLayer) { line ->
                val segment = layer * segmentsPerLayer + line
                val offset = segment * GcodeLayerPreview.SEGMENT_STRIDE
                val role = if (line < outerWallSegments) 0 else 2
                val pathLine = if (role == 0) line else line - outerWallSegments
                segments[offset] = pathLine.toFloat()
                segments[offset + 1] = layer.toFloat()
                segments[offset + 2] = pathLine + 1f
                segments[offset + 3] = layer.toFloat()
                segments[offset + 4] = (layer + 1) * 0.2f
                segments[offset + 5] = role.toFloat()
                roleCounts[role] += 1
            }
        }
        val preview = GcodeLayerPreview(
            startLayer = 0,
            endLayer = layerCount - 1,
            layerCount = layerCount,
            minZMm = 0.2f,
            maxZMm = layerCount * 0.2f,
            segments = segments,
            roleSegmentCounts = roleCounts,
        )

        val plan = preview.buildRenderPlan(depthPreviewSegmentBudget(PreviewDetail.PERFORMANCE))
        val representedLayers = plan.segmentOffsets
            .map { offset -> (segments[offset + 4] / 0.2f).roundToInt() }
            .toSet()

        assertEquals(layerCount, representedLayers.size)
        assertEquals(1, representedLayers.minOrNull())
        assertEquals(layerCount, representedLayers.maxOrNull())
        assertTrue(plan.segmentOffsets.size <= depthPreviewSegmentBudget(PreviewDetail.PERFORMANCE))
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
    fun fragmentedLodKeepsSelectedPathsWholeAndInGcodeOrder() {
        val pathCount = 12
        val segmentsPerPath = 2
        val segments = FloatArray(
            pathCount * segmentsPerPath * GcodeLayerPreview.SEGMENT_STRIDE,
        )
        repeat(pathCount) { path ->
            repeat(segmentsPerPath) { withinPath ->
                val segment = path * segmentsPerPath + withinPath
                val offset = segment * GcodeLayerPreview.SEGMENT_STRIDE
                segments[offset] = withinPath.toFloat()
                segments[offset + 1] = path.toFloat()
                segments[offset + 2] = withinPath + 1f
                segments[offset + 3] = path.toFloat()
                segments[offset + 4] = (path + 1) * 0.2f
                segments[offset + 5] = 0f
            }
        }
        val preview = GcodeLayerPreview(
            0,
            pathCount - 1,
            pathCount,
            0.2f,
            pathCount * 0.2f,
            segments,
            intArrayOf(pathCount * segmentsPerPath, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        )

        val plan = preview.buildRenderPlan(segmentBudget = 4)

        assertArrayEquals(plan.segmentOffsets.sortedArray(), plan.segmentOffsets)
        assertEquals(4, plan.segmentOffsets.size)
        assertArrayEquals(
            booleanArrayOf(false, true, false, true),
            plan.connectsToPrevious,
        )
        plan.segmentOffsets.asList().chunked(2).forEach { pathOffsets ->
            assertEquals(GcodeLayerPreview.SEGMENT_STRIDE, pathOffsets[1] - pathOffsets[0])
        }
    }

    @Test
    fun repeatedLodRequestsReuseTheImmutableRenderPlan() {
        val preview = twoLayerPreview()
        val first = preview.buildRenderPlan(segmentBudget = 1, visibleRoles = setOf(0, 1))
        val second = preview.buildRenderPlan(segmentBudget = 1, visibleRoles = setOf(1, 0))

        assertSame(first, second)
    }

    @Test
    fun toolpathsBecomeCompactOutlinedRibbonInstancesInsteadOfExpandedBoxes() {
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
        val payload = ToolpathMeshBuilder.build(
            ToolpathScene(preview, 100f, 100f, 1f, 0.8f, PreviewDetail.BALANCED),
        )
        val instances = payload.toolpathInstances
            .duplicate()
            .order(payload.toolpathInstances.order())
        val zValues = List(payload.instanceCount) { index ->
            instances.getFloat(
                index * ToolpathMeshBuilder.INSTANCE_STRIDE_BYTES + 2 * Float.SIZE_BYTES,
            )
        }
        val firstHalfWidth = instances.getFloat(
            ToolpathMeshBuilder.INSTANCE_HALF_WIDTH_OFFSET_BYTES,
        )
        val firstAlpha = instances
            .get(ToolpathMeshBuilder.INSTANCE_COLOR_OFFSET_BYTES + 3)
            .toInt() and 0xff

        val bedValues = FloatArray(payload.bedVertices.remaining()).also(payload.bedVertices::get)
        assertTrue(
            "The mesh must include the bed below Z=0",
            bedValues.indices.filter { it % 8 == 2 }.map(bedValues::get).any { it < 0f },
        )
        assertTrue("Toolpath ribbons must retain real separated heights", zValues.any { it > 0.4f })
        assertTrue("Each instance must retain the role-specific extrusion width", firstHalfWidth > 0f)
        assertEquals("Packed instance alpha must preserve full opacity", 255, firstAlpha)
        assertEquals("Each toolpath must become one GPU instance", 2, payload.instanceCount)
        assertEquals(
            "Each instance must contain two endpoints, width, and packed RGBA",
            payload.instanceCount * ToolpathMeshBuilder.INSTANCE_STRIDE_BYTES,
            payload.toolpathInstances.remaining(),
        )
        assertTrue("GPU bed staging must use direct native memory", payload.bedVertices.isDirect)
        assertTrue("GPU instance staging must use direct native memory", payload.toolpathInstances.isDirect)
    }

    @Test
    fun overviewLinesUseOneExplicitCompactVertexStream() {
        val preview = twoLayerPreview()
        val payload = ToolpathMeshBuilder.build(
            ToolpathScene(
                preview = preview,
                bedSizeX = 100f,
                bedSizeY = 100f,
                opacity = 1f,
                depthContrast = 0.8f,
                detail = PreviewDetail.PERFORMANCE,
                renderAsLines = true,
            ),
        )

        assertEquals(0, payload.instanceCount)
        assertEquals(0, payload.toolpathInstances.remaining())
        assertEquals(4, payload.lineVertexCount)
        assertEquals(
            payload.lineVertexCount * ToolpathMeshBuilder.LINE_VERTEX_STRIDE_BYTES,
            payload.lineVertices.remaining(),
        )
        assertTrue(payload.lineVertices.isDirect)
    }

    @Test
    fun nearOpaquePreviewUploadsHighLayersFirstForEarlyDepthRejection() {
        val preview = twoLayerPreview()

        val opaqueZ = uploadedStartZ(preview, opacity = 0.92f)
        val translucentZ = uploadedStartZ(preview, opacity = 0.5f)

        assertTrue("Near-opaque paths must start at the high layer", opaqueZ[0] > opaqueZ[1])
        assertTrue("Translucent paths must retain source order", translucentZ[0] < translucentZ[1])
        assertTrue(ToolpathMeshBuilder.EARLY_Z_OPACITY_THRESHOLD > 0.5f)
        assertTrue(ToolpathMeshBuilder.EARLY_Z_OPACITY_THRESHOLD <= 0.92f)
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

        assertTrue(
            "Hiding the outer wall must remove its geometry",
            onlyInnerWall.instanceCount < allRoles.instanceCount,
        )
        assertTrue(
            "Selecting the inner wall must retain its geometry",
            onlyInnerWall.instanceCount > noToolpaths.instanceCount,
        )
        assertEquals(
            "Hiding toolpaths must not remove the bed",
            allRoles.bedVertices.remaining(),
            noToolpaths.bedVertices.remaining(),
        )
        assertTrue(
            "The bed must remain visible when every role is hidden",
            noToolpaths.bedVertices.remaining() > 0,
        )
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
        val payload = ToolpathMeshBuilder.build(
            ToolpathScene(preview, 220f, 220f, 0.92f, 0.78f, PreviewDetail.BALANCED),
        )

        assertEquals(
            "Dense previews must retain every segment at this size",
            segmentCount,
            payload.instanceCount,
        )
        assertEquals(
            "One compact 32-byte instance must replace six expanded 32-byte vertices",
            segmentCount * ToolpathMeshBuilder.INSTANCE_STRIDE_BYTES,
            payload.toolpathInstances.remaining(),
        )
        assertTrue(
            "Bed plus toolpath staging must stay far below expanded ribbon storage",
            payload.stagingByteCount < segmentCount * 6 * 8 * Float.SIZE_BYTES,
        )
        assertTrue(
            "The maximum 120,000-segment instance payload must stay below 4 MiB",
            GcodeLayerPreview.MAX_SEGMENTS.toLong() *
                ToolpathMeshBuilder.INSTANCE_STRIDE_BYTES < 4L * 1024L * 1024L,
        )
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
    fun sceneCacheUsesImmutablePreviewIdentityWithoutHashingItsCoordinates() {
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
        val copied = scene.copy()
        val separatePayload = scene.copy(preview = preview.copy())

        assertEquals(scene, copied)
        assertEquals(scene.hashCode(), copied.hashCode())
        assertNotEquals(
            "Separate immutable payloads must never alias one cached GPU buffer",
            scene,
            separatePayload,
        )

        val hashBeforeCoordinateChange = scene.hashCode()
        preview.segments[0] = 11f
        assertEquals(
            "Scene hashing must not rescan the large coordinate array on camera frames",
            hashBeforeCoordinateChange,
            scene.hashCode(),
        )
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

    private fun twoLayerPreview(): GcodeLayerPreview = GcodeLayerPreview(
        startLayer = 0,
        endLayer = 1,
        layerCount = 2,
        minZMm = 0.2f,
        maxZMm = 0.4f,
        segments = floatArrayOf(
            10f, 10f, 20f, 10f, 0.2f, 0f,
            10f, 12f, 20f, 12f, 0.4f, 0f,
        ),
        roleSegmentCounts = intArrayOf(2, 0, 0, 0, 0, 0, 0, 0, 0, 0),
    )

    private fun uploadedStartZ(preview: GcodeLayerPreview, opacity: Float): List<Float> {
        val payload = ToolpathMeshBuilder.build(
            ToolpathScene(preview, 100f, 100f, opacity, 0.8f, PreviewDetail.BALANCED),
        )
        val instances = payload.toolpathInstances.duplicate().order(payload.toolpathInstances.order())
        return List(payload.instanceCount) { index ->
            instances.getFloat(
                index * ToolpathMeshBuilder.INSTANCE_STRIDE_BYTES + 2 * Float.SIZE_BYTES,
            )
        }
    }
}
