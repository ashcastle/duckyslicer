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

internal data class PreparePickingIndexKey(
    val objectId: String,
    val volumeId: String,
)

/**
 * Immutable spatial hierarchy over source triangles. STL triangle order is not guaranteed to be
 * spatially coherent, so fixed contiguous chunks can span most of a model and collapse into a
 * full scan. This index partitions triangle centroids while retaining original triangle indices;
 * projected node bounds only reject impossible hits and the final triangle test remains exact.
 */
internal class PrepareVolumePickingIndex private constructor(
    private val triangleOrder: IntArray,
    private val bounds: FloatArray,
    private val nodeData: IntArray,
    val leafCount: Int,
) {
    internal fun candidateTriangles(
        transform: PreparePickingTransform,
        projection: PreparePickingProjection,
        screenX: Float,
        screenY: Float,
        touchRadiusPx: Float,
    ): IntArray {
        if (triangleOrder.isEmpty()) return IntArray(0)
        var result = IntArray(min(triangleOrder.size, PREPARE_PICKING_INITIAL_CANDIDATES))
        val stack = IntArray(nodeData.size / PREPARE_PICKING_NODE_INTS)
        val projected = FloatArray(3)
        var stackSize = 1
        stack[0] = 0
        var output = 0
        while (stackSize > 0) {
            val nodeIndex = stack[--stackSize]
            if (!transform.intersectsProjectedBounds(
                    bounds = bounds,
                    offset = nodeIndex * PREPARE_PICKING_BOUNDS_FLOATS,
                    projection = projection,
                    screenX = screenX,
                    screenY = screenY,
                    touchRadiusPx = touchRadiusPx,
                    projected = projected,
                )
            ) continue
            val dataOffset = nodeIndex * PREPARE_PICKING_NODE_INTS
            val start = nodeData[dataOffset]
            val count = nodeData[dataOffset + 1]
            if (count > 0) {
                if (output + count > result.size) {
                    result = result.copyOf(
                        max(output + count, result.size * 2).coerceAtMost(triangleOrder.size),
                    )
                }
                triangleOrder.copyInto(result, output, start, start + count)
                output += count
            } else {
                val left = nodeData[dataOffset + 2]
                val right = nodeData[dataOffset + 3]
                if (right >= 0) stack[stackSize++] = right
                if (left >= 0) stack[stackSize++] = left
            }
        }
        return result.copyOf(output)
    }

    companion object {
        fun build(vertices: FloatArray): PrepareVolumePickingIndex {
            require(vertices.size % 9 == 0) { "Prepare picking vertices are malformed" }
            val triangleCount = vertices.size / 9
            if (triangleCount == 0) {
                return PrepareVolumePickingIndex(IntArray(0), FloatArray(0), IntArray(0), 0)
            }
            return PreparePickingIndexBuilder(vertices, triangleCount).build()
        }

        internal fun fromBuilt(
            triangleOrder: IntArray,
            bounds: FloatArray,
            nodeData: IntArray,
            leafCount: Int,
        ): PrepareVolumePickingIndex = PrepareVolumePickingIndex(
            triangleOrder,
            bounds,
            nodeData,
            leafCount,
        )
    }
}

