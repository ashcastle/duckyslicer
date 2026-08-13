package com.ashcastle.duckyslicer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrepareModelSceneBuilderTest {
    @Test
    fun prepareGeometryKeepsRawTopologyForOneTimeGpuUpload() {
        val model = ModelInfo(
            fileName = "triangle.stl",
            triangles = 1,
            dimensions = listOf(2.0, 2.0, 0.0),
            localPath = "",
            minMm = listOf(0.0, 0.0, 0.0),
            maxMm = listOf(2.0, 2.0, 0.0),
            previewTriangles = floatArrayOf(
                0f, 0f, 0f,
                2f, 0f, 0f,
                0f, 2f, 0f,
            ),
        )
        val projectObject = ProjectObject(
            id = "object",
            model = model,
            transform = ModelTransform(offsetXmm = 20f, rotationZdeg = 45f, scale = 1.5f),
            filamentSlot = 3,
        )

        val scene = PrepareModelSceneBuilder.build(
            projectObjects = listOf(projectObject),
            bedSizeX = 100f,
            bedSizeY = 80f,
            requestedBedPolygon = rectangularBedPolygon(100f, 80f),
        )

        assertEquals(1, scene.meshes.size)
        val mesh = scene.meshes.single()
        assertEquals("object", mesh.objectId)
        assertEquals(3, mesh.filamentSlot)
        assertEquals(3, mesh.vertexCount)
        assertArrayEquals(floatArrayOf(1f, 1f, 0f), mesh.sourceCenter, 0f)
        assertArrayEquals(
            floatArrayOf(
                0f, 0f, 0f,
                2f, 0f, 0f,
                0f, 2f, 0f,
            ),
            mesh.vertices,
            0f,
        )
        assertTrue(scene.bedFill.isNotEmpty())
        assertTrue(scene.bedGrid.isNotEmpty())
        assertEquals(4, scene.bedOutline.size / 3)
    }
}
