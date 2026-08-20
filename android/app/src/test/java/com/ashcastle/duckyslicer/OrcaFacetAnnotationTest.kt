package com.ashcastle.duckyslicer

import java.nio.file.Files
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OrcaFacetAnnotationTest {
    @Test
    fun exactSplitTreesRoundTripThroughSidecarAndJson() {
        val annotation = OrcaFacetAnnotation(
            linkedMapOf(
                2 to "4",
                7 to "841",
                9 to "DC",
            ),
        )
        assertEquals(16, annotation.maximumState)

        val sidecar = Files.createTempFile("ducky-orca-facets-", ".bin").toFile()
        try {
            annotation.writeSidecar(sidecar)
            assertEquals(annotation, OrcaFacetAnnotation.readSidecar(sidecar))
            assertEquals(annotation, OrcaFacetAnnotation.fromJson(annotation.toJson(), 10))
        } finally {
            sidecar.delete()
        }
    }

    @Test
    fun malformedSplitTreesAndStatesAreRejectedBeforeNativeCode() {
        assertThrows(IllegalArgumentException::class.java) {
            OrcaFacetAnnotation(mapOf(0 to "41"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OrcaFacetAnnotation(mapOf(0 to "44"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OrcaFacetAnnotation(mapOf(0 to "c"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OrcaFacetAnnotation.fromJson(JSONArray().put(4).put("4"), 4)
        }
    }

    @Test
    fun projectVolumeEnforcesAnnotationGeometryAndCategoryRanges() {
        val model = ModelInfo(
            fileName = "part.stl",
            triangles = 1,
            dimensions = listOf(1.0, 1.0, 1.0),
            localPath = "/tmp/part.stl",
            minMm = listOf(0.0, 0.0, 0.0),
            maxMm = listOf(1.0, 1.0, 1.0),
            previewTriangles = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ProjectVolume(
                id = "bad-support",
                model = model,
                orcaFacetAnnotations = OrcaFacetAnnotations(
                    support = OrcaFacetAnnotation(mapOf(0 to "0C")),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectVolume(
                id = "missing-triangle",
                model = model,
                orcaFacetAnnotations = OrcaFacetAnnotations(
                    seam = OrcaFacetAnnotation(mapOf(1 to "4")),
                ),
            )
        }
    }
}
