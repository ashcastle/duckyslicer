package com.ashcastle.duckyslicer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeEngineInstrumentedTest {
    @Test
    fun userProfilesRoundTripInPrivateStorage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "profile-store-test").apply { mkdirs() }
        val file = File(directory, "profiles.json").also { it.delete() }
        val store = ProfileStore(file)
        val edited = SliceOptions()
            .selectPrinter(PrinterProfile.U1_06)
            .selectFilament(FilamentProfile.PETG)
            .copy(nozzleTemp = 248, firstLayerNozzleTemp = 253)
            .selectQuality(QualityProfile.FINE_06)
            .copy(fillDensity = 0.22f, supportEnabled = true)

        val printer = store.savePrinter("Workshop U1", edited)
        val filament = store.saveFilament("My PETG", edited)
        val slicing = store.saveSlicing("Fine supports", edited)
        val restored = ProfileStore(file).load()

        assertEquals(printer, restored.printers.last())
        assertEquals(filament, restored.filaments.last())
        assertEquals(slicing, restored.slicing.last())
        assertEquals(248, restored.filaments.last().nozzleTemp)
        assertEquals(253, restored.filaments.last().firstLayerNozzleTemp)
        assertEquals(0.22f, restored.slicing.last().fillDensity)
        assertTrue(restored.slicing.last().supportEnabled)
        assertEquals(null, restored.printers.last().brand)
        assertEquals(null, restored.filaments.last().brand)
        assertTrue("Saved profiles must stay in app-private storage", file.canonicalPath.startsWith(context.cacheDir.canonicalPath))
        file.delete()
        directory.delete()
    }

    @Test
    fun builtInCatalogCoversAllU1NozzlesAndCommonMaterials() {
        assertEquals(listOf(0.2f, 0.4f, 0.6f, 0.8f), PrinterProfile.builtIns.map { it.nozzleDiameter })
        assertTrue(FilamentProfile.builtIns.map { it.nativeName }.containsAll(listOf("PLA", "PETG", "ABS", "ASA", "PLA-CF", "PETG-CF", "TPU", "PA-CF")))
        assertEquals(setOf("Snapmaker"), PrinterProfile.builtIns.mapNotNull { it.brand }.toSet())
        assertEquals(setOf("Snapmaker"), FilamentProfile.builtIns.mapNotNull { it.brand }.toSet())
        assertEquals(QualityProfile.STANDARD_02, QualityProfile.standardFor(0.2f))
        assertEquals(QualityProfile.STANDARD_08, QualityProfile.standardFor(0.8f))
    }

    @Test
    fun previewRenderPlanKeepsEveryToolpathRoleWithinItsBudget() {
        val segmentCount = 8_000
        val segments = FloatArray(segmentCount * GcodeLayerPreview.SEGMENT_STRIDE)
        val roleCounts = IntArray(GcodeLayerPreview.ROLE_COUNT)
        repeat(segmentCount) { index ->
            val offset = index * GcodeLayerPreview.SEGMENT_STRIDE
            val role = index % GcodeLayerPreview.ROLE_COUNT
            segments[offset] = index.toFloat()
            segments[offset + 1] = role.toFloat()
            segments[offset + 2] = index + 1f
            segments[offset + 3] = role.toFloat()
            segments[offset + 4] = 0.2f
            segments[offset + 5] = role.toFloat()
            roleCounts[role] += 1
        }
        val preview = GcodeLayerPreview(0, 0, 1, 0.2f, 0.2f, segments, roleCounts)
        val plan = preview.buildRenderPlan(segmentBudget = 450)
        val selectedRoles = plan.segmentOffsets.map { segments[it + 5].toInt() }.toSet()

        assertEquals((0 until GcodeLayerPreview.ROLE_COUNT).toSet(), selectedRoles)
        assertTrue(plan.segmentOffsets.size <= 450 + GcodeLayerPreview.ROLE_COUNT * 26)
        assertEquals(plan.segmentOffsets.size, plan.connectsToPrevious.size)
    }

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
            .selectPrinter(PrinterProfile.U1_06)
            .selectFilament(FilamentProfile.PETG)
            .selectQuality(QualityProfile.DRAFT_06)
        val outcome = OnDeviceSlicer.slice(model, options) { progress ->
            highestProgress = maxOf(highestProgress, progress)
        }

        assertTrue("Slicing must report progress", highestProgress > 0)
        assertTrue("Slicing must produce at least one layer", outcome.layers > 0)
        assertTrue("G-code must be a non-trivial file", outcome.output.length() > 1_000L)
        val gcode = outcome.output.readText()
        assertTrue("G-code must contain motion commands", gcode.lineSequence().any { it.startsWith("G1 ") })
        assertTrue("Printer nozzle must reach G-code", gcode.contains("; nozzle_diameter = 0.6"))
        assertTrue("Filament type must reach G-code", gcode.contains("; filament_type = PETG"))
        assertTrue("First layer nozzle temperature must reach G-code", gcode.contains("M104 S250"))
        assertTrue("Filament nozzle temperature must reach G-code", gcode.contains("M104 S245"))
        assertTrue("Filament bed temperature must reach G-code", gcode.contains("M190 S70"))
        assertTrue("Filament flow ratio must reach G-code", gcode.contains("; filament_flow_ratio = 0.95"))
        assertTrue("Maximum flow must reach G-code", gcode.contains("; filament_max_volumetric_speed = 10"))
        assertTrue("Layer height must reach G-code", gcode.contains("; layer_height = 0.4"))
        assertTrue("First layer height must reach G-code", gcode.contains("; first_layer_height = 0.350"))

        val preview = GcodeLayerPreview.fromJson(
            NativeEngine.previewGcodeRange(outcome.output.absolutePath, 0, Int.MAX_VALUE),
        )
        assertTrue("Preview must report generated layers", preview.layerCount > 0)
        assertTrue("Preview must include the first layer", preview.startLayer == 0)
        assertTrue("Preview must include the final G-code layer", preview.endLayer == preview.layerCount - 1)
        assertTrue("Full preview must contain extrusion paths", preview.segments.isNotEmpty())
        assertEquals(0, preview.segments.size % GcodeLayerPreview.SEGMENT_STRIDE)
        assertTrue("Segment Z coordinates must be positive", preview.segments[4] > 0f)
        assertTrue("Outer-wall paths must be classified", preview.roleSegmentCounts[0] > 0)
        assertTrue("Preview must report a positive first layer Z", preview.minZMm > 0f)
        assertTrue("Multi-layer preview must span upward in Z", preview.maxZMm > preview.minZMm)
    }
}
