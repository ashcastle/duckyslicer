package com.ashcastle.duckyslicer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
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
            val model = ModelInfo.fromJson(
                NativeEngine.inspectStl(source.absolutePath),
                source.absolutePath,
            ).copy(fileName = "variable-layer-cube.stl")
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
}
