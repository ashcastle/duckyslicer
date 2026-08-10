package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewPerformancePolicyTest {
    @Test
    fun automaticDefaultsToSmoothOnMemoryConstrainedDevices() {
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
    fun automaticUsesBalancedQualityWhenTheDeviceHasHeadroom() {
        assertEquals(
            PreviewDetail.BALANCED,
            resolvePreviewDetail(
                PreviewDetail.AUTOMATIC,
                PreviewDeviceCapabilities(isLowRamDevice = false, appMemoryClassMb = 256),
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
        assertEquals(24_000, depthPreviewSegmentBudget(PreviewDetail.PERFORMANCE))
        assertEquals(80_000, depthPreviewSegmentBudget(PreviewDetail.BALANCED))
        assertEquals(120_000, depthPreviewSegmentBudget(PreviewDetail.DETAIL))
        assertEquals(250, compatibilityPreviewSegmentBudget(PreviewDetail.PERFORMANCE, refined = false))
        assertEquals(8_000, compatibilityPreviewSegmentBudget(PreviewDetail.DETAIL, refined = true))
    }
}
