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

        // The physical-device command in README.md copies this external fixture into filesDir.
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

        val options = SliceOptions()
            .selectFilament(FilamentProfile.PETG)
            .selectQuality(QualityProfile.DRAFT)
        val outcome = OnDeviceSlicer.slice(model, options) { progress ->
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
        assertTrue("Filament nozzle temperature must reach G-code", gcode.contains("M104 S245"))
        assertTrue("Filament bed temperature must reach G-code", gcode.contains("M190 S75"))

        val preview = GcodeLayerPreview.fromJson(
            NativeEngine.previewGcodeRange(outcome.output.absolutePath, 0, Int.MAX_VALUE),
        )
        assertTrue("Preview must report generated layers", preview.layerCount > 0)
        assertTrue("Preview must include the first layer", preview.startLayer == 0)
        assertTrue("Preview must include the final G-code layer", preview.endLayer == preview.layerCount - 1)
        assertTrue("Full preview must contain extrusion paths", preview.segments.isNotEmpty())
        assertTrue("Full preview must contain segment Z coordinates", preview.segments.size % 5 == 0)
        assertTrue("Full preview must span upward in Z", preview.maxZMm >= preview.minZMm)
    }
}
