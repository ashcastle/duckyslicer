package com.ashcastle.duckyslicer

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal data class PrepareObjectPlacement(
    val geometry: ProjectObjectGeometry,
    val minimumRotatedZ: Float,
)

internal data class PrepareHitTestViewport(
    val widthPx: Float,
    val heightPx: Float,
    val bedSizeX: Float,
    val bedSizeY: Float,
    val yawDegrees: Float,
    val pitchDegrees: Float,
    val zoom: Float,
    val panX: Float,
    val panY: Float,
)

/**
 * Performs exact Prepare-model picking only when the user touches the scene. The projection and
 * object transforms intentionally mirror [PrepareModelRenderer], so ordinary camera movement does
 * not require rebuilding a CPU copy of every projected triangle.
 */
internal fun findPrepareObjectAtScreen(
    projectObjects: List<ProjectObject>,
    placements: Map<String, PrepareObjectPlacement>,
    viewport: PrepareHitTestViewport,
    screenX: Float,
    screenY: Float,
    touchRadiusPx: Float,
): String? {
    if (
        viewport.widthPx <= 0f || viewport.heightPx <= 0f || viewport.bedSizeX <= 0f ||
        viewport.bedSizeY <= 0f || viewport.zoom <= 0f || !screenX.isFinite() ||
        !screenY.isFinite() || touchRadiusPx < 0f
    ) {
        return null
    }
    val projection = PreparePickingProjection(viewport)
    val singleCandidate = projectObjects.size == 1
    var bestInside: PrepareObjectHit? = null
    var bestNearby: PrepareObjectHit? = null
    projectObjects.forEach { projectObject ->
        val placement = placements[projectObject.id] ?: return@forEach
        val transform = PreparePickingTransform(
            transform = projectObject.transform,
            geometry = placement.geometry,
            minimumRotatedZ = placement.minimumRotatedZ,
            bedSizeX = viewport.bedSizeX,
            bedSizeY = viewport.bedSizeY,
        )
        val projected = FloatArray(9)
        projectObject.volumes.forEach { volume ->
            val vertices = volume.model.previewTriangles
            var index = 0
            while (index + 8 < vertices.size) {
                transform.project(vertices, index, projection, projected, 0)
                transform.project(vertices, index + 3, projection, projected, 3)
                transform.project(vertices, index + 6, projection, projected, 6)
                val surfaceDepth = triangleDepthAtPoint(screenX, screenY, projected)
                val depth = surfaceDepth ?: (projected[2] + projected[5] + projected[8]) / 3f
                if (surfaceDepth != null) {
                    if (singleCandidate) return projectObject.id
                    if (bestInside == null || depth > checkNotNull(bestInside).depth) {
                        bestInside = PrepareObjectHit(projectObject.id, 0f, depth)
                    }
                } else if (bestInside == null && touchRadiusPx > 0f) {
                    val distance = minOf(
                        pointToSegmentDistance(screenX, screenY, projected, 0, 3),
                        pointToSegmentDistance(screenX, screenY, projected, 3, 6),
                        pointToSegmentDistance(screenX, screenY, projected, 6, 0),
                    )
                    val current = bestNearby
                    if (
                        distance <= touchRadiusPx &&
                        (current == null || distance < current.distance - 0.001f ||
                            (abs(distance - current.distance) <= 0.001f && depth > current.depth))
                    ) {
                        bestNearby = PrepareObjectHit(projectObject.id, distance, depth)
                    }
                }
                index += 9
            }
        }
    }
    return bestInside?.objectId ?: bestNearby?.objectId
}