private class PreparePickingIndexBuilder(
    private val vertices: FloatArray,
    triangleCount: Int,
) {
    private val triangleOrder = IntArray(triangleCount) { it }
    private val triangleBounds = FloatArray(triangleCount * PREPARE_PICKING_BOUNDS_FLOATS)
    private val triangleCentroids = FloatArray(triangleCount * 3)
    private val nodes = ArrayList<PreparePickingBuildNode?>()
    private var leafCount = 0

    init {
        repeat(triangleCount) { triangle ->
            val source = triangle * 9
            val target = triangle * PREPARE_PICKING_BOUNDS_FLOATS
            val ax = vertices[source]
            val ay = vertices[source + 1]
            val az = vertices[source + 2]
            val bx = vertices[source + 3]
            val by = vertices[source + 4]
            val bz = vertices[source + 5]
            val cx = vertices[source + 6]
            val cy = vertices[source + 7]
            val cz = vertices[source + 8]
            triangleBounds[target] = minOf(ax, bx, cx)
            triangleBounds[target + 1] = minOf(ay, by, cy)
            triangleBounds[target + 2] = minOf(az, bz, cz)
            triangleBounds[target + 3] = maxOf(ax, bx, cx)
            triangleBounds[target + 4] = maxOf(ay, by, cy)
            triangleBounds[target + 5] = maxOf(az, bz, cz)
            val centroid = triangle * 3
            triangleCentroids[centroid] = ax + bx + cx
            triangleCentroids[centroid + 1] = ay + by + cy
            triangleCentroids[centroid + 2] = az + bz + cz
        }
    }

    fun build(): PrepareVolumePickingIndex {
        buildNode(0, triangleOrder.size)
        val bounds = FloatArray(nodes.size * PREPARE_PICKING_BOUNDS_FLOATS)
        val nodeData = IntArray(nodes.size * PREPARE_PICKING_NODE_INTS)
        nodes.forEachIndexed { index, nullableNode ->
            val node = checkNotNull(nullableNode)
            node.bounds.copyInto(bounds, index * PREPARE_PICKING_BOUNDS_FLOATS)
            val output = index * PREPARE_PICKING_NODE_INTS
            nodeData[output] = node.start
            nodeData[output + 1] = node.count
            nodeData[output + 2] = node.left
            nodeData[output + 3] = node.right
        }
        return PrepareVolumePickingIndex.fromBuilt(triangleOrder, bounds, nodeData, leafCount)
    }

    private fun buildNode(start: Int, end: Int): Int {
        val nodeIndex = nodes.size
        nodes += null
        val nodeBounds = triangleBounds(start, end)
        val count = end - start
        if (count <= PREPARE_PICKING_TRIANGLES_PER_LEAF) {
            leafCount += 1
            nodes[nodeIndex] = PreparePickingBuildNode(nodeBounds, start, count)
            return nodeIndex
        }
        val middle = splitSpatially(start, end)
        val left = buildNode(start, middle)
        val right = buildNode(middle, end)
        nodes[nodeIndex] = PreparePickingBuildNode(nodeBounds, left = left, right = right)
        return nodeIndex
    }

    private fun triangleBounds(start: Int, end: Int): FloatArray {
        val result = floatArrayOf(
            Float.POSITIVE_INFINITY,
            Float.POSITIVE_INFINITY,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
        )
        for (position in start until end) {
            val triangle = triangleOrder[position] * PREPARE_PICKING_BOUNDS_FLOATS
            result[0] = min(result[0], triangleBounds[triangle])
            result[1] = min(result[1], triangleBounds[triangle + 1])
            result[2] = min(result[2], triangleBounds[triangle + 2])
            result[3] = max(result[3], triangleBounds[triangle + 3])
            result[4] = max(result[4], triangleBounds[triangle + 4])
            result[5] = max(result[5], triangleBounds[triangle + 5])
        }
        return result
    }

    private fun splitSpatially(start: Int, end: Int): Int {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        for (position in start until end) {
            val triangle = triangleOrder[position]
            val centroid = triangle * 3
            val x = triangleCentroids[centroid]
            val y = triangleCentroids[centroid + 1]
            val z = triangleCentroids[centroid + 2]
            minX = min(minX, x)
            minY = min(minY, y)
            minZ = min(minZ, z)
            maxX = max(maxX, x)
            maxY = max(maxY, y)
            maxZ = max(maxZ, z)
        }
        var axis = 0
        var minimum = minX
        var maximum = maxX
        if (maxY - minY > maximum - minimum) {
            axis = 1
            minimum = minY
            maximum = maxY
        }
        if (maxZ - minZ > maximum - minimum) {
            axis = 2
            minimum = minZ
            maximum = maxZ
        }
        val split = (minimum + maximum) * 0.5f
        var left = start
        var right = end - 1
        while (left <= right) {
            while (left <= right && centroidComponent(triangleOrder[left], axis) < split) {
                left += 1
            }
            while (left <= right && centroidComponent(triangleOrder[right], axis) >= split) {
                right -= 1
            }
            if (left < right) {
                val value = triangleOrder[left]
                triangleOrder[left] = triangleOrder[right]
                triangleOrder[right] = value
                left += 1
                right -= 1
            }
        }
        return left.takeIf { it > start && it < end } ?: (start + (end - start) / 2)
    }

    private fun centroidComponent(triangle: Int, axis: Int): Float {
        return triangleCentroids[triangle * 3 + axis]
    }

}

private data class PreparePickingBuildNode(
    val bounds: FloatArray = FloatArray(PREPARE_PICKING_BOUNDS_FLOATS),
    val start: Int = 0,
    val count: Int = 0,
    val left: Int = -1,
    val right: Int = -1,
)

