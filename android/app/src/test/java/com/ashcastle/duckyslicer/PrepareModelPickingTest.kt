package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class PrepareModelPickingTest {
    private val viewport = PrepareHitTestViewport(
        widthPx = 1_000f,
        heightPx = 800f,
        bedSizeX = 100f,
        bedSizeY = 80f,
        yawDegrees = 0f,
        pitchDegrees = 90f,
        zoom = 1f,
        panX = 0f,
        panY = 0f,
    )

    @Test
    fun exactPickingRejectsEmptyPartOfObjectBounds() {
        val projectObject = triangleObject("triangle")
        val placements = placements(projectObject)

        assertEquals(
            "triangle",
            findPrepareObjectAtScreen(
                listOf(projectObject), placements, viewport,
                screenX = 498f, screenY = 382f, touchRadiusPx = 0f,
            ),
        )
        assertNull(
            findPrepareObjectAtScreen(
                listOf(projectObject), placements, viewport,
                screenX = 504f, screenY = 388f, touchRadiusPx = 0f,
            ),
        )
    }

    @Test
    fun exactPickingSelectsFrontmostOverlappingObject() {
        val lower = triangleObject("lower")
        val upper = triangleObject("upper", ModelTransform(offsetZmm = 1f))
        val objects = listOf(lower, upper)
        val tiltedViewport = viewport.copy(pitchDegrees = 55f)

        assertEquals(
            "upper",
            findPrepareObjectAtScreen(
                objects, placements(*objects.toTypedArray()), tiltedViewport,
                screenX = 498f, screenY = 380f, touchRadiusPx = 0f,
            ),
        )
    }

    @Test
    fun facetPickingReturnsSourceFacetAndHonorsSelectableCandidates() {
        val projectObject = triangleObject("facet").let { original ->
            original.copy(
                volumes = listOf(
                    original.singleVolume.copy(
                        model = original.singleVolume.model.copy(
                            previewTriangleIndices = intArrayOf(42),
                            triangles = 43,
                        ),
                    ),
                ),
            )
        }
        val placement = placements(projectObject).getValue(projectObject.id)

        val hit = findPrepareFacetAtScreen(
            projectObject = projectObject,
            placement = placement,
            viewport = viewport,
            screenX = 498f,
            screenY = 382f,
            touchRadiusPx = 0f,
        )

        assertEquals(42, hit?.sourceFacetIndex)
        assertEquals(0, hit?.previewTriangleIndex)
        assertEquals(projectObject.singleVolume.id, hit?.volumeId)
        assertNull(
            findPrepareFacetAtScreen(
                projectObject = projectObject,
                placement = placement,
                viewport = viewport,
                screenX = 498f,
                screenY = 382f,
                touchRadiusPx = 0f,
                selectableTriangles = mapOf(
                    projectObject.singleVolume.id to booleanArrayOf(false),
                ),
            ),
        )
    }

    @Test
    fun layOnFacePickingAcceptsAVisibleSurfaceWithoutSuggestedPlanes() {
        val projectObject = triangleObject("unsuggested-face")
        val placement = placements(projectObject).getValue(projectObject.id)
        assertTrue(
            detectLayOnFaceCandidates(
                projectObject.singleVolume.model.previewTriangles,
            ).isEmpty(),
        )

        val hit = findLayOnFaceFacetAtScreen(
            projectObject = projectObject,
            placement = placement,
            viewport = viewport,
            screenX = 498f,
            screenY = 382f,
            touchRadiusPx = 0f,
            pickingIndices = buildPreparePickingIndices(listOf(projectObject)),
        )

        assertEquals(0, hit?.previewTriangleIndex)
        assertEquals(projectObject.singleVolume.id, hit?.volumeId)
    }

    @Test
    fun spatialIndexCullsArbitraryFacetOrderWithoutChangingExactHits() {
        val orderedVertices = FloatArray(10 * 6 * 2 * 9)
        var output = 0
        repeat(6) { row ->
            repeat(10) { column ->
                val x0 = column * 2f
                val x1 = x0 + 2f
                val y0 = row * 2f
                val y1 = y0 + 2f
                floatArrayOf(
                    x0, y0, 0f, x1, y0, 0f, x1, y1, 0f,
                    x0, y0, 0f, x1, y1, 0f, x0, y1, 0f,
                ).copyInto(orderedVertices, output)
                output += 18
            }
        }
        val triangleCount = orderedVertices.size / 9
        val vertices = FloatArray(orderedVertices.size)
        repeat(triangleCount) { target ->
            val source = if (target % 2 == 0) target / 2 else triangleCount - 1 - target / 2
            orderedVertices.copyInto(vertices, target * 9, source * 9, source * 9 + 9)
        }
        val model = ModelInfo(
            fileName = "indexed-grid.stl",
            triangles = vertices.size / 9,
            dimensions = listOf(20.0, 12.0, 0.0),
            localPath = "",
            minMm = listOf(0.0, 0.0, 0.0),
            maxMm = listOf(20.0, 12.0, 0.0),
            previewTriangles = vertices,
            previewTriangleIndices = IntArray(vertices.size / 9) { it + 100 },
        )
        val projectObject = ProjectObject(id = "indexed-grid", model = model)
        val placement = placements(projectObject).getValue(projectObject.id)
        val indices = buildPreparePickingIndices(listOf(projectObject))
        val index = indices.getValue(
            PreparePickingIndexKey(projectObject.id, projectObject.singleVolume.id),
        )
        assertTrue(index.leafCount > 1)

        val transform = PreparePickingTransform(
            projectObject.transform,
            placement.geometry,
            placement.minimumRotatedZ,
            viewport.bedSizeX,
            viewport.bedSizeY,
        )
        val candidates = index.candidateTriangles(
            transform = transform,
            projection = PreparePickingProjection(viewport),
            screenX = 450f,
            screenY = 355f,
            touchRadiusPx = 0f,
        )
        assertTrue(candidates.size in 1 until model.triangles)

        listOf(
            450f to 355f,
            500f to 384f,
            550f to 415f,
            430f to 340f,
        ).forEach { (screenX, screenY) ->
            assertEquals(
                findPrepareObjectAtScreen(
                    listOf(projectObject),
                    mapOf(projectObject.id to placement),
                    viewport,
                    screenX,
                    screenY,
                    8f,
                ),
                findPrepareObjectAtScreen(
                    listOf(projectObject),
                    mapOf(projectObject.id to placement),
                    viewport,
                    screenX,
                    screenY,
                    8f,
                    indices,
                ),
            )
            assertEquals(
                findPrepareFacetAtScreen(
                    projectObject, placement, viewport, screenX, screenY, 8f,
                )?.previewTriangleIndex,
                findPrepareFacetAtScreen(
                    projectObject, placement, viewport, screenX, screenY, 8f,
                    pickingIndices = indices,
                )?.previewTriangleIndex,
            )
        }

        val transformedObject = projectObject.copy(
            transform = ModelTransform(
                offsetXmm = 4f,
                offsetYmm = -3f,
                rotationXdeg = 31f,
                rotationYdeg = -19f,
                rotationZdeg = 47f,
                scale = 1.2f,
                scaleY = 0.8f,
                scaleZ = 1.4f,
                mirrorX = true,
            ),
        )
        val transformedPlacement = placements(transformedObject).getValue(transformedObject.id)
        val selectable = BooleanArray(triangleCount) { triangle -> triangle % 3 != 0 }
        listOf(
            viewport.copy(yawDegrees = -42f, pitchDegrees = 52f, zoom = 1.35f),
            viewport.copy(yawDegrees = 71f, pitchDegrees = 34f, zoom = 0.85f, panX = 18f),
        ).forEach { transformedViewport ->
            listOf(440f to 340f, 480f to 370f, 520f to 400f, 560f to 430f).forEach { point ->
                assertEquals(
                    findPrepareObjectAtScreen(
                        listOf(transformedObject),
                        mapOf(transformedObject.id to transformedPlacement),
                        transformedViewport,
                        point.first,
                        point.second,
                        12f,
                    ),
                    findPrepareObjectAtScreen(
                        listOf(transformedObject),
                        mapOf(transformedObject.id to transformedPlacement),
                        transformedViewport,
                        point.first,
                        point.second,
                        12f,
                        indices,
                    ),
                )
                assertEquals(
                    findPrepareFacetAtScreen(
                        transformedObject,
                        transformedPlacement,
                        transformedViewport,
                        point.first,
                        point.second,
                        12f,
                        selectableTriangles = mapOf(transformedObject.singleVolume.id to selectable),
                    )?.previewTriangleIndex,
                    findPrepareFacetAtScreen(
                        transformedObject,
                        transformedPlacement,
                        transformedViewport,
                        point.first,
                        point.second,
                        12f,
                        selectableTriangles = mapOf(transformedObject.singleVolume.id to selectable),
                        pickingIndices = indices,
                    )?.previewTriangleIndex,
                )
            }
        }
    }

    private fun placements(vararg objects: ProjectObject): Map<String, PrepareObjectPlacement> =
        objects.associate { projectObject ->
            projectObject.id to PrepareObjectPlacement(
                geometry = projectObject.geometry(),
                minimumRotatedZ = projectObject.transform.minimumRotatedZ(projectObject),
            )
        }

    private fun triangleObject(
        id: String,
        transform: ModelTransform = ModelTransform(),
    ): ProjectObject = ProjectObject(
        id = id,
        model = ModelInfo(
            fileName = "$id.stl",
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
        ),
        transform = transform,
    )
}
