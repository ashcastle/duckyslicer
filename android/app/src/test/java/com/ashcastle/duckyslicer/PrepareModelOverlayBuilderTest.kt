package com.ashcastle.duckyslicer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrepareModelOverlayBuilderTest {
    @Test
    fun overlayBuilderMapsPreviewTrianglesBackToPaintedSourceFacets() {
        val projectObject = objectWithPaint()

        val overlays = PrepareModelOverlayBuilder.build(
            projectObjects = listOf(projectObject),
            layOnFaceObjectId = projectObject.id,
            layOnFaceCandidateFacets = mapOf(
                projectObject.singleVolume.id to booleanArrayOf(false, true),
            ),
        )

        assertEquals(4, overlays.size)
        assertTrue(overlays.all { it.meshIndex == 0 })
        assertTrue(overlays.all { it.fillIndices.size == 3 && it.lineIndices.size == 6 })
        assertTrue(overlays.any { it.fillColor.alpha == 0.16f })
        assertTrue(overlays.any { it.fillColor.alpha == 0.94f })
        assertTrue(overlays.any { it.fillColor.green > 0.8f && it.fillColor.red < 0.5f })
        overlays.forEach { overlay ->
            val expectedVertex = if (overlay.fillIndices.first() == 0) 0 else 3
            assertArrayEquals(
                intArrayOf(expectedVertex, expectedVertex + 1, expectedVertex + 2),
                overlay.fillIndices,
            )
        }
    }

    @Test
    fun exactSplitAnnotationBuildsPartialCustomGeometry() {
        val projectObject = objectWithExactMultiColor("841")

        val overlays = PrepareModelOverlayBuilder.build(
            projectObjects = listOf(projectObject),
            layOnFaceObjectId = null,
            layOnFaceCandidateFacets = emptyMap(),
        )

        assertEquals(2, overlays.size)
        assertEquals(listOf(0, 1), overlays.map { it.fillColor }.map(::filamentSlotForColor))
        assertTrue(overlays.all { it.fillIndices.isEmpty() })
        assertTrue(overlays.all { it.customVertices?.size == 9 })
        assertArrayEquals(
            floatArrayOf(1f, 1f, 0f, 0f, 2f, 0f, 0f, 0f, 0f),
            overlays[0].customVertices,
            0f,
        )
        assertArrayEquals(
            floatArrayOf(0f, 0f, 0f, 2f, 0f, 0f, 1f, 1f, 0f),
            overlays[1].customVertices,
            0f,
        )
    }

    @Test
    fun wholeExactAnnotationUsesIndexedGeometryAndSimplePaintTakesPrecedence() {
        val exact = objectWithExactMultiColor("8")
        val exactOverlay = PrepareModelOverlayBuilder.build(
            listOf(exact),
            null,
            emptyMap(),
        ).single()
        assertArrayEquals(intArrayOf(0, 1, 2), exactOverlay.fillIndices)
        assertEquals(null, exactOverlay.customVertices)

        val overridden = exact.copy(
            volumes = listOf(
                exact.singleVolume.copy(
                    multiColorPaint = MultiColorPaint(mapOf(0 to 4)),
                ),
            ),
        )
        val overriddenOverlay = PrepareModelOverlayBuilder.build(
            listOf(overridden),
            null,
            emptyMap(),
        ).single()
        assertEquals(4, filamentSlotForColor(overriddenOverlay.fillColor))
        assertEquals(null, overriddenOverlay.customVertices)
    }

    @Test
    fun multiColorOverlayUsesProjectFilamentColors() {
        val customColors = DefaultFilamentColors.toMutableList().apply {
            this[0] = 0x102030
            this[1] = 0xA0B0C0
        }
        val overlays = PrepareModelOverlayBuilder.build(
            projectObjects = listOf(objectWithExactMultiColor("841")),
            layOnFaceObjectId = null,
            layOnFaceCandidateFacets = emptyMap(),
            filamentColors = customColors,
        )

        assertEquals(0x10 / 255f, overlays[0].fillColor.red, 0.0001f)
        assertEquals(0x20 / 255f, overlays[0].fillColor.green, 0.0001f)
        assertEquals(0x30 / 255f, overlays[0].fillColor.blue, 0.0001f)
        assertEquals(0xA0 / 255f, overlays[1].fillColor.red, 0.0001f)
        assertEquals(0xB0 / 255f, overlays[1].fillColor.green, 0.0001f)
        assertEquals(0xC0 / 255f, overlays[1].fillColor.blue, 0.0001f)
    }

    private fun objectWithExactMultiColor(value: String): ProjectObject {
        val model = ModelInfo(
            fileName = "exact.stl",
            triangles = 1,
            dimensions = listOf(2.0, 2.0, 0.0),
            localPath = "",
            minMm = listOf(0.0, 0.0, 0.0),
            maxMm = listOf(2.0, 2.0, 0.0),
            previewTriangles = floatArrayOf(
                0f, 0f, 0f, 2f, 0f, 0f, 0f, 2f, 0f,
            ),
            previewTriangleIndices = intArrayOf(0),
        )
        return ProjectObject(
            id = "exact",
            volumes = listOf(
                ProjectVolume(
                    id = "volume",
                    model = model,
                    orcaFacetAnnotations = OrcaFacetAnnotations(
                        multiColor = OrcaFacetAnnotation(mapOf(0 to value)),
                    ),
                ),
            ),
        )
    }

    private fun filamentSlotForColor(color: PrepareOverlayColor): Int =
        (0..8).first { slot ->
            val expected = filamentSlotColor(slot)
            expected.red == color.red && expected.green == color.green &&
                expected.blue == color.blue && color.alpha == 0.94f
        }

    private fun objectWithPaint(): ProjectObject {
        val model = ModelInfo(
            fileName = "painted.stl",
            triangles = 20,
            dimensions = listOf(2.0, 2.0, 1.0),
            localPath = "",
            minMm = listOf(0.0, 0.0, 0.0),
            maxMm = listOf(2.0, 2.0, 1.0),
            previewTriangles = floatArrayOf(
                0f, 0f, 0f, 2f, 0f, 0f, 0f, 2f, 0f,
                0f, 0f, 1f, 2f, 0f, 1f, 0f, 2f, 1f,
            ),
            previewTriangleIndices = intArrayOf(10, 15),
        )
        return ProjectObject(
            id = "painted",
            volumes = listOf(
                ProjectVolume(
                    id = "volume",
                    model = model,
                    supportPaint = SupportPaint(mapOf(10 to SupportPaintState.ENFORCE)),
                    seamPaint = SeamPaint(mapOf(15 to SeamPaintState.BLOCK)),
                    multiColorPaint = MultiColorPaint(mapOf(15 to 2)),
                ),
            ),
        )
    }
}
