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
): List<LayOnFaceCandidate> {
    require(previewTriangles.size % 9 == 0 && maximumCandidates > 0)
    val triangleCount = previewTriangles.size / 9
    if (triangleCount == 0) return emptyList()
    val faces = ArrayList<CandidateFace?>(triangleCount)
    val neighbors = Array(triangleCount) { linkedSetOf<Int>() }
    val edgeOwners = HashMap<CandidateEdge, Int>(triangleCount * 2)
    repeat(triangleCount) { triangleIndex ->
        val offset = triangleIndex * 9
        val face = candidateFace(previewTriangles, offset)
        faces += face
        if (face == null) return@repeat
        val vertices = Array(3) { vertex ->
            val start = offset + vertex * 3
            CandidateVertex(
                previewTriangles[start].normalizedCandidateBits(),
                previewTriangles[start + 1].normalizedCandidateBits(),
                previewTriangles[start + 2].normalizedCandidateBits(),
            )
        }
        arrayOf(0 to 1, 1 to 2, 2 to 0).forEach { (start, end) ->
            val edge = if (vertices[start] <= vertices[end]) {
                CandidateEdge(vertices[start], vertices[end])
            } else {
                CandidateEdge(vertices[end], vertices[start])
            }
            edgeOwners.putIfAbsent(edge, triangleIndex)?.let { adjacent ->
                neighbors[triangleIndex] += adjacent
                neighbors[adjacent] += triangleIndex
            }
        }
    }

    val visited = BooleanArray(triangleCount)
    val candidates = ArrayList<LayOnFaceCandidate>()
    repeat(triangleCount) { seedIndex ->
        if (visited[seedIndex]) return@repeat
        val seed = faces[seedIndex] ?: run {
            visited[seedIndex] = true
            return@repeat
        }
        val queue = ArrayDeque<Int>()
        val grouped = ArrayList<Int>()
        queue += seedIndex
        while (queue.isNotEmpty()) {
            val triangleIndex = queue.removeFirst()
            if (visited[triangleIndex]) continue
            val face = faces[triangleIndex] ?: continue
            if (!face.normalMatches(seed)) continue
            visited[triangleIndex] = true
            grouped += triangleIndex
            neighbors[triangleIndex].forEach { neighbor ->
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
    if (listOf(ax, ay, az, bx, by, bz, cx, cy, cz).any { !it.isFinite() }) return null
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
