package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewPerformancePolicyTest {
    @Test
    fun automaticDefaultsToMeasuredPerformanceTier() {
        assertEquals(PreviewDetail.AUTOMATIC, AppSettings().previewDetail)
        assertEquals(
            PreviewDetail.PERFORMANCE,
            resolvePreviewDetail(
                PreviewDetail.AUTOMATIC,
                PreviewDeviceCapabilities(isLowRamDevice = true, appMemoryClassMb = 512),
            ),
        )
        assertEquals(
            PreviewDetail.PERFORMANCE,
            resolvePreviewDetail(
                PreviewDetail.AUTOMATIC,
                PreviewDeviceCapabilities(isLowRamDevice = false, appMemoryClassMb = 192),
            ),
        )
    }

    @Test
    fun automaticDoesNotMistakeRamCapacityForGpuHeadroom() {
        assertEquals(
            PreviewDetail.PERFORMANCE,
            resolvePreviewDetail(
                PreviewDetail.AUTOMATIC,
                PreviewDeviceCapabilities(isLowRamDevice = false, appMemoryClassMb = 256),
            ),
        )
    }

    @Test
    fun automaticPromotesOnlyAfterThreeCompletedResponsiveFramesPerTier() {
        val controller = AdaptivePreviewDetailController()
        val workload = Any()

        assertEquals(PreviewDetail.PERFORMANCE, controller.detailFor(PreviewDetail.AUTOMATIC, workload))
        assertTrue(controller.shouldMeasure(PreviewDetail.AUTOMATIC, workload))
        repeat(3) { sample ->
            assertTrue(
                controller.recordCompletedFrame(
                    PreviewDetail.AUTOMATIC,
                    workload,
                    PreviewDetail.PERFORMANCE,
                    completionMs = 20.0 + sample,
                ),
            )
        }
        assertEquals(PreviewDetail.BALANCED, controller.detailFor(PreviewDetail.AUTOMATIC, workload))

        repeat(3) {
            assertTrue(
                controller.recordCompletedFrame(
                    PreviewDetail.AUTOMATIC,
                    workload,
                    PreviewDetail.BALANCED,
                    completionMs = 20.0,
                ),
            )
        }
        assertEquals(PreviewDetail.DETAIL, controller.detailFor(PreviewDetail.AUTOMATIC, workload))
        repeat(2) {
            assertTrue(
                controller.recordCompletedFrame(
                    PreviewDetail.AUTOMATIC,
                    workload,
                    PreviewDetail.DETAIL,
                    completionMs = 23.0,
                ),
            )
        }
        assertFalse(
            controller.recordCompletedFrame(
                PreviewDetail.AUTOMATIC,
                workload,
                PreviewDetail.DETAIL,
                completionMs = 23.0,
            ),
        )
        assertEquals(PreviewDetail.DETAIL, controller.detailFor(PreviewDetail.AUTOMATIC, workload))
        assertTrue(controller.isSettledForTest())
        assertFalse(controller.shouldMeasure(PreviewDetail.AUTOMATIC, workload))
    }

    @Test
    fun slowCandidateFallsBackToLastProvenTierWithoutOscillation() {
        val controller = AdaptivePreviewDetailController()
        val workload = Any()

        repeat(3) {
            assertTrue(
                controller.recordCompletedFrame(
                    PreviewDetail.AUTOMATIC,
                    workload,
                    PreviewDetail.PERFORMANCE,
                    completionMs = 18.0,
                ),
            )
        }
        assertEquals(PreviewDetail.BALANCED, controller.detailFor(PreviewDetail.AUTOMATIC, workload))
        assertFalse(
            controller.recordCompletedFrame(
                PreviewDetail.AUTOMATIC,
                workload,
                PreviewDetail.BALANCED,
                completionMs = 25.0,
            ),
        )
        assertEquals(PreviewDetail.PERFORMANCE, controller.detailFor(PreviewDetail.AUTOMATIC, workload))
        assertTrue(controller.isSettledForTest())
        assertFalse(controller.shouldMeasure(PreviewDetail.AUTOMATIC, workload))
    }

    @Test
    fun automaticCalibrationResetsForAChangedPreviewWorkload() {
        val controller = AdaptivePreviewDetailController(fastFrameMs = 48.0, requiredFastSamples = 1)
        val first = Any()
        val second = Any()

        assertTrue(
            controller.recordCompletedFrame(
                PreviewDetail.AUTOMATIC,
                first,
                PreviewDetail.PERFORMANCE,
                completionMs = 10.0,
            ),
        )
        assertEquals(PreviewDetail.BALANCED, controller.detailFor(PreviewDetail.AUTOMATIC, first))
        assertEquals(PreviewDetail.PERFORMANCE, controller.detailFor(PreviewDetail.AUTOMATIC, second))
        assertFalse(controller.isSettledForTest())
    }

    @Test
    fun explicitQualityNeverRunsAutomaticCalibration() {
        val controller = AdaptivePreviewDetailController()
        val workload = Any()

        assertEquals(PreviewDetail.DETAIL, controller.detailFor(PreviewDetail.DETAIL, workload))
        assertFalse(controller.shouldMeasure(PreviewDetail.DETAIL, workload))
        assertFalse(
            controller.recordCompletedFrame(
                PreviewDetail.DETAIL,
                workload,
                PreviewDetail.DETAIL,
                completionMs = 1.0,
            ),
        )
    }

    @Test
    fun explicitQualityAlwaysWinsOverAutomaticDeviceSelection() {
        val constrained = PreviewDeviceCapabilities(isLowRamDevice = true, appMemoryClassMb = 128)

        assertEquals(PreviewDetail.PERFORMANCE, resolvePreviewDetail(PreviewDetail.PERFORMANCE, constrained))
        assertEquals(PreviewDetail.BALANCED, resolvePreviewDetail(PreviewDetail.BALANCED, constrained))
        assertEquals(PreviewDetail.DETAIL, resolvePreviewDetail(PreviewDetail.DETAIL, constrained))
    }

    @Test
    fun gesturesTemporarilyUseOneLowerGeometryTier() {
        assertEquals(
            PreviewDetail.PERFORMANCE,
            previewDetailForInteraction(PreviewDetail.PERFORMANCE, interactionActive = true),
        )
        assertEquals(
            PreviewDetail.PERFORMANCE,
            previewDetailForInteraction(PreviewDetail.BALANCED, interactionActive = true),
        )
        assertEquals(
            PreviewDetail.BALANCED,
            previewDetailForInteraction(PreviewDetail.DETAIL, interactionActive = true),
        )
        assertEquals(
            PreviewDetail.DETAIL,
            previewDetailForInteraction(PreviewDetail.DETAIL, interactionActive = false),
        )
    }

    @Test
    fun segmentBudgetsStayBoundedForBothRenderers() {
        assertEquals(10_000, depthPreviewSegmentBudget(PreviewDetail.PERFORMANCE))
        assertEquals(80_000, depthPreviewSegmentBudget(PreviewDetail.BALANCED))
        assertEquals(120_000, depthPreviewSegmentBudget(PreviewDetail.DETAIL))
        assertEquals(4_000, depthPreviewInteractionSegmentBudget(PreviewDetail.PERFORMANCE))
        assertEquals(4_000, depthPreviewInteractionSegmentBudget(PreviewDetail.BALANCED))
        assertEquals(10_000, depthPreviewInteractionSegmentBudget(PreviewDetail.DETAIL))
        assertEquals(250, compatibilityPreviewSegmentBudget(PreviewDetail.PERFORMANCE, refined = false))
        assertEquals(8_000, compatibilityPreviewSegmentBudget(PreviewDetail.DETAIL, refined = true))
    }

    @Test
    fun weakDevicePerformanceTierKeepsContinuousPathsCheap() {
        assertTrue(shouldDrawToolpathLines(PreviewDetail.PERFORMANCE, false, false))
        assertTrue(shouldDrawToolpathLines(PreviewDetail.BALANCED, true, false))
        assertTrue(shouldDrawToolpathLines(PreviewDetail.DETAIL, false, true))
        assertTrue(shouldDrawToolpathLines(PreviewDetail.DETAIL, false, false, true))
        assertFalse(shouldDrawToolpathLines(PreviewDetail.BALANCED, false, false))
        assertFalse(shouldDrawToolpathLines(PreviewDetail.DETAIL, false, false))
    }

    @Test
    fun denseOverviewUsesScreenSpaceBudgetAndRestoresFullDetailWhenZoomed() {
        assertFalse(shouldUseDenseOverviewLines(DENSE_PREVIEW_OVERVIEW_SEGMENTS - 1, 1f))
        assertTrue(shouldUseDenseOverviewLines(DENSE_PREVIEW_OVERVIEW_SEGMENTS, 1f))
        assertTrue(
            shouldUseDenseOverviewLines(
                GcodeLayerPreview.MAX_SEGMENTS,
                DENSE_PREVIEW_RIBBON_ZOOM,
            ),
        )
        assertFalse(
            shouldUseDenseOverviewLines(
                GcodeLayerPreview.MAX_SEGMENTS,
                DENSE_PREVIEW_RIBBON_ZOOM + 0.01f,
            ),
        )
        assertFalse(shouldUseDenseOverviewLines(GcodeLayerPreview.MAX_SEGMENTS, Float.NaN))

        assertEquals(
            DENSE_PREVIEW_MIN_SEGMENTS,
            depthPreviewOverviewSegmentBudget(PreviewDetail.DETAIL, 360, 640, 1f),
        )
        assertEquals(
            19_200,
            depthPreviewOverviewSegmentBudget(PreviewDetail.DETAIL, 720, 1_280, 1f),
        )
        assertEquals(
            DENSE_PREVIEW_MAX_SEGMENTS,
            depthPreviewOverviewSegmentBudget(PreviewDetail.DETAIL, 1_440, 3_200, 1f),
        )
        assertEquals(
            GcodeLayerPreview.MAX_SEGMENTS,
            depthPreviewOverviewSegmentBudget(
                PreviewDetail.DETAIL,
                360,
                640,
                DENSE_PREVIEW_RIBBON_ZOOM + 0.01f,
            ),
        )
        assertEquals(
            depthPreviewSegmentBudget(PreviewDetail.PERFORMANCE),
            depthPreviewOverviewSegmentBudget(PreviewDetail.PERFORMANCE, 1_440, 3_200, 1f),
        )
    }

    @Test
    fun depthRendererFailureFallsBackWithoutOverwritingTheUserPreference() {
        assertTrue(
            shouldUseDepthTestedPreview(
                PreviewRenderingMode.DEPTH_TESTED,
                deviceSupported = true,
                runtimeAvailable = true,
            ),
        )
        assertFalse(
            shouldUseDepthTestedPreview(
                PreviewRenderingMode.DEPTH_TESTED,
                deviceSupported = true,
                runtimeAvailable = false,
            ),
        )
        assertFalse(
            shouldUseDepthTestedPreview(
                PreviewRenderingMode.DEPTH_TESTED,
                deviceSupported = false,
                runtimeAvailable = true,
            ),
        )
        assertFalse(
            shouldUseDepthTestedPreview(
                PreviewRenderingMode.COMPATIBILITY,
                deviceSupported = true,
                runtimeAvailable = true,
            ),
        )
    }
}
