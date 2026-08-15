package com.ashcastle.duckyslicer

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal fun rectangularBedPolygon(width: Float, depth: Float): List<Float> = listOf(
    0f, 0f,
    width, 0f,
    width, depth,
    0f, depth,
)

internal fun bedPolygonIsValid(
    polygon: List<Float>,
    width: Float,
    depth: Float,
): Boolean {
    if (!width.isFinite() || !depth.isFinite() || width <= 0f || depth <= 0f) return false
    if (polygon.size !in 6..MAX_BED_POLYGON_COORDINATES || polygon.size % 2 != 0) return false
    if (polygon.any { !it.isFinite() }) return false

    val xs = polygon.indices.filter { it % 2 == 0 }.map(polygon::get)
    val ys = polygon.indices.filter { it % 2 == 1 }.map(polygon::get)
    if (xs.any { it < -BED_GEOMETRY_TOLERANCE_MM || it > width + BED_GEOMETRY_TOLERANCE_MM }) return false
    if (ys.any { it < -BED_GEOMETRY_TOLERANCE_MM || it > depth + BED_GEOMETRY_TOLERANCE_MM }) return false
    if (
        abs(xs.min() - 0f) > BED_GEOMETRY_TOLERANCE_MM ||
        abs(ys.min() - 0f) > BED_GEOMETRY_TOLERANCE_MM ||
        abs(xs.max() - width) > BED_GEOMETRY_TOLERANCE_MM ||
        abs(ys.max() - depth) > BED_GEOMETRY_TOLERANCE_MM
    ) {
        return false
    }

    val pointCount = polygon.size / 2
    var signedDoubleArea = 0f
    repeat(pointCount) { index ->
        val next = (index + 1) % pointCount
        val ax = polygon[index * 2]
        val ay = polygon[index * 2 + 1]
        val bx = polygon[next * 2]
        val by = polygon[next * 2 + 1]
        if (squaredDistance(ax, ay, bx, by) < MINIMUM_BED_EDGE_MM * MINIMUM_BED_EDGE_MM) return false
        signedDoubleArea += ax * by - bx * ay
    }
    if (abs(signedDoubleArea) < MINIMUM_BED_AREA_MM2 * 2f) return false

    repeat(pointCount) { first ->
        val firstNext = (first + 1) % pointCount
        for (second in first + 1 until pointCount) {
            val secondNext = (second + 1) % pointCount
            if (first == secondNext || firstNext == second) continue
            if (
                segmentsIntersect(
                    polygon[first * 2],
                    polygon[first * 2 + 1],
                    polygon[firstNext * 2],
                    polygon[firstNext * 2 + 1],
                    polygon[second * 2],
                    polygon[second * 2 + 1],
                    polygon[secondNext * 2],
                    polygon[secondNext * 2 + 1],
                )
            ) {
                return false
            }
        }
    }
    return true
}

internal fun bedExcludeAreaIsValid(
    points: List<Float>,
    width: Float,
    depth: Float,
): Boolean {
    if (!width.isFinite() || !depth.isFinite() || width <= 0f || depth <= 0f) return false
    if (points.isEmpty()) return true
    if (points.size > MAX_BED_POLYGON_COORDINATES || points.size % 2 != 0) return false
    if (points.size == 2) {
        return abs(points[0]) <= BED_GEOMETRY_TOLERANCE_MM &&
            abs(points[1]) <= BED_GEOMETRY_TOLERANCE_MM
    }
    if (points.size < 6 || points.any { !it.isFinite() }) return false
    return points.indices.all { index ->
        val coordinate = points[index]
        coordinate >= -BED_GEOMETRY_TOLERANCE_MM &&
            coordinate <= (if (index % 2 == 0) width else depth) + BED_GEOMETRY_TOLERANCE_MM
    }
}

internal fun scaledBedPolygon(
    polygon: List<Float>,
    oldWidth: Float,
    oldDepth: Float,
    newWidth: Float,
    newDepth: Float,
): List<Float> {
    if (!bedPolygonIsValid(polygon, oldWidth, oldDepth) || newWidth <= 0f || newDepth <= 0f) {
        return rectangularBedPolygon(newWidth, newDepth)
    }
    return polygon.mapIndexed { index, coordinate ->
        if (index % 2 == 0) coordinate * newWidth / oldWidth else coordinate * newDepth / oldDepth
    }
}

internal fun scaledBedExcludeArea(
    points: List<Float>,
    oldWidth: Float,
    oldDepth: Float,
    newWidth: Float,
    newDepth: Float,
): List<Float> {
    if (
        !bedExcludeAreaIsValid(points, oldWidth, oldDepth) ||
        newWidth <= 0f || newDepth <= 0f
    ) {
        return listOf(0f, 0f)
    }
    return points.mapIndexed { index, coordinate ->
        if (index % 2 == 0) coordinate * newWidth / oldWidth else coordinate * newDepth / oldDepth
    }
}

