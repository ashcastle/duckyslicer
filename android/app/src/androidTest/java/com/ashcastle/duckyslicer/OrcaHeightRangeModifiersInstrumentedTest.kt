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
class OrcaHeightRangeModifiersInstrumentedTest {
    @Test
    fun selectedHeightUsesRealOrcaLayerConfigWithoutChangingTheRestOfTheObject() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        val store = ProjectStore(context)
        projectRoot.deleteRecursively()
        try {
            val source = store.createModelDestination("height-range-cube.stl")
            instrumentation.context.assets.open("20mmbox-LF.stl").use { input ->
                source.outputStream().use(input::copyTo)
            }
            val model = inspectModel(source.absolutePath).copy(fileName = source.name)
            val options = SliceOptions().copy(
                layerHeight = 0.2f,
                firstLayerHeight = 0.2f,
                fillDensity = 0.1f,
                bedSizeX = 100f,
                bedSizeY = 100f,
                bedPolygon = rectangularBedPolygon(100f, 100f),
            )
            val baseline = OnDeviceSlicer.slice(
                listOf(ProjectObject("height-range-baseline", model)),
                options,
            )
            val modified = OnDeviceSlicer.slice(
                listOf(
                    ProjectObject(
                        id = "height-range-modified",
                        model = model,
                        heightRangeModifiers = HeightRangeModifiers(
                            listOf(
                                HeightRangeModifier(
                                    startZmm = 2f,
                                    endZmm = 12f,
                                    overrides = ObjectProcessOverrides(
                                        layerHeightMm = 0.1f,
                                        sparseInfillDensityPercent = 80f,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                options,
            )
            val zValues = modified.output.readLines()
                .asSequence()
                .filter { it.startsWith(";Z:") }
                .mapNotNull { it.removePrefix(";Z:").toFloatOrNull() }
                .toList()
            val rangedDeltas = zValues.zipWithNext()
                .filter { (first, second) -> first >= 2f && second <= 12.01f }
                .map { (first, second) -> second - first }
            val upperDeltas = zValues.zipWithNext()
                .filter { (first, second) -> first >= 13f && second <= 19.5f }
                .map { (first, second) -> second - first }

            assertTrue("baseline=${baseline.layers}", baseline.layers in 90..110)
            assertTrue(
                "baseline=${baseline.layers}, modified=${modified.layers}",
                modified.layers > baseline.layers + 40,
            )
            assertTrue(
                "Expected 0.10 mm layers in selected range: $rangedDeltas",
                rangedDeltas.count { abs(it - 0.1f) < 0.002f } >= 80,
            )
            assertTrue(
                "Expected 0.20 mm layers above selected range: $upperDeltas",
                upperDeltas.isNotEmpty() && upperDeltas.all { abs(it - 0.2f) < 0.002f },
            )
            assertTrue(modified.output.length() > baseline.output.length() * 1.25)
        } finally {
            projectRoot.deleteRecursively()
        }
    }
}
