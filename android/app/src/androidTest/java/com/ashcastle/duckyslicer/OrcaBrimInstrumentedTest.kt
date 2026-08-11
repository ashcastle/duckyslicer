package com.ashcastle.duckyslicer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrcaBrimInstrumentedTest {
    @Test
    fun automaticBrimEarsReachOrcaAndRemainDistinctFromNoBrimAndOuterBrim() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "brim-semantics-cube.stl")
        val outputs = mutableListOf<File>()
        try {
            instrumentation.context.assets.open("20mmbox-LF.stl").use { input ->
                source.outputStream().use(input::copyTo)
            }
            val model = ModelInfo.fromJson(
                NativeEngine.inspectStl(source.absolutePath),
                source.absolutePath,
            )
            val base = SliceOptions()
                .selectPrinter(PrinterProfile.U1_04)
                .selectFilament(FilamentProfile.GENERIC_PLA.copy(
                    compatiblePrinters = listOf(PrinterProfile.U1_04.name),
                ))
                .selectQuality(QualityProfile.DRAFT)
                .copy(
                    bedSizeX = 100f,
                    bedSizeY = 100f,
                    bedPolygon = rectangularBedPolygon(100f, 100f),
                    brimWidth = 8f,
                    brimObjectGap = 0f,
                )

            fun slice(brimType: String): SliceOutcome = OnDeviceSlicer.slice(
                listOf(ProjectObject("brim-$brimType", model)),
                base.copy(brimType = brimType),
            ).also { outputs += it.output }

            val none = slice("no_brim")
            val ears = slice("brim_ears")
            val outer = slice("outer_only")
            val noBrimGcode = none.output.readText()
            val earGcode = ears.output.readText()
            val outerGcode = outer.output.readText()

            assertTrue(noBrimGcode.contains("; brim_type = no_brim"))
            assertTrue(earGcode.contains("; brim_type = brim_ears"))
            assertTrue(outerGcode.contains("; brim_type = outer_only"))
            assertNotEquals(extrusionMotions(noBrimGcode), extrusionMotions(earGcode))
            assertNotEquals(extrusionMotions(earGcode), extrusionMotions(outerGcode))
            assertTrue(
                "Automatic ears must add extrusion beyond no brim: ${none.filamentMm} -> ${ears.filamentMm}",
                ears.filamentMm > none.filamentMm,
            )
            assertTrue(
                "A full outer brim must use more filament than corner ears: " +
                    "${ears.filamentMm} -> ${outer.filamentMm}",
                outer.filamentMm > ears.filamentMm,
            )
        } finally {
            outputs.forEach(File::delete)
            source.delete()
        }
    }

    @Test
    fun manualBrimPointUsesOrcaPaintedBrimAndSurvivesObjectTransform() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "manual-brim-cube.stl")
        val outputs = mutableListOf<File>()
        try {
            instrumentation.context.assets.open("20mmbox-LF.stl").use { input ->
                source.outputStream().use(input::copyTo)
            }
            val model = ModelInfo.fromJson(
                NativeEngine.inspectStl(source.absolutePath),
                source.absolutePath,
            )
            val options = SliceOptions()
                .selectPrinter(PrinterProfile.U1_04)
                .selectFilament(
                    FilamentProfile.GENERIC_PLA.copy(
                        compatiblePrinters = listOf(PrinterProfile.U1_04.name),
                    ),
                )
                .selectQuality(QualityProfile.DRAFT)
                .copy(
                    bedSizeX = 100f,
                    bedSizeY = 100f,
                    bedPolygon = rectangularBedPolygon(100f, 100f),
                    brimType = "no_brim",
                    brimWidth = 8f,
                    brimObjectGap = 0f,
                )
            val transform = ModelTransform(
                offsetXmm = 8f,
                offsetYmm = -6f,
                rotationZdeg = 90f,
                scale = 1.25f,
            )
            val without = OnDeviceSlicer.slice(
                listOf(ProjectObject("without-manual-brim", model, transform = transform)),
                options,
            ).also { outputs += it.output }
            val withPoint = OnDeviceSlicer.slice(
                listOf(
                    ProjectObject(
                        id = "with-manual-brim",
                        model = model,
                        transform = transform,
                        brimPoints = BrimPoints(
                            listOf(BrimPoint(0f, 0f, 0f, 5f)),
                        ),
                    ),
                ),
                options,
            ).also { outputs += it.output }

            val withoutGcode = without.output.readText()
            val withPointGcode = withPoint.output.readText()
            assertTrue(withoutGcode.contains("; brim_type = no_brim"))
            assertTrue(withPointGcode.contains("; brim_type = no_brim"))
            assertNotEquals(extrusionMotions(withoutGcode), extrusionMotions(withPointGcode))
            assertTrue(
                "A manual Orca Brim point must add extrusion: " +
                    "${without.filamentMm} -> ${withPoint.filamentMm}",
                withPoint.filamentMm > without.filamentMm,
            )
        } finally {
            outputs.forEach(File::delete)
            source.delete()
        }
    }

    private fun extrusionMotions(gcode: String): List<String> = gcode.lineSequence()
        .map(String::trim)
        .filter { line ->
            (line.startsWith("G1 ") || line.startsWith("G2 ") || line.startsWith("G3 ")) &&
                " E" in line
        }
        .toList()
}
