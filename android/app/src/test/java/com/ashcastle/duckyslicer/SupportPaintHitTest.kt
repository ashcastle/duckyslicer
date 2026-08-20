package com.ashcastle.duckyslicer

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        val front = back.copy(sourceFacetIndex = 9, previewTriangleIndex = 27, depth = 8f)

        assertEquals(9, closestPaintFacet(listOf(back, front), Offset(5f, 5f), 10f))
        assertEquals(
            27,
            closestModelTriangle(listOf(back, front), Offset(5f, 5f), 10f)
                ?.previewTriangleIndex,
        )
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

    @Test
    fun facetBrushUsesViewAwareBoundedSubdivisionAndStableRegions() {
        val large = ModelScreenTriangle(
            sourceFacetIndex = 12,
            a = Offset(0f, 0f),
            b = Offset(400f, 0f),
            c = Offset(0f, 400f),
            depth = 1f,
        )
        val first = facetPaintTarget(large, Offset(48f, 48f), brushRadius = 18f)
        val nearby = facetPaintTarget(large, Offset(48.2f, 48.1f), brushRadius = 18f)
        val small = facetPaintTarget(
            large.copy(b = Offset(30f, 0f), c = Offset(0f, 30f)),
            Offset(5f, 5f),
            brushRadius = 18f,
        )
        val broadBrush = facetPaintTarget(large, Offset(48f, 48f), brushRadius = 48f)

        assertEquals(4, first.subdivisionDepth)
        assertEquals(3, broadBrush.subdivisionDepth)
        assertEquals(first.regionKey, nearby.regionKey)
        assertEquals(1, small.subdivisionDepth)
        assertEquals(12, first.facetIndex)
    }

    @Test
    fun facetBrushFootprintUsesABoundedCircularSamplePattern() {
        val radius = 20f
        val offsets = facetBrushSampleOffsets(radius)

        assertEquals(FACET_BRUSH_SAMPLE_COUNT, offsets.size)
        assertEquals(Offset.Zero, offsets.first())
        offsets.drop(1).forEach { offset ->
            assertEquals(radius * 0.68f, offset.getDistance(), 0.0001f)
            assertTrue(offset.getDistance() <= radius)
        }
    }

    @Test
    fun facetBrushStrokeFillsNormalPointerGapsAndCapsExtremeJumps() {
        val radius = 20f
        val normal = facetBrushStrokeCenters(
            previous = Offset.Zero,
            current = Offset(40f, 0f),
            radiusPx = radius,
        )
        val extreme = facetBrushStrokeCenters(
            previous = Offset.Zero,
            current = Offset(10_000f, 0f),
            radiusPx = radius,
        )

        assertEquals(Offset(40f, 0f), normal.last())
        assertTrue(normal.first().x <= radius * 0.72f)
        normal.zipWithNext().forEach { (first, second) ->
            assertTrue((second - first).getDistance() <= radius * 0.72f + 0.0001f)
        }
        assertEquals(MAX_FACET_BRUSH_STROKE_CENTERS, extreme.size)
        assertEquals(Offset(10_000f, 0f), extreme.last())
    }

    @Test
    fun facetBrushClampsNearbyEdgeHitsToValidBarycentricCoordinates() {
        val triangle = ModelScreenTriangle(
            sourceFacetIndex = 4,
            a = Offset(0f, 0f),
            b = Offset(100f, 0f),
            c = Offset(0f, 100f),
            depth = 1f,
        )

        val target = facetPaintTarget(triangle, Offset(50f, -8f), brushRadius = 18f)

        assertEquals(0f, target.weightC, 0.0001f)
        assertEquals(1f, target.weightA + target.weightB + target.weightC, 0.0001f)
    }

    @Test
    fun solidModelFacesReceiveDirectionalShadingInsteadOfUniformWireframeColor() {
        val upward = modelSurfaceShade(
            floatArrayOf(0f, 0f, 0f),
            floatArrayOf(1f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f),
        )
        val side = modelSurfaceShade(
            floatArrayOf(0f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f),
            floatArrayOf(0f, 0f, 1f),
        )

        assertTrue("Different face directions must remain visually distinguishable", upward > side)
        assertTrue(upward in 0f..1f && side in 0f..1f)
    }

    @Test
    fun surfaceMeasurementInterpolatesTheExactTransformedFacePoint() {
        val triangle = ModelScreenTriangle(
            sourceFacetIndex = 3,
            a = Offset(0f, 0f),
            b = Offset(100f, 0f),
            c = Offset(0f, 100f),
            depth = 4f,
        )

        val point = modelSurfacePoint(
            triangle = triangle,
            point = Offset(25f, 25f),
            worldA = floatArrayOf(10f, 20f, 30f),
            worldB = floatArrayOf(30f, 20f, 30f),
            worldC = floatArrayOf(10f, 60f, 50f),
        )

        assertEquals(15f, checkNotNull(point).x, 0.0001f)
        assertEquals(30f, point.y, 0.0001f)
        assertEquals(35f, point.z, 0.0001f)
    }

    @Test
    fun nearbyEdgeHitClampsToTheVisibleSurfaceInsteadOfExtrapolating() {
        val triangle = ModelScreenTriangle(
            sourceFacetIndex = 3,
            a = Offset(0f, 0f),
            b = Offset(100f, 0f),
            c = Offset(0f, 100f),
            depth = 4f,
        )

        val point = modelSurfacePoint(
            triangle = triangle,
            point = Offset(50f, -8f),
            worldA = floatArrayOf(10f, 20f, 30f),
            worldB = floatArrayOf(30f, 20f, 30f),
            worldC = floatArrayOf(10f, 60f, 50f),
        )

        assertEquals(20f, checkNotNull(point).x, 0.0001f)
        assertEquals(20f, point.y, 0.0001f)
        assertEquals(30f, point.z, 0.0001f)
    }

    @Test
    fun edgeOnFaceStillProducesAFiniteMeasurementPoint() {
        val triangle = ModelScreenTriangle(
            sourceFacetIndex = 8,
            a = Offset(0f, 0f),
            b = Offset(100f, 0f),
            c = Offset(200f, 0f),
            depth = 2f,
        )

        val point = modelSurfacePoint(
            triangle = triangle,
            point = Offset(150f, 4f),
            worldA = floatArrayOf(0f, 0f, 0f),
            worldB = floatArrayOf(100f, 0f, 0f),
            worldC = floatArrayOf(200f, 0f, 0f),
        )

        assertEquals(150f, checkNotNull(point).x, 0.0001f)
        assertTrue(point.y.isFinite() && point.z.isFinite())
    }

    @Test
    fun measurementReportsEuclideanAndAxisDistances() {
        val measurement = measurementBetween(
            ModelPoint3(1f, 2f, 3f),
            ModelPoint3(4f, 6f, 15f),
        )

        assertEquals(13f, checkNotNull(measurement).distanceMm, 0.0001f)
        assertEquals(3f, measurement.deltaXmm, 0.0001f)
        assertEquals(4f, measurement.deltaYmm, 0.0001f)
        assertEquals(12f, measurement.deltaZmm, 0.0001f)
    }

    @Test
    fun aThirdTapStartsANewMeasurementAndInvalidInputIsRejected() {
        val first = ModelPoint3(1f, 2f, 3f)
        val second = ModelPoint3(4f, 5f, 6f)
        val third = ModelPoint3(7f, 8f, 9f)

        assertEquals(listOf(third), nextMeasurementPoints(listOf(first, second), third))
        assertNull(
            modelSurfacePoint(
                triangle = ModelScreenTriangle(
                    sourceFacetIndex = 0,
                    a = Offset.Zero,
                    b = Offset(1f, 0f),
                    c = Offset(0f, 1f),
                    depth = 1f,
                ),
                point = Offset.Zero,
                worldA = floatArrayOf(Float.NaN, 0f, 0f),
                worldB = floatArrayOf(1f, 0f, 0f),
                worldC = floatArrayOf(0f, 1f, 0f),
            ),
        )
    }
}
