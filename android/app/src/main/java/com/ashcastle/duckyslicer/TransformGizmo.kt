package com.ashcastle.duckyslicer

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class TransformGizmoMode { MOVE, SCALE }

internal enum class TransformGizmoAxis(val scaleAxis: ModelScaleAxis) {
    X(ModelScaleAxis.X),
    Y(ModelScaleAxis.Y),
    Z(ModelScaleAxis.Z),
}

internal data class TransformGizmoHandle(
    val axis: TransformGizmoAxis,
    val start: Offset,
    val end: Offset,
    val pixelsPerMillimeter: Float,
)

internal data class TransformGizmoLayout(
    val anchor: Offset,
    val handles: List<TransformGizmoHandle>,
)

internal fun transformGizmoLayoutForObject(
    projectObject: ProjectObject,
    minimumRotatedZ: Float,
    mode: TransformGizmoMode,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    bedSizeX: Float,
    bedSizeY: Float,
    yawDegrees: Float,
    pitchDegrees: Float,
    zoom: Float,
    pan: Offset,
    handleLengthPx: Float,
    centerClearancePx: Float,
): TransformGizmoLayout {
    if (
        viewportWidthPx <= 0f || viewportHeightPx <= 0f || bedSizeX <= 0f || bedSizeY <= 0f ||
        !minimumRotatedZ.isFinite() || !zoom.isFinite() || zoom <= 0f
    ) {
        return TransformGizmoLayout(Offset.Zero, emptyList())
    }
    val transform = projectObject.transform
    val sceneScale = min(viewportWidthPx * 0.64f, viewportHeightPx * 0.72f) /
        max(bedSizeX, bedSizeY) * zoom
    val yaw = yawDegrees / 180f * PI.toFloat()
    val pitch = pitchDegrees / 180f * PI.toFloat()
    val worldX = bedSizeX / 2f + transform.offsetXmm
    val worldY = bedSizeY / 2f + transform.offsetYmm
    val worldZ = -minimumRotatedZ + transform.offsetZmm
    val dx = worldX - bedSizeX / 2f
    val dy = worldY - bedSizeY / 2f
    val rotatedX = dx * cos(yaw) - dy * sin(yaw)
    val rotatedY = dx * sin(yaw) + dy * cos(yaw)
    val anchor = Offset(
        x = viewportWidthPx / 2f + pan.x + rotatedX * sceneScale,
        y = viewportHeightPx * 0.48f + pan.y +
            (rotatedY * sin(pitch) - worldZ * cos(pitch)) * sceneScale,
    )
    return transformGizmoLayout(
        anchor = anchor,
        transform = transform,
        mode = mode,
        yawDegrees = yawDegrees,
        pitchDegrees = pitchDegrees,
        sceneScale = sceneScale,
        handleLengthPx = handleLengthPx,
        centerClearancePx = centerClearancePx,
    )
}

/**
 * Builds fixed-size touch handles while retaining the exact screen projection of each world axis.
 * Move uses bed/world axes; scale uses the object's rotated local axes.
 */
internal fun transformGizmoLayout(
    anchor: Offset,
    transform: ModelTransform,
    mode: TransformGizmoMode,
    yawDegrees: Float,
    pitchDegrees: Float,
    sceneScale: Float,
    handleLengthPx: Float,
    centerClearancePx: Float,
): TransformGizmoLayout {
    if (
        !anchor.x.isFinite() || !anchor.y.isFinite() || !yawDegrees.isFinite() ||
        !pitchDegrees.isFinite() || !sceneScale.isFinite() || sceneScale <= 0f ||
        !handleLengthPx.isFinite() || handleLengthPx <= 0f ||
        !centerClearancePx.isFinite() || centerClearancePx < 0f ||
        centerClearancePx >= handleLengthPx
    ) {
        return TransformGizmoLayout(anchor, emptyList())
    }
    val yaw = yawDegrees / 180f * PI.toFloat()
    val pitch = pitchDegrees / 180f * PI.toFloat()
    val handles = TransformGizmoAxis.entries.mapNotNull { axis ->
        val local = when (axis) {
            TransformGizmoAxis.X -> floatArrayOf(1f, 0f, 0f)
            TransformGizmoAxis.Y -> floatArrayOf(0f, 1f, 0f)
            TransformGizmoAxis.Z -> floatArrayOf(0f, 0f, 1f)
        }
        val world = if (mode == TransformGizmoMode.SCALE) transform.rotate(local) else local
        val cameraX = world[0] * cos(yaw) - world[1] * sin(yaw)
        val cameraY = (
            world[0] * sin(yaw) + world[1] * cos(yaw)
            ) * sin(pitch) - world[2] * cos(pitch)
        val projectedLength = sqrt(cameraX * cameraX + cameraY * cameraY)
        if (!projectedLength.isFinite() || projectedLength <= 0.0001f) return@mapNotNull null
        val direction = Offset(cameraX / projectedLength, cameraY / projectedLength)
        TransformGizmoHandle(
            axis = axis,
            start = anchor + direction * centerClearancePx,
            end = anchor + direction * handleLengthPx,
            // Preserve projected motion so the object follows the finger along the grabbed axis.
            // A small floor keeps a nearly edge-on axis controllable instead of numerically wild.
            pixelsPerMillimeter = sceneScale * max(projectedLength, 0.08f),
        )
    }
    return TransformGizmoLayout(anchor, handles)
}