internal fun findPrepareFacetAtScreen(
    projectObject: ProjectObject,
    placement: PrepareObjectPlacement,
    viewport: PrepareHitTestViewport,
    screenX: Float,
    screenY: Float,
    touchRadiusPx: Float,
    selectableTriangles: Map<String, BooleanArray>? = null,
): ModelScreenTriangle? {
    if (
        viewport.widthPx <= 0f || viewport.heightPx <= 0f || viewport.bedSizeX <= 0f ||
        viewport.bedSizeY <= 0f || viewport.zoom <= 0f || !screenX.isFinite() ||
        !screenY.isFinite() || touchRadiusPx < 0f
    ) {
        return null
    }
    val projection = PreparePickingProjection(viewport)
    val transform = PreparePickingTransform(
        transform = projectObject.transform,
        geometry = placement.geometry,
        minimumRotatedZ = placement.minimumRotatedZ,
        bedSizeX = viewport.bedSizeX,
        bedSizeY = viewport.bedSizeY,
    )
    val projected = FloatArray(9)
    val bestProjected = FloatArray(9)
    var bestVolumeId: String? = null
    var bestPreviewTriangleIndex = -1
    var bestSourceFacetIndex = -1
    var bestInsideDepth = Float.NEGATIVE_INFINITY
    var bestNearbyDistance = Float.POSITIVE_INFINITY
    var bestNearbyDepth = Float.NEGATIVE_INFINITY
    var hasInside = false

    projectObject.volumes.forEach { volume ->
        val vertices = volume.model.previewTriangles
        var previewTriangleIndex = 0
        var index = 0
        while (index + 8 < vertices.size) {
            val selectable = selectableTriangles?.get(volume.id)
            if (
                selectableTriangles == null ||
                selectable?.getOrNull(previewTriangleIndex) == true
            ) {
                transform.project(vertices, index, projection, projected, 0)
                transform.project(vertices, index + 3, projection, projected, 3)
                transform.project(vertices, index + 6, projection, projected, 6)
                val surfaceDepth = triangleDepthAtPoint(screenX, screenY, projected)
                val averageDepth = (projected[2] + projected[5] + projected[8]) / 3f
                val nearbyDistance = if (surfaceDepth == null && touchRadiusPx > 0f) {
                    minOf(
                        pointToSegmentDistance(screenX, screenY, projected, 0, 3),
                        pointToSegmentDistance(screenX, screenY, projected, 3, 6),
                        pointToSegmentDistance(screenX, screenY, projected, 6, 0),
                    )
                } else {
                    Float.POSITIVE_INFINITY
                }
                val replace = if (surfaceDepth != null) {
                    !hasInside || surfaceDepth > bestInsideDepth
                } else {
                    !hasInside && nearbyDistance <= touchRadiusPx &&
                        (nearbyDistance < bestNearbyDistance - 0.001f ||
                            (abs(nearbyDistance - bestNearbyDistance) <= 0.001f &&
                                averageDepth > bestNearbyDepth))
                }
                if (replace) {
                    hasInside = surfaceDepth != null
                    if (surfaceDepth != null) bestInsideDepth = surfaceDepth
                    bestNearbyDistance = nearbyDistance
                    bestNearbyDepth = averageDepth
                    bestVolumeId = volume.id
                    bestPreviewTriangleIndex = previewTriangleIndex
                    bestSourceFacetIndex = volume.model.previewTriangleIndices
                        .getOrElse(previewTriangleIndex) { previewTriangleIndex }
                    projected.copyInto(bestProjected)
                }
            }
            previewTriangleIndex += 1
            index += 9
        }
    }
    val volumeId = bestVolumeId ?: return null
    return ModelScreenTriangle(
        sourceFacetIndex = bestSourceFacetIndex,
        previewTriangleIndex = bestPreviewTriangleIndex,
        a = Offset(bestProjected[0], bestProjected[1]),
        b = Offset(bestProjected[3], bestProjected[4]),
        c = Offset(bestProjected[6], bestProjected[7]),
        depth = if (hasInside) bestInsideDepth else bestNearbyDepth,
        volumeId = volumeId,
    )
}

private data class PrepareObjectHit(
    val objectId: String,
    val distance: Float,
    val depth: Float,
)

private class PreparePickingProjection(viewport: PrepareHitTestViewport) {
    private val yaw = viewport.yawDegrees / 180f * PI.toFloat()
    private val pitch = viewport.pitchDegrees / 180f * PI.toFloat()
    private val yawSin = sin(yaw)
    private val yawCos = cos(yaw)
    private val pitchSin = sin(pitch)
    private val pitchCos = cos(pitch)
    private val scale = min(viewport.widthPx * 0.64f, viewport.heightPx * 0.72f) /
        max(viewport.bedSizeX, viewport.bedSizeY) * viewport.zoom
    private val centerX = viewport.widthPx / 2f + viewport.panX
    private val centerY = viewport.heightPx * 0.48f + viewport.panY
    private val bedCenterX = viewport.bedSizeX / 2f
    private val bedCenterY = viewport.bedSizeY / 2f

