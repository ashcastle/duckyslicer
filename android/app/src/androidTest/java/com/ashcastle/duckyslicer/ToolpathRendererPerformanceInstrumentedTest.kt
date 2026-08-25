package com.ashcastle.duckyslicer

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class ToolpathRendererPerformanceInstrumentedTest {
    @Test
    fun maximumNativePayloadDecodesAndBuildsTheFirstPlanWithinBounds() {
        val raw = denseNativePayload(
            segmentCount = GcodeLayerPreview.MAX_SEGMENTS,
            layerCount = 500,
        )
        val direct = ByteBuffer.allocateDirect(raw.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        direct.asFloatBuffer().put(raw)
        val decodeDurations = ArrayList<Long>()
        val firstPlanDurations = ArrayList<Long>()
        val planSegmentCounts = ArrayList<Int>()
        repeat(3) {
            val decodeStarted = SystemClock.elapsedRealtimeNanos()
            val preview = GcodeLayerPreview.fromTrustedNative(direct, raw.size)
            decodeDurations += SystemClock.elapsedRealtimeNanos() - decodeStarted

            val planStarted = SystemClock.elapsedRealtimeNanos()
            val plan = preview.buildRenderPlan(depthPreviewSegmentBudget(PreviewDetail.PERFORMANCE))
            firstPlanDurations += SystemClock.elapsedRealtimeNanos() - planStarted
            planSegmentCounts += plan.segmentCount
            assertTrue(
                "First plan must remain non-empty and bounded: ${plan.segmentCount}",
                plan.segmentCount in 1..(
                    depthPreviewSegmentBudget(PreviewDetail.PERFORMANCE) +
                        GcodeLayerPreview.MAX_SEGMENTS / 500
                    ),
            )
        }
        val decodeMs = decodeDurations.map { it / 1_000_000.0 }
        val planMs = firstPlanDurations.map { it / 1_000_000.0 }
        println(
            "DuckyPreview decode sourceSegments=${GcodeLayerPreview.MAX_SEGMENTS} " +
                "decodeMs=$decodeMs firstPlanMs=$planMs planSegments=$planSegmentCounts",
        )
        assertTrue("Maximum Preview decode must stay responsive: $decodeMs", decodeMs.max() <= 150.0)
        assertTrue("First Preview plan must stay responsive: $planMs", planMs.max() <= 50.0)
    }

    @Test
    fun maximumPreviewCacheLookupNeverRehashesCoordinates() {
        val preview = densePreview(segmentCount = GcodeLayerPreview.MAX_SEGMENTS, layerCount = 300)
        val scene = ToolpathScene(
            preview = preview,
            bedSizeX = 220f,
            bedSizeY = 220f,
            opacity = 0.92f,
            depthContrast = 0.78f,
            detail = PreviewDetail.DETAIL,
        )
        val state = ToolpathGeometryUploadState(capacity = 2)
        state.markUploaded(scene)
        val durations = ArrayList<Long>()
        repeat(30) {
            val started = SystemClock.elapsedRealtimeNanos()
            assertTrue(!state.needsUpload(scene.copy()))
            state.markUsed(scene.copy())
            durations += SystemClock.elapsedRealtimeNanos() - started
        }
        val sorted = durations.drop(5).sorted()
        val cacheP50Ms = sorted[sorted.size / 2] / 1_000_000.0
        val cacheP95Ms = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.lastIndex)] /
            1_000_000.0
        println(
            "DuckyPreview cache sourceSegments=${GcodeLayerPreview.MAX_SEGMENTS} " +
                "cacheP50Ms=$cacheP50Ms cacheP95Ms=$cacheP95Ms",
        )
        assertTrue(
            "Camera-frame cache lookup must not hash Preview coordinates: p95=$cacheP95Ms ms",
            cacheP95Ms <= 4.0,
        )
    }

    @Test
    fun maximumLayerRangeBuildsResponsiveInteractionGeometry() {
        val preview = densePreview(segmentCount = GcodeLayerPreview.MAX_SEGMENTS, layerCount = 300)
        val scene = ToolpathScene(
            preview = preview,
            bedSizeX = 220f,
            bedSizeY = 220f,
            opacity = 0.92f,
            depthContrast = 0.78f,
            detail = PreviewDetail.DETAIL,
            segmentBudgetOverride = depthPreviewInteractionSegmentBudget(PreviewDetail.DETAIL),
            renderAsLines = true,
        )
        val indexStarted = SystemClock.elapsedRealtimeNanos()
        preview.prepareRenderIndex()
        val indexMs = (SystemClock.elapsedRealtimeNanos() - indexStarted) / 1_000_000.0
        val planDurations = ArrayList<Long>()
        val durations = ArrayList<Long>()
        var instances = 0
        repeat(5) {
            val planStarted = SystemClock.elapsedRealtimeNanos()
            val plan = preview.buildRenderPlan(
                depthPreviewInteractionSegmentBudget(PreviewDetail.DETAIL),
            )
            planDurations += SystemClock.elapsedRealtimeNanos() - planStarted
            assertTrue(plan.segmentOffsets.isNotEmpty())
            val started = SystemClock.elapsedRealtimeNanos()
            val payload = ToolpathMeshBuilder.build(scene)
            assertTrue("Android geometry must use bounded Rust packing", payload.nativePackingUsed)
            instances = payload.lineVertexCount / 2
            durations += SystemClock.elapsedRealtimeNanos() - started
        }
        val sortedPlans = planDurations.drop(2).sorted()
        val sorted = durations.drop(2).sorted()
        val planP50Ms = sortedPlans[sortedPlans.size / 2] / 1_000_000.0
        val planP95Ms = sortedPlans.last() / 1_000_000.0
        val p50Ms = sorted[sorted.size / 2] / 1_000_000.0
        val p95Ms = sorted.last() / 1_000_000.0
        println(
            "DuckyPreview sourceSegments=${GcodeLayerPreview.MAX_SEGMENTS} " +
                "instances=$instances indexMs=$indexMs planP50Ms=$planP50Ms planP95Ms=$planP95Ms " +
                "interactionBuildP50Ms=$p50Ms interactionBuildP95Ms=$p95Ms",
        )
        assertTrue("Background path indexing must stay bounded: index=$indexMs ms", indexMs <= 300.0)
        assertTrue("Cached path planning must stay responsive: p95=$planP95Ms ms", planP95Ms <= 25.0)
        assertTrue(instances in 1..depthPreviewInteractionSegmentBudget(PreviewDetail.DETAIL))
        assertTrue(
            "Maximum preview interaction median must stay responsive: p50=$p50Ms ms",
            p50Ms <= 80.0,
        )
        assertTrue(
            "Maximum preview interaction geometry must stay bounded: p95=$p95Ms ms",
            p95Ms <= 150.0,
        )
    }

    private fun densePreview(segmentCount: Int, layerCount: Int): GcodeLayerPreview {
        require(segmentCount % layerCount == 0)
        val segmentsPerLayer = segmentCount / layerCount
        val segments = FloatArray(segmentCount * GcodeLayerPreview.SEGMENT_STRIDE)
        val roleCounts = IntArray(GcodeLayerPreview.ROLE_COUNT)
        repeat(layerCount) { layer ->
            val z = 0.2f + layer * 0.2f
            val role = layer % GcodeLayerPreview.ROLE_COUNT
            repeat(segmentsPerLayer) { segment ->
                val offset = (layer * segmentsPerLayer + segment) * GcodeLayerPreview.SEGMENT_STRIDE
                val x = segment * (200f / segmentsPerLayer)
                val y = 10f + layer % 200
                segments[offset] = x
                segments[offset + 1] = y
                segments[offset + 2] = x + 200f / segmentsPerLayer
                segments[offset + 3] = y
                segments[offset + 4] = z
                segments[offset + 5] = role.toFloat()
                roleCounts[role] += 1
            }
        }
        assertEquals(segmentCount, roleCounts.sum())
        return GcodeLayerPreview(
            startLayer = 0,
            endLayer = layerCount - 1,
            layerCount = layerCount,
            minZMm = 0.2f,
            maxZMm = layerCount * 0.2f,
            segments = segments,
            roleSegmentCounts = roleCounts,
        )
    }

    private fun denseNativePayload(segmentCount: Int, layerCount: Int): FloatArray {
        val preview = densePreview(segmentCount, layerCount)
        val headerFloats = 20
        val pathStride = 1
        return FloatArray(
            headerFloats + layerCount + preview.segments.size + layerCount * pathStride,
        ).also { raw ->
            raw[0] = 17_491f
            raw[1] = 4f
            raw[2] = preview.startLayer.toFloat()
            raw[3] = preview.endLayer.toFloat()
            raw[4] = preview.layerCount.toFloat()
            raw[5] = preview.minZMm
            raw[6] = preview.maxZMm
            raw[7] = segmentCount.toFloat()
            raw[8] = layerCount.toFloat()
            raw[9] = layerCount.toFloat()
            preview.roleSegmentCounts.forEachIndexed { role, count ->
                raw[10 + role] = count.toFloat()
            }
            repeat(layerCount) { layer -> raw[headerFloats + layer] = (layer + 1) * 0.2f }
            preview.segments.copyInto(raw, destinationOffset = headerFloats + layerCount)
            val segmentsPerLayer = segmentCount / layerCount
            var pathOffset = headerFloats + layerCount + preview.segments.size
            repeat(layerCount) { layer ->
                raw[pathOffset] = ((layer + 1) * segmentsPerLayer).toFloat()
                pathOffset += pathStride
            }
        }
    }
}