internal fun hitTestTransformGizmo(
    layout: TransformGizmoLayout,
    point: Offset,
    touchRadiusPx: Float,
): TransformGizmoAxis? {
    if (
        !point.x.isFinite() || !point.y.isFinite() ||
        !touchRadiusPx.isFinite() || touchRadiusPx < 0f
    ) {
        return null
    }
    return layout.handles
        .map { handle ->
            val endpointDistance = (point - handle.end).getDistance()
            val segmentDistance = gizmoPointToSegmentDistance(point, handle.start, handle.end)
            handle.axis to minOf(endpointDistance * 0.8f, segmentDistance)
        }
        .filter { (_, distance) -> distance <= touchRadiusPx }
        .minByOrNull { (_, distance) -> distance }
        ?.first
}

internal fun transformGizmoDragMillimeters(
    handle: TransformGizmoHandle,
    pointerDelta: Offset,
): Float {
    if (
        !pointerDelta.x.isFinite() || !pointerDelta.y.isFinite() ||
        !handle.pixelsPerMillimeter.isFinite() || handle.pixelsPerMillimeter <= 0f
    ) {
        return 0f
    }
    val axisVector = handle.end - handle.start
    val length = axisVector.getDistance()
    if (!length.isFinite() || length <= 0.0001f) return 0f
    val direction = axisVector / length
    return (pointerDelta.x * direction.x + pointerDelta.y * direction.y) /
        handle.pixelsPerMillimeter
}

internal fun moveTransformFromGizmo(
    start: ModelTransform,
    axis: TransformGizmoAxis,
    dragMillimeters: Float,
    bedSizeX: Float,
    bedSizeY: Float,
    bedPolygon: List<Float>,
    maxPrintHeight: Float,
): ModelTransform {
    if (
        !dragMillimeters.isFinite() || !bedSizeX.isFinite() || !bedSizeY.isFinite() ||
        bedSizeX <= 0f || bedSizeY <= 0f || !maxPrintHeight.isFinite() || maxPrintHeight <= 0f
    ) {
        return start
    }
    if (axis == TransformGizmoAxis.Z) {
        return start.copy(
            offsetZmm = (start.offsetZmm + dragMillimeters)
                .coerceIn(-maxPrintHeight, maxPrintHeight),
        )
    }
    val requestedX = start.offsetXmm +
        if (axis == TransformGizmoAxis.X) dragMillimeters else 0f
    val requestedY = start.offsetYmm +
        if (axis == TransformGizmoAxis.Y) dragMillimeters else 0f
    val coerced = coercePointToBedPolygon(
        bedSizeX / 2f + requestedX,
        bedSizeY / 2f + requestedY,
        bedPolygon,
    )
    return start.copy(
        offsetXmm = coerced.first - bedSizeX / 2f,
        offsetYmm = coerced.second - bedSizeY / 2f,
    )
}

internal fun scaleTransformFromGizmo(
    start: ModelTransform,
    axis: TransformGizmoAxis,
    dragMillimeters: Float,
    sourceDimensionMm: Float,
    range: ClosedFloatingPointRange<Float> = ProjectStore.MIN_SCALE..ProjectStore.MAX_SCALE,
): ModelTransform {
    if (
        !dragMillimeters.isFinite() || !sourceDimensionMm.isFinite() ||
        sourceDimensionMm <= 0.0001f
    ) {
        return start
    }
    val current = when (axis) {
        TransformGizmoAxis.X -> start.scale
        TransformGizmoAxis.Y -> start.scaleY
        TransformGizmoAxis.Z -> start.scaleZ
    }
    // The object scales around its center, so a positive-side handle moves by half the full size.
    val requested = current + dragMillimeters * 2f / sourceDimensionMm
    if (!requested.isFinite()) return start
    return start.withAxisScale(axis.scaleAxis, requested, keepProportions = false, range = range)
}

private fun gizmoPointToSegmentDistance(point: Offset, start: Offset, end: Offset): Float {
    val segment = end - start
    val lengthSquared = segment.x * segment.x + segment.y * segment.y
    if (lengthSquared <= 0.0001f) return (point - start).getDistance()
    val offset = point - start
    val position = ((offset.x * segment.x + offset.y * segment.y) / lengthSquared)
        .coerceIn(0f, 1f)
    return (point - (start + segment * position)).getDistance()
}