internal fun buildPreparePickingIndices(
    projectObjects: List<ProjectObject>,
): Map<PreparePickingIndexKey, PrepareVolumePickingIndex> = buildMap {
    projectObjects.forEach { projectObject ->
        projectObject.volumes.forEach { volume ->
            // Warm the exact deduplicated support set on the same background pass. A later
            // rotation or Place-on-face action can then resolve bed contact without UI work.
            volume.model.placementVertices
            put(
                PreparePickingIndexKey(projectObject.id, volume.id),
                PrepareVolumePickingIndex.build(volume.model.previewTriangles),
            )
        }
    }
}

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
    pickingIndices: Map<PreparePickingIndexKey, PrepareVolumePickingIndex> = emptyMap(),
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
            val triangleCount = vertices.size / 9
            val candidates = pickingIndices[
                PreparePickingIndexKey(projectObject.id, volume.id)
            ]?.candidateTriangles(
                transform, projection, screenX, screenY, touchRadiusPx,
            )
            val candidateCount = candidates?.size ?: triangleCount
            var candidatePosition = 0
            while (candidatePosition < candidateCount) {
                val triangleIndex = candidates?.get(candidatePosition) ?: candidatePosition
                val index = triangleIndex * 9
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
                            (abs(distance - current.distance) <= 0.001f &&
                                depth > current.depth))
                    ) {
                        bestNearby = PrepareObjectHit(projectObject.id, distance, depth)
                    }
                }
                candidatePosition += 1
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
    pickingIndices: Map<PreparePickingIndexKey, PrepareVolumePickingIndex> = emptyMap(),
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
        val triangleCount = vertices.size / 9
        val candidates = pickingIndices[
            PreparePickingIndexKey(projectObject.id, volume.id)
        ]?.candidateTriangles(
            transform, projection, screenX, screenY, touchRadiusPx,
        )
        val candidateCount = candidates?.size ?: triangleCount
        var candidatePosition = 0
        while (candidatePosition < candidateCount) {
            val previewTriangleIndex = candidates?.get(candidatePosition) ?: candidatePosition
            val index = previewTriangleIndex * 9
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
                val stableInsideTie = surfaceDepth != null &&
                    abs(surfaceDepth - bestInsideDepth) <= 0.0001f &&
                    previewTriangleIndex < bestPreviewTriangleIndex
                val stableNearbyTie = abs(nearbyDistance - bestNearbyDistance) <= 0.001f &&
                    abs(averageDepth - bestNearbyDepth) <= 0.0001f &&
                    previewTriangleIndex < bestPreviewTriangleIndex
                val replace = if (surfaceDepth != null) {
                    !hasInside || surfaceDepth > bestInsideDepth + 0.0001f || stableInsideTie
                } else {
                    !hasInside && nearbyDistance <= touchRadiusPx &&
                        (nearbyDistance < bestNearbyDistance - 0.001f ||
                            (abs(nearbyDistance - bestNearbyDistance) <= 0.001f &&
                                (averageDepth > bestNearbyDepth + 0.0001f || stableNearbyTie)))
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
            candidatePosition += 1
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

internal class PreparePickingProjection(viewport: PrepareHitTestViewport) {
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

internal class PreparePickingTransform(
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
        projectSource(
            vertices[index],
            vertices[index + 1],
            vertices[index + 2],
            projection,
            output,
            offset,
        )
    }

    fun intersectsProjectedBounds(
        bounds: FloatArray,
        offset: Int,
        projection: PreparePickingProjection,
        screenX: Float,
        screenY: Float,
        touchRadiusPx: Float,
        projected: FloatArray,
    ): Boolean {
        var left = Float.POSITIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        repeat(8) { corner ->
            projectSource(
                if (corner and 1 == 0) bounds[offset] else bounds[offset + 3],
                if (corner and 2 == 0) bounds[offset + 1] else bounds[offset + 4],
                if (corner and 4 == 0) bounds[offset + 2] else bounds[offset + 5],
                projection,
                projected,
                0,
            )
            left = min(left, projected[0])
            top = min(top, projected[1])
            right = max(right, projected[0])
            bottom = max(bottom, projected[1])
        }
        return screenX >= left - touchRadiusPx && screenX <= right + touchRadiusPx &&
            screenY >= top - touchRadiusPx && screenY <= bottom + touchRadiusPx
    }

    private fun projectSource(
        sourceX: Float,
        sourceY: Float,
        sourceZ: Float,
        projection: PreparePickingProjection,
        output: FloatArray,
        offset: Int,
    ) {
        val x = (sourceX - centerX) * signedScaleX
        val y = (sourceY - centerY) * signedScaleY
        val z = (sourceZ - centerZ) * signedScaleZ
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

private const val PREPARE_PICKING_TRIANGLES_PER_LEAF = 48
private const val PREPARE_PICKING_BOUNDS_FLOATS = 6
private const val PREPARE_PICKING_NODE_INTS = 4
private const val PREPARE_PICKING_INITIAL_CANDIDATES = 256

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
