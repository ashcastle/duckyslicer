package com.ashcastle.duckyslicer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrcaMultiColorPaintInstrumentedTest {
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
        val absoluteExtruders = mutableMapOf(0 to 0.0)
        var activeTool = 0
        var toolChanges = 0
        var relativeExtrusion = false
        var primeTower = false
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
                        if (primeTower) primeTowerMotions += 1
                    }
                }
            }
        }
        return MultiColorGcodeAnalysis(
            objectExtrusionByTool = objectExtrusionByTool,
            primeTowerExtrusionByTool = primeTowerExtrusionByTool,
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

    private companion object {
        const val MINIMUM_EXTRUSION_MM = 0.000_000_1
    }
}

private data class MultiColorGcodeAnalysis(
    val objectExtrusionByTool: Map<Int, Double>,
    val primeTowerExtrusionByTool: Map<Int, Double>,
    val primeTowerMotions: Int,
    val toolChanges: Int,
)
