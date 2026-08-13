package com.ashcastle.duckyslicer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrcaMultiVolumeInstrumentedTest {
    @Test
    fun automaticArrangementTreatsMultiVolumeGeometryAsOneAggregateObject() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "arrange-volume-source.stl")
        val leftFile = File(context.cacheDir, "arrange-volume-left.stl")
        val rightFile = File(context.cacheDir, "arrange-volume-right.stl")
        val singleFile = File(context.cacheDir, "arrange-volume-single.stl")
        try {
            instrumentation.context.assets.open("20mmbox-LF.stl").use { input ->
                source.outputStream().use(input::copyTo)
            }
            transform(source, leftFile, -18f)
            transform(source, rightFile, 18f)
            transform(source, singleFile, 0f)
            val multi = ProjectObject(
                id = "arrange-multi",
                volumes = listOf(
                    ProjectVolume("arrange-left", inspect(leftFile)),
                    ProjectVolume("arrange-right", inspect(rightFile)),
                ),
            )
            val single = ProjectObject("arrange-single", inspect(singleFile))
            val options = SliceOptions().copy(
                bedSizeX = 120f,
                bedSizeY = 100f,
                bedPolygon = rectangularBedPolygon(120f, 100f),
            )

            val arrangement = OnDeviceSlicer.arrange(
                listOf(multi, single),
                options,
                minimumGap = 6f,
            )

            assertEquals("Orca must return one placement per owning object", 2, arrangement.objectCount)
            assertEquals(6, arrangement.sizesMm.size)
            assertTrue(
                "The first footprint must include both model-part volumes",
                arrangement.sizesMm[0] > 50f,
            )
            val arranged = ProjectHistoryState()
                .add(multi)
                .add(single)
                .applyOrcaArrangement(arrangement, options.bedSizeX, options.bedSizeY)
            assertEquals(2, arranged.current.objects.size)
            assertEquals(2, arranged.current.objects.first().volumes.size)
        } finally {
            source.delete()
            leftFile.delete()
            rightFile.delete()
            singleFile.delete()
        }
    }

    @Test
    fun oneVolumeCompatibilityConstructorKeepsTheSameOrcaToolpaths() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "one-volume-compatibility.stl")
        val outputs = mutableListOf<File>()
        try {
            instrumentation.context.assets.open("20mmbox-LF.stl").use { input ->
                source.outputStream().use(input::copyTo)
            }
            val model = inspect(source)
            val options = testOptions(listOf(FilamentProfile.GENERIC_PLA))
            val legacy = ProjectObject(id = "one-volume", model = model)
            val explicit = ProjectObject(
                id = "one-volume",
                volumes = listOf(
                    ProjectVolume(
                        id = legacyProjectVolumeId("one-volume"),
                        model = model,
                    ),
                ),
            )

            val legacyOutcome = OnDeviceSlicer.slice(listOf(legacy), options)
            outputs.add(legacyOutcome.output)
            val explicitOutcome = OnDeviceSlicer.slice(listOf(explicit), options)
            outputs.add(explicitOutcome.output)

            assertEquals(legacyOutcome.layers, explicitOutcome.layers)
            assertEquals(legacyOutcome.filamentMm, explicitOutcome.filamentMm, 0.001f)
            assertEquals(
                extrusionMotions(legacyOutcome.output),
                extrusionMotions(explicitOutcome.output),
            )
        } finally {
            outputs.forEach(File::delete)
            source.delete()
        }
    }

    @Test
    fun oneObjectWithTwoPaintedVolumesReachesOrcaAsTwoModelParts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "multi-volume-source.stl")
        val leftFile = File(context.cacheDir, "multi-volume-left.stl")
        val rightFile = File(context.cacheDir, "multi-volume-right.stl")
        var output: File? = null
        try {
            instrumentation.context.assets.open("20mmbox-LF.stl").use { input ->
                source.outputStream().use(input::copyTo)
            }
            transform(source, leftFile, -18f)
            transform(source, rightFile, 18f)
            val left = inspect(leftFile)
            val right = inspect(rightFile)
            val primary = FilamentProfile.GENERIC_PLA.copy(
                compatiblePrinters = listOf(PrinterProfile.U1_04.name),
            )
            val secondary = FilamentProfile.PETG.copy(
                compatiblePrinters = listOf(PrinterProfile.U1_04.name),
            )
            val projectObject = ProjectObject(
                id = "two-volume-object",
                volumes = listOf(
                    ProjectVolume(
                        id = "left-volume",
                        model = left,
                        filamentSlot = 0,
                    ),
                    ProjectVolume(
                        id = "right-volume",
                        model = right,
                        supportPaint = SupportPaint().paint(0, SupportPaintState.ENFORCE),
                        seamPaint = SeamPaint().paint(1, SeamPaintState.BLOCK),
                        multiColorPaint = MultiColorPaint()
                            .paint(4, 0)
                            .paint(5, 0),
                        filamentSlot = 1,
                    ),
                ),
            )
            val outcome = OnDeviceSlicer.slice(
                listOf(projectObject),
                testOptions(listOf(primary, secondary)),
            )
            output = outcome.output
            val gcode = outcome.output.readText()
            val commands = gcode.lineSequence().map(String::trim).toList()
            val xBounds = extrusionXBounds(commands)
            val toolCommands = commands.filter { it.matches(Regex("T\\d+.*")) }.distinct()

            assertTrue("Both volume filaments must reach Orca", gcode.contains("filament_type = PLA;PETG"))
            assertTrue(
                "Both separated model-part volumes must be sliced; X bounds=$xBounds",
                xBounds.second - xBounds.first > 40f,
            )
            assertTrue("The first model-part volume must use tool 0", commands.any { it == "T0" })
            assertTrue(
                "The second model-part volume must use tool 1; tools=$toolCommands",
                commands.any { it == "T1" },
            )
            assertTrue("The multi-volume object must contain extrusion", gcode.contains(";TYPE:Outer wall"))
            assertNotEquals(
                "Multi-volume slicing must stay outside the app process",
                android.os.Process.myPid(),
                SlicerProcessClient.lastWorkerPid(),
            )
        } finally {
            output?.delete()
            source.delete()
            leftFile.delete()
            rightFile.delete()
        }
    }

    private fun inspect(file: File): ModelInfo = inspectModel(file.absolutePath)

    private fun transform(source: File, output: File, xOffsetMm: Float) {
        val result = JSONObject(
            NativeEngine.transformStl(
                source.absolutePath,
                output.absolutePath,
                ModelTransform(offsetXmm = xOffsetMm).toJson(0f, 0f),
            ),
        )
        assertTrue(result.optString("error"), result.optBoolean("ok"))
    }

    private fun testOptions(filaments: List<FilamentProfile>): SliceOptions {
        val primary = filaments.first().copy(
            compatiblePrinters = listOf(PrinterProfile.U1_04.name),
        )
        return SliceOptions()
            .selectPrinter(PrinterProfile.U1_04)
            .selectFilament(primary)
            .selectQuality(QualityProfile.DRAFT)
            .copy(filamentSlots = listOf(primary) + filaments.drop(1))
    }

    private fun extrusionMotions(file: File): List<String> = file.useLines { lines ->
        lines.map(String::trim)
            .filter { it.startsWith("G1 ") && it.contains(" E") }
            .toList()
    }

    private fun extrusionXBounds(commands: List<String>): Pair<Float, Float> {
        val values = commands.asSequence()
            .filter { it.startsWith("G1 ") && it.contains(" E") }
            .mapNotNull { command ->
                command.split(' ').firstOrNull { it.startsWith("X") }
                    ?.drop(1)
                    ?.toFloatOrNull()
            }
            .toList()
        return Pair(values.minOrNull() ?: 0f, values.maxOrNull() ?: 0f)
    }
}
