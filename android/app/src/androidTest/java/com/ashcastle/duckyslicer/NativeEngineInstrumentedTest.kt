package com.ashcastle.duckyslicer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeEngineInstrumentedTest {
    private fun fixtureModel(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val requestedName = InstrumentationRegistry.getArguments().getString("modelName")
        val modelName = requestedName ?: "20mmbox-LF.stl"
        val destination = File(context.filesDir, modelName)
        if (destination.isFile) return destination

        instrumentation.context.assets.open(modelName).use { input ->
            destination.outputStream().use(input::copyTo)
        }
        return destination
    }

    @Test
    fun projectSurvivesStoreRecreationAndNativeReinspection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "project-store-${System.nanoTime()}")
        val inspector: (File) -> ModelInfo = { model ->
            ModelInfo.fromJson(NativeEngine.inspectStl(model.absolutePath), model.absolutePath)
        }
        try {
            val firstStore = ProjectStore(root, inspector)
            val destination = firstStore.createModelDestination("restored duck.stl")
            fixtureModel().copyTo(destination)
            val savedOptions = SliceOptions()
                .selectPrinter(PrinterProfile.U1_06)
                .selectFilament(FilamentProfile.PETG)
                .selectQuality(QualityProfile.FINE_06)
                .copy(
                    nozzleTemp = 247,
                    fillDensity = 0.23f,
                    outerWallLineWidth = 0.63f,
                    innerWallLineWidth = 0.69f,
                    wallSequence = "outer-inner",
                    gcodeFlavor = "klipper",
                    maxAccelerationTravel = 4_700f,
                )
            firstStore.save(
                ProjectSnapshot(
                    objects = listOf(
                        ProjectObject(
                            id = "restored-object",
                            model = inspector(destination).copy(fileName = "restored duck.stl"),
                            transform = ModelTransform(
                                offsetXmm = 18f,
                                offsetYmm = -11f,
                                rotationZdeg = 30f,
                                scale = 1.4f,
                            ),
                        ),
                    ),
                    selectedObjectId = "restored-object",
                ),
                savedOptions,
            )

            val restoredDocument = ProjectStore(root, inspector).loadProject()
            val restored = restoredDocument.snapshot

            assertEquals("restored-object", restored.selectedObjectId)
            assertEquals("restored duck.stl", restored.selectedObject!!.model.fileName)
            assertEquals(18f, restored.selectedObject!!.transform.offsetXmm)
            assertEquals(-11f, restored.selectedObject!!.transform.offsetYmm)
            assertEquals(30f, restored.selectedObject!!.transform.rotationZdeg)
            assertEquals(1.4f, restored.selectedObject!!.transform.scale)
            assertTrue(restored.selectedObject!!.model.previewTriangles.isNotEmpty())
            assertEquals(247, restoredDocument.sliceOptions?.nozzleTemp)
            assertEquals(0.23f, restoredDocument.sliceOptions?.fillDensity)
            assertEquals(0.63f, restoredDocument.sliceOptions?.outerWallLineWidth)
            assertEquals(0.69f, restoredDocument.sliceOptions?.innerWallLineWidth)
            assertEquals("outer-inner", restoredDocument.sliceOptions?.wallSequence)
            assertEquals("klipper", restoredDocument.sliceOptions?.gcodeFlavor)
            assertEquals(4_700f, restoredDocument.sliceOptions?.maxAccelerationTravel)
        } finally {
            root.deleteRecursively()
        }
    }

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
            .copy(
                fillPattern = "grid",
                topSolidLayers = 7,
                travelSpeed = 420f,
                retractLength = 1.2f,
                fanMinSpeed = 40,
                pressureAdvanceEnabled = true,
                pressureAdvance = 0.035f,
                outerWallLineWidth = 0.64f,
                innerWallLineWidth = 0.68f,
                wallSequence = "outer-inner",
                detectThinWalls = true,
                onlyOneWallOnTop = false,
                preciseOuterWalls = true,
                gcodeFlavor = "marlin2",
                maxSpeedX = 320f,
                maxAccelerationX = 4_200f,
                maxAccelerationTravel = 5_000f,
                maxJerkX = 7f,
            )

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
        assertEquals("grid", restored.slicing.last().fillPattern)
        assertEquals(7, restored.slicing.last().topSolidLayers)
        assertEquals(420f, restored.slicing.last().travelSpeed)
        assertEquals(1.2f, restored.filaments.last().retractLength)
        assertEquals(40, restored.filaments.last().fanMinSpeed)
        assertTrue(restored.filaments.last().pressureAdvanceEnabled)
        assertEquals(0.035f, restored.filaments.last().pressureAdvance)
        assertEquals(0.64f, restored.slicing.last().outerWallLineWidth)
        assertEquals(0.68f, restored.slicing.last().innerWallLineWidth)
        assertEquals("outer-inner", restored.slicing.last().wallSequence)
        assertTrue(restored.slicing.last().detectThinWalls)
        assertEquals(false, restored.slicing.last().onlyOneWallOnTop)
        assertTrue(restored.slicing.last().preciseOuterWalls)
        assertEquals("marlin2", restored.printers.last().gcodeFlavor)
        assertEquals(320f, restored.printers.last().maxSpeedX)
        assertEquals(4_200f, restored.printers.last().maxAccelerationX)
        assertEquals(5_000f, restored.printers.last().maxAccelerationTravel)
        assertEquals(7f, restored.printers.last().maxJerkX)
        assertEquals(null, restored.printers.last().brand)
        assertEquals(null, restored.filaments.last().brand)
        assertEquals(4, JSONObject(file.readText()).getInt("schemaVersion"))
        assertTrue("Saved profiles must stay in app-private storage", file.canonicalPath.startsWith(context.cacheDir.canonicalPath))
        file.delete()
        directory.delete()
    }

    @Test
    fun builtInCatalogCoversAllU1NozzlesAndCommonMaterials() {
        assertEquals(
            listOf(0.2f, 0.4f, 0.6f, 0.8f),
            PrinterProfile.builtIns.filter { it.brand == "Snapmaker" }.map { it.nozzleDiameter },
        )
        assertTrue(FilamentProfile.builtIns.map { it.nativeName }.containsAll(listOf("PLA", "PETG", "ABS", "ASA", "PLA-CF", "PETG-CF", "TPU", "PA-CF")))
        assertEquals(setOf("Custom", "Snapmaker"), PrinterProfile.builtIns.mapNotNull { it.brand }.toSet())
        assertTrue(PrinterProfile.builtIns.contains(PrinterProfile.CUSTOM_CARTESIAN))
        assertTrue(
            FilamentProfile.builtIns.mapNotNull { it.brand }.containsAll(
                listOf("Generic", "Snapmaker", "Prusa", "Creality", "Anycubic", "Elegoo"),
            ),
        )
        assertEquals(QualityProfile.STANDARD_02, QualityProfile.standardFor(0.2f))
        assertEquals(QualityProfile.STANDARD_08, QualityProfile.standardFor(0.8f))
    }

    @Test
    fun bundledOrcaCatalogIsVersionedValidatedAndBroad() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = OrcaProfileCatalog(context).load()

        assertEquals(2, catalog.schemaVersion)
        assertEquals("2c8a5385bc53cbc16211b4dd36ef9963ee185f4a", catalog.sourceRevision)
        assertTrue("The catalog must cover hundreds of printer variants", catalog.printers.size > 700)
        assertTrue("The catalog must include Orca filament presets", catalog.filaments.size > 3_000)
        assertTrue("The catalog must include Orca slicing presets", catalog.slicing.size > 2_000)
        assertTrue(catalog.printers.all(ProfileValidation::printer))
        assertTrue(catalog.filaments.all(ProfileValidation::filament))
        assertTrue(catalog.slicing.all(ProfileValidation::slicing))
        assertTrue(catalog.slicing.any { it.outerWallLineWidth != it.innerWallLineWidth })
        assertTrue(catalog.slicing.map { it.wallSequence }.toSet().containsAll(listOf("inner-outer", "outer-inner")))
        assertTrue(catalog.printers.mapNotNull { it.brand }.containsAll(listOf("Creality", "Prusa", "Anycubic")))
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
        val model = fixtureModel()

        assertTrue("Bundled model fixture must be available", model.isFile)
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
        val model = fixtureModel()
        var highestProgress = 0

        assertTrue("Bundled model fixture must be available", model.isFile)

        val options = SliceOptions()
            .selectPrinter(PrinterProfile.U1_06)
            .selectFilament(FilamentProfile.PETG)
            .selectQuality(QualityProfile.DRAFT_06)
            .copy(
                topSolidLayers = 6,
                bottomSolidLayers = 5,
                fillPattern = "grid",
                travelSpeed = 420f,
                firstLayerSpeed = 35f,
                retractLength = 1.1f,
                retractSpeed = 38f,
                skirtLoops = 2,
                skirtDistance = 7f,
                perimeters = 3,
                outerWallLineWidth = 0.62f,
                innerWallLineWidth = 0.68f,
                wallSequence = "outer-inner",
                detectThinWalls = true,
                detectOverhangWalls = false,
                onlyOneWallOnTop = false,
                preciseOuterWalls = true,
            )
        val outcome = OnDeviceSlicer.slice(model, options) { progress ->
            highestProgress = maxOf(highestProgress, progress)
        }

        assertTrue("Slicing must report progress", highestProgress > 0)
        assertTrue("Slicing must produce at least one layer", outcome.layers > 0)
        assertTrue("G-code must be a non-trivial file", outcome.output.length() > 1_000L)
        val gcode = outcome.output.readText()
        assertTrue("G-code must contain motion commands", gcode.lineSequence().any { it.startsWith("G1 ") })
        assertTrue("Orca must emit distinct inner-wall regions", gcode.contains(";TYPE:Inner wall"))
        assertTrue("Orca must emit distinct outer-wall regions", gcode.contains(";TYPE:Outer wall"))
        assertTrue("Printer nozzle must reach G-code", gcode.contains("; nozzle_diameter = 0.6"))
        assertTrue("Filament type must reach G-code", gcode.contains("; filament_type = PETG"))
        assertTrue("First layer nozzle temperature must reach G-code", gcode.contains("M104 S250"))
        assertTrue("Filament nozzle temperature must reach G-code", gcode.contains("M104 S245"))
        assertTrue("Filament bed temperature must reach G-code", gcode.contains("M190 S70"))
        assertTrue("Filament flow ratio must reach G-code", gcode.contains("; filament_flow_ratio = 0.95"))
        assertTrue("Maximum flow must reach G-code", gcode.contains("; filament_max_volumetric_speed = 10"))
        assertTrue("Layer height must reach G-code", gcode.contains("; layer_height = 0.4"))
        assertTrue("First layer height must reach G-code", gcode.contains("; first_layer_height = 0.350"))
        assertTrue("Top shell layers must reach G-code", gcode.contains("; top_shell_layers = 6"))
        assertTrue("Bottom shell layers must reach G-code", gcode.contains("; bottom_shell_layers = 5"))
        assertTrue("Infill pattern must reach G-code", gcode.contains("; sparse_infill_pattern = grid"))
        assertTrue("Travel speed must reach G-code", gcode.contains("; travel_speed = 420"))
        assertTrue("First layer speed must reach G-code", gcode.contains("; initial_layer_speed = 35"))
        assertTrue("Retraction length must reach G-code", gcode.contains("; retraction_length = 1.1"))
        assertTrue("Skirt loops must reach G-code", gcode.contains("; skirt_loops = 2"))
        assertTrue("Wall count must reach Orca", gcode.contains("; wall_loops = 3"))
        assertTrue("Outer-wall width must remain independent", gcode.contains("; outer_wall_line_width = 0.62"))
        assertTrue("Inner-wall width must remain independent", gcode.contains("; inner_wall_line_width = 0.68"))
        assertTrue("Wall order must reach Orca", gcode.contains("; wall_sequence = outer wall/inner wall"))
        assertTrue("Thin-wall detection must reach Orca", gcode.contains("; detect_thin_wall = 1"))
        assertTrue("Overhang-wall detection must reach Orca", gcode.contains("; detect_overhang_wall = 0"))
        assertTrue("Top-surface wall rule must reach Orca", gcode.contains("; only_one_wall_top = 0"))
        assertTrue("Outer-wall precision must reach Orca", gcode.contains("; precise_outer_wall = 1"))

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
        assertTrue("Inner-wall paths must be classified", preview.roleSegmentCounts[1] > 0)
        assertTrue("Visible top/bottom surfaces must be classified", preview.roleSegmentCounts[3] > 0)
        assertTrue("Internal solid infill must stay separate", preview.roleSegmentCounts[4] > 0)
        assertTrue("Preview must report a positive first layer Z", preview.minZMm > 0f)
        assertTrue("Multi-layer preview must span upward in Z", preview.maxZMm > preview.minZMm)
    }

    @Test
    fun multipleObjectsReachTheOrcaProjectAndSliceTogether() {
        val modelFile = fixtureModel()
        val model = ModelInfo.fromJson(
            NativeEngine.inspectStl(modelFile.absolutePath),
            modelFile.absolutePath,
        )
        val runtime = NativeLibrary()
        try {
            assertTrue(runtime.loadModel(modelFile.absolutePath))
            assertTrue(runtime.addModel(modelFile.absolutePath))
            assertEquals(
                "Orca must expose one XYZ bounding box per loaded object",
                6,
                runtime.getObjectBoundingBoxes().size,
            )
        } finally {
            runtime.clearModel()
        }

        val outcome = OnDeviceSlicer.slice(
            listOf(
                ProjectObject("left", model, ModelTransform(offsetXmm = -18f)),
                ProjectObject("right", model, ModelTransform(offsetXmm = 18f)),
            ),
            SliceOptions().selectQuality(QualityProfile.DRAFT),
        )

        assertTrue("A multi-object project must produce G-code", outcome.output.length() > 1_000L)
        assertTrue("Both objects must contribute layers", outcome.layers > 0)
        val gcode = outcome.output.readText()
        assertTrue(gcode.contains(";TYPE:Outer wall"))
        assertTrue(gcode.contains(";TYPE:Inner wall"))
    }

    @Test
    fun customPrinterGeometryAndMotionReachOrca() {
        val model = fixtureModel()
        val customPrinter = PrinterProfile.CUSTOM_CARTESIAN.copy(
            bedSizeX = 180f,
            bedSizeY = 190f,
            maxPrintHeight = 180f,
            gcodeFlavor = "marlin2",
            maxSpeedX = 240f,
            maxSpeedY = 250f,
            maxAccelerationX = 4_200f,
            maxAccelerationY = 4_300f,
            maxAccelerationExtruding = 3_100f,
            maxAccelerationTravel = 4_000f,
        )
        val options = SliceOptions()
            .selectPrinter(customPrinter)
            .selectFilament(FilamentProfile.GENERIC_PLA)
            .selectQuality(QualityProfile.STANDARD)
        val outcome = OnDeviceSlicer.slice(model, options)
        val gcode = outcome.output.readText()
        val printableArea = gcode.lineSequence().firstOrNull { it.startsWith("; printable_area =") }.orEmpty()

        assertTrue("Custom bed width must reach Orca", printableArea.contains("180"))
        assertTrue("Custom bed depth must reach Orca", printableArea.contains("190"))
        assertTrue("Custom height must reach Orca", gcode.contains("; printable_height = 180"))
        assertTrue("Custom X speed must reach Orca", gcode.contains("; machine_max_speed_x = 240,240"))
        assertTrue("Custom Y speed must reach Orca", gcode.contains("; machine_max_speed_y = 250,250"))
        assertTrue("Custom X acceleration must reach Orca", gcode.contains("; machine_max_acceleration_x = 4200,4200"))
        assertTrue("Custom Y acceleration must reach Orca", gcode.contains("; machine_max_acceleration_y = 4300,4300"))
        assertTrue("Custom G-code flavor must reach Orca", gcode.contains("; gcode_flavor = marlin2"))
    }

    @Test
    fun marlinAndKlipperFirmwareContractsReachOrca() {
        val model = fixtureModel()
        for (flavor in listOf("marlin", "marlin2", "klipper")) {
            val printer = PrinterProfile.CUSTOM_CARTESIAN.copy(
                id = "contract-$flavor",
                name = "Contract $flavor",
                gcodeFlavor = flavor,
                maxAccelerationExtruding = 3_000f,
                maxAccelerationTravel = 4_000f,
            )
            val outcome = OnDeviceSlicer.slice(
                model,
                SliceOptions()
                    .selectPrinter(printer)
                    .selectFilament(FilamentProfile.GENERIC_PLA)
                    .selectQuality(QualityProfile.DRAFT),
            )
            val gcode = outcome.output.readText()

            assertTrue("$flavor metadata must reach Orca", gcode.contains("; gcode_flavor = $flavor"))
            assertTrue("$flavor output must contain extrusion", gcode.contains(";TYPE:Outer wall"))
            if (flavor == "klipper") {
                assertTrue(
                    "Klipper must use its native acceleration command",
                    gcode.contains("SET_VELOCITY_LIMIT ACCEL="),
                )
            } else {
                assertTrue("Marlin must use M204 acceleration", gcode.lineSequence().any { it.startsWith("M204 ") })
            }
        }
    }

}
