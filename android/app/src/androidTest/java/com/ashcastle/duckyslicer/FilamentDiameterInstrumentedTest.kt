package com.ashcastle.duckyslicer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FilamentDiameterInstrumentedTest {
    @Test
    fun inheritedDiameterChangesRealOrcaExtrusion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelFile = File(context.cacheDir, "filament-diameter-cube.stl").apply {
            writeText(cubeStl())
        }
        val outputs = ArrayList<File>()
        try {
            val model = ProjectObject("filament-diameter", inspectModel(modelFile.absolutePath))
            val base = SliceOptions().copy(
                bedSizeX = 100f,
                bedSizeY = 100f,
                bedPolygon = rectangularBedPolygon(100f, 100f),
                fillDensity = 0.2f,
            )
            val standard = OnDeviceSlicer.slice(
                listOf(model),
                base.selectFilament(FilamentProfile.GENERIC_PLA.copy(diameter = 1.75f)),
            ).also { outputs += it.output }
            val wide = OnDeviceSlicer.slice(
                listOf(model),
                base.selectFilament(FilamentProfile.GENERIC_PLA.copy(diameter = 2.85f)),
            ).also { outputs += it.output }

            assertTrue(standard.output.readText().contains("; filament_diameter: 1.75"))
            assertTrue(wide.output.readText().contains("; filament_diameter: 2.85"))
            assertTrue("Standard-diameter estimate must be meaningful", standard.filamentMm > 100f)
            assertTrue(
                "A wider filament must need materially less E-axis length, " +
                    "standard=${standard.filamentMm}, wide=${wide.filamentMm}",
                wide.filamentMm < standard.filamentMm * 0.45f,
            )
        } finally {
            outputs.forEach(File::delete)
            modelFile.delete()
        }
    }

    @Test
    fun pelletFlowCoefficientChangesRealOrcaExtrusion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelFile = File(context.cacheDir, "pellet-flow-cube.stl").apply {
            writeText(cubeStl())
        }
        val outputs = ArrayList<File>()
        try {
            val model = ProjectObject("pellet-flow", inspectModel(modelFile.absolutePath))
            val base = SliceOptions().copy(
                bedSizeX = 100f,
                bedSizeY = 100f,
                bedPolygon = rectangularBedPolygon(100f, 100f),
                fillDensity = 0.2f,
            )
            val filament = FilamentProfile.GENERIC_PLA.copy(
                id = "ginger-pellet-pla",
                pelletFlowCoefficient = 1f,
                diameter = filamentDiameterFromPelletFlowCoefficient(1f),
            )
            val pellet = OnDeviceSlicer.slice(
                listOf(model),
                base.copy(
                    printerProfile = base.printerProfile.copy(pelletModded = true),
                ).selectFilament(filament),
            ).also { outputs += it.output }

            assertTrue(pellet.output.readText().contains("; filament_diameter: 1.12838"))
            assertTrue("Pellet extrusion estimate must be meaningful", pellet.filamentMm > 100f)
        } finally {
            outputs.forEach(File::delete)
            modelFile.delete()
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
