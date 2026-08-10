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
            val model = ModelInfo.fromJson(
                NativeEngine.inspectStl(source.absolutePath),
                source.absolutePath,
            ).copy(fileName = "two-cubes.stl")
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
            val expected = ModelInfo.fromJson(
                NativeEngine.inspectStl(expectedFile.absolutePath),
                expectedFile.absolutePath,
            )

            val split = splitProjectObject(parent, store, options)

            assertEquals(2, split.objects.size)
            assertTrue(split.clearedFacetPaint)
            assertTrue(split.objects.all { it.supportPaint.facets.isEmpty() && it.seamPaint.facets.isEmpty() })
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
                ModelInfo.fromJson(NativeEngine.inspectStl(output.absolutePath), output.absolutePath)
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
