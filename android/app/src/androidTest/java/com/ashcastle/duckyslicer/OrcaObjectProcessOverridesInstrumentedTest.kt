package com.ashcastle.duckyslicer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrcaObjectProcessOverridesInstrumentedTest {
    @Test
    fun oneObjectCanOverrideTheSharedProcessThroughOrcaModelConfig() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        val store = ProjectStore(context)
        projectRoot.deleteRecursively()
        try {
            val source = store.createModelDestination("object-settings-box.stl")
            instrumentation.context.assets.open("20mmbox-LF.stl").use { input ->
                source.outputStream().use(input::copyTo)
            }
            val model = ModelInfo.fromJson(
                NativeEngine.inspectStl(source.absolutePath),
                source.absolutePath,
            ).copy(fileName = "object-settings-box.stl")
            val options = SliceOptions().copy(
                layerHeight = 0.2f,
                firstLayerHeight = 0.2f,
                perimeters = 2,
                fillDensity = 0.2f,
                bedSizeX = 100f,
                bedSizeY = 100f,
                bedPolygon = rectangularBedPolygon(100f, 100f),
            )
            fun objectAt(id: String, x: Float, overrides: ObjectProcessOverrides) = ProjectObject(
                id = id,
                model = model,
                transform = ModelTransform(offsetXmm = x),
                processOverrides = overrides,
            )

            val baseline = OnDeviceSlicer.slice(
                listOf(
                    objectAt("left", -15f, ObjectProcessOverrides()),
                    objectAt("right", 15f, ObjectProcessOverrides()),
                ),
                options,
            )
            val overridden = OnDeviceSlicer.slice(
                listOf(
                    objectAt("left", -15f, ObjectProcessOverrides()),
                    objectAt(
                        "right",
                        15f,
                        ObjectProcessOverrides(
                            layerHeightMm = 0.1f,
                            wallLoops = 5,
                            topShellLayers = 7,
                            bottomShellLayers = 6,
                            sparseInfillDensityPercent = 0f,
                            outerWallSpeedMmS = 35f,
                            innerWallSpeedMmS = 55f,
                            sparseInfillSpeedMmS = 70f,
                            supportEnabled = false,
                        ),
                    ),
                ),
                options,
            )

            assertTrue("baseline=${baseline.layers}", baseline.layers in 90..110)
            assertTrue(
                "baseline=${baseline.layers}, overridden=${overridden.layers}",
                overridden.layers > baseline.layers + 80,
            )
            val gcode = overridden.output.readText()
            assertTrue(gcode.contains(";TYPE:Outer wall"))
            assertTrue(gcode.contains(";TYPE:Inner wall"))
            assertTrue(overridden.output.length() > 10_000L)
        } finally {
            projectRoot.deleteRecursively()
        }
    }
}
