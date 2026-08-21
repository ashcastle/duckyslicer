package com.ashcastle.duckyslicer

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransformGizmoTest {
    @Test
    fun moveAxesFollowTheWorkspaceCameraProjection() {
        val layout = transformGizmoLayout(
            anchor = Offset(100f, 200f),
            transform = ModelTransform(rotationZdeg = 90f),
            mode = TransformGizmoMode.MOVE,
            yawDegrees = 0f,
            pitchDegrees = 60f,
            sceneScale = 2f,
            handleLengthPx = 80f,
            centerClearancePx = 16f,
        )

        assertEquals(3, layout.handles.size)
        val x = layout.handles.single { it.axis == TransformGizmoAxis.X }
        val y = layout.handles.single { it.axis == TransformGizmoAxis.Y }
        val z = layout.handles.single { it.axis == TransformGizmoAxis.Z }
        assertTrue(x.end.x > layout.anchor.x)
        assertEquals(layout.anchor.y, x.end.y, 0.001f)
        assertEquals(layout.anchor.x, y.end.x, 0.001f)
        assertTrue(y.end.y > layout.anchor.y)
        assertTrue(z.end.y < layout.anchor.y)
    }

    @Test
    fun scaleAxesUseTheObjectsLocalRotation() {
        val layout = transformGizmoLayout(
            anchor = Offset.Zero,
            transform = ModelTransform(rotationZdeg = 90f),
            mode = TransformGizmoMode.SCALE,
            yawDegrees = 0f,
            pitchDegrees = 60f,
            sceneScale = 2f,
            handleLengthPx = 80f,
            centerClearancePx = 16f,
        )

        val x = layout.handles.single { it.axis == TransformGizmoAxis.X }
        assertEquals(0f, x.end.x, 0.001f)
        assertTrue(x.end.y > 0f)
    }

    @Test
    fun hitTestingPrefersTheNearestTouchSizedAxis() {
        val layout = transformGizmoLayout(
            anchor = Offset(100f, 100f),
            transform = ModelTransform(),
            mode = TransformGizmoMode.MOVE,
            yawDegrees = -45f,
            pitchDegrees = 55f,
            sceneScale = 2f,
            handleLengthPx = 80f,
            centerClearancePx = 16f,
        )
        val x = layout.handles.single { it.axis == TransformGizmoAxis.X }

        assertEquals(
            TransformGizmoAxis.X,
            hitTestTransformGizmo(layout, x.end + Offset(3f, -2f), 18f),
        )
        assertNull(hitTestTransformGizmo(layout, Offset(500f, 500f), 18f))
        assertNull(hitTestTransformGizmo(layout, Offset.Zero, -1f))
    }

    @Test
    fun dragDistanceUsesTheProjectedAxisScale() {
        val layout = transformGizmoLayout(
            anchor = Offset.Zero,
            transform = ModelTransform(),
            mode = TransformGizmoMode.MOVE,
            yawDegrees = 0f,
            pitchDegrees = 60f,
            sceneScale = 2f,
            handleLengthPx = 80f,
            centerClearancePx = 16f,
        )
        val x = layout.handles.single { it.axis == TransformGizmoAxis.X }

        assertEquals(10f, transformGizmoDragMillimeters(x, Offset(20f, 0f)), 0.001f)
        assertEquals(0f, transformGizmoDragMillimeters(x, Offset(0f, 20f)), 0.001f)
    }

    @Test
    fun scaleGestureChangesOnlyTheGrabbedAxisAndStaysBounded() {
        val start = ModelTransform(scale = 1f, scaleY = 2f, scaleZ = 3f)

        assertEquals(
            ModelTransform(scale = 1f, scaleY = 3f, scaleZ = 3f),
            scaleTransformFromGizmo(start, TransformGizmoAxis.Y, 10f, 20f),
        )
        assertEquals(
            ProjectStore.MIN_SCALE,
            scaleTransformFromGizmo(start, TransformGizmoAxis.X, -1000f, 20f).scale,
            0f,
        )
        assertEquals(
            start,
            scaleTransformFromGizmo(start, TransformGizmoAxis.Z, Float.NaN, 20f),
        )
    }

    @Test
    fun moveGestureConstrainsPlanarAndVerticalAxes() {
        val bed = rectangularBedPolygon(100f, 80f)
        val start = ModelTransform(offsetXmm = 10f, offsetYmm = -5f, offsetZmm = 2f)

        assertEquals(
            ModelTransform(offsetXmm = 50f, offsetYmm = -5f, offsetZmm = 2f),
            moveTransformFromGizmo(start, TransformGizmoAxis.X, 100f, 100f, 80f, bed, 120f),
        )
        val yMoved = moveTransformFromGizmo(
            start,
            TransformGizmoAxis.Y,
            -100f,
            100f,
            80f,
            bed,
            120f,
        )
        assertEquals(10f, yMoved.offsetXmm, 0.0001f)
        assertEquals(-40f, yMoved.offsetYmm, 0.0001f)
        assertEquals(2f, yMoved.offsetZmm, 0f)
        assertEquals(
            ModelTransform(offsetXmm = 10f, offsetYmm = -5f, offsetZmm = 120f),
            moveTransformFromGizmo(start, TransformGizmoAxis.Z, 500f, 100f, 80f, bed, 120f),
        )
    }
}
