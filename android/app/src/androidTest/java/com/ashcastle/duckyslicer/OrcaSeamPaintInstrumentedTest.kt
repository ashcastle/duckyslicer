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
    fun enforcedAndBlockedSeamFacetsControlRealOuterWallStarts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        val store = ProjectStore(context)
        val outputs = mutableListOf<File>()
        projectRoot.deleteRecursively()
        try {
            val source = store.createModelDestination("seam-cube.stl").apply {
                writeText(cubeStl())
            }
            val model = inspectModel(source.absolutePath).copy(fileName = "seam-cube.stl")
            val options = SliceOptions().copy(
                bedSizeX = 100f,
                bedSizeY = 100f,
                bedPolygon = rectangularBedPolygon(100f, 100f),
                seamPosition = "aligned",
            )
            suspend fun slice(name: String, seamPaint: SeamPaint = SeamPaint()): String {
                val output = OnDeviceSlicer.slice(
                    listOf(ProjectObject(name, model, seamPaint = seamPaint)),
                    options,
                ).output
                outputs += output
                return output.readText()
            }

            val baseline = slice("baseline")
            val enforcedPaint = SeamPaint()
                .paint(10, SeamPaintState.ENFORCE)
                .paint(11, SeamPaintState.ENFORCE)
            val blockedPaint = SeamPaint()
                .paint(10, SeamPaintState.BLOCK)
                .paint(11, SeamPaintState.BLOCK)
            val enforced = slice("enforced", enforcedPaint)
            val blocked = slice("blocked", blockedPaint)
            val baselineStarts = outerWallStartPoints(baseline)
            val enforcedStarts = outerWallStartPoints(enforced)
            val blockedStarts = outerWallStartPoints(blocked)

            assertTrue(baseline.length > 1_000 && enforced.length > 1_000 && blocked.length > 1_000)
            assertTrue("Every seam mode must expose many real outer-wall starts", baselineStarts.size >= 20)
            assertTrue("Enforced seams must expose many real outer-wall starts", enforcedStarts.size >= 20)
            assertTrue("Blocked seams must expose many real outer-wall starts", blockedStarts.size >= 20)
            assertNotEquals(extrusionMotions(baseline), extrusionMotions(enforced))
            assertNotEquals(extrusionMotions(enforced), extrusionMotions(blocked))
            assertTrue(
                "Enforcing the x-min face must place most seams on that face: $enforcedStarts",
                enforcedStarts.count { (x, _) -> x <= 40.5f } >= enforcedStarts.size * 3 / 4,
            )
            assertTrue(
                "Blocking the x-min face must move most seams away from it: $blockedStarts",
                blockedStarts.count { (x, _) -> x > 40.5f } >= blockedStarts.size * 3 / 4,
            )
        } finally {
            outputs.forEach(File::delete)
            projectRoot.deleteRecursively()
        }
    }

    private fun outerWallStartPoints(gcode: String): List<Pair<Float, Float>> {
        var x: Float? = null
        var y: Float? = null
        var outerWall = false
        var capturedForRole = false
        val starts = mutableListOf<Pair<Float, Float>>()
        gcode.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith(";TYPE:") || line.startsWith("; FEATURE:")) {
                outerWall = line.substringAfter(':').trim().equals("Outer wall", ignoreCase = true)
                capturedForRole = false
                return@forEach
            }
            val command = line.substringBefore(';').trim()
            if (!command.startsWith("G1 ") && !command.startsWith("G2 ") && !command.startsWith("G3 ")) {
                return@forEach
            }
            val startX = x
            val startY = y
            command.axisValue('X')?.let { x = it }
            command.axisValue('Y')?.let { y = it }
            val extrusion = command.axisValue('E') ?: return@forEach
            if (outerWall && !capturedForRole && extrusion > 0f && startX != null && startY != null) {
                starts += startX to startY
                capturedForRole = true
            }
        }
        return starts
    }

    private fun String.axisValue(axis: Char): Float? = split(' ')
        .firstOrNull { token -> token.length > 1 && token[0] == axis }
        ?.substring(1)
        ?.toFloatOrNull()

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
