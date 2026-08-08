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
                    topSurfaceLineWidth = 0.57f,
                    sparseInfillLineWidth = 0.72f,
                    internalSolidInfillLineWidth = 0.66f,
                    supportLineWidth = 0.58f,
                    innerWallSpeed = 175f,
                    sparseInfillSpeed = 205f,
                    internalSolidInfillSpeed = 165f,
                    topSurfaceSpeed = 95f,
                    supportSpeed = 80f,
                    bridgeSpeed = 42f,
                    gapInfillSpeed = 132f,
                    firstLayerInfillSpeed = 62f,
                    supportInterfaceSpeed = 53f,
                    bridgeFlowRatio = 0.91f,
                    internalBridgeFlowRatio = 0.96f,
                    topSurfaceFlowRatio = 0.97f,
                    bottomSurfaceFlowRatio = 0.98f,
                    topShellThickness = 0.8f,
                    bottomShellThickness = 0.7f,
                    supportInterfaceTopLayers = 4,
                    supportInterfaceBottomLayers = 2,
                    supportInterfaceSpacing = 0.24f,
                    supportBottomInterfaceSpacing = 0.28f,
                    supportTopZDistance = 0.18f,
                    supportBottomZDistance = 0.22f,
                    supportObjectXYDistance = 0.4f,
                    initialLayerLineWidth = 0.74f,
                    defaultAcceleration = 4_200f,
                    outerWallAcceleration = 2_100f,
                    innerWallAcceleration = 3_800f,
                    topSurfaceAcceleration = 1_300f,
                    travelAcceleration = 4_700f,
                    firstLayerAcceleration = 650f,
                    wallGenerator = "classic",
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
            assertEquals(0.57f, restoredDocument.sliceOptions?.topSurfaceLineWidth)
            assertEquals(0.72f, restoredDocument.sliceOptions?.sparseInfillLineWidth)
            assertEquals(0.66f, restoredDocument.sliceOptions?.internalSolidInfillLineWidth)
            assertEquals(0.58f, restoredDocument.sliceOptions?.supportLineWidth)
            assertEquals(175f, restoredDocument.sliceOptions?.innerWallSpeed)
            assertEquals(205f, restoredDocument.sliceOptions?.sparseInfillSpeed)
            assertEquals(165f, restoredDocument.sliceOptions?.internalSolidInfillSpeed)
            assertEquals(95f, restoredDocument.sliceOptions?.topSurfaceSpeed)
            assertEquals(80f, restoredDocument.sliceOptions?.supportSpeed)
            assertEquals(42f, restoredDocument.sliceOptions?.bridgeSpeed)
            assertEquals(132f, restoredDocument.sliceOptions?.gapInfillSpeed)
            assertEquals(62f, restoredDocument.sliceOptions?.firstLayerInfillSpeed)
            assertEquals(53f, restoredDocument.sliceOptions?.supportInterfaceSpeed)
            assertEquals(0.91f, restoredDocument.sliceOptions?.bridgeFlowRatio)
            assertEquals(0.8f, restoredDocument.sliceOptions?.topShellThickness)
            assertEquals(4, restoredDocument.sliceOptions?.supportInterfaceTopLayers)
            assertEquals(0.24f, restoredDocument.sliceOptions?.supportInterfaceSpacing)
            assertEquals(0.74f, restoredDocument.sliceOptions?.initialLayerLineWidth)
            assertEquals(4_200f, restoredDocument.sliceOptions?.defaultAcceleration)
            assertEquals(2_100f, restoredDocument.sliceOptions?.outerWallAcceleration)
            assertEquals(3_800f, restoredDocument.sliceOptions?.innerWallAcceleration)
            assertEquals(1_300f, restoredDocument.sliceOptions?.topSurfaceAcceleration)
            assertEquals(4_700f, restoredDocument.sliceOptions?.travelAcceleration)
            assertEquals(650f, restoredDocument.sliceOptions?.firstLayerAcceleration)
            assertEquals("classic", restoredDocument.sliceOptions?.wallGenerator)
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
                fillPattern = "crosshatch",
                topSurfacePattern = "monotonic",
                bottomSurfacePattern = "concentric",
                internalSolidInfillPattern = "rectilinear",
                topSolidLayers = 7,
                travelSpeed = 420f,
                retractLength = 1.2f,
                fanMinSpeed = 40,
                pressureAdvanceEnabled = true,
                pressureAdvance = 0.035f,
                outerWallLineWidth = 0.64f,
                innerWallLineWidth = 0.68f,
                topSurfaceLineWidth = 0.56f,
                sparseInfillLineWidth = 0.72f,
                internalSolidInfillLineWidth = 0.66f,
                supportLineWidth = 0.58f,
                innerWallSpeed = 180f,
                sparseInfillSpeed = 220f,
                internalSolidInfillSpeed = 170f,
                topSurfaceSpeed = 100f,
                supportSpeed = 85f,
                bridgeSpeed = 44f,
                gapInfillSpeed = 134f,
                firstLayerInfillSpeed = 64f,
                supportInterfaceSpeed = 54f,
                overhangSpeedEnabled = false,
                overhangSpeed1 = 76f,
                overhangSpeed1Percent = true,
                bridgeFlowRatio = 0.92f,
                internalBridgeFlowRatio = 0.95f,
                topSurfaceFlowRatio = 0.97f,
                bottomSurfaceFlowRatio = 0.98f,
                topShellThickness = 0.85f,
                bottomShellThickness = 0.75f,
                supportInterfaceTopLayers = 4,
                supportInterfaceBottomLayers = 2,
                supportInterfaceSpacing = 0.25f,
                supportBottomInterfaceSpacing = 0.3f,
                supportTopZDistance = 0.18f,
                supportBottomZDistance = 0.22f,
                supportObjectXYDistance = 0.4f,
                supportBasePattern = "rectilinear-grid",
                supportInterfacePattern = "rectilinear_interlaced",
                supportStyle = "snug",
                initialLayerLineWidth = 0.73f,
                seamPosition = "nearest",
                ironingType = "top",
                ironingPattern = "concentric",
                ironingFlow = 12f,
                ironingSpacing = 0.16f,
                ironingSpeed = 26f,
                defaultAcceleration = 4_000f,
                outerWallAcceleration = 2_000f,
                innerWallAcceleration = 3_500f,
                topSurfaceAcceleration = 1_200f,
                travelAcceleration = 4_500f,
                firstLayerAcceleration = 600f,
                wallGenerator = "classic",
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
        assertEquals("crosshatch", restored.slicing.last().fillPattern)
        assertEquals("monotonic", restored.slicing.last().topSurfacePattern)
        assertEquals("concentric", restored.slicing.last().bottomSurfacePattern)
        assertEquals("rectilinear", restored.slicing.last().internalSolidInfillPattern)
        assertEquals(false, restored.slicing.last().overhangSpeedEnabled)
        assertEquals(76f, restored.slicing.last().overhangSpeed1)
        assertTrue(restored.slicing.last().overhangSpeed1Percent)
        assertEquals("rectilinear-grid", restored.slicing.last().supportBasePattern)
        assertEquals("rectilinear_interlaced", restored.slicing.last().supportInterfacePattern)
        assertEquals("snug", restored.slicing.last().supportStyle)
        assertEquals("nearest", restored.slicing.last().seamPosition)
        assertEquals("top", restored.slicing.last().ironingType)
        assertEquals("concentric", restored.slicing.last().ironingPattern)
        assertEquals(12f, restored.slicing.last().ironingFlow)
        assertEquals(0.16f, restored.slicing.last().ironingSpacing)
        assertEquals(26f, restored.slicing.last().ironingSpeed)
        assertEquals(7, restored.slicing.last().topSolidLayers)
        assertEquals(420f, restored.slicing.last().travelSpeed)
        assertEquals(1.2f, restored.filaments.last().retractLength)
        assertEquals(40, restored.filaments.last().fanMinSpeed)
        assertTrue(restored.filaments.last().pressureAdvanceEnabled)
        assertEquals(0.035f, restored.filaments.last().pressureAdvance)
        assertEquals(0.64f, restored.slicing.last().outerWallLineWidth)
        assertEquals(0.68f, restored.slicing.last().innerWallLineWidth)
        assertEquals(0.56f, restored.slicing.last().topSurfaceLineWidth)
        assertEquals(0.72f, restored.slicing.last().sparseInfillLineWidth)
        assertEquals(0.66f, restored.slicing.last().internalSolidInfillLineWidth)
        assertEquals(0.58f, restored.slicing.last().supportLineWidth)
        assertEquals(180f, restored.slicing.last().innerWallSpeed)
        assertEquals(220f, restored.slicing.last().sparseInfillSpeed)
        assertEquals(170f, restored.slicing.last().internalSolidInfillSpeed)
        assertEquals(100f, restored.slicing.last().topSurfaceSpeed)
        assertEquals(85f, restored.slicing.last().supportSpeed)
        assertEquals(44f, restored.slicing.last().bridgeSpeed)
        assertEquals(134f, restored.slicing.last().gapInfillSpeed)
        assertEquals(64f, restored.slicing.last().firstLayerInfillSpeed)
        assertEquals(54f, restored.slicing.last().supportInterfaceSpeed)
        assertEquals(0.92f, restored.slicing.last().bridgeFlowRatio)
        assertEquals(0.85f, restored.slicing.last().topShellThickness)
        assertEquals(4, restored.slicing.last().supportInterfaceTopLayers)
        assertEquals(0.25f, restored.slicing.last().supportInterfaceSpacing)
        assertEquals(0.73f, restored.slicing.last().initialLayerLineWidth)
        assertEquals(4_000f, restored.slicing.last().defaultAcceleration)
        assertEquals(2_000f, restored.slicing.last().outerWallAcceleration)
        assertEquals(3_500f, restored.slicing.last().innerWallAcceleration)
        assertEquals(1_200f, restored.slicing.last().topSurfaceAcceleration)
        assertEquals(4_500f, restored.slicing.last().travelAcceleration)
        assertEquals(600f, restored.slicing.last().firstLayerAcceleration)
        assertEquals("classic", restored.slicing.last().wallGenerator)
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
        assertEquals(8, JSONObject(file.readText()).getInt("schemaVersion"))
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

        assertEquals(6, catalog.schemaVersion)
        assertEquals("2c8a5385bc53cbc16211b4dd36ef9963ee185f4a", catalog.sourceRevision)
        assertTrue("The catalog must cover hundreds of printer variants", catalog.printers.size > 700)
        assertTrue("The catalog must include Orca filament presets", catalog.filaments.size > 3_000)
        assertTrue("The catalog must include Orca slicing presets", catalog.slicing.size > 2_000)
        assertTrue(catalog.printers.all(ProfileValidation::printer))
        assertTrue(catalog.filaments.all(ProfileValidation::filament))
        assertTrue(catalog.slicing.all(ProfileValidation::slicing))
        assertTrue(catalog.slicing.any { it.outerWallLineWidth != it.innerWallLineWidth })
        assertTrue(catalog.slicing.any { it.topSurfaceLineWidth != it.internalSolidInfillLineWidth })
        assertTrue(catalog.slicing.any { it.printSpeed != it.innerWallSpeed })
        assertTrue(catalog.slicing.any { it.sparseInfillSpeed != it.internalSolidInfillSpeed })
        assertTrue(catalog.slicing.any { it.bridgeSpeed != 50f })
        assertTrue(catalog.slicing.any { it.firstLayerSpeed != it.firstLayerInfillSpeed })
        assertTrue(catalog.slicing.any { it.supportSpeed != it.supportInterfaceSpeed })
        assertTrue(catalog.slicing.any { it.bridgeFlowRatio != 1f })
        assertTrue(catalog.slicing.any { it.topShellThickness > 0f })
        assertTrue(catalog.slicing.any { it.supportInterfaceSpacing == 0f })
        assertTrue(catalog.slicing.any { it.fillPattern == "crosshatch" })
        assertTrue(catalog.slicing.any { it.overhangSpeed1Percent })
        assertTrue(catalog.slicing.any { it.seamPosition == "nearest" })
        assertTrue(catalog.slicing.any { it.ironingType == "top" })
        assertTrue(catalog.slicing.any { it.supportBasePattern == "rectilinear-grid" })
        assertTrue(catalog.slicing.map { it.wallGenerator }.toSet().containsAll(listOf("arachne", "classic")))
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
                fillPattern = "crosshatch",
                topSurfacePattern = "monotonic",
                bottomSurfacePattern = "concentric",
                internalSolidInfillPattern = "rectilinear",
                travelSpeed = 420f,
                firstLayerSpeed = 35f,
                retractLength = 1.1f,
                retractSpeed = 38f,
                skirtLoops = 2,
                skirtDistance = 7f,
                perimeters = 3,
                outerWallLineWidth = 0.62f,
                innerWallLineWidth = 0.68f,
                topSurfaceLineWidth = 0.58f,
                sparseInfillLineWidth = 0.71f,
                internalSolidInfillLineWidth = 0.66f,
                supportLineWidth = 0.55f,
                innerWallSpeed = 177f,
                sparseInfillSpeed = 188f,
                internalSolidInfillSpeed = 166f,
                topSurfaceSpeed = 99f,
                supportSpeed = 77f,
                bridgeSpeed = 43f,
                gapInfillSpeed = 137f,
                firstLayerInfillSpeed = 63f,
                supportInterfaceSpeed = 57f,
                overhangSpeedEnabled = true,
                overhangSpeed1 = 81f,
                overhangSpeed1Percent = true,
                overhangSpeed2 = 52f,
                overhangSpeed2Percent = false,
                overhangSpeed3 = 33f,
                overhangSpeed3Percent = true,
                overhangSpeed4 = 21f,
                overhangSpeed4Percent = false,
                bridgeFlowRatio = 0.91f,
                internalBridgeFlowRatio = 0.96f,
                topSurfaceFlowRatio = 0.97f,
                bottomSurfaceFlowRatio = 0.98f,
                topShellThickness = 0.83f,
                bottomShellThickness = 0.74f,
                supportInterfaceTopLayers = 4,
                supportInterfaceBottomLayers = 2,
                supportInterfaceSpacing = 0.23f,
                supportBottomInterfaceSpacing = 0.27f,
                supportTopZDistance = 0.18f,
                supportBottomZDistance = 0.22f,
                supportObjectXYDistance = 0.41f,
                supportBasePattern = "rectilinear-grid",
                supportInterfacePattern = "rectilinear_interlaced",
                supportStyle = "snug",
                initialLayerLineWidth = 0.73f,
                seamPosition = "nearest",
                ironingType = "top",
                ironingPattern = "concentric",
                ironingFlow = 13f,
                ironingSpacing = 0.17f,
                ironingSpeed = 27f,
                defaultAcceleration = 4_567f,
                outerWallAcceleration = 2_345f,
                innerWallAcceleration = 3_456f,
                topSurfaceAcceleration = 1_234f,
                travelAcceleration = 5_678f,
                firstLayerAcceleration = 678f,
                wallGenerator = "arachne",
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
        assertTrue("Top shell thickness must reach G-code", gcode.contains("; top_shell_thickness = 0.83"))
        assertTrue("Bottom shell thickness must reach G-code", gcode.contains("; bottom_shell_thickness = 0.74"))
        assertTrue("Sparse pattern must preserve Orca crosshatch", gcode.contains("; sparse_infill_pattern = crosshatch"))
        assertTrue("Top surface pattern must remain distinct", gcode.contains("; top_surface_pattern = monotonic"))
        assertTrue("Bottom surface pattern must remain distinct", gcode.contains("; bottom_surface_pattern = concentric"))
        assertTrue("Internal solid pattern must remain distinct", gcode.contains("; internal_solid_infill_pattern = rectilinear"))
        assertTrue("Travel speed must reach G-code", gcode.contains("; travel_speed = 420"))
        assertTrue("First layer speed must reach G-code", gcode.contains("; initial_layer_speed = 35"))
        assertTrue("Initial-layer solid speed must reach Orca", gcode.contains("; initial_layer_infill_speed = 63"))
        assertTrue("Bridge speed must reach Orca", gcode.contains("; bridge_speed = 43"))
        assertTrue("Gap-infill speed must reach Orca", gcode.contains("; gap_infill_speed = 137"))
        assertTrue("Retraction length must reach G-code", gcode.contains("; retraction_length = 1.1"))
        assertTrue("Skirt loops must reach G-code", gcode.contains("; skirt_loops = 2"))
        assertTrue("Wall count must reach Orca", gcode.contains("; wall_loops = 3"))
        assertTrue("Outer-wall width must remain independent", gcode.contains("; outer_wall_line_width = 0.62"))
        assertTrue("Inner-wall width must remain independent", gcode.contains("; inner_wall_line_width = 0.68"))
        assertTrue("Top-surface width must remain independent", gcode.contains("; top_surface_line_width = 0.58"))
        assertTrue("Sparse-infill width must remain independent", gcode.contains("; sparse_infill_line_width = 0.71"))
        assertTrue("Internal-solid width must remain independent", gcode.contains("; internal_solid_infill_line_width = 0.66"))
        assertTrue("Support width must remain independent", gcode.contains("; support_line_width = 0.55"))
        assertTrue("Initial-layer width must remain independent", gcode.contains("; initial_layer_line_width = 0.73"))
        assertTrue("Inner-wall speed must reach Orca", gcode.contains("; inner_wall_speed = 177"))
        assertTrue("Sparse-infill speed must reach Orca", gcode.contains("; sparse_infill_speed = 188"))
        assertTrue("Internal-solid speed must reach Orca", gcode.contains("; internal_solid_infill_speed = 166"))
        assertTrue("Top-surface speed must reach Orca", gcode.contains("; top_surface_speed = 99"))
        assertTrue("Support speed must reach Orca", gcode.contains("; support_speed = 77"))
        assertTrue("Support-interface speed must reach Orca", gcode.contains("; support_interface_speed = 57"))
        assertTrue("Bridge flow must reach Orca", gcode.contains("; bridge_flow = 0.91"))
        assertTrue("Internal bridge flow must reach Orca", gcode.contains("; internal_bridge_flow = 0.96"))
        assertTrue("Top surface flow must reach Orca", gcode.contains("; top_solid_infill_flow_ratio = 0.97"))
        assertTrue("Bottom surface flow must reach Orca", gcode.contains("; bottom_solid_infill_flow_ratio = 0.98"))
        assertTrue("Top support interface layers must reach Orca", gcode.contains("; support_interface_top_layers = 4"))
        assertTrue("Bottom support interface layers must reach Orca", gcode.contains("; support_interface_bottom_layers = 2"))
        assertTrue("Top support interface spacing must reach Orca", gcode.contains("; support_interface_spacing = 0.23"))
        assertTrue("Bottom support interface spacing must reach Orca", gcode.contains("; support_bottom_interface_spacing = 0.27"))
        assertTrue("Support top Z distance must reach Orca", gcode.contains("; support_top_z_distance = 0.18"))
        assertTrue("Support bottom Z distance must reach Orca", gcode.contains("; support_bottom_z_distance = 0.22"))
        assertTrue("Support XY distance must reach Orca", gcode.contains("; support_object_xy_distance = 0.41"))
        assertTrue("Support base pattern must reach Orca", gcode.contains("; support_base_pattern = rectilinear-grid"))
        assertTrue("Support interface pattern must reach Orca", gcode.contains("; support_interface_pattern = rectilinear_interlaced"))
        assertTrue("Support style must reach Orca", gcode.contains("; support_style = snug"))
        assertTrue("Seam position must reach Orca", gcode.contains("; seam_position = nearest"))
        assertTrue("Ironing type must reach Orca", gcode.contains("; ironing_type = top"))
        assertTrue("Ironing pattern must reach Orca", gcode.contains("; ironing_pattern = concentric"))
        assertTrue("Ironing flow must reach Orca", gcode.contains("; ironing_flow = 13%"))
        assertTrue("Ironing spacing must reach Orca", gcode.contains("; ironing_spacing = 0.17"))
        assertTrue("Ironing speed must reach Orca", gcode.contains("; ironing_speed = 27"))
        assertTrue("Overhang stage 1 must preserve percent units", gcode.contains("; overhang_1_4_speed = 81%"))
        assertTrue("Overhang stage 2 must preserve absolute units", gcode.contains("; overhang_2_4_speed = 52"))
        assertTrue("Overhang stage 3 must preserve percent units", gcode.contains("; overhang_3_4_speed = 33%"))
        assertTrue("Overhang stage 4 must preserve absolute units", gcode.contains("; overhang_4_4_speed = 21"))
        assertTrue("Default acceleration must reach Orca", gcode.contains("; default_acceleration = 4567"))
        assertTrue("Outer-wall acceleration must reach Orca", gcode.contains("; outer_wall_acceleration = 2345"))
        assertTrue("Inner-wall acceleration must reach Orca", gcode.contains("; inner_wall_acceleration = 3456"))
        assertTrue("Top-surface acceleration must reach Orca", gcode.contains("; top_surface_acceleration = 1234"))
        assertTrue("Travel acceleration must reach Orca", gcode.contains("; travel_acceleration = 5678"))
        assertTrue("First-layer acceleration must reach Orca", gcode.contains("; initial_layer_acceleration = 678"))
        assertTrue("Arachne selection must reach Orca", gcode.contains("; wall_generator = arachne"))
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
    fun classicWallGeneratorProducesDistinctOuterAndInnerWallsOnDevice() {
        val model = fixtureModel()
        val outcome = OnDeviceSlicer.slice(
            model,
            SliceOptions()
                .selectPrinter(PrinterProfile.U1_04)
                .selectFilament(FilamentProfile.PLA)
                .selectQuality(QualityProfile.DRAFT)
                .copy(
                    wallGenerator = "classic",
                    perimeters = 3,
                    fillDensity = 0.12f,
                    outerWallLineWidth = 0.42f,
                    innerWallLineWidth = 0.45f,
                    detectThinWalls = true,
                    detectOverhangWalls = true,
                ),
        )

        val gcode = outcome.output.readText()
        assertTrue("Classic selection must reach Orca", gcode.contains("; wall_generator = classic"))
        assertTrue("Classic must generate outer walls", gcode.contains(";TYPE:Outer wall"))
        assertTrue("Classic must generate inner walls", gcode.contains(";TYPE:Inner wall"))
        assertTrue("Classic G-code must contain extrusion", gcode.lineSequence().any { it.startsWith("G1 ") && it.contains(" E") })
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
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = OrcaProfileCatalog(context).load()
        data class Contract(
            val flavor: String,
            val printerId: String,
            val processId: String,
            val expectedSettings: Map<String, String>,
        )
        val contracts = listOf(
            Contract(
                flavor = "marlin",
                printerId = "orca-printer-b0d4d6e7890c59e89228",
                processId = "orca-process-ce89c8ef371d6032980c",
                expectedSettings = mapOf(
                    "outer_wall_speed" to "40",
                    "inner_wall_speed" to "40",
                    "sparse_infill_speed" to "60",
                    "internal_solid_infill_speed" to "50",
                    "top_surface_speed" to "30",
                    "support_speed" to "40",
                    "bridge_speed" to "25",
                    "gap_infill_speed" to "30",
                    "initial_layer_infill_speed" to "35",
                    "support_interface_speed" to "80",
                    "bridge_flow" to "0.95",
                    "initial_layer_line_width" to "0.42",
                    "top_shell_thickness" to "0.8",
                    "support_interface_top_layers" to "3",
                    "support_interface_bottom_layers" to "-1",
                    "support_interface_spacing" to "0.2",
                    "support_top_z_distance" to "0.15",
                    "top_surface_line_width" to "0.4",
                    "internal_solid_infill_line_width" to "0.45",
                    "support_line_width" to "0.38",
                    "wall_generator" to "arachne",
                    "sparse_infill_pattern" to "crosshatch",
                    "top_surface_pattern" to "monotonicline",
                    "bottom_surface_pattern" to "monotonic",
                    "internal_solid_infill_pattern" to "monotonic",
                    "seam_position" to "aligned",
                    "ironing_type" to "no ironing",
                    "ironing_flow" to "15%",
                    "overhang_2_4_speed" to "20",
                    "support_base_pattern" to "rectilinear",
                    "support_style" to "grid",
                ),
            ),
            Contract(
                flavor = "marlin2",
                printerId = "orca-printer-62803969e82d53d3720a",
                processId = "orca-process-f42a24b8fb07dff14515",
                expectedSettings = mapOf(
                    "outer_wall_speed" to "170",
                    "inner_wall_speed" to "170",
                    "sparse_infill_speed" to "200",
                    "internal_solid_infill_speed" to "200",
                    "top_surface_speed" to "100",
                    "support_speed" to "150",
                    "bridge_speed" to "25",
                    "gap_infill_speed" to "120",
                    "initial_layer_infill_speed" to "80",
                    "support_interface_speed" to "80",
                    "initial_layer_line_width" to "0.48",
                    "top_shell_thickness" to "0.8",
                    "support_interface_top_layers" to "2",
                    "support_interface_bottom_layers" to "2",
                    "support_top_z_distance" to "0.08",
                    "support_bottom_z_distance" to "0.08",
                    "default_acceleration" to "4000",
                    "outer_wall_acceleration" to "3000",
                    "inner_wall_acceleration" to "4000",
                    "top_surface_acceleration" to "1000",
                    "travel_acceleration" to "4000",
                    "initial_layer_acceleration" to "700",
                    "top_surface_line_width" to "0.375",
                    "internal_solid_infill_line_width" to "0.48",
                    "support_line_width" to "0.384",
                    "wall_generator" to "arachne",
                    "sparse_infill_pattern" to "crosshatch",
                    "top_surface_pattern" to "monotonicline",
                    "bottom_surface_pattern" to "monotonic",
                    "internal_solid_infill_pattern" to "monotonic",
                    "ironing_spacing" to "0.15",
                    "overhang_2_4_speed" to "50",
                    "support_interface_pattern" to "auto",
                ),
            ),
            Contract(
                flavor = "klipper",
                printerId = "orca-printer-8d5fc727726c00b46b13",
                processId = "orca-process-169e5f32752a1719ac3e",
                expectedSettings = mapOf(
                    "outer_wall_speed" to "120",
                    "inner_wall_speed" to "300",
                    "sparse_infill_speed" to "300",
                    "internal_solid_infill_speed" to "240",
                    "top_surface_speed" to "120",
                    "support_speed" to "150",
                    "bridge_speed" to "50",
                    "gap_infill_speed" to "200",
                    "initial_layer_infill_speed" to "60",
                    "support_interface_speed" to "80",
                    "bridge_flow" to "0.9",
                    "initial_layer_line_width" to "0.5",
                    "top_shell_thickness" to "1",
                    "bottom_shell_thickness" to "0.6",
                    "support_interface_top_layers" to "2",
                    "support_interface_bottom_layers" to "2",
                    "default_acceleration" to "10000",
                    "outer_wall_acceleration" to "5000",
                    "inner_wall_acceleration" to "10000",
                    "top_surface_acceleration" to "2000",
                    "travel_acceleration" to "10000",
                    "initial_layer_acceleration" to "5000",
                    "top_surface_line_width" to "0.42",
                    "internal_solid_infill_line_width" to "0.42",
                    "support_line_width" to "0.4",
                    "wall_generator" to "classic",
                    "sparse_infill_pattern" to "crosshatch",
                    "top_surface_pattern" to "monotonicline",
                    "bottom_surface_pattern" to "monotonic",
                    "internal_solid_infill_pattern" to "monotonic",
                    "ironing_speed" to "30",
                    "overhang_3_4_speed" to "40",
                    "support_style" to "default",
                ),
            ),
        )

        for (contract in contracts) {
            val printer = requireNotNull(catalog.printers.find { it.id == contract.printerId })
            val process = requireNotNull(catalog.slicing.find { it.id == contract.processId })
            val outcome = OnDeviceSlicer.slice(
                model,
                SliceOptions()
                    .selectPrinter(printer)
                    .selectFilament(FilamentProfile.GENERIC_PLA)
                    .selectQuality(process),
            )
            val gcode = outcome.output.readText()
            val settings = gcode.lineSequence()
                .filter { it.startsWith("; ") && it.contains(" = ") }
                .associate { line -> line.removePrefix("; ").split(" = ", limit = 2).let { it[0] to it[1] } }

            assertEquals("${contract.flavor} metadata must reach Orca", contract.flavor, settings["gcode_flavor"])
            contract.expectedSettings.forEach { (key, value) ->
                assertEquals("${contract.flavor} must preserve $key", value, settings[key])
            }
            assertTrue("${contract.flavor} output must contain extrusion", gcode.contains(";TYPE:Outer wall"))
            if (contract.flavor == "klipper") {
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
