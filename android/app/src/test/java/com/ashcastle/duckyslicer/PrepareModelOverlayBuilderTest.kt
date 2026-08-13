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
