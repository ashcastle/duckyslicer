package com.ashcastle.duckyslicer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FilamentStatisticsInstrumentedTest {
    @Test
    fun inheritedDensityAndPriceChangeRealOrcaStatistics() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelFile = File(context.cacheDir, "filament-statistics-cube.stl").apply {
            writeText(cubeStl())
        }
        val outputs = ArrayList<File>()
        try {
            val model = ProjectObject("filament-statistics", inspectModel(modelFile.absolutePath))
            val base = SliceOptions().copy(
                bedSizeX = 100f,
                bedSizeY = 100f,
                bedPolygon = rectangularBedPolygon(100f, 100f),
                fillDensity = 0.2f,
            )
            val light = OnDeviceSlicer.slice(
                listOf(model),
                base.selectFilament(
                    FilamentProfile.GENERIC_PLA.copy(
                        density = 1f,
                        costPerKilogram = 20f,
                    ),
                ),
            ).also { outputs += it.output }
            val dense = OnDeviceSlicer.slice(
                listOf(model),
                base.selectFilament(
                    FilamentProfile.GENERIC_PLA.copy(
                        density = 2f,
                        costPerKilogram = 50f,
                    ),
                ),
            ).also { outputs += it.output }

            val lightGcode = light.output.readText()
            val denseGcode = dense.output.readText()
            val lightCost = materialCost(lightGcode)
            val denseCost = materialCost(denseGcode)

            assertTrue(lightGcode.contains("; filament_density: 1"))
            assertTrue(denseGcode.contains("; filament_density: 2"))
            assertTrue(lightGcode.contains("filament_cost = 20"))
            assertTrue(denseGcode.contains("filament_cost = 50"))
            assertTrue("Density must not alter extrusion length", abs(light.filamentMm - dense.filamentMm) < 1f)
            assertTrue(
                "Twice the density must produce twice the reported mass, " +
                    "light=${light.filamentGrams}, dense=${dense.filamentGrams}",
                dense.filamentGrams > light.filamentGrams * 1.95f &&
                    dense.filamentGrams < light.filamentGrams * 2.05f,
            )
            assertTrue("Density and price must both affect material cost", denseCost > lightCost * 4.8f)
        } finally {
            outputs.forEach(File::delete)
            modelFile.delete()
        }
    }

    private fun materialCost(gcode: String): Float = requireNotNull(
        MATERIAL_COST.find(gcode)?.groupValues?.get(1)?.toFloatOrNull(),
    ) { "Orca did not emit material cost" }

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

    private companion object {
        val MATERIAL_COST = Regex("^; filament cost = ([0-9.]+)", RegexOption.MULTILINE)
    }
}
