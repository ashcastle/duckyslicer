package com.ashcastle.duckyslicer

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class SupportPaintHitTest {
    @Test
    fun overlappingTrianglesChooseTheFrontmostFacet() {
        val back = ModelScreenTriangle(
            sourceFacetIndex = 4,
            a = Offset(0f, 0f),
            b = Offset(40f, 0f),
            c = Offset(0f, 40f),
            depth = 2f,
        )
        val front = back.copy(sourceFacetIndex = 9, depth = 8f)

        assertEquals(9, closestPaintFacet(listOf(back, front), Offset(5f, 5f), 10f))
    }

    @Test
    fun brushRadiusReachesANearbyThinFacetWithoutPaintingDistantGeometry() {
        val thin = ModelScreenTriangle(
            sourceFacetIndex = 12,
            a = Offset(10f, 10f),
            b = Offset(50f, 10f),
            c = Offset(50f, 11f),
            depth = 1f,
        )

        assertEquals(12, closestPaintFacet(listOf(thin), Offset(30f, 18f), 9f))
        assertEquals(null, closestPaintFacet(listOf(thin), Offset(30f, 40f), 9f))
    }
}
