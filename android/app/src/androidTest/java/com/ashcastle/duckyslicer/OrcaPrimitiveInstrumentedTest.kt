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
class OrcaPrimitiveInstrumentedTest {
    @Test
    fun inheritedOrcaGeneratorsCreateEveryMobileShapeAndRealGcode() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        val store = ProjectStore(context)
        projectRoot.deleteRecursively()
        try {
            OrcaPrimitive.entries.forEach { primitive ->
                val staging = store.createModelImportStaging()
                try {
                    val generated = SlicerProcessClient.createPrimitive(primitive, 20f, staging)
                    val info = inspectModel(generated.file.absolutePath)
                    assertTrue("$primitive must contain triangles", info.triangles >= 12)
                    assertTrue("$primitive must be finite", info.dimensions.all(Double::isFinite))
                    assertEquals(20.0, info.dimensions[0], 0.25)
                    assertEquals(20.0, info.dimensions[1], 0.25)
                    val expectedHeight = when (primitive) {
                        OrcaPrimitive.DISC -> 0.2
                        OrcaPrimitive.TORUS -> 5.0
                        else -> 20.0
                    }
                    assertEquals(expectedHeight, info.dimensions[2], 0.25)

                    if (primitive == OrcaPrimitive.CUBE) {
                        val installed = store.installImportedModel(
                            generated.file,
                            "cube.stl",
                        )
                        val sliceOptions = SliceOptions().copy(
                            layerHeight = 0.2f,
                            firstLayerHeight = 0.2f,
                            bedSizeX = 100f,
                            bedSizeY = 100f,
                            bedPolygon = rectangularBedPolygon(100f, 100f),
                        )
                        val sliced = OnDeviceSlicer.slice(
                            File(installed.localPath),
                            sliceOptions,
                        )
                        assertTrue("layers=${sliced.layers}", sliced.layers in 90..110)
                        assertTrue(sliced.output.length() > 10_000L)

                        val axisTransform = ModelTransform(
                            scale = 1f,
                            scaleY = 1.5f,
                            scaleZ = 2f,
                        )
                        val transformed = File(staging, "cube-axis-scaled.stl")
                        val transformResult = JSONObject(
                            NativeEngine.transformStl(
                                installed.localPath,
                                transformed.absolutePath,
                                axisTransform.toJson(0f, 0f),
                            ),
                        )
                        assertTrue(
                            "Independent-axis STL transform failed: ${transformResult.optString("error")}",
                            transformResult.optBoolean("ok"),
                        )
                        val transformedInfo = inspectModel(transformed.absolutePath)
                        assertEquals(20.0, transformedInfo.dimensions[0], 0.25)
                        assertEquals(30.0, transformedInfo.dimensions[1], 0.25)
                        assertEquals(40.0, transformedInfo.dimensions[2], 0.25)

                        val axisSliced = OnDeviceSlicer.slice(
                            File(installed.localPath),
                            sliceOptions,
                            axisTransform,
                        )
                        assertTrue("axis layers=${axisSliced.layers}", axisSliced.layers in 190..210)
                        assertTrue(axisSliced.output.length() > 10_000L)
                    }
                    if (primitive == OrcaPrimitive.CYLINDER) {
                        val installed = store.installImportedModel(
                            generated.file,
                            "cylinder.stl",
                        )
                        val baseOptions = SliceOptions().copy(
                            layerHeight = 0.2f,
                            firstLayerHeight = 0.2f,
                            bedSizeX = 100f,
                            bedSizeY = 100f,
                            bedPolygon = rectangularBedPolygon(100f, 100f),
                            resolution = 0.01f,
                        )
                        val linearGcode = OnDeviceSlicer.slice(
                            File(installed.localPath),
                            baseOptions.copy(gcodeSettings = GcodeSettings(arcFitting = false)),
                        ).output.readText()
                        val arcGcode = OnDeviceSlicer.slice(
                            File(installed.localPath),
                            baseOptions.copy(gcodeSettings = GcodeSettings(arcFitting = true)),
                        ).output.readText()
                        fun String.extrusionArcCount() = lineSequence().count { line ->
                            (line.startsWith("G2 ") || line.startsWith("G3 ")) &&
                                line.split(' ').any { it.startsWith("E") }
                        }
                        assertEquals(0, linearGcode.extrusionArcCount())
                        assertTrue(
                            "A curved inherited primitive must emit extrusion arcs when enabled",
                            arcGcode.extrusionArcCount() > 0,
                        )
                        assertTrue(arcGcode.contains("; enable_arc_fitting = 1"))
                    }
                } finally {
                    staging.deleteRecursively()
                }
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }
}
