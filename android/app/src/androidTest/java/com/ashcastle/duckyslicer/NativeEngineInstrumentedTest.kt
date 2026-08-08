package com.ashcastle.duckyslicer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeEngineInstrumentedTest {
    @Test
    fun attachedStlLoadsThroughRustAndCppBridge() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val modelName = InstrumentationRegistry.getArguments()
            .getString("modelName", "model-under-test.stl")
        val model = File(context.filesDir, modelName)

        assertTrue("Model fixture must be copied into ${context.filesDir}", model.isFile)
        assertTrue(NativeEngine.version().startsWith("DuckySlicer native bridge"))

        val result = JSONObject(NativeEngine.inspectStl(model.absolutePath))
        assertTrue(result.optString("error"), result.optBoolean("ok"))
        assertTrue("STL must contain triangles", result.getInt("triangles") > 0)
        assertTrue("STL preview must contain sampled mesh triangles", result.getJSONArray("previewTriangles").length() > 0)
        assertTrue("STL X dimension must be positive", result.getJSONArray("dimensionsMm").getDouble(0) > 0.0)
        assertTrue("STL Y dimension must be positive", result.getJSONArray("dimensionsMm").getDouble(1) > 0.0)
        assertTrue("STL Z dimension must be positive", result.getJSONArray("dimensionsMm").getDouble(2) > 0.0)
    }

    @Test
    fun attachedStlProducesGcodeOnDevice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val modelName = InstrumentationRegistry.getArguments()
            .getString("modelName", "model-under-test.stl")
        val model = File(context.filesDir, modelName)
        var highestProgress = 0

        assertTrue("Model fixture must be copied into ${context.filesDir}", model.isFile)

        val outcome = OnDeviceSlicer.slice(model) { progress ->
            highestProgress = maxOf(highestProgress, progress)
        }

        assertTrue("Slicing must report progress", highestProgress > 0)
        assertTrue("Slicing must produce at least one layer", outcome.layers > 0)
        assertTrue("G-code must be a non-trivial file", outcome.output.length() > 1_000L)
        val gcode = outcome.output.bufferedReader().use { reader ->
            buildString {
                repeat(2_000) {
                    val line = reader.readLine() ?: return@repeat
                    appendLine(line)
                }
            }
        }
        assertTrue("G-code must contain motion commands", gcode.lineSequence().any { it.startsWith("G1 ") })

        val preview = GcodeLayerPreview.fromJson(
            NativeEngine.previewGcode(outcome.output.absolutePath, 0),
        )
        assertTrue("Preview must report all generated layers", preview.layerCount == outcome.layers)
        assertTrue("First layer must contain extrusion paths", preview.segments.isNotEmpty())
    }
}
