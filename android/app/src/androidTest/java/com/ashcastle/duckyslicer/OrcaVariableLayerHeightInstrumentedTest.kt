package com.ashcastle.duckyslicer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrcaVariableLayerHeightInstrumentedTest {
    @Test
    fun objectRangeUsesOrcaLayerConfigAndChangesRealLayerCount() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        val store = ProjectStore(context)
        projectRoot.deleteRecursively()
        try {
            val source = store.createModelDestination("variable-layer-cube.stl")
            instrumentation.context.assets.open("20mmbox-LF.stl").use { input ->
                source.outputStream().use(input::copyTo)
            }
            val model = inspectModel(source.absolutePath).copy(fileName = "variable-layer-cube.stl")
            val options = SliceOptions().copy(
                layerHeight = 0.2f,
                firstLayerHeight = 0.2f,
                bedSizeX = 100f,
                bedSizeY = 100f,
                bedPolygon = rectangularBedPolygon(100f, 100f),
            )

            val baseline = OnDeviceSlicer.slice(
                listOf(ProjectObject("baseline", model)),
                options,
            )
            val variable = OnDeviceSlicer.slice(
                listOf(
                    ProjectObject(
                        id = "variable",
                        model = model,
                        variableLayerHeights = VariableLayerHeights(
                            listOf(VariableLayerRange(0.25f, 0.75f, 0.1f)),
                        ),
                    ),
                ),
                options,
            )

            assertTrue("baseline=${baseline.layers}", baseline.layers in 90..110)
            assertTrue(
                "baseline=${baseline.layers}, variable=${variable.layers}",
                variable.layers > baseline.layers + 35,
            )
            assertTrue(variable.output.isFile && variable.output.length() > 1_000L)
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun machineMaximumAllowsARealVariableLayerAboveTheOldAndroidCeiling() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        val store = ProjectStore(context)
        projectRoot.deleteRecursively()
        try {
            val source = store.createModelDestination("machine-layer-limit-cube.stl")
            instrumentation.context.assets.open("20mmbox-LF.stl").use { input ->
                source.outputStream().use(input::copyTo)
            }
            val model = inspectModel(source.absolutePath).copy(fileName = source.name)
            val options = SliceOptions()
                .selectPrinter(PrinterProfile.U1_04)
                .copy(
                    layerHeight = 0.2f,
                    firstLayerHeight = 0.2f,
                    bedSizeX = 100f,
                    bedSizeY = 100f,
                    bedPolygon = rectangularBedPolygon(100f, 100f),
                )

            val result = OnDeviceSlicer.slice(
                listOf(
                    ProjectObject(
                        id = "variable-030",
                        model = model,
                        variableLayerHeights = VariableLayerHeights(
                            listOf(VariableLayerRange(0.25f, 0.75f, 0.30f)),
                        ),
                    ),
                ),
                options,
            )
            val gcode = result.output.readText()
            val zValues = gcode.lineSequence()
                .filter { it.startsWith(";Z:") }
                .mapNotNull { it.removePrefix(";Z:").toFloatOrNull() }
                .toList()
            val layerDeltas = zValues.zipWithNext { first, second -> second - first }

            assertTrue(gcode.contains("; min_layer_height = 0.08"))
            assertTrue(gcode.contains("; max_layer_height = 0.32"))
            assertTrue(
                "Expected a real 0.30 mm variable layer, got ${layerDeltas.distinct()}",
                layerDeltas.any { abs(it - 0.30f) < 0.002f },
            )
            assertTrue(result.output.isFile && result.output.length() > 1_000L)
        } finally {
            projectRoot.deleteRecursively()
        }
    }
}
