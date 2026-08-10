package com.ashcastle.duckyslicer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MultiFilamentInstrumentedTest {
    @Test
    fun twoObjectsAssignedToTwoFilamentsProduceTwoToolGcode() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val model = File(context.cacheDir, "multi-filament-box.stl")
        var output: File? = null
        try {
            instrumentation.context.assets.open("20mmbox-LF.stl").use { input ->
                model.outputStream().use(input::copyTo)
            }
            val info = ModelInfo.fromJson(NativeEngine.inspectStl(model.absolutePath), model.absolutePath)
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
            val objects = listOf(
                ProjectObject(
                    id = "pla-box",
                    model = info,
                    transform = ModelTransform(offsetXmm = -18f),
                    filamentSlot = 0,
                ),
                ProjectObject(
                    id = "petg-box",
                    model = info,
                    transform = ModelTransform(offsetXmm = 18f),
                    filamentSlot = 1,
                ),
            )

            val outcome = OnDeviceSlicer.slice(objects, options)
            output = outcome.output
            val gcode = outcome.output.readText()
            val commands = gcode.lineSequence().map(String::trim).toList()

            assertTrue("Both filament definitions must reach Orca", gcode.contains("filament_type = PLA;PETG"))
            assertTrue("The first object must use tool 0", commands.any { it == "T0" })
            assertTrue("The second object must use tool 1", commands.any { it == "T1" })
            assertTrue("The two-tool slice must contain extrusion", gcode.contains(";TYPE:Outer wall"))
        } finally {
            output?.delete()
            model.delete()
        }
    }
}
