package com.ashcastle.duckyslicer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
            detailPreviewTriangles = floatArrayOf(
                0f, 0f, 0f,
                2f, 0f, 0f,
                0f, 2f, 0f,
                2f, 0f, 0f,
                2f, 2f, 0f,
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
            requestedBedExcludeArea = listOf(0f, 0f, 18f, 0f, 18f, 28f, 0f, 28f),
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
        assertEquals(6, mesh.detailVertices.size / 3)
        assertTrue(scene.bedFill.isNotEmpty())
        assertTrue(scene.bedGrid.isNotEmpty())
        assertEquals(4, scene.bedOutline.size / 3)
        assertEquals(8, scene.bedExcludeOutline.size / 3)
    }

    @Test
    fun repeatedPlacementsShareImmutableLowAndDetailTopology() {
        val model = modelWithDetail("shared")
        val scene = PrepareModelSceneBuilder.build(
            projectObjects = listOf(
                ProjectObject(id = "first", model = model),
                ProjectObject(id = "second", model = model),
            ),
            bedSizeX = 100f,
            bedSizeY = 100f,
            requestedBedPolygon = rectangularBedPolygon(100f, 100f),
        )

        assertSame(scene.meshes[0].vertices, scene.meshes[1].vertices)
        assertSame(scene.meshes[0].detailVertices, scene.meshes[1].detailVertices)
        assertEquals(2, uniquePrepareVertexArrays(scene.meshes).size)
    }

    @Test
    fun distinctDetailLodsObeyTheSceneBudgetWithoutTruncatingLowLods() {
        val first = modelWithDetail("first")
        val second = modelWithDetail("second")
        val oneDetailBufferBytes = first.detailPreviewTriangles.size.toLong() * Float.SIZE_BYTES
        val scene = PrepareModelSceneBuilder.build(
            projectObjects = listOf(
                ProjectObject(id = "first", model = first),
                ProjectObject(id = "second", model = second),
            ),
            bedSizeX = 100f,
            bedSizeY = 100f,
            requestedBedPolygon = rectangularBedPolygon(100f, 100f),
            additionalDetailBudgetBytes = oneDetailBufferBytes,
        )

        assertSame(first.previewTriangles, scene.meshes[0].vertices)
        assertSame(first.detailPreviewTriangles, scene.meshes[0].detailVertices)
        assertSame(second.previewTriangles, scene.meshes[1].vertices)
        assertSame(
            "Overflow must keep the complete connected low LOD, not a triangle subset",
            second.previewTriangles,
            scene.meshes[1].detailVertices,
        )
        assertEquals(3, uniquePrepareVertexArrays(scene.meshes).size)
    }

    @Test
    fun printableModelDetailTakesPriorityOverEarlierAuxiliaryVolumes() {
        val auxiliary = modelWithDetail("auxiliary")
        val printable = modelWithDetail("printable")
        val scene = PrepareModelSceneBuilder.build(
            projectObjects = listOf(
                ProjectObject(
                    id = "compound",
                    volumes = listOf(
                        ProjectVolume(
                            id = "modifier",
                            model = auxiliary,
                            role = ProjectVolumeRole.PARAMETER_MODIFIER,
                        ),
                        ProjectVolume(id = "model", model = printable),
                    ),
                ),
            ),
            bedSizeX = 100f,
            bedSizeY = 100f,
            requestedBedPolygon = rectangularBedPolygon(100f, 100f),
            additionalDetailBudgetBytes =
                printable.detailPreviewTriangles.size.toLong() * Float.SIZE_BYTES,
        )

        assertSame(auxiliary.previewTriangles, scene.meshes[0].detailVertices)
        assertSame(printable.detailPreviewTriangles, scene.meshes[1].detailVertices)
    }

    private fun modelWithDetail(name: String): ModelInfo = ModelInfo(
        fileName = "$name.stl",
        triangles = 2,
        dimensions = listOf(2.0, 2.0, 0.0),
        localPath = "",
        minMm = listOf(0.0, 0.0, 0.0),
        maxMm = listOf(2.0, 2.0, 0.0),
        previewTriangles = floatArrayOf(
            0f, 0f, 0f,
            2f, 0f, 0f,
            0f, 2f, 0f,
        ),
        detailPreviewTriangles = floatArrayOf(
            0f, 0f, 0f,
            2f, 0f, 0f,
            0f, 2f, 0f,
            2f, 0f, 0f,
            2f, 2f, 0f,
            0f, 2f, 0f,
        ),
    )
}
