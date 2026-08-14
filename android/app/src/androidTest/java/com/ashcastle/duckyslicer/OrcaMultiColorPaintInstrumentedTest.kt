package com.ashcastle.duckyslicer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrcaMultiColorPaintInstrumentedTest {
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
            )
            val secondary = FilamentProfile.PETG.copy(
                compatiblePrinters = listOf(PrinterProfile.U1_04.name),
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
}