internal fun scaledBedOrigin(origin: Float, oldSize: Float, newSize: Float): Float =
    if (origin.isFinite() && oldSize.isFinite() && oldSize > 0f && newSize.isFinite() && newSize > 0f) {
        origin * newSize / oldSize
    } else {
        0f
    }

internal fun machineBedPolygon(
    polygon: List<Float>,
    originX: Float,
    originY: Float,
): List<Float> = polygon.mapIndexed { index, coordinate ->
    coordinate + if (index % 2 == 0) originX else originY
}

internal fun machineBedExcludeArea(
    points: List<Float>,
    originX: Float,
    originY: Float,
): List<Float> = points.mapIndexed { index, coordinate ->
    coordinate + if (index % 2 == 0) originX else originY
}

internal fun pointInsideBedPolygon(x: Float, y: Float, polygon: List<Float>): Boolean {
    if (polygon.size < 6 || polygon.size % 2 != 0 || !x.isFinite() || !y.isFinite()) return false
    var inside = false
    val pointCount = polygon.size / 2
    repeat(pointCount) { index ->
        val next = (index + 1) % pointCount
        val ax = polygon[index * 2]
        val ay = polygon[index * 2 + 1]
        val bx = polygon[next * 2]
        val by = polygon[next * 2 + 1]
        if (pointOnSegment(x, y, ax, ay, bx, by)) return true
        if ((ay > y) != (by > y)) {
            val edgeX = (bx - ax) * (y - ay) / (by - ay) + ax
            if (x < edgeX) inside = !inside
        }
    }
    return inside
}

internal fun coercePointToBedPolygon(x: Float, y: Float, polygon: List<Float>): Pair<Float, Float> {
    if (pointInsideBedPolygon(x, y, polygon)) return x to y
    var closestX = polygon.getOrElse(0) { 0f }
    var closestY = polygon.getOrElse(1) { 0f }
    var closestDistance = Float.POSITIVE_INFINITY
    repeat(polygon.size / 2) { index ->
        val next = (index + 1) % (polygon.size / 2)
        val candidate = closestPointOnSegment(
            x,
            y,
            polygon[index * 2],
            polygon[index * 2 + 1],
            polygon[next * 2],
            polygon[next * 2 + 1],
        )
        val distance = squaredDistance(x, y, candidate.first, candidate.second)
        if (distance < closestDistance) {
            closestDistance = distance
            closestX = candidate.first
            closestY = candidate.second
        }
    }
    return closestX to closestY
}

internal fun triangulateBedPolygon(polygon: List<Float>): List<Int> {
    val pointCount = polygon.size / 2
    if (pointCount < 3) return emptyList()
    val signedArea = (0 until pointCount).sumOf { index ->
        val next = (index + 1) % pointCount
        (
            polygon[index * 2] * polygon[next * 2 + 1] -
                polygon[next * 2] * polygon[index * 2 + 1]
            ).toDouble()
    }
    val remaining = if (signedArea >= 0.0) {
        MutableList(pointCount) { it }
    } else {
        MutableList(pointCount) { pointCount - 1 - it }
    }
    val triangles = ArrayList<Int>((pointCount - 2) * 3)
    var attemptsWithoutEar = 0
    while (remaining.size > 3 && attemptsWithoutEar < remaining.size) {
        val centerIndex = attemptsWithoutEar % remaining.size
        val previous = remaining[(centerIndex - 1 + remaining.size) % remaining.size]
        val center = remaining[centerIndex]
        val next = remaining[(centerIndex + 1) % remaining.size]
        if (
            cross(
                polygon[previous * 2],
                polygon[previous * 2 + 1],
                polygon[center * 2],
                polygon[center * 2 + 1],
                polygon[next * 2],
                polygon[next * 2 + 1],
            ) > BED_GEOMETRY_EPSILON &&
            remaining.none { candidate ->
                candidate !in setOf(previous, center, next) && pointInsideTriangle(
                    polygon[candidate * 2],
                    polygon[candidate * 2 + 1],
                    polygon[previous * 2],
                    polygon[previous * 2 + 1],
                    polygon[center * 2],
                    polygon[center * 2 + 1],
                    polygon[next * 2],
                    polygon[next * 2 + 1],
                )
            }
        ) {
            triangles += previous
            triangles += center
            triangles += next
            remaining.removeAt(centerIndex)
            attemptsWithoutEar = 0
        } else {
            attemptsWithoutEar++
        }
    }
    if (remaining.size == 3) triangles.addAll(remaining)
    return triangles.takeIf { it.size == (pointCount - 2) * 3 }.orEmpty()
}

