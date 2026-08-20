package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OrcaFacetEditingTest {
    private val source = floatArrayOf(
        0f, 0f, 0f,
        2f, 0f, 0f,
        0f, 2f, 0f,
    )

    @Test
    fun partialEditsPaintAndEraseIndependentRegionsThenCompress() {
        val nearA = target(0.8f, 0.1f, 0.1f)
        val nearB = target(0.1f, 0.8f, 0.1f)
        var annotation = OrcaFacetAnnotation()

        annotation = annotation.paintAt(nearA, state = 1)
        assertEquals("40003", annotation.triangles[0])
        annotation = annotation.paintAt(nearB, state = 2)
        assertEquals("48003", annotation.triangles[0])
        annotation = annotation.paintAt(nearA, state = 0)
        assertEquals("08003", annotation.triangles[0])
        annotation = annotation.paintAt(nearB, state = 0)
        assertTrue(annotation.triangles.isEmpty())
    }

    @Test
    fun wholeFacetFallbackBecomesTheUntouchedPartialState() {
        val annotation = OrcaFacetAnnotation().paintAt(
            target = target(0.8f, 0.1f, 0.1f),
            state = 1,
            fallbackState = 2,
        )

        assertEquals("48883", annotation.triangles[0])
        val leaves = OrcaFacetPreviewTessellator.tessellate(
            checkNotNull(annotation.triangles[0]),
            source,
            0,
            8,
        )
        assertEquals(listOf(2, 2, 2, 1), leaves.map { it.state })
    }

    @Test
    fun importedIrregularChildrenRemainWhenOneRegionIsRefined() {
        val annotation = OrcaFacetAnnotation(mapOf(0 to "841")).paintAt(
            target = FacetPaintTarget(0, 0.8f, 0.1f, 0.1f, subdivisionDepth = 2),
            state = 1,
        )

        val leaves = OrcaFacetPreviewTessellator.tessellate(
            checkNotNull(annotation.triangles[0]),
            source,
            0,
            16,
        )
        assertEquals(5, leaves.size)
        assertEquals(2, leaves.count { it.state == 1 })
        assertEquals(3, leaves.count { it.state == 2 })
    }

    @Test
    fun paintingAllFirstLevelRegionsUniformlyCollapsesToOneLeaf() {
        var annotation = OrcaFacetAnnotation()
        listOf(
            target(0.8f, 0.1f, 0.1f),
            target(0.1f, 0.8f, 0.1f),
            target(0.1f, 0.1f, 0.8f),
            target(0.34f, 0.33f, 0.33f),
        ).forEach { target -> annotation = annotation.paintAt(target, state = 1) }

        assertEquals("4", annotation.triangles[0])
    }

    @Test
    fun extendedStatesAndStableRegionKeysRemainBounded() {
        val first = FacetPaintTarget(7, 0.79f, 0.11f, 0.10f, subdivisionDepth = 4)
        val nearby = FacetPaintTarget(7, 0.78f, 0.12f, 0.10f, subdivisionDepth = 4)
        val annotation = OrcaFacetAnnotation().paintAt(first, state = 16)

        assertEquals(first.regionKey, nearby.regionKey)
        assertTrue(checkNotNull(annotation.triangles[7]).length <= 64)
        assertEquals(16, annotation.maximumState)
    }

    @Test
    fun excessivelyDeepImportedTreeRemainsUntouchedInsteadOfOverflowingTheEditor() {
        val consumed = buildList {
            repeat(65) {
                add(1)
                add(0)
            }
            add(4)
        }
        val encoded = consumed.asReversed().joinToString("") { it.toString(16).uppercase() }
        val annotation = OrcaFacetAnnotation(mapOf(0 to encoded))

        val edited = annotation.paintAt(target(0.8f, 0.1f, 0.1f), state = 2)

        assertEquals(annotation, edited)
    }

    @Test
    fun invalidTargetsAndStatesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            FacetPaintTarget(0, -0.2f, 0.6f, 0.6f, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OrcaFacetAnnotation().paintAt(target(0.8f, 0.1f, 0.1f), state = 256)
        }
    }

    private fun target(a: Float, b: Float, c: Float) =
        FacetPaintTarget(0, a, b, c, subdivisionDepth = 1)
}
