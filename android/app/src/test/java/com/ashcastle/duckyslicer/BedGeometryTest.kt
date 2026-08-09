package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BedGeometryTest {
    private val diamond = listOf(50f, 0f, 100f, 50f, 50f, 100f, 0f, 50f)

    @Test
    fun validatesNonRectangularBedAndRejectsUnsafeGeometry() {
        assertTrue(bedPolygonIsValid(diamond, 100f, 100f))
        assertFalse(bedPolygonIsValid(listOf(0f, 0f, 100f, 100f, 0f, 100f, 100f, 0f), 100f, 100f))
        assertFalse(bedPolygonIsValid(listOf(0f, 0f, Float.NaN, 100f, 100f, 0f), 100f, 100f))
    }

    @Test
    fun clampsPointsAndGridSegmentsToTheActualPolygon() {
        assertTrue(pointInsideBedPolygon(50f, 50f, diamond))
        assertFalse(pointInsideBedPolygon(5f, 5f, diamond))
        val coerced = coercePointToBedPolygon(0f, 0f, diamond)
        assertEquals(25f, coerced.first, 0.001f)
        assertEquals(25f, coerced.second, 0.001f)
        assertEquals(listOf(10f to 90f), horizontalBedSegments(50f, scaledDiamond(80f)))
    }

    @Test
    fun preservesMachineOriginSeparatelyFromDisplayGeometry() {
        assertEquals(-200f, scaledBedOrigin(-150f, 300f, 400f), 0.001f)
        assertEquals(
            listOf(0f, -50f, 50f, 0f, 0f, 50f, -50f, 0f),
            machineBedPolygon(diamond, -50f, -50f),
        )
    }

    @Test
    fun triangulatesConvexAndConcaveBedsWithoutFillingOutsideCorners() {
        assertEquals(6, triangulateBedPolygon(diamond).size)
        val concave = listOf(0f, 0f, 100f, 0f, 100f, 40f, 40f, 40f, 40f, 100f, 0f, 100f)
        assertTrue(bedPolygonIsValid(concave, 100f, 100f))
        assertEquals((concave.size / 2 - 2) * 3, triangulateBedPolygon(concave).size)
    }

    private fun scaledDiamond(size: Float) = listOf(
        50f, 50f - size / 2f,
        50f + size / 2f, 50f,
        50f, 50f + size / 2f,
        50f - size / 2f, 50f,
    )
}
