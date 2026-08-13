package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
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
