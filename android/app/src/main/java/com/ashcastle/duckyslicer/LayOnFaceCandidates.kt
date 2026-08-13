package com.ashcastle.duckyslicer

import kotlin.math.abs
import kotlin.math.sqrt

internal data class LayOnFaceCandidate(
    val previewTriangleIndices: IntArray,
    val areaMm2: Float,
)

private data class CandidateVertex(val x: Int, val y: Int, val z: Int)

private data class CandidateEdge(val first: CandidateVertex, val second: CandidateVertex)

private data class CandidateFace(
    val normalX: Float,
    val normalY: Float,
    val normalZ: Float,
    val areaMm2: Float,
    val minimumSideMm: Float,
)

internal fun detectLayOnFaceCandidates(
    previewTriangles: FloatArray,
    maximumCandidates: Int = 24,
    checkCancellation: () -> Unit = {},
): List<LayOnFaceCandidate> {
    require(previewTriangles.size % 9 == 0 && maximumCandidates > 0)
    val triangleCount = previewTriangles.size / 9
    if (triangleCount == 0) return emptyList()
    val faces = arrayOfNulls<CandidateFace>(triangleCount)
    val neighbors = IntArray(triangleCount * MAXIMUM_FACE_NEIGHBORS) { -1 }
    val neighborCounts = ByteArray(triangleCount)
    val overflowNeighbors = HashMap<Int, MutableList<Int>>()
    val edgeOwners = HashMap<CandidateEdge, Int>(triangleCount * 2)

    fun addNeighbor(triangle: Int, neighbor: Int) {
        val count = neighborCounts[triangle].toInt()
        val start = triangle * MAXIMUM_FACE_NEIGHBORS
        repeat(count) { index -> if (neighbors[start + index] == neighbor) return }
        if (count < MAXIMUM_FACE_NEIGHBORS) {
            neighbors[start + count] = neighbor
            neighborCounts[triangle] = (count + 1).toByte()
        } else {
            val overflow = overflowNeighbors.getOrPut(triangle) { ArrayList() }
            if (neighbor !in overflow) overflow += neighbor
        }
    }

    fun connectEdge(first: CandidateVertex, second: CandidateVertex, triangleIndex: Int) {
        val edge = if (first <= second) CandidateEdge(first, second) else CandidateEdge(second, first)
        edgeOwners.putIfAbsent(edge, triangleIndex)?.let { adjacent ->
            addNeighbor(triangleIndex, adjacent)
            addNeighbor(adjacent, triangleIndex)
        }
    }

    repeat(triangleCount) { triangleIndex ->
        if (triangleIndex % LAY_ON_FACE_CANCELLATION_INTERVAL == 0) checkCancellation()
        val offset = triangleIndex * 9
        val face = candidateFace(previewTriangles, offset)
        faces[triangleIndex] = face
        if (face == null) return@repeat
        fun vertex(start: Int) =
            CandidateVertex(
                previewTriangles[start].normalizedCandidateBits(),
                previewTriangles[start + 1].normalizedCandidateBits(),
                previewTriangles[start + 2].normalizedCandidateBits(),
            )
        val first = vertex(offset)
        val second = vertex(offset + 3)
        val third = vertex(offset + 6)
        connectEdge(first, second, triangleIndex)
        connectEdge(second, third, triangleIndex)
        connectEdge(third, first, triangleIndex)
    }

    val visited = BooleanArray(triangleCount)
    val candidates = ArrayList<LayOnFaceCandidate>()
    repeat(triangleCount) { seedIndex ->
        if (seedIndex % LAY_ON_FACE_CANCELLATION_INTERVAL == 0) checkCancellation()
        if (visited[seedIndex]) return@repeat
        val seed = faces[seedIndex] ?: run {
            visited[seedIndex] = true
            return@repeat
        }
        val queue = ArrayDeque<Int>()
        val grouped = ArrayList<Int>()
        queue += seedIndex
        while (queue.isNotEmpty()) {
            if (grouped.size % LAY_ON_FACE_CANCELLATION_INTERVAL == 0) checkCancellation()
            val triangleIndex = queue.removeFirst()
            if (visited[triangleIndex]) continue
            val face = faces[triangleIndex] ?: continue
            if (!face.normalMatches(seed)) continue
            visited[triangleIndex] = true
            grouped += triangleIndex
            val neighborStart = triangleIndex * MAXIMUM_FACE_NEIGHBORS
            repeat(neighborCounts[triangleIndex].toInt()) { neighborIndex ->
                val neighbor = neighbors[neighborStart + neighborIndex]
                if (!visited[neighbor]) queue += neighbor
            }
            overflowNeighbors[triangleIndex].orEmpty().forEach { neighbor ->
                if (!visited[neighbor]) queue += neighbor
            }
        }
        if (grouped.isEmpty()) return@repeat
        val area = grouped.sumOf { faces[it]?.areaMm2?.toDouble() ?: 0.0 }.toFloat()
        if (area < MINIMUM_CANDIDATE_AREA_MM2) return@repeat
        if (
            grouped.size == 1 &&
            (faces[grouped.first()]?.minimumSideMm ?: 0f) < MINIMUM_CANDIDATE_SIDE_MM
        ) {
            return@repeat
        }
        candidates += LayOnFaceCandidate(grouped.toIntArray(), area)
    }
    return candidates
        .sortedByDescending(LayOnFaceCandidate::areaMm2)
        .take(maximumCandidates)
}

