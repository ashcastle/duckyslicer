package com.ashcastle.duckyslicer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
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
}