    fun project(x: Float, y: Float, z: Float, output: FloatArray, offset: Int) {
        val dx = x - bedCenterX
        val dy = y - bedCenterY
        val rotatedX = dx * yawCos - dy * yawSin
        val rotatedY = dx * yawSin + dy * yawCos
        output[offset] = centerX + rotatedX * scale
        output[offset + 1] = centerY + (rotatedY * pitchSin - z * pitchCos) * scale
        output[offset + 2] = rotatedY * pitchCos + z * pitchSin
    }
}

private class PreparePickingTransform(
    transform: ModelTransform,
    geometry: ProjectObjectGeometry,
    minimumRotatedZ: Float,
    bedSizeX: Float,
    bedSizeY: Float,
) {
    private val centerX = (geometry.minX + geometry.maxX) / 2f
    private val centerY = (geometry.minY + geometry.maxY) / 2f
    private val centerZ = (geometry.minZ + geometry.maxZ) / 2f
    private val signedScaleX = transform.scale * if (transform.mirrorX) -1f else 1f
    private val signedScaleY = transform.scaleY * if (transform.mirrorY) -1f else 1f
    private val signedScaleZ = transform.scaleZ * if (transform.mirrorZ) -1f else 1f
    private val rx = transform.rotationXdeg / 180f * PI.toFloat()
    private val ry = transform.rotationYdeg / 180f * PI.toFloat()
    private val rz = transform.rotationZdeg / 180f * PI.toFloat()
    private val sinX = sin(rx)
    private val cosX = cos(rx)
    private val sinY = sin(ry)
    private val cosY = cos(ry)
    private val sinZ = sin(rz)
    private val cosZ = cos(rz)
    private val translationX = bedSizeX / 2f + transform.offsetXmm
    private val translationY = bedSizeY / 2f + transform.offsetYmm
    private val translationZ = -minimumRotatedZ + transform.offsetZmm

    fun project(
        vertices: FloatArray,
        index: Int,
        projection: PreparePickingProjection,
        output: FloatArray,
        offset: Int,
    ) {
        val x = (vertices[index] - centerX) * signedScaleX
        val y = (vertices[index + 1] - centerY) * signedScaleY
        val z = (vertices[index + 2] - centerZ) * signedScaleZ
        val afterXy = y * cosX - z * sinX
        val afterXz = y * sinX + z * cosX
        val afterYx = x * cosY + afterXz * sinY
        val afterYz = -x * sinY + afterXz * cosY
        projection.project(
            x = afterYx * cosZ - afterXy * sinZ + translationX,
            y = afterYx * sinZ + afterXy * cosZ + translationY,
            z = afterYz + translationZ,
            output = output,
            offset = offset,
        )
    }
}

private fun triangleDepthAtPoint(
    x: Float,
    y: Float,
    triangle: FloatArray,
): Float? {
    val denominator = (triangle[4] - triangle[7]) * (triangle[0] - triangle[6]) +
        (triangle[6] - triangle[3]) * (triangle[1] - triangle[7])
    if (abs(denominator) <= 0.0001f) return null
    val weightA = (
        (triangle[4] - triangle[7]) * (x - triangle[6]) +
            (triangle[6] - triangle[3]) * (y - triangle[7])
        ) / denominator
    val weightB = (
        (triangle[7] - triangle[1]) * (x - triangle[6]) +
            (triangle[0] - triangle[6]) * (y - triangle[7])
        ) / denominator
    val weightC = 1f - weightA - weightB
    if (weightA < -0.0001f || weightB < -0.0001f || weightC < -0.0001f) return null
    return triangle[2] * weightA + triangle[5] * weightB + triangle[8] * weightC
}

private fun pointToSegmentDistance(
    x: Float,
    y: Float,
    triangle: FloatArray,
    start: Int,
    end: Int,
): Float {
    val segmentX = triangle[end] - triangle[start]
    val segmentY = triangle[end + 1] - triangle[start + 1]
    val lengthSquared = segmentX * segmentX + segmentY * segmentY
    if (lengthSquared <= 0.0001f) {
        val dx = x - triangle[start]
        val dy = y - triangle[start + 1]
        return sqrt(dx * dx + dy * dy)
    }
    val position = (
        ((x - triangle[start]) * segmentX + (y - triangle[start + 1]) * segmentY) /
            lengthSquared
        )
        .coerceIn(0f, 1f)
    val dx = x - (triangle[start] + segmentX * position)
    val dy = y - (triangle[start + 1] + segmentY * position)
    return sqrt(dx * dx + dy * dy)
}
