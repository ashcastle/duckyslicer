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
class OrcaModelCutInstrumentedTest {
    @Test
    fun planarCutUsesOrcaAndKeepsTheProjectAssignment() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        val store = ProjectStore(context)
        var gcode: File? = null
        projectRoot.deleteRecursively()
        try {
            val source = store.createModelDestination("cut-cube.stl").apply {
                writeText(cubeStl())
            }
            val model = ModelInfo.fromJson(
                NativeEngine.inspectStl(source.absolutePath),
                source.absolutePath,
            ).copy(fileName = "cut-cube.stl")
            val options = SliceOptions().addFilamentSlot(FilamentProfile.PETG).copy(
                bedSizeX = 100f,
                bedSizeY = 100f,
                bedPolygon = rectangularBedPolygon(100f, 100f),
            )
            val parent = ProjectObject(
                id = "cut-parent",
                model = model,
                transform = ModelTransform(offsetXmm = 7f, offsetYmm = -4f, offsetZmm = 3f),
                supportPaint = SupportPaint().paint(0, SupportPaintState.ENFORCE),
                seamPaint = SeamPaint().paint(1, SeamPaintState.BLOCK),
                filamentSlot = 1,
            )
            val expectedFile = File(context.cacheDir, "expected-cut-parent.stl")
            assertTrue(
                JSONObject(
                    NativeEngine.transformStl(
                        source.absolutePath,
                        expectedFile.absolutePath,
                        parent.transform.toJson(100f, 100f),
                    ),
                ).optBoolean("ok"),
            )
            val expected = ModelInfo.fromJson(
                NativeEngine.inspectStl(expectedFile.absolutePath),
                expectedFile.absolutePath,
            )

            val cut = cutProjectObject(parent, store, options, 0.5f, placeOnCut = false)

            assertEquals(2, cut.objects.size)
            assertTrue(cut.clearedFacetPaint)
            assertTrue(
                cut.objects.all {
                    it.supportPaint.facets.isEmpty() && it.seamPaint.facets.isEmpty() &&
                        it.filamentSlot == 1
                },
            )
            val placed = cut.objects.mapIndexed { index, projectObject ->
                val output = File(context.cacheDir, "placed-cut-$index.stl")
                assertTrue(
                    JSONObject(
                        NativeEngine.transformStl(
                            projectObject.model.localPath,
                            output.absolutePath,
                            projectObject.transform.toJson(100f, 100f),
                        ),
                    ).optBoolean("ok"),
                )
                ModelInfo.fromJson(NativeEngine.inspectStl(output.absolutePath), output.absolutePath)
            }
            repeat(3) { axis ->
                assertEquals(
                    "axis=$axis expected=${expected.minMm}/${expected.maxMm} " +
                        "placed=${placed.map { it.minMm to it.maxMm }}",
                    expected.minMm[axis],
                    placed.minOf { it.minMm[axis] },
                    0.01,
                )
                assertEquals(
                    "axis=$axis expected=${expected.minMm}/${expected.maxMm} " +
                        "placed=${placed.map { it.minMm to it.maxMm }}",
                    expected.maxMm[axis],
                    placed.maxOf { it.maxMm[axis] },
                    0.01,
                )
            }

            val outcome = OnDeviceSlicer.slice(cut.objects, options)
            gcode = outcome.output
            assertTrue(outcome.output.length() > 1_000L)

            val placedOnCut = cutProjectObject(parent, store, options, 0.5f, placeOnCut = true)
            assertTrue(
                placedOnCut.objects.all {
                    it.transform.offsetXmm == parent.transform.offsetXmm &&
                        it.transform.offsetYmm == parent.transform.offsetYmm &&
                        it.transform.offsetZmm == 0f
                },
            )
        } finally {
            gcode?.delete()
            context.cacheDir.listFiles { file ->
                file.name.startsWith("expected-cut-") || file.name.startsWith("placed-cut-")
            }.orEmpty().forEach(File::delete)
            projectRoot.deleteRecursively()
        }
    }

    private fun cubeStl(): String {
        val vertices = arrayOf(
            floatArrayOf(0f, 0f, 0f), floatArrayOf(20f, 0f, 0f),
            floatArrayOf(20f, 20f, 0f), floatArrayOf(0f, 20f, 0f),
            floatArrayOf(0f, 0f, 20f), floatArrayOf(20f, 0f, 20f),
            floatArrayOf(20f, 20f, 20f), floatArrayOf(0f, 20f, 20f),
        )
        val faces = arrayOf(
            intArrayOf(0, 2, 1), intArrayOf(0, 3, 2), intArrayOf(4, 5, 6),
            intArrayOf(4, 6, 7), intArrayOf(0, 1, 5), intArrayOf(0, 5, 4),
            intArrayOf(1, 2, 6), intArrayOf(1, 6, 5), intArrayOf(2, 3, 7),
            intArrayOf(2, 7, 6), intArrayOf(3, 0, 4), intArrayOf(3, 4, 7),
        )
        return buildString {
            appendLine("solid cube")
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
            appendLine("endsolid cube")
        }
    }
}
