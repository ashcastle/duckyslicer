package com.ashcastle.duckyslicer

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceLayoutPolicyTest {
    @Test
    fun landscapePhoneKeepsBottomNavigation() {
        assertFalse(useWorkspaceNavigationRail(widthDp = 914f, heightDp = 411f))
    }

    @Test
    fun tabletUsesNavigationRailInBothOrientations() {
        assertTrue(useWorkspaceNavigationRail(widthDp = 800f, heightDp = 1_280f))
        assertTrue(useWorkspaceNavigationRail(widthDp = 1_280f, heightDp = 800f))
    }

    @Test
    fun thresholdRequiresTheShortestSideToBeTabletSized() {
        assertFalse(useWorkspaceNavigationRail(widthDp = 599f, heightDp = 1_200f))
        assertTrue(useWorkspaceNavigationRail(widthDp = 600f, heightDp = 600f))
    }

    @Test
    fun largeFontUsesIconNavigationWithoutClippedVisibleLabels() {
        assertTrue(showWorkspaceNavigationLabels(fontScale = 1.49f))
        assertFalse(showWorkspaceNavigationLabels(fontScale = 1.5f))
        assertFalse(showWorkspaceNavigationLabels(fontScale = 2f))
    }

    @Test
    fun workspacePanelAlwaysLeavesTheTopOverlayReachable() {
        assertEquals(141f, workspacePanelMaxHeightDp(283f), 0.001f)
        assertEquals(644f, workspacePanelMaxHeightDp(786f), 0.001f)
        assertEquals(1f, workspacePanelMaxHeightDp(60f), 0.001f)
    }

    @Test
    fun activeSliceAndInitialPreviewLockModelEditing() {
        assertTrue(workspaceEditingBusy(false, false, slicing = true, previewLoading = false))
        assertTrue(workspaceEditingBusy(false, false, slicing = false, previewLoading = true))
        assertFalse(workspaceEditingBusy(false, false, slicing = false, previewLoading = false))
    }

    @Test
    fun pinchKeepsTheTouchedScenePointUnderTheFingerCentroid() {
        val result = anchoredWorkspacePanZoom(
            pan = Offset.Zero,
            zoom = 1f,
            viewportAnchor = Offset(500f, 500f),
            previousCentroid = Offset(750f, 500f),
            currentCentroid = Offset(750f, 500f),
            zoomChange = 2f,
        )

        assertEquals(1.3333f, result.zoom, 0.0001f)
        assertEquals(-83.325f, result.pan.x, 0.01f)
        assertEquals(0f, result.pan.y, 0.001f)
    }

    @Test
    fun twoFingerTranslationPansWithoutChangingZoom() {
        val result = anchoredWorkspacePanZoom(
            pan = Offset(10f, -5f),
            zoom = 2f,
            viewportAnchor = Offset(500f, 500f),
            previousCentroid = Offset(400f, 600f),
            currentCentroid = Offset(424f, 588f),
            zoomChange = 1f,
        )

        assertEquals(2f, result.zoom, 0f)
        assertEquals(34f, result.pan.x, 0.001f)
        assertEquals(-17f, result.pan.y, 0.001f)
    }

    @Test
    fun oneFingerOrbitIsViewportNormalizedInsteadOfPixelSensitive() {
        val fullViewport = workspaceOrbitDelta(Offset(1_080f, 2_340f), 1_080f, 2_340f)
        val sameFraction = workspaceOrbitDelta(Offset(540f, 1_170f), 540f, 1_170f)

        assertEquals(150f, fullViewport.x, 0f)
        assertEquals(110f, fullViewport.y, 0f)
        assertEquals(fullViewport, sameFraction)
    }
}
