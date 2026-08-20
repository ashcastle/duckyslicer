package com.ashcastle.duckyslicer

internal data class OrcaFacetPreviewLeaf(
    val state: Int,
    val vertices: FloatArray,
)

/** Reconstructs the midpoint subdivision encoded by Orca's FacetsAnnotation strings. */
internal object OrcaFacetPreviewTessellator {
    fun rootLeafState(value: String): Int? {
        val cursor = NibbleCursor(value)
        val code = cursor.next()
        if ((code and 0b11) != 0) return null
        val state = cursor.readLeafState(code)
        require(cursor.complete) { "Facet annotation split tree has trailing data" }
        return state
    }

    fun tessellate(
        value: String,
        sourceVertices: FloatArray,
        sourceOffset: Int,
        maximumTriangles: Int,
    ): List<OrcaFacetPreviewLeaf> {
        require(maximumTriangles >= 0)
        require(sourceOffset >= 0 && sourceOffset + SOURCE_TRIANGLE_FLOATS <= sourceVertices.size)
        val source = FacetTriangle.from(sourceVertices, sourceOffset)
        val stateCounts = paintedStateCounts(value)
        val paintedLeaves = stateCounts.values.sum()
        if (paintedLeaves == 0 || maximumTriangles == 0) return emptyList()
        if (paintedLeaves > maximumTriangles) {
            val dominantState = stateCounts.entries.maxWithOrNull(
                compareBy<Map.Entry<Int, Int>>({ it.value }, { -it.key }),
            )?.key ?: return emptyList()
            return listOf(OrcaFacetPreviewLeaf(dominantState, source.packed()))
        }

        val cursor = NibbleCursor(value)
        val pending = ArrayDeque<FacetTriangle>()
        val output = ArrayList<OrcaFacetPreviewLeaf>(paintedLeaves)
        pending.addLast(source)
        while (pending.isNotEmpty()) {
            val triangle = pending.removeLast()
            val code = cursor.next()
            val splitSides = code and 0b11
            if (splitSides == 0) {
                val state = cursor.readLeafState(code)
                if (state != 0) output += OrcaFacetPreviewLeaf(state, triangle.packed())
                continue
            }
            val children = triangle.split(splitSides, code ushr 2)
            // Orca serializes and deserializes each parent's children from the highest index down.
            children.forEach(pending::addLast)
        }
        require(cursor.complete) { "Facet annotation split tree has trailing data" }
        return output
    }

    private fun paintedStateCounts(value: String): Map<Int, Int> {
        val cursor = NibbleCursor(value)
        var pendingNodes = 1
        val counts = HashMap<Int, Int>()
        while (pendingNodes > 0) {
            val code = cursor.next()
            pendingNodes -= 1
            val splitSides = code and 0b11
            if (splitSides != 0) {
                val specialSide = code ushr 2
                require(
                    (splitSides == 3 && specialSide == 0) ||
                        (splitSides < 3 && specialSide in 0..2),
                ) { "Facet annotation split side is invalid" }
                pendingNodes += splitSides + 1
            } else {
                val state = cursor.readLeafState(code)
                if (state != 0) counts[state] = counts.getOrDefault(state, 0) + 1
            }
        }
        require(cursor.complete) { "Facet annotation split tree has trailing data" }
        return counts
    }

    private class NibbleCursor(private val value: String) {
        private var index = value.lastIndex

        val complete: Boolean get() = index == -1

        fun next(): Int {
            require(index >= 0) { "Facet annotation split tree is incomplete" }
            return value[index--].digitToInt(16)
        }

        fun readLeafState(code: Int): Int {
            if ((code and 0b1100) != 0b1100) return code ushr 2
            var extensionCount = 0
            var next: Int
            do {
                next = next()
                if (next == 0xF) extensionCount += 1
                require(extensionCount <= 16) { "Facet annotation state is invalid" }
            } while (next == 0xF)
            return next + 15 * extensionCount + 3
        }
    }

    private data class FacetVertex(val x: Float, val y: Float, val z: Float) {
        fun midpoint(other: FacetVertex) = FacetVertex(
            x = (x + other.x) * 0.5f,
            y = (y + other.y) * 0.5f,
            z = (z + other.z) * 0.5f,
        )
    }

    private data class FacetTriangle(
        val a: FacetVertex,
        val b: FacetVertex,
        val c: FacetVertex,
    ) {
        fun split(splitSides: Int, specialSide: Int): List<FacetTriangle> {
            require(splitSides in 1..3)
            require(
                (splitSides == 3 && specialSide == 0) ||
                    (splitSides < 3 && specialSide in 0..2),
            ) { "Facet annotation split side is invalid" }
            val original = arrayOf(a, b, c)
            val first = original[specialSide]
            val second = original[(specialSide + 1) % 3]
            val third = original[(specialSide + 2) % 3]
            return when (splitSides) {
                1 -> {
                    val midpoint = third.midpoint(second)
                    listOf(
                        FacetTriangle(first, second, midpoint),
                        FacetTriangle(midpoint, third, first),
                    )
                }
                2 -> {
                    val firstMidpoint = second.midpoint(first)
                    val secondMidpoint = first.midpoint(third)
                    listOf(
                        FacetTriangle(first, firstMidpoint, secondMidpoint),
                        FacetTriangle(firstMidpoint, second, secondMidpoint),
                        FacetTriangle(second, third, secondMidpoint),
                    )
                }
                else -> {
                    val firstMidpoint = second.midpoint(first)
                    val secondMidpoint = third.midpoint(second)
                    val thirdMidpoint = first.midpoint(third)
                    listOf(
                        FacetTriangle(first, firstMidpoint, thirdMidpoint),
                        FacetTriangle(firstMidpoint, second, secondMidpoint),
                        FacetTriangle(secondMidpoint, third, thirdMidpoint),
                        FacetTriangle(firstMidpoint, secondMidpoint, thirdMidpoint),
                    )
                }
            }
        }

        fun packed() = floatArrayOf(
            a.x, a.y, a.z,
            b.x, b.y, b.z,
            c.x, c.y, c.z,
        )

        companion object {
            fun from(values: FloatArray, offset: Int): FacetTriangle {
                require((offset until offset + SOURCE_TRIANGLE_FLOATS).all { values[it].isFinite() })
                return FacetTriangle(
                    FacetVertex(values[offset], values[offset + 1], values[offset + 2]),
                    FacetVertex(values[offset + 3], values[offset + 4], values[offset + 5]),
                    FacetVertex(values[offset + 6], values[offset + 7], values[offset + 8]),
                )
            }
        }
    }

    private const val SOURCE_TRIANGLE_FLOATS = 9
}
