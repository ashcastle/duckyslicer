package com.ashcastle.duckyslicer

import kotlin.math.abs

internal data class FacetPaintTarget(
    val facetIndex: Int,
    val weightA: Float,
    val weightB: Float,
    val weightC: Float,
    val subdivisionDepth: Int,
) {
    init {
        require(facetIndex >= 0) { "Facet paint target is invalid" }
        require(subdivisionDepth in 1..MAX_SUBDIVISION_DEPTH) {
            "Facet paint depth is invalid"
        }
        require(weightA.isFinite() && weightB.isFinite() && weightC.isFinite()) {
            "Facet paint position is invalid"
        }
        require(weightA >= -WEIGHT_EPSILON && weightB >= -WEIGHT_EPSILON && weightC >= -WEIGHT_EPSILON) {
            "Facet paint position is outside the triangle"
        }
        require(weightA + weightB + weightC > WEIGHT_EPSILON) {
            "Facet paint position is invalid"
        }
    }

    internal val point: FacetPoint = FacetPoint.normalized(weightA, weightB, weightC)
    internal val regionKey: String = buildString(subdivisionDepth) {
        var triangle = FacetTriangle.ROOT
        repeat(subdivisionDepth) {
            val children = triangle.split(splitSides = 3, specialSide = 0)
            val childIndex = children.childContaining(point)
            append(childIndex)
            triangle = children[childIndex]
        }
    }

    companion object {
        const val MAX_SUBDIVISION_DEPTH = 4
    }
}

/** Applies one bounded partial-facet edit while preserving every untouched recursive child. */
internal fun OrcaFacetAnnotation.paintAt(
    target: FacetPaintTarget,
    state: Int,
    fallbackState: Int = 0,
): OrcaFacetAnnotation {
    require(state in 0..MAX_FACET_STATE && fallbackState in 0..MAX_FACET_STATE) {
        "Facet paint state is invalid"
    }
    val currentValue = triangles[target.facetIndex]
    val root = if (currentValue == null) {
        FacetNode.Leaf(fallbackState)
    } else {
        runCatching { FacetTreeCodec.parse(currentValue) }.getOrNull() ?: return this
    }
    val edited = root.paint(
        triangle = FacetTriangle.ROOT,
        point = target.point,
        currentDepth = 0,
        targetDepth = target.subdivisionDepth,
        state = state,
    ).compressed()
    val encoded = FacetTreeCodec.serialize(edited)
    if (encoded == currentValue || (currentValue == null && encoded == "0")) return this
    if (encoded.length > OrcaFacetAnnotation.MAX_TRIANGLE_VALUE_BYTES) return this

    val next = triangles.toMutableMap()
    if (encoded == "0") {
        next.remove(target.facetIndex)
    } else {
        if (
            target.facetIndex !in next &&
            next.size >= OrcaFacetAnnotation.MAX_ANNOTATED_TRIANGLES
        ) {
            return this
        }
        next[target.facetIndex] = encoded
    }
    return OrcaFacetAnnotation(next.toSortedMap())
}

private sealed interface FacetNode {
    data class Leaf(val state: Int) : FacetNode

    data class Split(
        val splitSides: Int,
        val specialSide: Int,
        val children: List<FacetNode>,
    ) : FacetNode
}

private fun FacetNode.paint(
    triangle: FacetTriangle,
    point: FacetPoint,
    currentDepth: Int,
    targetDepth: Int,
    state: Int,
): FacetNode {
    if (currentDepth >= targetDepth) return FacetNode.Leaf(state)
    val split = when (this) {
        is FacetNode.Leaf -> FacetNode.Split(
            splitSides = 3,
            specialSide = 0,
            children = List(4) { FacetNode.Leaf(this.state) },
        )
        is FacetNode.Split -> this
    }
    val childTriangles = triangle.split(split.splitSides, split.specialSide)
    val childIndex = childTriangles.childContaining(point)
    val children = split.children.toMutableList()
    children[childIndex] = children[childIndex].paint(
        triangle = childTriangles[childIndex],
        point = point,
        currentDepth = currentDepth + 1,
        targetDepth = targetDepth,
        state = state,
    )
    return FacetNode.Split(split.splitSides, split.specialSide, children).compressed()
}

private fun FacetNode.compressed(): FacetNode = when (this) {
    is FacetNode.Leaf -> this
    is FacetNode.Split -> {
        val compactChildren = children.map(FacetNode::compressed)
        val first = compactChildren.firstOrNull() as? FacetNode.Leaf
        if (first != null && compactChildren.all { it == first }) {
            first
        } else {
            copy(children = compactChildren)
        }
    }
}

private object FacetTreeCodec {
    fun parse(value: String): FacetNode {
        val cursor = FacetNibbleCursor(value)
        val root = parseNode(cursor, depth = 0)
        require(cursor.complete) { "Facet annotation split tree has trailing data" }
        return root
    }

    fun serialize(root: FacetNode): String {
        val consumed = ArrayList<Int>()
        appendConsumed(root, consumed)
        return buildString(consumed.size) {
            for (index in consumed.lastIndex downTo 0) append(HEX_DIGITS[consumed[index]])
        }
    }

