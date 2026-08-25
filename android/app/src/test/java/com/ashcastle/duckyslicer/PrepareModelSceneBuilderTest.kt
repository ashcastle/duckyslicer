package com.ashcastle.duckyslicer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            filamentColors = listOf(0x112233, 0x445566, 0x778899, 0xAABBCC),
        )

        assertEquals(1, scene.meshes.size)
        assertEquals(listOf(0x112233, 0x445566, 0x778899, 0xAABBCC), scene.filamentColors)
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
        assertSame(scene.meshes[0].coarseVertices, scene.meshes[1].coarseVertices)
        assertSame(scene.meshes[0].detailVertices, scene.meshes[1].detailVertices)
        assertEquals(3, uniquePrepareVertexArrays(scene.meshes).size)
        assertEquals(0, scene.normalUploadCache.pendingTopologyCountForTest())

        val prepared = scene.withPrecomputedPrepareNormals()
        assertEquals(3, prepared.normalUploadCache.pendingTopologyCountForTest())
        assertEquals(0, prepared.normalUploadCache.fallbackGenerationCountForTest())
    }

    @Test
    fun distinctInteractionLodsFallBackToCompleteCoarseMeshesAtTheSceneBudget() {
        val first = modelWithDetail("first")
        val second = modelWithDetail("second")
        val baselineBytes = prepareMeshGpuBytes(first.coarsePreviewTriangles) +
            prepareMeshGpuBytes(second.coarsePreviewTriangles)
        val onePreviewBytes = prepareMeshGpuBytes(first.previewTriangles)
        val scene = PrepareModelSceneBuilder.build(
            projectObjects = listOf(
                ProjectObject(id = "first", model = first),
                ProjectObject(id = "second", model = second),
            ),
            bedSizeX = 100f,
            bedSizeY = 100f,
            requestedBedPolygon = rectangularBedPolygon(100f, 100f),
            additionalDetailBudgetBytes = 0L,
            lowDetailBudgetBytes = baselineBytes + onePreviewBytes,
        )

        assertSame(first.previewTriangles, scene.meshes[0].vertices)
        assertSame(
            "Overflow must use a complete connected coarse LOD, not truncate triangles",
            second.coarsePreviewTriangles,
            scene.meshes[1].vertices,
        )
        assertSame(scene.meshes[0].vertices, scene.meshes[0].detailVertices)
        assertSame(scene.meshes[1].vertices, scene.meshes[1].detailVertices)
    }

    @Test
    fun repeatedPlacementsChargeSharedInteractionTopologyOnlyOnce() {
        val shared = modelWithDetail("shared")
        val budget = prepareMeshGpuBytes(shared.coarsePreviewTriangles) +
            prepareMeshGpuBytes(shared.previewTriangles)
        val scene = PrepareModelSceneBuilder.build(
            projectObjects = listOf(
                ProjectObject(id = "first", model = shared),
                ProjectObject(id = "second", model = shared),
            ),
            bedSizeX = 100f,
            bedSizeY = 100f,
            requestedBedPolygon = rectangularBedPolygon(100f, 100f),
            additionalDetailBudgetBytes = 0L,
            lowDetailBudgetBytes = budget,
        )

        assertSame(shared.previewTriangles, scene.meshes[0].vertices)
        assertSame(scene.meshes[0].vertices, scene.meshes[1].vertices)
    }

    @Test
    fun distinctDetailLodsObeyTheSceneBudgetWithoutTruncatingLowLods() {
        val first = modelWithDetail("first")
        val second = modelWithDetail("second")
        val oneDetailBufferBytes = prepareMeshGpuBytes(first.detailPreviewTriangles)
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
        assertEquals(5, uniquePrepareVertexArrays(scene.meshes).size)
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
            additionalDetailBudgetBytes = prepareMeshGpuBytes(printable.detailPreviewTriangles),
        )

        assertSame(auxiliary.previewTriangles, scene.meshes[0].detailVertices)
        assertSame(printable.detailPreviewTriangles, scene.meshes[1].detailVertices)
    }

    @Test
    fun printableInteractionMeshTakesPriorityOverEarlierAuxiliaryVolumes() {
        val auxiliary = modelWithDetail("auxiliary-low")
        val printable = modelWithDetail("printable-low")
        val baselineBytes = prepareMeshGpuBytes(auxiliary.coarsePreviewTriangles) +
            prepareMeshGpuBytes(printable.coarsePreviewTriangles)
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
            additionalDetailBudgetBytes = 0L,
            lowDetailBudgetBytes = baselineBytes + prepareMeshGpuBytes(printable.previewTriangles),
        )

        assertSame(auxiliary.coarsePreviewTriangles, scene.meshes[0].vertices)
        assertSame(printable.previewTriangles, scene.meshes[1].vertices)
    }

    @Test
    fun prepareRenderTierUsesConnectedCoarseGeometryOnlyForBedFitGestures() {
        assertEquals(
            PrepareModelRenderTier.COARSE,
            prepareModelRenderTier(interactionActive = true, overlaysActive = false, zoom = 1f),
        )
        assertEquals(
            PrepareModelRenderTier.PREVIEW,
            prepareModelRenderTier(interactionActive = true, overlaysActive = false, zoom = 2f),
        )
        assertEquals(
            PrepareModelRenderTier.PREVIEW,
            prepareModelRenderTier(interactionActive = true, overlaysActive = true, zoom = 1f),
        )
        assertEquals(
            PrepareModelRenderTier.DETAIL,
            prepareModelRenderTier(interactionActive = false, overlaysActive = false, zoom = 1f),
        )
        assertEquals(
            PrepareModelRenderTier.PREVIEW,
            prepareModelRenderTier(
                interactionActive = false,
                overlaysActive = false,
                zoom = 1f,
                refinementReady = false,
            ),
        )
    }

    @Test
    fun detailNormalWorkYieldsToInteractionAndMemoryPressure() {
        assertTrue(
            prepareDetailNormalsAllowed(
                sceneComplete = true,
                detailNormalsReady = false,
                interactionActive = false,
                memoryPressureActive = false,
            ),
        )
        assertFalse(
            prepareDetailNormalsAllowed(
                sceneComplete = true,
                detailNormalsReady = false,
                interactionActive = true,
                memoryPressureActive = false,
            ),
        )
        assertFalse(
            prepareDetailNormalsAllowed(
                sceneComplete = true,
                detailNormalsReady = false,
                interactionActive = false,
                memoryPressureActive = true,
            ),
        )
        assertFalse(prepareDetailNormalsAllowed(false, false, false, false))
        assertFalse(prepareDetailNormalsAllowed(true, true, false, false))
    }

    @Test
    fun prepareRotationMatrixMatchesTheCanonicalModelTransformOrder() {
        val transform = ModelTransform(
            rotationXdeg = 37f,
            rotationYdeg = -23f,
            rotationZdeg = 81f,
        )
        val point = floatArrayOf(4f, -7f, 11f)
        val expected = transform.rotate(point)
        val matrix = transform.prepareRotationMatrix()
        val actual = floatArrayOf(
            matrix[0] * point[0] + matrix[3] * point[1] + matrix[6] * point[2],
            matrix[1] * point[0] + matrix[4] * point[1] + matrix[7] * point[2],
            matrix[2] * point[0] + matrix[5] * point[1] + matrix[8] * point[2],
        )

        assertArrayEquals(expected, actual, 0.0001f)
    }

    private fun modelWithDetail(name: String): ModelInfo = ModelInfo(
        fileName = "$name.stl",
        triangles = 3,
        dimensions = listOf(2.0, 2.0, 0.0),
        localPath = "",
        minMm = listOf(0.0, 0.0, 0.0),
        maxMm = listOf(2.0, 2.0, 0.0),
        previewTriangles = floatArrayOf(
            0f, 0f, 0f,
            2f, 0f, 0f,
            0f, 2f, 0f,
            2f, 0f, 0f,
            2f, 2f, 0f,
            0f, 2f, 0f,
        ),
        coarsePreviewTriangles = floatArrayOf(
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
            0f, 0f, 0f,
            2f, 2f, 0f,
            0f, 2f, 0f,
        ),
    )
}