internal fun verticalBedSegments(x: Float, polygon: List<Float>): List<Pair<Float, Float>> =
    scanlineIntersections(x, polygon, vertical = true)

internal fun horizontalBedSegments(y: Float, polygon: List<Float>): List<Pair<Float, Float>> =
    scanlineIntersections(y, polygon, vertical = false)

private fun scanlineIntersections(
    coordinate: Float,
    polygon: List<Float>,
    vertical: Boolean,
): List<Pair<Float, Float>> {
    val intersections = ArrayList<Float>()
    repeat(polygon.size / 2) { index ->
        val next = (index + 1) % (polygon.size / 2)
        val ax = polygon[index * 2]
        val ay = polygon[index * 2 + 1]
        val bx = polygon[next * 2]
        val by = polygon[next * 2 + 1]
        val first = if (vertical) ax else ay
        val second = if (vertical) bx else by
        if ((first <= coordinate && second > coordinate) || (second <= coordinate && first > coordinate)) {
            val acrossA = if (vertical) ay else ax
            val acrossB = if (vertical) by else bx
            intersections += acrossA + (coordinate - first) * (acrossB - acrossA) / (second - first)
        }
    }
    intersections.sort()
    return intersections.chunked(2).mapNotNull { pair ->
        pair.takeIf { it.size == 2 }?.let { it[0] to it[1] }
    }
}

private fun pointInsideTriangle(
    px: Float,
    py: Float,
    ax: Float,
    ay: Float,
    bx: Float,
    by: Float,
    cx: Float,
    cy: Float,
): Boolean {
    val first = cross(ax, ay, bx, by, px, py)
    val second = cross(bx, by, cx, cy, px, py)
    val third = cross(cx, cy, ax, ay, px, py)
    return first >= -BED_GEOMETRY_EPSILON &&
        second >= -BED_GEOMETRY_EPSILON &&
        third >= -BED_GEOMETRY_EPSILON
}

private fun closestPointOnSegment(
    px: Float,
    py: Float,
    ax: Float,
    ay: Float,
    bx: Float,
    by: Float,
): Pair<Float, Float> {
    val dx = bx - ax
    val dy = by - ay
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared <= 0f) return ax to ay
    val fraction = (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
    return ax + dx * fraction to ay + dy * fraction
}

private fun segmentsIntersect(
    ax: Float,
    ay: Float,
    bx: Float,
    by: Float,
    cx: Float,
    cy: Float,
    dx: Float,
    dy: Float,
): Boolean {
    val abC = cross(ax, ay, bx, by, cx, cy)
    val abD = cross(ax, ay, bx, by, dx, dy)
    val cdA = cross(cx, cy, dx, dy, ax, ay)
    val cdB = cross(cx, cy, dx, dy, bx, by)
    if ((abC > 0f && abD < 0f || abC < 0f && abD > 0f) &&
        (cdA > 0f && cdB < 0f || cdA < 0f && cdB > 0f)
    ) {
        return true
    }
    return abs(abC) <= BED_GEOMETRY_EPSILON && pointOnSegment(cx, cy, ax, ay, bx, by) ||
        abs(abD) <= BED_GEOMETRY_EPSILON && pointOnSegment(dx, dy, ax, ay, bx, by) ||
        abs(cdA) <= BED_GEOMETRY_EPSILON && pointOnSegment(ax, ay, cx, cy, dx, dy) ||
        abs(cdB) <= BED_GEOMETRY_EPSILON && pointOnSegment(bx, by, cx, cy, dx, dy)
}

private fun pointOnSegment(
    px: Float,
    py: Float,
    ax: Float,
    ay: Float,
    bx: Float,
    by: Float,
): Boolean {
    if (abs(cross(ax, ay, bx, by, px, py)) > BED_GEOMETRY_EPSILON) return false
    return px in min(ax, bx) - BED_GEOMETRY_EPSILON..max(ax, bx) + BED_GEOMETRY_EPSILON &&
        py in min(ay, by) - BED_GEOMETRY_EPSILON..max(ay, by) + BED_GEOMETRY_EPSILON
}

private fun cross(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float): Float =
    (bx - ax) * (py - ay) - (by - ay) * (px - ax)

private fun squaredDistance(ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = bx - ax
    val dy = by - ay
    return dx * dx + dy * dy
}

private const val MAX_BED_POLYGON_COORDINATES = 512
private const val BED_GEOMETRY_TOLERANCE_MM = 0.05f
private const val BED_GEOMETRY_EPSILON = 0.0001f
private const val MINIMUM_BED_EDGE_MM = 0.001f
private const val MINIMUM_BED_AREA_MM2 = 1f