    private fun parseNode(cursor: FacetNibbleCursor, depth: Int): FacetNode {
        require(depth <= MAX_EDIT_TREE_DEPTH) { "Facet annotation split tree is too deep to edit" }
        val code = cursor.next()
        val splitSides = code and 0b11
        if (splitSides == 0) return FacetNode.Leaf(cursor.readLeafState(code))
        val specialSide = code ushr 2
        require(
            (splitSides == 3 && specialSide == 0) ||
                (splitSides < 3 && specialSide in 0..2),
        ) { "Facet annotation split side is invalid" }
        val children = arrayOfNulls<FacetNode>(splitSides + 1)
        for (childIndex in children.lastIndex downTo 0) {
            children[childIndex] = parseNode(cursor, depth + 1)
        }
        return FacetNode.Split(
            splitSides,
            specialSide,
            children.map(::checkNotNull),
        )
    }

    private fun appendConsumed(node: FacetNode, output: MutableList<Int>) {
        when (node) {
            is FacetNode.Leaf -> {
                if (node.state < 3) {
                    output += node.state shl 2
                } else {
                    output += 0xC
                    var remaining = node.state - 3
                    while (remaining >= 15) {
                        output += 0xF
                        remaining -= 15
                    }
                    output += remaining
                }
            }
            is FacetNode.Split -> {
                output += (node.specialSide shl 2) or node.splitSides
                for (childIndex in node.children.lastIndex downTo 0) {
                    appendConsumed(node.children[childIndex], output)
                }
            }
        }
    }
}

private class FacetNibbleCursor(private val value: String) {
    private var index = value.lastIndex

    val complete: Boolean get() = index == -1

    fun next(): Int {
        require(index >= 0) { "Facet annotation split tree is incomplete" }
        return value[index--].digitToInt(16)
    }

    fun readLeafState(code: Int): Int {
        if ((code and 0b1100) != 0b1100) return code ushr 2
        var extensions = 0
        var next: Int
        do {
            next = next()
            if (next == 0xF) extensions += 1
            require(extensions <= 16) { "Facet annotation state is invalid" }
        } while (next == 0xF)
        return next + 15 * extensions + 3
    }
}

internal data class FacetPoint(val a: Float, val b: Float, val c: Float) {
    fun midpoint(other: FacetPoint) = FacetPoint(
        (a + other.a) * 0.5f,
        (b + other.b) * 0.5f,
        (c + other.c) * 0.5f,
    )

    companion object {
        fun normalized(a: Float, b: Float, c: Float): FacetPoint {
            val clampedA = a.coerceAtLeast(0f)
            val clampedB = b.coerceAtLeast(0f)
            val clampedC = c.coerceAtLeast(0f)
            val total = clampedA + clampedB + clampedC
            require(total > WEIGHT_EPSILON)
            return FacetPoint(clampedA / total, clampedB / total, clampedC / total)
        }
    }
}

internal data class FacetTriangle(
    val a: FacetPoint,
    val b: FacetPoint,
    val c: FacetPoint,
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

    companion object {
        val ROOT = FacetTriangle(
            FacetPoint(1f, 0f, 0f),
            FacetPoint(0f, 1f, 0f),
            FacetPoint(0f, 0f, 1f),
        )
    }
}

private fun List<FacetTriangle>.childContaining(point: FacetPoint): Int {
    var bestIndex = -1
    var bestInterior = Float.NEGATIVE_INFINITY
    forEachIndexed { index, triangle ->
        val weights = triangle.localWeights(point) ?: return@forEachIndexed
        val interior = minOf(weights.a, weights.b, weights.c)
        if (interior >= -WEIGHT_EPSILON && interior > bestInterior + WEIGHT_EPSILON) {
            bestIndex = index
            bestInterior = interior
        }
    }
    if (bestIndex >= 0) return bestIndex
    return indices.minBy { index ->
        val triangle = this[index]
        val centerA = (triangle.a.a + triangle.b.a + triangle.c.a) / 3f
        val centerB = (triangle.a.b + triangle.b.b + triangle.c.b) / 3f
        val centerC = (triangle.a.c + triangle.b.c + triangle.c.c) / 3f
        abs(point.a - centerA) + abs(point.b - centerB) + abs(point.c - centerC)
    }
}

private fun FacetTriangle.localWeights(point: FacetPoint): FacetPoint? {
    val denominator = (b.b - c.b) * (a.a - c.a) + (c.a - b.a) * (a.b - c.b)
    if (abs(denominator) <= WEIGHT_EPSILON) return null
    val localA = ((b.b - c.b) * (point.a - c.a) + (c.a - b.a) * (point.b - c.b)) /
        denominator
    val localB = ((c.b - a.b) * (point.a - c.a) + (a.a - c.a) * (point.b - c.b)) /
        denominator
    val localC = 1f - localA - localB
    return if (localA.isFinite() && localB.isFinite() && localC.isFinite()) {
        FacetPoint(localA, localB, localC)
    } else {
        null
    }
}

private const val MAX_FACET_STATE = 255
private const val MAX_EDIT_TREE_DEPTH = 64
private const val WEIGHT_EPSILON = 0.00001f
private const val HEX_DIGITS = "0123456789ABCDEF"
