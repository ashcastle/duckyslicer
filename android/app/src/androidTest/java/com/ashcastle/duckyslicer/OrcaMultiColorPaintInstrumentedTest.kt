package com.ashcastle.duckyslicer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrcaMultiColorPaintInstrumentedTest {
    @Test
    fun purgeRoutingChangesRealInfillAndObjectExtrusionPaths() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val modelFile = File(context.cacheDir, "purge-routing-box.stl")
        val outputs = mutableListOf<File>()
        try {
            instrumentation.context.assets.open("20mmbox-LF.stl").use { input ->
                modelFile.outputStream().use(input::copyTo)
            }
            val model = inspectModel(modelFile.absolutePath)
            val printer = PrinterProfile.CUSTOM_CARTESIAN.copy(
                id = "purge-routing-semm",
                name = "Purge routing SEMM",
                singleExtruderMultiMaterial = true,
                extruderCount = 2,
            )
            val primary = FilamentProfile.GENERIC_PLA.copy(
                id = "purge-routing-primary",
                name = "Purge routing primary",
                builtIn = false,
                compatiblePrinters = listOf(printer.name),
            )
            val secondary = primary.copy(
                id = "purge-routing-secondary",
                name = "Purge routing secondary",
            )
            val objects = listOf(
                ProjectObject(
                    id = "purge-routing-left",
                    model = model,
                    transform = ModelTransform(offsetXmm = -20f),
                    filamentSlot = 0,
                ),
                ProjectObject(
                    id = "purge-routing-right",
                    model = model,
                    transform = ModelTransform(offsetXmm = 20f),
                    filamentSlot = 1,
                ),
            )
            val base = SliceOptions()
                .selectPrinter(printer)
                .selectFilament(primary)
                .selectQuality(QualityProfile.DRAFT)
                .copy(
                    filamentSlots = listOf(primary, secondary),
                    fillDensity = 0.35f,
                    wipeTowerEnabled = true,
                    brimType = "no_brim",
                    brimWidth = 0f,
                    skirtLoops = 0,
                    multiMaterial = MultiMaterialSettings(
                        purgeVolumes = listOf(0f, 260f, 260f, 0f),
                        flushIntoInfill = false,
                        flushIntoSupport = false,
                        flushIntoObjects = false,
                    ),
                )

            fun slice(settings: MultiMaterialSettings): Pair<String, MultiColorGcodeAnalysis> {
                val outcome = OnDeviceSlicer.slice(
                    objects,
                    base.copy(multiMaterial = settings),
                ).also { outputs += it.output }
                val gcode = outcome.output.readText()
                return gcode to analyzePositiveExtrusion(gcode)
            }

            val (baselineGcode, baseline) = slice(base.multiMaterial)
            val (infillGcode, intoInfill) = slice(
                base.multiMaterial.copy(flushIntoInfill = true),
            )
            val (objectsGcode, intoObjects) = slice(
                base.multiMaterial.copy(flushIntoObjects = true),
            )
            val baselineTower = baseline.primeTowerExtrusionByTool.values.sum()
            val infillTower = intoInfill.primeTowerExtrusionByTool.values.sum()
            val objectTower = intoObjects.primeTowerExtrusionByTool.values.sum()

            assertTrue(baselineGcode.contains("; flush_into_infill = 0"))
            assertTrue(infillGcode.contains("; flush_into_infill = 1"))
            assertTrue(objectsGcode.contains("; flush_into_objects = 1"))
            assertTrue(
                "Infill routing must consume purge in real model paths instead of only changing metadata",
                infillTower < baselineTower - 5.0 &&
                    intoInfill.extrusionMotionsByRoleAndTool["Sparse infill"] !=
                    baseline.extrusionMotionsByRoleAndTool["Sparse infill"],
            )
            assertTrue(
                "Object routing must consume purge in real model paths instead of only changing metadata",
                objectTower < baselineTower - 5.0 &&
                    intoObjects.nonTowerMotionSignature() != baseline.nonTowerMotionSignature(),
            )
        } finally {
            outputs.forEach(File::delete)
            modelFile.delete()
        }
    }

    @Test
    fun supportPurgeRoutingAndSolubleInterfaceChangeRealMaterialPaths() {
        val modelFile = supportOverhangModel()
        val outputs = mutableListOf<File>()
        try {
            val model = inspectModel(modelFile.absolutePath)
            val printer = PrinterProfile.CUSTOM_CARTESIAN.copy(
                id = "support-routing-semm",
                name = "Support routing SEMM",
                singleExtruderMultiMaterial = true,
                extruderCount = 2,
            )
            val primary = FilamentProfile.GENERIC_PLA.copy(
                id = "support-routing-primary",
                name = "Support routing primary",
                builtIn = false,
                compatiblePrinters = listOf(printer.name),
            )
            val regularInterface = primary.copy(
                id = "support-routing-interface",
                name = "Support routing interface",
            )
            val solubleInterface = regularInterface.copy(
                id = "soluble-support-interface",
                name = "Soluble support interface",
                soluble = true,
                supportMaterial = true,
            )
            val routedObjects = listOf(
                ProjectObject(
                    id = "support-routing-left",
                    model = model,
                    transform = ModelTransform(offsetXmm = -20f),
                    filamentSlot = 0,
                ),
                ProjectObject(
                    id = "support-routing-right",
                    model = model,
                    transform = ModelTransform(offsetXmm = 20f),
                    filamentSlot = 1,
                ),
            )
            val dedicatedObject = ProjectObject(id = "dedicated-soluble-interface", model = model)
            val base = SliceOptions()
                .selectPrinter(printer)
                .selectFilament(primary)
                .selectQuality(QualityProfile.DRAFT)
                .copy(
                    filamentSlots = listOf(primary, regularInterface),
                    supportEnabled = true,
                    supportType = "normal(auto)",
                    supportFilament = 0,
                    supportInterfaceFilament = 0,
                    supportInterfaceTopLayers = 3,
                    supportInterfaceBottomLayers = 0,
                    wipeTowerEnabled = true,
                    brimType = "no_brim",
                    brimWidth = 0f,
                    skirtLoops = 0,
                    multiMaterial = MultiMaterialSettings(
                        purgeVolumes = listOf(0f, 260f, 260f, 0f),
                        flushIntoInfill = false,
                        flushIntoSupport = false,
                        flushIntoObjects = false,
                    ),
                )

            fun slice(
                objects: List<ProjectObject>,
                options: SliceOptions,
            ): Pair<String, MultiColorGcodeAnalysis> {
                val outcome = OnDeviceSlicer.slice(objects, options).also {
                    outputs += it.output
                }
                val gcode = outcome.output.readText()
                return gcode to analyzePositiveExtrusion(gcode)
            }

            val (disabledGcode, disabled) = slice(routedObjects, base)
            val (routedGcode, routed) = slice(
                routedObjects,
                base.copy(multiMaterial = base.multiMaterial.copy(flushIntoSupport = true)),
            )
            val (solubleGcode, soluble) = slice(
                listOf(dedicatedObject),
                base.selectFilament(primary).copy(
                    filamentSlots = listOf(primary, solubleInterface),
                    supportFilament = 1,
                    supportInterfaceFilament = 2,
                    multiMaterial = base.multiMaterial.copy(flushIntoSupport = true),
                ),
            )
            val disabledTower = disabled.primeTowerExtrusionByTool.values.sum()
            val routedTower = routed.primeTowerExtrusionByTool.values.sum()
            val solubleTower = soluble.primeTowerExtrusionByTool.values.sum()

            assertTrue(disabledGcode.contains("; flush_into_support = 0"))
            assertTrue(routedGcode.contains("; flush_into_support = 1"))
            assertTrue(solubleGcode.contains("; filament_soluble = 0,1"))
            assertTrue(solubleGcode.contains("; filament_is_support = 0,1"))
            assertEquals(
                "Dedicated support base and interface must physically extrude with both tools",
                setOf(0, 1),
                soluble.supportExtrusionTools(),
            )
            assertTrue(
                "Support routing must replace tower purge with real support extrusion",
                routedTower < disabledTower - 5.0 &&
                    routed.supportMotionSignature() != disabled.supportMotionSignature(),
            )
            assertTrue(
                "Soluble/support interface must retain a material prime tower for real tool changes",
                solubleTower > 5.0 && soluble.primeTowerMotions >= 20,
            )
        } finally {
            outputs.forEach(File::delete)
            modelFile.delete()
        }
    }

    @Test
    fun primeTowerWallTypesProduceDistinctExtrusionGeometry() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val modelFile = File(context.cacheDir, "prime-tower-wall-types-box.stl")
        val outputs = mutableListOf<File>()
        try {
            instrumentation.context.assets.open("20mmbox-LF.stl").use { input ->
                modelFile.outputStream().use(input::copyTo)
            }
            val model = inspectModel(modelFile.absolutePath)
            val primary = FilamentProfile.GENERIC_PLA.copy(
                id = "tower-wall-primary",
                name = "Tower wall primary",
                builtIn = false,
                compatiblePrinters = listOf(PrinterProfile.U1_04.name),
            )
            val secondary = primary.copy(
                id = "tower-wall-secondary",
                name = "Tower wall secondary",
            )
            val objects = listOf(
                ProjectObject(
                    id = "tower-wall-left",
                    model = model,
                    transform = ModelTransform(offsetXmm = -20f),
                    filamentSlot = 0,
                ),
                ProjectObject(
                    id = "tower-wall-right",
                    model = model,
                    transform = ModelTransform(offsetXmm = 20f),
                    filamentSlot = 1,
                ),
            )
            val base = SliceOptions()
                .selectPrinter(PrinterProfile.U1_04)
                .selectFilament(primary)
                .selectQuality(QualityProfile.DRAFT)
                .copy(
                    filamentSlots = listOf(primary, secondary),
                    wipeTowerEnabled = true,
                    wipeTowerWidth = 40f,
                    brimType = "no_brim",
                    brimWidth = 0f,
                    skirtLoops = 0,
                    multiMaterial = MultiMaterialSettings(
                        purgeVolumes = listOf(0f, 90f, 90f, 0f),
                    ),
                )

            fun slice(wallType: String): Pair<String, MultiColorGcodeAnalysis> {
                val settings = when (wallType) {
                    "cone" -> base.multiMaterial.copy(
                        wipeTowerWallType = wallType,
                        wipeTowerConeAngle = 48f,
                    )
                    "rib" -> base.multiMaterial.copy(
                        wipeTowerWallType = wallType,
                        wipeTowerExtraRibLength = 12f,
                        wipeTowerRibWidth = 10f,
                        wipeTowerFilletWall = false,
                    )
                    else -> base.multiMaterial.copy(wipeTowerWallType = wallType)
                }
                val outcome = OnDeviceSlicer.slice(
                    objects,
                    base.copy(multiMaterial = settings),
                ).also { outputs += it.output }
                val gcode = outcome.output.readText()
                return gcode to analyzePositiveExtrusion(gcode)
            }

            val (rectangleGcode, rectangle) = slice("rectangle")
            val (coneGcode, cone) = slice("cone")
            val (ribGcode, rib) = slice("rib")

            assertTrue(rectangleGcode.contains("; wipe_tower_wall_type = rectangle"))
            assertTrue(coneGcode.contains("; wipe_tower_wall_type = cone"))
            assertTrue(coneGcode.contains("; wipe_tower_cone_angle = 48"))
            assertTrue(ribGcode.contains("; wipe_tower_wall_type = rib"))
            assertTrue(ribGcode.contains("; wipe_tower_extra_rib_length = 12"))
            assertTrue(ribGcode.contains("; wipe_tower_rib_width = 10"))
            assertTrue(ribGcode.contains("; wipe_tower_fillet_wall = 0"))
            listOf(rectangle, cone, rib).forEach { analysis ->
                assertTrue(
                    "Every wall type must generate a material prime tower",
                    analysis.primeTowerMotions >= 20 &&
                        analysis.primeTowerExtrusionByTool.values.sum() >= 5.0,
                )
            }
            assertEquals(
                "Rectangle, cone, and rib must produce three distinct physical tower paths",
                3,
                setOf(
                    rectangle.primeTowerMotionSignature,
                    cone.primeTowerMotionSignature,
                    rib.primeTowerMotionSignature,
                ).size,
            )
        } finally {
            outputs.forEach(File::delete)
            modelFile.delete()
        }
    }

    @Test
    fun primeTowerPositionMovesThePhysicalTowerWithoutMovingTheObjects() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val modelFile = File(context.cacheDir, "positioned-prime-tower-box.stl")
        val outputs = mutableListOf<File>()
        try {
            instrumentation.context.assets.open("20mmbox-LF.stl").use { input ->
                modelFile.outputStream().use(input::copyTo)
            }
            val model = inspectModel(modelFile.absolutePath)
            val primary = FilamentProfile.GENERIC_PLA.copy(
                id = "tower-position-primary",
                name = "Tower position primary",
                builtIn = false,
                compatiblePrinters = listOf(PrinterProfile.U1_04.name),
            )
            val secondary = primary.copy(
                id = "tower-position-secondary",
                name = "Tower position secondary",
            )
            val objects = listOf(
                ProjectObject(
                    id = "tower-position-left",
                    model = model,
                    transform = ModelTransform(offsetXmm = -20f),
                    filamentSlot = 0,
                ),
                ProjectObject(
                    id = "tower-position-right",
                    model = model,
                    transform = ModelTransform(offsetXmm = 20f),
                    filamentSlot = 1,
                ),
            )
            val base = SliceOptions()
                .selectPrinter(PrinterProfile.U1_04)
                .selectFilament(primary)
                .selectQuality(QualityProfile.DRAFT)
                .copy(
                    filamentSlots = listOf(primary, secondary),
                    wipeTowerEnabled = true,
                    wipeTowerWidth = 40f,
                    brimType = "no_brim",
                    brimWidth = 0f,
                    skirtLoops = 0,
                    multiMaterial = MultiMaterialSettings(
                        purgeVolumes = listOf(0f, 90f, 90f, 0f),
                    ),
                )

            fun slice(positionX: Float, positionY: Float): Pair<String, MultiColorGcodeAnalysis> {
                val outcome = OnDeviceSlicer.slice(
                    objects,
                    base.copy(
                        multiMaterial = base.multiMaterial.copy(
                            primeTowerPositionX = positionX,
                            primeTowerPositionY = positionY,
                        ),
                    ),
                ).also { outputs += it.output }
                val gcode = outcome.output.readText()
                return gcode to analyzePositiveExtrusion(gcode)
            }

            val (firstGcode, first) = slice(170f, 140f)
            val (movedGcode, moved) = slice(90f, 190f)
            val firstCenter = first.primeTowerCenter()
            val movedCenter = moved.primeTowerCenter()

            assertTrue(firstGcode.contains("; wipe_tower_x = 170"))
            assertTrue(firstGcode.contains("; wipe_tower_y = 140"))
            assertTrue(movedGcode.contains("; wipe_tower_x = 90"))
            assertTrue(movedGcode.contains("; wipe_tower_y = 190"))
            assertTrue(first.primeTowerMotions >= 20 && moved.primeTowerMotions >= 20)
            assertTrue(
                "Changing X must move the physical prime tower by 80 mm",
                abs((movedCenter.first - firstCenter.first) + 80.0) < 1.0,
            )
            assertTrue(
                "Changing Y must move the physical prime tower by 50 mm",
                abs((movedCenter.second - firstCenter.second) - 50.0) < 1.0,
            )
            assertEquals(
                "Tower placement must not rewrite object extrusion paths",
                first.nonTowerMotionSignature(),
                moved.nonTowerMotionSignature(),
            )
        } finally {
            outputs.forEach(File::delete)
            modelFile.delete()
        }
    }

    @Test
    fun fourColorFacetPaintProducesObjectAndPrimeTowerExtrusionOnEveryTool() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val modelFile = File(context.cacheDir, "four-color-prime-tower-box.stl")
        val outputs = mutableListOf<File>()
        try {
            instrumentation.context.assets.open("20mmbox-LF.stl").use { input ->
                modelFile.outputStream().use(input::copyTo)
            }
            val model = inspectModel(modelFile.absolutePath)
            assertTrue("The deterministic box must expose all painted sides", model.triangles >= 12)
            val filaments = List(4) { slot ->
                FilamentProfile.GENERIC_PLA.copy(
                    id = "four-color-slot-$slot",
                    name = "Four-color slot ${slot + 1}",
                    builtIn = false,
                    compatiblePrinters = listOf(PrinterProfile.U1_04.name),
                )
            }
            val purgeVolumes = List(16) { index ->
                if (index / 4 == index % 4) 0f else 45f
            }
            val options = SliceOptions()
                .selectPrinter(PrinterProfile.U1_04)
                .selectFilament(filaments.first())
                .selectQuality(QualityProfile.DRAFT)
                .copy(
                    filamentSlots = filaments,
                    wipeTowerEnabled = true,
                    wipeTowerWidth = 40f,
                    brimType = "no_brim",
                    brimWidth = 0f,
                    skirtLoops = 0,
                    multiMaterial = MultiMaterialSettings(
                        primeVolume = 45f,
                        purgeVolumes = purgeVolumes,
                    ),
                )
            val projectObject = ProjectObject(
                id = "four-color-painted-box",
                model = model,
                multiColorPaint = MultiColorPaint()
                    .paint(4, 1)
                    .paint(5, 1)
                    .paint(6, 2)
                    .paint(7, 2)
                    .paint(8, 3)
                    .paint(9, 3),
            )

            val outcome = OnDeviceSlicer.slice(listOf(projectObject), options).also {
                outputs += it.output
            }
            val gcode = outcome.output.readText()
            val analysis = analyzePositiveExtrusion(gcode)
            val withoutTower = OnDeviceSlicer.slice(
                listOf(projectObject),
                options.copy(wipeTowerEnabled = false),
            ).also { outputs += it.output }
            val withoutTowerGcode = withoutTower.output.readText()
            val withoutTowerAnalysis = analyzePositiveExtrusion(withoutTowerGcode)

            assertTrue(
                "The four-slot profile must reach Orca",
                gcode.contains("; filament_type = PLA;PLA;PLA;PLA"),
            )
            assertTrue("The requested prime tower must remain enabled", gcode.contains("; enable_prime_tower = 1"))
            assertTrue("The requested prime tower width must reach Orca", gcode.contains("; prime_tower_width = 40"))
            assertEquals(
                "Painted and unpainted model regions must positively extrude on all four tools",
                setOf(0, 1, 2, 3),
                analysis.objectExtrusionByTool.filterValues { it > MINIMUM_EXTRUSION_MM }.keys,
            )
            assertEquals(
                "The prime tower must contain positive extrusion from every active tool",
                setOf(0, 1, 2, 3),
                analysis.primeTowerExtrusionByTool.filterValues { it > MINIMUM_EXTRUSION_MM }.keys,
            )
            assertTrue(
                "Four-color slicing must perform repeated real tool changes",
                analysis.toolChanges >= 6,
            )
            assertTrue(
                "Prime tower output must be a material structure, not a marker-only feature",
                analysis.primeTowerMotions >= 40 &&
                    analysis.primeTowerExtrusionByTool.values.sum() >= 10.0,
            )
            assertTrue(
                "Disabling the prime tower must reach Orca",
                withoutTowerGcode.contains("; enable_prime_tower = 0"),
            )
            assertEquals(
                "Disabling the prime tower must remove its positive extrusion paths",
                0,
                withoutTowerAnalysis.primeTowerMotions,
            )
            assertTrue(
                "The tower setting must materially change generated G-code",
                gcode != withoutTowerGcode,
            )
        } finally {
            outputs.forEach(File::delete)
            modelFile.delete()
        }
    }

    @Test
    fun paintedFacetsUseOrcaMmuSegmentationAndProduceTwoToolGcode() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val modelFile = File(context.cacheDir, "multi-color-box.stl")
        var output: File? = null
        try {
            instrumentation.context.assets.open("20mmbox-LF.stl").use { input ->
                modelFile.outputStream().use(input::copyTo)
            }
            val model = inspectModel(modelFile.absolutePath)
            val primary = FilamentProfile.GENERIC_PLA.copy(
                compatiblePrinters = listOf(PrinterProfile.U1_04.name),
                filamentStartGcode = "M117 DUCKY_SLOT_0_START",
                filamentEndGcode = "M117 DUCKY_SLOT_0_END",
            )
            val secondary = FilamentProfile.PETG.copy(
                compatiblePrinters = listOf(PrinterProfile.U1_04.name),
                filamentStartGcode = "M117 DUCKY_SLOT_1_START",
                filamentEndGcode = "M117 DUCKY_SLOT_1_END",
            )
            val options = SliceOptions()
                .selectPrinter(PrinterProfile.U1_04)
                .selectFilament(primary)
                .selectQuality(QualityProfile.DRAFT)
                .copy(filamentSlots = listOf(primary, secondary))
            val paint = MultiColorPaint()
                .paint(4, 1)
                .paint(5, 1)
            val projectObject = ProjectObject(
                id = "painted-box",
                model = model,
                multiColorPaint = paint,
            )

            val outcome = OnDeviceSlicer.slice(listOf(projectObject), options)
            output = outcome.output
            val gcode = outcome.output.readText()
            val commands = gcode.lineSequence().map(String::trim).toList()

            assertTrue("Both filament definitions must reach Orca", gcode.contains("filament_type = PLA;PETG"))
            assertTrue("Unpainted faces must retain tool 0", commands.any { it == "T0" })
            assertTrue("Painted faces must use tool 1", commands.any { it == "T1" })
            assertTrue("Tool 0 start template must execute", commands.any { it == "M117 DUCKY_SLOT_0_START" })
            assertTrue("Tool 0 end template must execute", commands.any { it == "M117 DUCKY_SLOT_0_END" })
            assertTrue("Tool 1 start template must execute", commands.any { it == "M117 DUCKY_SLOT_1_START" })
            assertTrue("Tool 1 end template must execute", commands.any { it == "M117 DUCKY_SLOT_1_END" })
            assertTrue(
                "The first real T0 to T1 transition must close slot 0 before opening slot 1",
                commands.indexOf("M117 DUCKY_SLOT_0_START") <
                    commands.indexOf("M117 DUCKY_SLOT_0_END") &&
                    commands.indexOf("M117 DUCKY_SLOT_0_END") <
                    commands.indexOf("M117 DUCKY_SLOT_1_START"),
            )
            assertTrue(
                "The final active slot must close after it starts",
                commands.indexOf("M117 DUCKY_SLOT_1_START") <
                    commands.lastIndexOf("M117 DUCKY_SLOT_1_END"),
            )
            assertTrue("The painted slice must contain extrusion", gcode.contains(";TYPE:Outer wall"))
        } finally {
            output?.delete()
            modelFile.delete()
        }
    }

    @Test
    fun segmentedRegionInterlockingChangesPaintedTwoToolGeometry() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val modelFile = File(context.cacheDir, "segmented-region-box.stl")
        val outputs = mutableListOf<File>()
        try {
            instrumentation.context.assets.open("20mmbox-LF.stl").use { input ->
                modelFile.outputStream().use(input::copyTo)
            }
            val primary = FilamentProfile.GENERIC_PLA.copy(
                compatiblePrinters = listOf(PrinterProfile.U1_04.name),
            )
            val secondary = FilamentProfile.PETG.copy(
                compatiblePrinters = listOf(PrinterProfile.U1_04.name),
            )
            val options = SliceOptions()
                .selectPrinter(PrinterProfile.U1_04)
                .selectFilament(primary)
                .selectQuality(QualityProfile.DRAFT)
                .copy(
                    filamentSlots = listOf(primary, secondary),
                    wipeTowerEnabled = false,
                )
            val projectObject = ProjectObject(
                id = "segmented-region-box",
                model = inspectModel(modelFile.absolutePath),
                multiColorPaint = MultiColorPaint().paint(4, 1).paint(5, 1),
            )

            val baseline = OnDeviceSlicer.slice(listOf(projectObject), options).also {
                outputs += it.output
            }
            val interlocked = OnDeviceSlicer.slice(
                listOf(projectObject),
                options.copy(
                    multiMaterial = MultiMaterialSettings(
                        segmentedRegionMaxWidth = 4f,
                        segmentedRegionInterlockingDepth = 1f,
                        interlockingBeam = false,
                    ),
                ),
            ).also { outputs += it.output }
            val baselinePreview = loadGcodePreview(baseline.output.absolutePath, 0, Int.MAX_VALUE)
            val interlockedPreview = loadGcodePreview(interlocked.output.absolutePath, 0, Int.MAX_VALUE)
            val interlockedGcode = interlocked.output.readText()

            assertTrue("Painted segmentation must keep tool 0", interlockedGcode.lineSequence().any { it == "T0" })
            assertTrue("Painted segmentation must keep tool 1", interlockedGcode.lineSequence().any { it == "T1" })
            assertTrue(
                "Segmented-region width must reach Orca",
                interlockedGcode.contains("; mmu_segmented_region_max_width = 4"),
            )
            assertTrue(
                "Segmented-region depth must reach Orca",
                interlockedGcode.contains("; mmu_segmented_region_interlocking_depth = 1"),
            )
            assertFalse(
                "Segmented-region interlocking must change painted extrusion geometry",
                baselinePreview.segments.contentEquals(interlockedPreview.segments),
            )
        } finally {
            outputs.forEach(File::delete)
            modelFile.delete()
        }
    }

    private fun analyzePositiveExtrusion(gcode: String): MultiColorGcodeAnalysis {
        val objectExtrusionByTool = mutableMapOf<Int, Double>()
        val primeTowerExtrusionByTool = mutableMapOf<Int, Double>()
        val extrusionMotionsByRoleAndTool = mutableMapOf<String, MutableMap<Int, MutableList<String>>>()
        val primeTowerMotionSignature = mutableListOf<String>()
        val absoluteExtruders = mutableMapOf(0 to 0.0)
        var activeTool = 0
        var toolChanges = 0
        var relativeExtrusion = false
        var primeTower = false
        var activeRole = "Unclassified"
        var primeTowerMotions = 0
        gcode.lineSequence().forEach { raw ->
            val line = raw.trim()
            val selectedTool = line.substringBefore(';').trim().let { command ->
                command.takeIf { it.startsWith('T') }
                    ?.substring(1)
                    ?.toIntOrNull()
                    ?.takeIf { it in 0 until MAX_FILAMENT_SLOTS }
            }
            when {
                line.startsWith(";TYPE:") || line.startsWith("; FEATURE:") -> {
                    val label = line.substringAfter(':').trim()
                    activeRole = label
                    primeTower = label.equals("Prime tower", ignoreCase = true) ||
                        label.equals("Wipe tower", ignoreCase = true)
                }
                selectedTool != null -> {
                    if (selectedTool != activeTool) toolChanges += 1
                    activeTool = selectedTool
                    absoluteExtruders.putIfAbsent(activeTool, 0.0)
                }
                line.startsWith("M82") -> relativeExtrusion = false
                line.startsWith("M83") -> relativeExtrusion = true
                line.startsWith("G92") -> {
                    axisValue(line, 'E')?.let { absoluteExtruders[activeTool] = it }
                }
                line.startsWith("G1 ") || line.startsWith("G2 ") || line.startsWith("G3 ") -> {
                    val encodedExtrusion = axisValue(line, 'E') ?: return@forEach
                    val previousExtrusion = absoluteExtruders.getValue(activeTool)
                    val extrusion = if (relativeExtrusion) {
                        encodedExtrusion
                    } else {
                        encodedExtrusion - previousExtrusion
                    }
                    if (!relativeExtrusion) absoluteExtruders[activeTool] = encodedExtrusion
                    val spatialMotion = listOf('X', 'Y', 'Z', 'I', 'J').any { axis ->
                        axisValue(line, axis) != null
                    }
                    if (extrusion > MINIMUM_EXTRUSION_MM && spatialMotion) {
                        val output = if (primeTower) primeTowerExtrusionByTool else objectExtrusionByTool
                        output[activeTool] = output.getOrDefault(activeTool, 0.0) + extrusion
                        val canonicalMotion = "T$activeTool|${line.substringBefore(';').trim()}"
                        extrusionMotionsByRoleAndTool
                            .getOrPut(activeRole) { mutableMapOf() }
                            .getOrPut(activeTool) { mutableListOf() }
                            .add(canonicalMotion)
                        if (primeTower) {
                            primeTowerMotions += 1
                            primeTowerMotionSignature += canonicalMotion
                        }
                    }
                }
            }
        }
        return MultiColorGcodeAnalysis(
            objectExtrusionByTool = objectExtrusionByTool,
            primeTowerExtrusionByTool = primeTowerExtrusionByTool,
            extrusionMotionsByRoleAndTool = extrusionMotionsByRoleAndTool.mapValues { (_, tools) ->
                tools.mapValues { (_, motions) -> motions.toList() }
            },
            primeTowerMotionSignature = primeTowerMotionSignature,
            primeTowerMotions = primeTowerMotions,
            toolChanges = toolChanges,
        )
    }

    private fun axisValue(line: String, axis: Char): Double? = line
        .substringBefore(';')
        .splitToSequence(' ')
        .firstOrNull { token -> token.length > 1 && token[0] == axis }
        ?.substring(1)
        ?.toDoubleOrNull()

    private fun MultiColorGcodeAnalysis.nonTowerMotionSignature(): List<String> =
        extrusionMotionsByRoleAndTool
            .filterKeys { role ->
                !role.equals("Prime tower", ignoreCase = true) &&
                    !role.equals("Wipe tower", ignoreCase = true)
            }
            .toSortedMap()
            .flatMap { (role, tools) ->
                tools.toSortedMap().flatMap { (tool, motions) ->
                    motions.map { motion -> "$role|T$tool|$motion" }
                }
            }

    private fun MultiColorGcodeAnalysis.primeTowerCenter(): Pair<Double, Double> {
        val points = primeTowerMotionSignature.mapNotNull { encoded ->
            val motion = encoded.substringAfter('|')
            val x = axisValue(motion, 'X') ?: return@mapNotNull null
            val y = axisValue(motion, 'Y') ?: return@mapNotNull null
            x to y
        }
        check(points.isNotEmpty()) { "Prime tower has no physical XY extrusion" }
        return points.sumOf { it.first } / points.size to points.sumOf { it.second } / points.size
    }

    private fun MultiColorGcodeAnalysis.supportMotionSignature(): List<String> =
        extrusionMotionsByRoleAndTool
            .filterKeys { role -> role.contains("support", ignoreCase = true) }
            .toSortedMap()
            .flatMap { (role, tools) ->
                tools.toSortedMap().flatMap { (tool, motions) ->
                    motions.map { motion -> "$role|T$tool|$motion" }
                }
            }

    private fun MultiColorGcodeAnalysis.supportExtrusionTools(): Set<Int> =
        extrusionMotionsByRoleAndTool
            .filterKeys { role -> role.contains("support", ignoreCase = true) }
            .values
            .flatMap(Map<Int, List<String>>::keys)
            .toSet()

    private fun supportOverhangModel(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val destination = File(context.cacheDir, "multi-material-support-overhang.stl")
        val facets = mutableListOf<List<Triple<Float, Float, Float>>>()

        fun vertex(x: Float, y: Float, z: Float) = Triple(x, y, z)
        fun quad(
            a: Triple<Float, Float, Float>,
            b: Triple<Float, Float, Float>,
            c: Triple<Float, Float, Float>,
            d: Triple<Float, Float, Float>,
        ) {
            facets += listOf(a, b, c)
            facets += listOf(a, c, d)
        }
        fun box(x0: Float, x1: Float, y0: Float, y1: Float, z0: Float, z1: Float) {
            quad(vertex(x0, y0, z0), vertex(x1, y0, z0), vertex(x1, y0, z1), vertex(x0, y0, z1))
            quad(vertex(x1, y0, z0), vertex(x1, y1, z0), vertex(x1, y1, z1), vertex(x1, y0, z1))
            quad(vertex(x1, y1, z0), vertex(x0, y1, z0), vertex(x0, y1, z1), vertex(x1, y1, z1))
            quad(vertex(x0, y1, z0), vertex(x0, y0, z0), vertex(x0, y0, z1), vertex(x0, y1, z1))
            quad(vertex(x0, y0, z1), vertex(x1, y0, z1), vertex(x1, y1, z1), vertex(x0, y1, z1))
            quad(vertex(x0, y1, z0), vertex(x1, y1, z0), vertex(x1, y0, z0), vertex(x0, y0, z0))
        }

        box(8f, 12f, 8f, 12f, 0f, 18f)
        box(0f, 20f, 0f, 20f, 18f, 22f)
        destination.bufferedWriter().use { writer ->
            writer.appendLine("solid multi_material_support_overhang")
            facets.forEach { triangle ->
                writer.appendLine("facet normal 0 0 0")
                writer.appendLine("outer loop")
                triangle.forEach { point ->
                    writer.appendLine("vertex ${point.first} ${point.second} ${point.third}")
                }
                writer.appendLine("endloop")
                writer.appendLine("endfacet")
            }
            writer.appendLine("endsolid multi_material_support_overhang")
        }
        return destination
    }

    private companion object {
        const val MINIMUM_EXTRUSION_MM = 0.000_000_1
    }
}

private data class MultiColorGcodeAnalysis(
    val objectExtrusionByTool: Map<Int, Double>,
    val primeTowerExtrusionByTool: Map<Int, Double>,
    val extrusionMotionsByRoleAndTool: Map<String, Map<Int, List<String>>>,
    val primeTowerMotionSignature: List<String>,
    val primeTowerMotions: Int,
    val toolChanges: Int,
)
