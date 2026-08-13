package com.ashcastle.duckyslicer

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolpathRendererPerformanceInstrumentedTest {
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
            instances = ToolpathMeshBuilder.build(scene).instanceCount
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
}