private fun candidateFace(values: FloatArray, offset: Int): CandidateFace? {
    val ax = values[offset]
    val ay = values[offset + 1]
    val az = values[offset + 2]
    val bx = values[offset + 3]
    val by = values[offset + 4]
    val bz = values[offset + 5]
    val cx = values[offset + 6]
    val cy = values[offset + 7]
    val cz = values[offset + 8]
    if (
        !ax.isFinite() || !ay.isFinite() || !az.isFinite() ||
        !bx.isFinite() || !by.isFinite() || !bz.isFinite() ||
        !cx.isFinite() || !cy.isFinite() || !cz.isFinite()
    ) return null
    val ux = bx - ax
    val uy = by - ay
    val uz = bz - az
    val vx = cx - ax
    val vy = cy - ay
    val vz = cz - az
    val nx = uy * vz - uz * vy
    val ny = uz * vx - ux * vz
    val nz = ux * vy - uy * vx
    val twiceArea = sqrt(nx * nx + ny * ny + nz * nz)
    if (!twiceArea.isFinite() || twiceArea <= 0.000001f) return null
    fun side(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val dz = z2 - z1
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
    return CandidateFace(
        normalX = nx / twiceArea,
        normalY = ny / twiceArea,
        normalZ = nz / twiceArea,
        areaMm2 = twiceArea * 0.5f,
        minimumSideMm = minOf(
            side(ax, ay, az, bx, by, bz),
            side(bx, by, bz, cx, cy, cz),
            side(cx, cy, cz, ax, ay, az),
        ),
    )
}

private fun CandidateFace.normalMatches(other: CandidateFace): Boolean =
    abs(normalX - other.normalX) < NORMAL_COMPONENT_TOLERANCE &&
        abs(normalY - other.normalY) < NORMAL_COMPONENT_TOLERANCE &&
        abs(normalZ - other.normalZ) < NORMAL_COMPONENT_TOLERANCE

private fun Float.normalizedCandidateBits(): Int = if (this == 0f) 0 else toRawBits()

private operator fun CandidateVertex.compareTo(other: CandidateVertex): Int = when {
    x != other.x -> x.compareTo(other.x)
    y != other.y -> y.compareTo(other.y)
    else -> z.compareTo(other.z)
}

private const val MINIMUM_CANDIDATE_AREA_MM2 = 5f
private const val MINIMUM_CANDIDATE_SIDE_MM = 1f
private const val NORMAL_COMPONENT_TOLERANCE = 0.001f
private const val MAXIMUM_FACE_NEIGHBORS = 3
private const val LAY_ON_FACE_CANCELLATION_INTERVAL = 256
