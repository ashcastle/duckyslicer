package com.ashcastle.duckyslicer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrcaModelSplitInstrumentedTest {
    @Test
    fun selectedVolumeSplitsIntoStablePartsAndStillSlicesAsOneObject() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        val store = ProjectStore(context)
        var gcode: File? = null
        projectRoot.deleteRecursively()
        try {
            val sourceFile = store.createModelDestination("compound-volume.stl").apply {
                writeText(cubeStl(0f) + cubeStl(30f, includeEnvelope = false))
            }
            val sourceModel = inspectModel(sourceFile.absolutePath).copy(fileName = "compound-volume.stl")
            val siblingFile = store.createModelDestination("sibling-volume.stl").apply {
                writeText(cubeStl(60f) + "endsolid compound\n")
            }
            val siblingModel = inspectModel(siblingFile.absolutePath).copy(fileName = "sibling-volume.stl")
            val sourceVolume = ProjectVolume(
                id = "source-volume",
                model = sourceModel,
                supportPaint = SupportPaint().paint(0, SupportPaintState.ENFORCE),
                filamentSlot = 0,
                config = ProjectVolumeConfig(mapOf("wall_loops" to "4")),
            )
            val siblingVolume = ProjectVolume(
                id = "sibling-volume",
                model = siblingModel,
                seamPaint = SeamPaint().paint(0, SeamPaintState.BLOCK),
                filamentSlot = 0,
            )
            val parent = ProjectObject(
                id = "multipart-object",
                volumes = listOf(sourceVolume, siblingVolume),
                transform = ModelTransform(
                    offsetXmm = 6f,
                    offsetYmm = -4f,
                    rotationZdeg = 20f,
                    scale = 0.8f,
                ),
                variableLayerHeights = VariableLayerHeights(
                    listOf(VariableLayerRange(0.2f, 0.8f, 0.12f)),
                ),
                processOverrides = ObjectProcessOverrides(sparseInfillDensityPercent = 22f),
            )
            val originalGeometry = parent.geometry()

            val canceledRequestId = "split-parts-canceled-before-start"
            assertTrue(SlicerProcessClient.cancelProjectRequestAsync(canceledRequestId))
            val canceled = runCatching {
                splitProjectObjectVolume(
                    projectObject = parent,
                    sourceVolumeId = sourceVolume.id,
                    projectStore = store,
                    requestId = canceledRequestId,
                )
            }
            assertTrue(canceled.exceptionOrNull() is ProjectEditCancelledException)
            SlicerProcessClient.releaseProjectRequest(canceledRequestId)
            assertTrue(sourceFile.isFile && siblingFile.isFile)

            val result = splitProjectObjectVolume(
                projectObject = parent,
                sourceVolumeId = sourceVolume.id,
                projectStore = store,
            )

            val split = result.projectObject
            assertEquals(2, result.createdPartCount)
            assertTrue(result.clearedSurfacePaint)
            assertEquals(parent.id, split.id)
            assertEquals(parent.transform, split.transform)
            assertEquals(parent.variableLayerHeights, split.variableLayerHeights)
            assertEquals(parent.processOverrides, split.processOverrides)
            assertEquals(3, split.volumes.size)
            assertEquals(sourceVolume.id, split.volumes[0].id)
            assertEquals(
                splitProjectVolumeId(parent.id, sourceVolume.id, 1),
                split.volumes[1].id,
            )
            assertEquals(siblingVolume, split.volumes[2])
            assertTrue(split.volumes.take(2).all { it.supportPaint.facets.isEmpty() })
            assertTrue(split.volumes.take(2).all { it.filamentSlot == sourceVolume.filamentSlot })
            assertTrue(split.volumes.take(2).all { it.role == sourceVolume.role })
            assertTrue(split.volumes.take(2).all { it.config == sourceVolume.config })
            val splitGeometry = split.geometry()
            assertEquals(originalGeometry.minX.toDouble(), splitGeometry.minX.toDouble(), 0.01)
            assertEquals(originalGeometry.maxX.toDouble(), splitGeometry.maxX.toDouble(), 0.01)
            assertEquals(originalGeometry.minY.toDouble(), splitGeometry.minY.toDouble(), 0.01)
            assertEquals(originalGeometry.maxY.toDouble(), splitGeometry.maxY.toDouble(), 0.01)
            assertEquals(originalGeometry.minZ.toDouble(), splitGeometry.minZ.toDouble(), 0.01)
            assertEquals(originalGeometry.maxZ.toDouble(), splitGeometry.maxZ.toDouble(), 0.01)

            var history = ProjectHistoryState().add(parent)
            history = history.replaceSelected(listOf(split))
            assertEquals(3, history.current.selectedObject!!.volumes.size)
            assertEquals(parent, history.undo().current.selectedObject)
            assertEquals(split, history.undo().redo().current.selectedObject)

            store.save(history.current, SliceOptions())
            val restored = store.load().selectedObject!!
            assertEquals(split.volumes.map(ProjectVolume::id), restored.volumes.map(ProjectVolume::id))
            assertEquals(split.transform, restored.transform)

            val options = SliceOptions().copy(
                bedSizeX = 120f,
                bedSizeY = 120f,
                bedPolygon = rectangularBedPolygon(120f, 120f),
            )
            val outcome = OnDeviceSlicer.slice(listOf(split), options)
            gcode = outcome.output
            assertTrue("Split parts must produce real Orca G-code", outcome.output.length() > 1_000L)
        } finally {
            gcode?.delete()
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun disconnectedShellsSplitThroughOrcaWithoutChangingTheirMachinePose() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        val store = ProjectStore(context)
        var gcode: File? = null
        projectRoot.deleteRecursively()
        try {
            val source = store.createModelDestination("two-cubes.stl").apply {
                writeText(cubeStl(0f) + cubeStl(30f, includeEnvelope = false))
            }
            val model = inspectModel(source.absolutePath).copy(fileName = "two-cubes.stl")
            val options = SliceOptions().copy(
                bedSizeX = 100f,
                bedSizeY = 100f,
                bedPolygon = rectangularBedPolygon(100f, 100f),
            )
            val parent = ProjectObject(
                id = "compound",
                model = model,
                transform = ModelTransform(
                    offsetXmm = 8f,
                    offsetYmm = -6f,
                    offsetZmm = 7f,
                    rotationZdeg = 90f,
                    scale = 1.2f,
                    mirrorX = true,
                ),
                supportPaint = SupportPaint().paint(0, SupportPaintState.ENFORCE),
                seamPaint = SeamPaint().paint(1, SeamPaintState.BLOCK),
                multiColorPaint = MultiColorPaint().paint(2, 0),
                variableLayerHeights = VariableLayerHeights(
                    listOf(VariableLayerRange(0.2f, 0.8f, 0.1f)),
                ),
            )
            val expectedFile = File(context.cacheDir, "expected-split-parent.stl")
            val transformed = JSONObject(
                NativeEngine.transformStl(
                    source.absolutePath,
                    expectedFile.absolutePath,
                    parent.transform.toJson(100f, 100f),
                ),
            )
            assertTrue(transformed.optBoolean("ok"))
            val expected = inspectModel(expectedFile.absolutePath)

            val split = splitProjectObject(parent, store, options)

            assertEquals(2, split.objects.size)
            assertTrue(split.clearedObjectSettings)
            assertTrue(
                split.objects.all {
                    it.supportPaint.facets.isEmpty() && it.seamPaint.facets.isEmpty() &&
                        it.multiColorPaint.facets.isEmpty() &&
                        it.variableLayerHeights.ranges.isEmpty()
                },
            )
            val placed = split.objects.mapIndexed { index, projectObject ->
                val output = File(context.cacheDir, "placed-split-$index.stl")
                val response = JSONObject(
                    NativeEngine.transformStl(
                        projectObject.model.localPath,
                        output.absolutePath,
                        projectObject.transform.toJson(100f, 100f),
                    ),
                )
                assertTrue(response.optBoolean("ok"))
                inspectModel(output.absolutePath)
            }
            repeat(3) { axis ->
                assertEquals(expected.minMm[axis], placed.minOf { it.minMm[axis] }, 0.01)
                assertEquals(expected.maxMm[axis], placed.maxOf { it.maxMm[axis] }, 0.01)
            }

            val outcome = OnDeviceSlicer.slice(split.objects, options)
            gcode = outcome.output
            assertTrue("Split objects must produce real Orca G-code", outcome.output.length() > 1_000L)
        } finally {
            gcode?.delete()
            context.cacheDir.listFiles { file ->
                file.name.startsWith("expected-split-") || file.name.startsWith("placed-split-")
            }.orEmpty().forEach(File::delete)
            projectRoot.deleteRecursively()
        }
    }

    private fun cubeStl(offsetX: Float, includeEnvelope: Boolean = true): String {
        val vertices = arrayOf(
            floatArrayOf(offsetX, 0f, 0f),
            floatArrayOf(offsetX + 10f, 0f, 0f),
            floatArrayOf(offsetX + 10f, 10f, 0f),
            floatArrayOf(offsetX, 10f, 0f),
            floatArrayOf(offsetX, 0f, 10f),
            floatArrayOf(offsetX + 10f, 0f, 10f),
            floatArrayOf(offsetX + 10f, 10f, 10f),
            floatArrayOf(offsetX, 10f, 10f),
        )
        val faces = arrayOf(
            intArrayOf(0, 2, 1), intArrayOf(0, 3, 2),
            intArrayOf(4, 5, 6), intArrayOf(4, 6, 7),
            intArrayOf(0, 1, 5), intArrayOf(0, 5, 4),
            intArrayOf(1, 2, 6), intArrayOf(1, 6, 5),
            intArrayOf(2, 3, 7), intArrayOf(2, 7, 6),
            intArrayOf(3, 0, 4), intArrayOf(3, 4, 7),
        )
        return buildString {
            if (includeEnvelope) appendLine("solid compound")
            faces.forEach { face ->
                appendLine("facet normal 0 0 0")
                appendLine("outer loop")
                face.forEach { vertexIndex ->
                    val vertex = vertices[vertexIndex]
                    appendLine("vertex ${vertex[0]} ${vertex[1]} ${vertex[2]}")
                }
                appendLine("endloop")
                appendLine("endfacet")
            }
            if (!includeEnvelope) appendLine("endsolid compound")
        }
    }
}
