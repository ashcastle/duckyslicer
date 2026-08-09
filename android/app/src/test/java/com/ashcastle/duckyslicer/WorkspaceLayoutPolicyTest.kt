package com.ashcastle.duckyslicer

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
        assertEquals(201f, workspacePanelMaxHeightDp(283f), 0.001f)
        assertEquals(704f, workspacePanelMaxHeightDp(786f), 0.001f)
        assertEquals(1f, workspacePanelMaxHeightDp(60f), 0.001f)
    }

    @Test
    fun activeSliceAndInitialPreviewLockModelEditing() {
        assertTrue(workspaceEditingBusy(false, false, slicing = true, previewLoading = false))
        assertTrue(workspaceEditingBusy(false, false, slicing = false, previewLoading = true))
        assertFalse(workspaceEditingBusy(false, false, slicing = false, previewLoading = false))
    }
}
