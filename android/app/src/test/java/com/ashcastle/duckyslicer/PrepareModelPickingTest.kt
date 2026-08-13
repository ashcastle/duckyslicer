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
    fun coarseIndexCullsDenseChunksWithoutChangingExactHits() {
        val vertices = FloatArray(10 * 6 * 2 * 9)
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
                ).copyInto(vertices, output)
                output += 18
            }
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
        assertEquals(3, index.chunkCount)

        val transform = PreparePickingTransform(
            projectObject.transform,
            placement.geometry,
            placement.minimumRotatedZ,
            viewport.bedSizeX,
            viewport.bedSizeY,
        )
        val candidates = index.candidateRanges(
            triangleCount = model.previewTriangles.size / 9,
            transform = transform,
            projection = PreparePickingProjection(viewport),
            screenX = 450f,
            screenY = 355f,
            touchRadiusPx = 0f,
        )
        val candidateTriangleCount = candidates.asList().chunked(2).sumOf { range ->
            range[1] - range[0]
        }
        assertTrue(candidateTriangleCount in 1 until model.triangles)

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
