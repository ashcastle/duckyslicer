package com.ashcastle.duckyslicer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrcaSeamPaintInstrumentedTest {
    @Test
    fun paintedSeamFacetsChangeTheInheritedOrcaToolpath() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        val store = ProjectStore(context)
        projectRoot.deleteRecursively()
        try {
            val source = store.createModelDestination("seam-cube.stl").apply {
                writeText(cubeStl())
            }
            val model = ModelInfo.fromJson(
                NativeEngine.inspectStl(source.absolutePath),
                source.absolutePath,
            ).copy(fileName = "seam-cube.stl")
            val options = SliceOptions().copy(
                bedSizeX = 100f,
                bedSizeY = 100f,
                bedPolygon = rectangularBedPolygon(100f, 100f),
            )
            val baseline = OnDeviceSlicer.slice(listOf(ProjectObject("baseline", model)), options)
                .output
                .readText()
            val seamPaint = SeamPaint()
                .paint(10, SeamPaintState.ENFORCE)
                .paint(11, SeamPaintState.ENFORCE)
            val painted = OnDeviceSlicer.slice(
                listOf(ProjectObject("painted", model, seamPaint = seamPaint)),
                options,
            ).output.readText()

            assertTrue(baseline.length > 1_000 && painted.length > 1_000)
            assertNotEquals(extrusionMotions(baseline), extrusionMotions(painted))
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    private fun extrusionMotions(gcode: String): List<String> = gcode.lineSequence()
        .filter { line ->
            (line.startsWith("G1 ") || line.startsWith("G2 ") || line.startsWith("G3 ")) &&
                " E" in line
        }
        .toList()

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
