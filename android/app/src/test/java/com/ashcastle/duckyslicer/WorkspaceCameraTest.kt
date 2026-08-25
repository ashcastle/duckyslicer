package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceCameraTest {
    @Test
    fun everyPresetFitsTheWholeBedAndUsesAStableOrientation() {
        val poses = WorkspaceCameraPreset.entries.associateWith(::cameraPoseForPreset)

        poses.values.forEach { pose ->
            assertEquals(1f, pose.zoom)
            assertEquals(0f, pose.panX)
            assertEquals(0f, pose.panY)
            assertTrue(pose.yawDegrees.isFinite())
            assertTrue(pose.elevationDegrees in 18f..86f)
        }
        assertEquals(-45f, poses.getValue(WorkspaceCameraPreset.ISOMETRIC).yawDegrees)
        assertEquals(86f, poses.getValue(WorkspaceCameraPreset.TOP).elevationDegrees)
        assertEquals(0f, poses.getValue(WorkspaceCameraPreset.FRONT).yawDegrees)
        assertEquals(90f, poses.getValue(WorkspaceCameraPreset.RIGHT).yawDegrees)
    }
}
