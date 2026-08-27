package com.ashcastle.duckyslicer

import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES30
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.data.DEFAULT_SMALL_AREA_FLOW_COMPENSATION_MODEL
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class NativeEngineInstrumentedTest {
    private data class ToolpathBounds(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

    private data class TestVertex(val x: Float, val y: Float, val z: Float)

    private data class MeshCorpusEntry(
        val name: String,
        val model: File,
        val mustSlice: Boolean,
    )

    @Test
    fun gcodeLineNumbersAreAppliedToEveryExportedLine() {
        val outcome = OnDeviceSlicer.slice(
            fixtureModel(),
            SliceOptions().copy(
                gcodeSettings = GcodeSettings(addLineNumbers = true),
            ),
        )
        try {
            val lines = outcome.output.readLines()
            assertTrue("Numbered G-code must not be empty", lines.isNotEmpty())
            lines.forEachIndexed { index, line ->
                assertTrue(
                    "Every G-code line must have a contiguous line number",
                    line.startsWith("N${index + 1} "),
                )
            }
            assertTrue(lines.any { it.contains("; gcode_add_line_number = 1") })
            val preview = loadGcodePreview(outcome.output.absolutePath, 0, Int.MAX_VALUE)
            assertTrue("Numbered G-code must remain previewable", preview.layerCount > 0)
            assertTrue("Numbered G-code preview must retain toolpaths", preview.segments.isNotEmpty())
        } finally {
            outcome.output.delete()
        }
    }

    @Test
    fun selectedPreviewLayerPauseUsesTheActivePrinterCommandThroughWorker() {
        val model = fixtureModel()
        val projectObject = ProjectObject("layer-pause", inspectModel(model.absolutePath))
        val options = SliceOptions().selectPrinter(
            PrinterProfile.CUSTOM_CARTESIAN.copy(
                machinePauseGcode = "M25 ; DUCKY_LAYER_PAUSE",
            ),
        )
        val baseline = OnDeviceSlicer.slice(listOf(projectObject), options)
        val baselinePreview = loadGcodePreview(baseline.output.absolutePath, 0, Int.MAX_VALUE)
        val selectedLayer = minOf(4, baselinePreview.layerCount - 1)
        val selectedPrintZ = requireNotNull(baselinePreview.printZForLayer(selectedLayer))

        val paused = OnDeviceSlicer.slice(
            listOf(projectObject),
            options,
            layerPauseEvents = LayerPauseEvents(
                listOf(LayerPauseEvent(selectedPrintZ, "Layer ${selectedLayer + 1}")),
            ),
        )

        assertEquals(
            "The selected printer pause command must be emitted exactly once",
            1,
            paused.output.readLines().count { it.trim() == "M25 ; DUCKY_LAYER_PAUSE" },
        )
    }

    @Test
    fun selectedPreviewLayerCanSwitchTheWholeModelToAnotherFilament() {
        val model = fixtureModel()
        val projectObject = ProjectObject("layer-filament", inspectModel(model.absolutePath))
        val options = SliceOptions()
            .selectPrinter(PrinterProfile.CUSTOM_CARTESIAN.copy(extruderCount = 2))
            .selectFilament(FilamentProfile.PLA)
            .copy(
                filamentSlots = listOf(FilamentProfile.PLA, FilamentProfile.PETG),
                filamentColors = listOf(0xF6C945, 0x44D7FF),
                wipeTowerEnabled = false,
            )
        val baseline = OnDeviceSlicer.slice(listOf(projectObject), options)
        try {
            val baselinePreview = loadGcodePreview(baseline.output.absolutePath, 0, Int.MAX_VALUE)
            val selectedLayer = (baselinePreview.layerCount / 2).coerceAtLeast(1)
            val selectedPrintZ = requireNotNull(baselinePreview.printZForLayer(selectedLayer))

            val changed = OnDeviceSlicer.slice(
                listOf(projectObject),
                options,
                layerFilamentChanges = LayerFilamentChanges(
                    listOf(LayerFilamentChange(selectedPrintZ, filamentSlot = 1)),
                ),
            )
            try {
                val preview = loadGcodePreview(changed.output.absolutePath, 0, Int.MAX_VALUE)
                assertTrue("The original filament must print below the selected layer", preview.toolSegmentCounts[0] > 0)
                assertTrue("The selected filament must print above the selected layer", preview.toolSegmentCounts[1] > 0)
                assertTrue(
                    "Orca must emit the selected physical tool",
                    changed.output.readLines().any { it.trim() == "T1" },
                )
            } finally {
                changed.output.delete()
            }
        } finally {
            baseline.output.delete()
            model.delete()
        }
    }

    @Test
    fun selectedPreviewLayerCanEmitBoundedCustomGCode() {
        val model = fixtureModel()
        val projectObject = ProjectObject("layer-custom-gcode", inspectModel(model.absolutePath))
        val options = SliceOptions()
        val baseline = OnDeviceSlicer.slice(listOf(projectObject), options)
        try {
            val baselinePreview = loadGcodePreview(baseline.output.absolutePath, 0, Int.MAX_VALUE)
            val selectedLayer = (baselinePreview.layerCount / 2).coerceAtLeast(1)
            val selectedPrintZ = requireNotNull(baselinePreview.printZForLayer(selectedLayer))
            val customCode = "M117 DUCKY_CUSTOM_LAYER\nM106 S77"

            val changed = OnDeviceSlicer.slice(
                listOf(projectObject),
                options,
                layerCustomGCodeEvents = LayerCustomGCodeEvents(
                    listOf(LayerCustomGCodeEvent(selectedPrintZ, customCode)),
                ),
            )
            try {
                val lines = changed.output.readLines().map(String::trim)
                assertEquals(1, lines.count { it == "M117 DUCKY_CUSTOM_LAYER" })
                assertEquals(1, lines.count { it == "M106 S77" })
                assertTrue(lines.indexOf("M117 DUCKY_CUSTOM_LAYER") < lines.indexOf("M106 S77"))
            } finally {
                changed.output.delete()
            }
        } finally {
            baseline.output.delete()
            model.delete()
        }
    }

    @Test
    fun automaticPreviewQualityResolvesToAConcreteDeviceTier() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val capabilities = previewDeviceCapabilities(context)
        val resolved = resolvePreviewDetail(PreviewDetail.AUTOMATIC, capabilities)

        assertTrue("Android must report a positive app memory class", capabilities.appMemoryClassMb > 0)
        assertEquals(
            "Automatic preview must use the measured smooth tier before building GPU geometry",
            PreviewDetail.PERFORMANCE,
            resolved,
        )
    }

    @Test
    fun persistentProjectModelSlicesIntoRetainedArtifact() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectStore = ProjectStore(context)
        val storedModel = projectStore.createModelDestination("persistent-slice.stl")
        val nativeOutput = File(requireNotNull(storedModel.parentFile), SliceArtifactStore.NATIVE_OUTPUT_NAME)
        try {
            fixtureModel().copyTo(storedModel, overwrite = true)

            val outcome = OnDeviceSlicer.slice(
                storedModel,
                SliceOptions().selectQuality(QualityProfile.DRAFT),
            )

            assertTrue("Persistent project models must produce retained G-code", outcome.output.length() > 1_000L)
            assertTrue("Slice outcome must retain Orca's print-time estimate", outcome.estimatedSeconds > 0f)
            assertTrue("Slice outcome must retain Orca's filament-length estimate", outcome.filamentMm > 0f)
            assertTrue("Slice outcome must retain Orca's filament-mass estimate", outcome.filamentGrams > 0f)
            assertEquals(
                "Completed G-code must move into bounded slice storage",
                File(context.filesDir, SliceArtifactStore.OUTPUT_DIRECTORY).canonicalFile,
                requireNotNull(outcome.output.parentFile).canonicalFile,
            )
            assertFalse("Native output must not remain beside the project model", nativeOutput.exists())
        } finally {
            storedModel.delete()
            nativeOutput.delete()
        }
    }

    @Test
    fun outputFilenameUsesOrcaPlaceholdersAndOriginalModelName() {
        val modelFile = fixtureModel()
        val printer = PrinterProfile.U1_04.copy(name = "Production Printer 0.4")
        val model = inspectModel(modelFile.absolutePath).copy(
            fileName = "production filename fixture.stl",
        )
        val options = SliceOptions()
            .selectPrinter(printer)
            .selectFilament(FilamentProfile.PLA)
            .selectQuality(QualityProfile.STANDARD)
            .copy(
                layerHeight = 0.2f,
                gcodeSettings = GcodeSettings(
                    filenameFormat = "{input_filename_base}__{filament_type[initial_tool]}__" +
                        "{layer_height}mm__{printer_model}__{print_time}.gcode",
                ),
            )

        val outcome = OnDeviceSlicer.slice(
            listOf(ProjectObject(id = "named-model", model = model)),
            options,
        )
        try {
            assertTrue(outcome.output.length() > 1_000L)
            assertTrue(
                outcome.suggestedName,
                outcome.suggestedName.startsWith("production filename fixture__PLA__0.2mm__"),
            )
            assertTrue(outcome.suggestedName, outcome.suggestedName.contains("__Production Printer 0.4__"))
            assertTrue(outcome.suggestedName, outcome.suggestedName.endsWith(".gcode"))
            assertFalse(outcome.suggestedName, outcome.suggestedName.contains('{'))
            assertFalse(outcome.suggestedName, outcome.suggestedName.contains('}'))
            assertEquals(outcome.suggestedName, safeGcodeFileName(outcome.suggestedName))
        } finally {
            outcome.output.delete()
        }
    }

    @Test
    fun inheritedPerFeatureJerkChangesActualMarlinToolpathCommands() {
        val printer = PrinterProfile.U1_04.copy(
            gcodeFlavor = "marlin2",
            maxJerkX = 50f,
            maxJerkY = 50f,
        )
        val options = SliceOptions()
            .selectPrinter(printer)
            .copy(
                perimeters = 3,
                fillDensity = 0.2f,
                jerk = JerkSettings(
                    defaultJerk = 8.5f,
                    outerWallJerk = 7.5f,
                    innerWallJerk = 8f,
                    topSurfaceJerk = 6.5f,
                    infillJerk = 9.5f,
                    firstLayerJerk = 5.5f,
                    travelJerk = 12.5f,
                ),
            )

        val outcome = OnDeviceSlicer.slice(fixtureModel(), options)
        try {
            val gcode = outcome.output.readText()
            for (value in listOf("5.5", "6.5", "7.5", "8", "9.5", "12.5")) {
                assertTrue(
                    "Orca must emit the requested feature jerk into real Marlin motion commands: $value",
                    gcode.contains("M205 X$value Y$value"),
                )
            }
            assertTrue(gcode.contains("; default_jerk = 8.5"))
            assertTrue(gcode.contains("; outer_wall_jerk = 7.5"))
            assertTrue(gcode.contains("; inner_wall_jerk = 8"))
            assertTrue(gcode.contains("; top_surface_jerk = 6.5"))
            assertTrue(gcode.contains("; infill_jerk = 9.5"))
            assertTrue(gcode.contains("; initial_layer_jerk = 5.5"))
            assertTrue(gcode.contains("; travel_jerk = 12.5"))
        } finally {
            outcome.output.delete()
        }
    }

    @Test
    fun adaptivePressureAdvanceChangesRealKlipperCommands() {
        val model = "0.20,0.001,1000\n0.80,1000,1000"
        val filament = FilamentProfile.GENERIC_PLA.copy(
            pressureAdvanceEnabled = true,
            pressureAdvance = 0.04f,
            adaptivePressureAdvance = AdaptivePressureAdvanceSettings(
                enabled = true,
                model = model,
                overhangs = true,
                bridge = 0.065f,
            ),
        )
        val base = SliceOptions()
            .selectPrinter(PrinterProfile.U1_04)
            .selectFilament(filament)
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                gcodeFlavor = "klipper",
                pressureAdvanceEnabled = true,
                pressureAdvance = 0.04f,
                perimeters = 3,
                fillDensity = 0.25f,
                gcodeSettings = GcodeSettings(verboseComments = true),
            )
        val enabled = OnDeviceSlicer.slice(fixtureModel(), base)
        val disabled = OnDeviceSlicer.slice(
            fixtureModel(),
            base.selectFilament(
                filament.copy(
                    adaptivePressureAdvance = filament.adaptivePressureAdvance.copy(enabled = false),
                ),
            ).copy(pressureAdvanceEnabled = true, pressureAdvance = 0.04f),
        )
        try {
            fun pressureAdvanceValues(gcode: String): List<Double> = gcode.lineSequence()
                .mapNotNull { line ->
                    Regex("^SET_PRESSURE_ADVANCE ADVANCE=([0-9.]+);")
                        .find(line)
                        ?.groupValues
                        ?.get(1)
                        ?.toDoubleOrNull()
                }
                .toList()

            val enabledGcode = enabled.output.readText()
            val disabledGcode = disabled.output.readText()
            val enabledValues = pressureAdvanceValues(enabledGcode)
            val disabledValues = pressureAdvanceValues(disabledGcode)

            assertTrue(enabledGcode.contains("; adaptive_pressure_advance = 1"))
            assertTrue(enabledGcode.contains("; adaptive_pressure_advance_overhangs = 1"))
            assertTrue(enabledGcode.contains("; adaptive_pressure_advance_bridges = 0.065"))
            assertTrue(enabledGcode.contains("; PA_CHANGE:"))
            assertTrue("Regular PA must still initialize Klipper", enabledValues.contains(0.04))
            assertTrue(
                "Adaptive PA must emit an interpolated command distinct from the fallback value",
                enabledValues.any { abs(it - 0.04) > 0.001 },
            )
            assertTrue(disabledGcode.contains("; adaptive_pressure_advance = 0"))
            assertFalse(disabledGcode.contains("; PA_CHANGE:"))
            assertTrue("Disabled adaptive PA must retain regular PA", disabledValues.contains(0.04))
            assertTrue(
                "Disabled adaptive PA must not emit interpolated values",
                disabledValues.all { abs(it - 0.04) <= 0.001 },
            )
        } finally {
            enabled.output.delete()
            disabled.output.delete()
        }
    }

    @Test
    fun nozzleHardnessWarningComesFromTheNativeOrcaSafetyCheck() {
        val abrasive = FilamentProfile.GENERIC_PLA.copy(
            id = "instrumented-abrasive-filament",
            name = "Instrumented abrasive filament",
            requiredNozzleHrc = 40,
        )
        fun options(material: NozzleMaterial) = SliceOptions()
            .selectPrinter(
                PrinterProfile.U1_04.copy(
                    nozzleMaterial = material,
                    nozzleHrc = 0,
                ),
            )
            .selectFilament(abrasive)
            .selectQuality(QualityProfile.DRAFT)

        val brass = OnDeviceSlicer.slice(fixtureModel(), options(NozzleMaterial.BRASS))
        val hardened = OnDeviceSlicer.slice(
            fixtureModel(),
            options(NozzleMaterial.HARDENED_STEEL),
        )
        try {
            assertTrue(
                "Orca must warn when the active filament requires a harder nozzle",
                SliceWarningCode.NOZZLE_HARDNESS in brass.warnings,
            )
            assertFalse(
                "A compatible hardened-steel nozzle must not receive the warning",
                SliceWarningCode.NOZZLE_HARDNESS in hardened.warnings,
            )
            val brassGcode = brass.output.readText()
            assertTrue(brassGcode.contains("; nozzle_type = brass"))
            assertTrue(brassGcode.contains("; nozzle_hrc = 0"))
            assertTrue(brassGcode.contains("; required_nozzle_HRC = 40"))
        } finally {
            brass.output.delete()
            hardened.output.delete()
        }
    }

    @Test
    fun extrusionRateSmoothingChangesRealExtrusionMotion() {
        val baseQuality = QualityProfile.DRAFT.copy(
            extrusionRateSmoothing = ExtrusionRateSmoothingSettings(),
        )
        val base = SliceOptions()
            .selectQuality(baseQuality)
            .copy(
                printSpeed = 180f,
                innerWallSpeed = 180f,
                sparseInfillSpeed = 180f,
                internalSolidInfillSpeed = 140f,
                topSurfaceSpeed = 80f,
                gcodeSettings = GcodeSettings(arcFitting = true),
            )
        val plain = OnDeviceSlicer.slice(fixtureModel(), base)
        val smoothed = OnDeviceSlicer.slice(
            fixtureModel(),
            base.copy(
                quality = base.quality.copy(
                    extrusionRateSmoothing = ExtrusionRateSmoothingSettings(
                        maximumSlope = 20f,
                        segmentLength = 5f,
                        externalOnly = false,
                    ),
                ),
            ),
        )
        try {
            fun extrusionMotion(gcode: String): List<String> = gcode.lineSequence()
                .filter { line ->
                    line.startsWith("G1 ") && line.contains(" E") &&
                        (line.contains(" X") || line.contains(" Y"))
                }
                .map { it.substringBefore(';').trimEnd() }
                .toList()

            val plainGcode = plain.output.readText()
            val smoothedGcode = smoothed.output.readText()
            assertTrue(smoothedGcode.contains("; max_volumetric_extrusion_rate_slope = 20"))
            assertTrue(smoothedGcode.contains("; max_volumetric_extrusion_rate_slope_segment_length = 5"))
            assertTrue(smoothedGcode.contains("; extrusion_rate_smoothing_external_perimeter_only = 0"))
            assertTrue(smoothedGcode.contains("; enable_arc_fitting = 0"))
            assertNotEquals(
                "Pressure Equalizer must rewrite real extrusion motion, not only profile metadata",
                extrusionMotion(plainGcode),
                extrusionMotion(smoothedGcode),
            )
        } finally {
            plain.output.delete()
            smoothed.output.delete()
        }
    }

    @Test
    fun fuzzySkinChangesRealOuterWallGeometry() {
        val baseOptions = SliceOptions()
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                wallGenerator = "classic",
                perimeters = 2,
                fuzzySkin = FuzzySkinSettings(
                    type = "external",
                    firstLayer = true,
                    pointDistance = 0.5f,
                    thickness = 0.25f,
                    mode = "displacement",
                    noiseType = "classic",
                ),
            )
        val plain = OnDeviceSlicer.slice(
            fixtureModel(),
            baseOptions.copy(fuzzySkin = baseOptions.fuzzySkin.copy(type = "none")),
        )
        val fuzzy = OnDeviceSlicer.slice(fixtureModel(), baseOptions)
        try {
            fun outerWallExtrusionMoveCount(gcode: String): Int {
                var outerWall = false
                return gcode.lineSequence().count { line ->
                    if (line.startsWith(";TYPE:")) outerWall = line == ";TYPE:Outer wall"
                    outerWall && line.startsWith("G1 ") && line.contains(" E") &&
                        (line.contains(" X") || line.contains(" Y"))
                }
            }

            val plainGcode = plain.output.readText()
            val fuzzyGcode = fuzzy.output.readText()
            assertTrue(fuzzyGcode.contains("; fuzzy_skin = external"))
            assertTrue(fuzzyGcode.contains("; fuzzy_skin_first_layer = 1"))
            assertTrue(fuzzyGcode.contains("; fuzzy_skin_point_distance = 0.5"))
            assertTrue(fuzzyGcode.contains("; fuzzy_skin_thickness = 0.25"))
            assertTrue(
                "Fuzzy skin must add real points to exterior perimeter paths",
                outerWallExtrusionMoveCount(fuzzyGcode) > outerWallExtrusionMoveCount(plainGcode),
            )
        } finally {
            plain.output.delete()
            fuzzy.output.delete()
        }
    }

    @Test
    fun lockedZagDensityControlsChangeRealSparseInfillGeometry() {
        val base = SliceOptions()
            .selectPrinter(PrinterProfile.U1_04)
            .selectFilament(FilamentProfile.PLA)
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                fillPattern = "lockedzag",
                fillDensity = 0.28f,
                topSolidLayers = 2,
                bottomSolidLayers = 2,
                perimeters = 2,
            )
        val sparse = OnDeviceSlicer.slice(
            fixtureModel(),
            base.copy(
                quality = base.quality.copy(
                    skeletonInfillDensity = 12f,
                    skinInfillDensity = 18f,
                    skinInfillDepth = 1f,
                    infillLockDepth = 0.5f,
                ),
            ),
        )
        val dense = OnDeviceSlicer.slice(
            fixtureModel(),
            base.copy(
                quality = base.quality.copy(
                    skeletonInfillDensity = 62f,
                    skinInfillDensity = 78f,
                    skinInfillDepth = 4f,
                    infillLockDepth = 2f,
                ),
            ),
        )
        try {
            fun sparseInfillExtrusionMotion(gcode: String): List<String> {
                var sparseInfill = false
                return gcode.lineSequence().mapNotNull { line ->
                    if (line.startsWith(";TYPE:")) sparseInfill = line == ";TYPE:Sparse infill"
                    line.substringBefore(';').trimEnd().takeIf {
                        sparseInfill && it.startsWith("G1 ") && it.contains(" E") &&
                            (it.contains(" X") || it.contains(" Y"))
                    }
                }.toList()
            }

            val sparseGcode = sparse.output.readText()
            val denseGcode = dense.output.readText()
            val sparseMotion = sparseInfillExtrusionMotion(sparseGcode)
            val denseMotion = sparseInfillExtrusionMotion(denseGcode)
            assertTrue(sparseGcode.contains("; sparse_infill_pattern = lockedzag"))
            assertTrue(denseGcode.contains("; sparse_infill_pattern = lockedzag"))
            assertTrue("Locked Zag must generate real sparse-infill extrusion", sparseMotion.isNotEmpty())
            assertTrue("Locked Zag must generate real sparse-infill extrusion", denseMotion.isNotEmpty())
            assertNotEquals(
                "Locked Zag density/depth controls must change extrusion geometry, not only metadata",
                sparseMotion,
                denseMotion,
            )
        } finally {
            sparse.output.delete()
            dense.output.delete()
        }
    }

    @Test
    fun lockedZagShiftChangesRealSparseInfillGeometry() {
        val base = SliceOptions()
            .selectPrinter(PrinterProfile.U1_04)
            .selectFilament(FilamentProfile.PLA)
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                fillPattern = "lockedzag",
                fillDensity = 0.28f,
                topSolidLayers = 2,
                bottomSolidLayers = 2,
                perimeters = 2,
            )

        fun slice(shift: Float, symmetric: Boolean): SliceOutcome = OnDeviceSlicer.slice(
            fixtureModel(),
            base.copy(
                quality = base.quality.copy(
                    infillShiftStep = shift,
                    symmetricInfillYAxis = symmetric,
                ),
            ),
        )

        fun sparseInfillMotion(gcode: String): List<String> {
            var sparseInfill = false
            return gcode.lineSequence().mapNotNull { line ->
                if (line.startsWith(";TYPE:")) sparseInfill = line == ";TYPE:Sparse infill"
                line.substringBefore(';').trimEnd().takeIf {
                    sparseInfill && it.startsWith("G1 ") && it.contains(" E") &&
                        (it.contains(" X") || it.contains(" Y"))
                }
            }.toList()
        }

        val stationary = slice(0f, false)
        val shifted = slice(2f, true)
        try {
            val stationaryGcode = stationary.output.readText()
            val shiftedGcode = shifted.output.readText()
            val stationaryMotion = sparseInfillMotion(stationaryGcode)
            val shiftedMotion = sparseInfillMotion(shiftedGcode)

            assertTrue(stationaryGcode.contains("; infill_shift_step = 0"))
            assertTrue(stationaryGcode.contains("; symmetric_infill_y_axis = 0"))
            assertTrue(shiftedGcode.contains("; infill_shift_step = 2"))
            assertTrue(shiftedGcode.contains("; symmetric_infill_y_axis = 1"))
            assertTrue("Locked Zag must retain physical sparse infill", stationaryMotion.isNotEmpty())
            assertTrue("Shifted Locked Zag must retain physical sparse infill", shiftedMotion.isNotEmpty())
            assertNotEquals(
                "Infill shift must change real sparse-infill geometry, not only metadata",
                stationaryMotion,
                shiftedMotion,
            )
        } finally {
            stationary.output.delete()
            shifted.output.delete()
        }
    }

    @Test
    fun lateralInfillAnglesChangeRealSparseInfillGeometry() {
        val base = SliceOptions()
            .selectPrinter(PrinterProfile.U1_04)
            .selectFilament(FilamentProfile.PLA)
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                fillPattern = "lateral-lattice",
                fillDensity = 0.28f,
                topSolidLayers = 2,
                bottomSolidLayers = 2,
                perimeters = 2,
            )

        fun slice(pattern: String, settings: LateralInfillSettings): SliceOutcome =
            OnDeviceSlicer.slice(
                fixtureModel(),
                base.copy(
                    fillPattern = pattern,
                    quality = base.quality.copy(lateralInfill = settings),
                ),
            )

        fun sparseInfillMotion(gcode: String): List<String> {
            var sparseInfill = false
            return gcode.lineSequence().mapNotNull { line ->
                if (line.startsWith(";TYPE:")) sparseInfill = line == ";TYPE:Sparse infill"
                line.substringBefore(';').trimEnd().takeIf {
                    sparseInfill && it.startsWith("G1 ") && it.contains(" E") &&
                        (it.contains(" X") || it.contains(" Y"))
                }
            }.toList()
        }

        val defaults = slice("lateral-lattice", LateralInfillSettings())
        val angled = slice("lateral-lattice", LateralInfillSettings(-20f, 70f, 35f))
        val honeycomb = slice("lateral-honeycomb", LateralInfillSettings(-20f, 70f, 35f))
        try {
            val defaultsGcode = defaults.output.readText()
            val angledGcode = angled.output.readText()
            val honeycombGcode = honeycomb.output.readText()
            val defaultsMotion = sparseInfillMotion(defaultsGcode)
            val angledMotion = sparseInfillMotion(angledGcode)
            val honeycombMotion = sparseInfillMotion(honeycombGcode)

            assertTrue(defaultsGcode.contains("; sparse_infill_pattern = lateral-lattice"))
            assertTrue(defaultsGcode.contains("; lateral_lattice_angle_1 = -45"))
            assertTrue(defaultsGcode.contains("; lateral_lattice_angle_2 = 45"))
            assertTrue(defaultsGcode.contains("; infill_overhang_angle = 60"))
            assertTrue(angledGcode.contains("; lateral_lattice_angle_1 = -20"))
            assertTrue(angledGcode.contains("; lateral_lattice_angle_2 = 70"))
            assertTrue(angledGcode.contains("; infill_overhang_angle = 35"))
            assertTrue(honeycombGcode.contains("; sparse_infill_pattern = lateral-honeycomb"))
            assertTrue("Lateral Lattice must generate sparse infill", defaultsMotion.isNotEmpty())
            assertTrue("Angled Lateral Lattice must generate sparse infill", angledMotion.isNotEmpty())
            assertTrue("Lateral Honeycomb must generate sparse infill", honeycombMotion.isNotEmpty())
            assertNotEquals(
                "Lateral infill angles must change extrusion geometry, not only metadata",
                defaultsMotion,
                angledMotion,
            )
            assertNotEquals(
                "Lateral Honeycomb and Lattice must remain distinct compiled patterns",
                angledMotion,
                honeycombMotion,
            )
        } finally {
            defaults.output.delete()
            angled.output.delete()
            honeycomb.output.delete()
        }
    }

    @Test
    fun everyCompiledSparsePatternAndMultilineReachRealFillGeometry() {
        val base = SliceOptions()
            .selectPrinter(PrinterProfile.U1_04)
            .selectFilament(FilamentProfile.PLA)
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                fillDensity = 0.24f,
                topSolidLayers = 2,
                bottomSolidLayers = 2,
                perimeters = 2,
            )

        fun slice(pattern: String, multiline: Int): SliceOutcome = OnDeviceSlicer.slice(
            fixtureModel(),
            base.copy(
                fillPattern = pattern,
                quality = base.quality.copy(fillMultiline = multiline),
            ),
        )

        fun sparseInfillMotion(gcode: String): List<String> {
            var sparseInfill = false
            return gcode.lineSequence().mapNotNull { line ->
                if (line.startsWith(";TYPE:")) sparseInfill = line == ";TYPE:Sparse infill"
                line.substringBefore(';').trimEnd().takeIf {
                    sparseInfill && it.startsWith("G1 ") && it.contains(" E") &&
                        (it.contains(" X") || it.contains(" Y"))
                }
            }.toList()
        }

        for (pattern in SPARSE_INFILL_PATTERNS) {
            val outcome = slice(pattern, 1)
            try {
                val gcode = outcome.output.readText()
                assertTrue(
                    "$pattern must survive strict Orca enum deserialization",
                    gcode.contains("; sparse_infill_pattern = $pattern"),
                )
                assertTrue("$pattern must produce a non-empty G-code file", gcode.contains("G1 "))
            } finally {
                outcome.output.delete()
            }
        }

        val single = slice("crosshatch", 1)
        val fourLines = slice("crosshatch", 4)
        try {
            val singleGcode = single.output.readText()
            val fourLineGcode = fourLines.output.readText()
            val singleMotion = sparseInfillMotion(singleGcode)
            val fourLineMotion = sparseInfillMotion(fourLineGcode)

            assertTrue(singleGcode.contains("; fill_multiline = 1"))
            assertTrue(fourLineGcode.contains("; fill_multiline = 4"))
            assertTrue("Single-line Cross Hatch must produce sparse infill", singleMotion.isNotEmpty())
            assertTrue("Four-line Cross Hatch must produce sparse infill", fourLineMotion.isNotEmpty())
            assertNotEquals(
                "Fill Multiline must change extrusion geometry, not only metadata",
                singleMotion,
                fourLineMotion,
            )
        } finally {
            single.output.delete()
            fourLines.output.delete()
        }
    }

    @Test
    fun printableOverhangsChangeRealModelGeometry() {
        val baseOptions = SliceOptions()
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                perimeters = 2,
                supportEnabled = false,
                precision = PrecisionSettings(
                    printableOverhangs = PrintableOverhangSettings(
                        enabled = false,
                        maximumAngle = 30f,
                        holeArea = 0f,
                    ),
                ),
            )
        val model = supportPaintOverhangModel()
        val plain = OnDeviceSlicer.slice(model, baseOptions)
        val printable = OnDeviceSlicer.slice(
            model,
            baseOptions.copy(
                precision = baseOptions.precision.copy(
                    printableOverhangs = baseOptions.printableOverhangs.copy(enabled = true),
                ),
            ),
        )
        try {
            fun outerWallLength(gcode: File): Float {
                val preview = loadGcodePreview(gcode.absolutePath, 0, Int.MAX_VALUE)
                var length = 0f
                preview.segments.indices.step(GcodeLayerPreview.SEGMENT_STRIDE).forEach { offset ->
                    if (preview.segments[offset + 5].toInt() != 0) return@forEach
                    val dx = preview.segments[offset + 2] - preview.segments[offset]
                    val dy = preview.segments[offset + 3] - preview.segments[offset + 1]
                    length += sqrt(dx * dx + dy * dy)
                }
                return length
            }

            val printableGcode = printable.output.readText()
            assertTrue(printableGcode.contains("; make_overhang_printable = 1"))
            assertTrue(printableGcode.contains("; make_overhang_printable_angle = 30"))
            assertTrue(printableGcode.contains("; make_overhang_printable_hole_size = 0"))
            assertTrue(
                "Printable-overhang processing must change real exterior toolpaths",
                abs(outerWallLength(printable.output) - outerWallLength(plain.output)) > 5f,
            )
        } finally {
            plain.output.delete()
            printable.output.delete()
        }
    }

    @Test
    fun polyholeSettingsChangeRealHoleGeometry() {
        val baseOptions = SliceOptions()
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                wallGenerator = "classic",
                perimeters = 2,
                fillDensity = 0f,
                topSolidLayers = 0,
                bottomSolidLayers = 0,
                precision = PrecisionSettings(
                    polyholes = PolyholeSettings(
                        enabled = false,
                        detectionMargin = 0.1f,
                        detectionMarginPercent = false,
                        twist = false,
                    ),
                ),
            )
        val model = polyholeModel()
        val plain = OnDeviceSlicer.slice(model, baseOptions)
        val converted = OnDeviceSlicer.slice(
            model,
            baseOptions.copy(
                precision = baseOptions.precision.copy(
                    polyholes = baseOptions.precision.polyholes.copy(enabled = true),
                ),
            ),
        )
        try {
            val gcode = converted.output.readText()
            assertTrue(gcode.contains("; hole_to_polyhole = 1"))
            assertTrue(gcode.contains("; hole_to_polyhole_threshold = 0.1"))
            assertTrue(gcode.contains("; hole_to_polyhole_twisted = 0"))
            val plainPreview = loadGcodePreview(plain.output.absolutePath, 0, Int.MAX_VALUE)
            val convertedPreview = loadGcodePreview(converted.output.absolutePath, 0, Int.MAX_VALUE)
            assertTrue(
                "Polyhole conversion must change real extrusion geometry, not only profile metadata",
                !plainPreview.segments.contentEquals(convertedPreview.segments),
            )
        } finally {
            plain.output.delete()
            converted.output.delete()
        }
    }

    @Test
    fun automaticBrimEarGeometryReachesTheRealEngine() {
        val base = SliceOptions()
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                brimType = "brim_ears",
                brimWidth = 6f,
                brimObjectGap = 0f,
                precision = PrecisionSettings(
                    brimEars = BrimEarSettings(maximumAngle = 0f, detectionRadius = 1.6f),
                ),
            )
        val disabled = OnDeviceSlicer.slice(fixtureModel(), base)
        val enabled = OnDeviceSlicer.slice(
            fixtureModel(),
            base.copy(
                precision = base.precision.copy(
                    brimEars = base.precision.brimEars.copy(maximumAngle = 125f),
                ),
            ),
        )
        try {
            val gcode = enabled.output.readText()
            assertTrue(gcode.contains("; brim_type = brim_ears"))
            assertTrue(gcode.contains("; brim_ears_max_angle = 125"))
            assertTrue(gcode.contains("; brim_ears_detection_length = 1.6"))
            assertTrue(
                "Automatic Brim ears must add actual extrusion geometry when enabled",
                enabled.filamentMm > disabled.filamentMm,
            )
        } finally {
            disabled.output.delete()
            enabled.output.delete()
        }
    }

    @Test
    fun gradualInitialLayerSpeedChangesRealPrintTime() {
        val base = SliceOptions()
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                printSpeed = 150f,
                innerWallSpeed = 150f,
                sparseInfillSpeed = 150f,
                internalSolidInfillSpeed = 150f,
                topSurfaceSpeed = 150f,
                firstLayerSpeed = 10f,
                firstLayerInfillSpeed = 10f,
                gcodeSettings = GcodeSettings(slowDownLayers = 0),
            )
        val plain = OnDeviceSlicer.slice(fixtureModel(), base)
        val ramped = OnDeviceSlicer.slice(
            fixtureModel(),
            base.copy(gcodeSettings = base.gcodeSettings.copy(slowDownLayers = 12)),
        )
        try {
            val rampedGcode = ramped.output.readText()
            assertTrue(rampedGcode.contains("; slow_down_layers = 12"))
            assertTrue(
                "Gradual initial-layer speeds must increase real estimated print time",
                ramped.estimatedSeconds > plain.estimatedSeconds + 1f,
            )
        } finally {
            plain.output.delete()
            ramped.output.delete()
        }
    }

    @Test
    fun inheritedMotionOutputChangesFirstLayerTravelAndKlipperLimits() {
        val base = SliceOptions()
            .selectPrinter(PrinterProfile.U1_04.copy(gcodeFlavor = "klipper"))
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                travelSpeed = 400f,
                defaultAcceleration = 2_000f,
                travelAcceleration = 4_000f,
            )
        val absolute = OnDeviceSlicer.slice(
            fixtureModel(),
            base.copy(
                firstLayerTravelAcceleration = 1_111f,
                firstLayerTravelAccelerationPercent = false,
                gcodeSettings = GcodeSettings(
                    initialLayerTravelSpeed = 40f,
                    initialLayerTravelSpeedPercent = false,
                    accelToDecelEnabled = true,
                    accelToDecelFactor = 25f,
                ),
            ),
        )
        val percentage = OnDeviceSlicer.slice(
            fixtureModel(),
            base.copy(
                firstLayerTravelAcceleration = 37f,
                firstLayerTravelAccelerationPercent = true,
                gcodeSettings = GcodeSettings(
                    initialLayerTravelSpeed = 25f,
                    initialLayerTravelSpeedPercent = true,
                    accelToDecelEnabled = false,
                    accelToDecelFactor = 25f,
                ),
            ),
        )
        try {
            fun firstLayerTravelFeeds(gcode: String): Set<Float> {
                val lines = gcode.lineSequence().toList()
                val layerMarkers = lines.indices.filter { lines[it] == ";LAYER_CHANGE" }
                assertTrue("A real slice needs at least two layers", layerMarkers.size >= 2)
                return lines.subList(layerMarkers[0] + 1, layerMarkers[1])
                    .asSequence()
                    .filter { line ->
                        (line.startsWith("G0 ") || line.startsWith("G1 ")) &&
                            !line.contains(" E") &&
                            (line.contains(" X") || line.contains(" Y"))
                    }
                    .mapNotNull { line ->
                        Regex("(?:^| )F([0-9.]+)").find(line)?.groupValues?.get(1)?.toFloatOrNull()
                    }
                    .toSet()
            }

            val absoluteGcode = absolute.output.readText()
            val percentageGcode = percentage.output.readText()
            assertTrue(
                "An absolute 40 mm/s first-layer travel must emit F2400",
                2_400f in firstLayerTravelFeeds(absoluteGcode),
            )
            assertTrue(
                "25% of a 400 mm/s travel speed must emit F6000",
                6_000f in firstLayerTravelFeeds(percentageGcode),
            )
            fun throughFirstLayerAccelerationCommands(gcode: String): List<Float> {
                val lines = gcode.lineSequence().toList()
                val layerMarkers = lines.indices.filter { lines[it] == ";LAYER_CHANGE" }
                assertTrue("A real slice needs at least two layers", layerMarkers.size >= 2)
                return lines.subList(0, layerMarkers[1])
                    .mapNotNull { line ->
                        Regex("SET_VELOCITY_LIMIT ACCEL=([0-9.]+)")
                            .find(line)?.groupValues?.get(1)?.toFloatOrNull()
                    }
            }
            val absoluteAccelerations = throughFirstLayerAccelerationCommands(absoluteGcode)
            val percentageAccelerations = throughFirstLayerAccelerationCommands(percentageGcode)
            assertTrue(
                "Absolute first-layer travel acceleration must change emitted motion commands; actual=$absoluteAccelerations",
                1_111f in absoluteAccelerations,
            )
            assertTrue(
                "37% of 4000 mm/s² travel acceleration must emit 1480 mm/s²; actual=$percentageAccelerations",
                1_480f in percentageAccelerations,
            )
            val velocityLimits = Regex(
                "SET_VELOCITY_LIMIT ACCEL=([0-9.]+) ACCEL_TO_DECEL=([0-9.]+)",
            ).findAll(absoluteGcode).toList()
            assertTrue("Enabled Klipper smoothing must emit acceleration limits", velocityLimits.isNotEmpty())
            assertTrue(
                "Every emitted accel-to-decel limit must retain the selected 25% factor",
                velocityLimits.all { match ->
                    val acceleration = match.groupValues[1].toFloat()
                    val deceleration = match.groupValues[2].toFloat()
                    abs(deceleration - acceleration * 0.25f) < 0.01f
                },
            )
            assertFalse(percentageGcode.contains("ACCEL_TO_DECEL="))
            assertTrue(absoluteGcode.contains("; initial_layer_travel_speed = 40"))
            assertTrue(absoluteGcode.contains("; accel_to_decel_enable = 1"))
            assertTrue(absoluteGcode.contains("; accel_to_decel_factor = 25%"))
        } finally {
            absolute.output.delete()
            percentage.output.delete()
        }
    }

    @Test
    fun verticalTravelSpeedChangesRealZOnlyMotion() {
        val base = SliceOptions()
            .selectQuality(QualityProfile.DRAFT.copy(travelSpeedZ = 0f))
            .copy(travelSpeed = 400f)
        val inherited = OnDeviceSlicer.slice(fixtureModel(), base)
        val explicit = OnDeviceSlicer.slice(
            fixtureModel(),
            base.copy(quality = base.quality.copy(travelSpeedZ = 17f)),
        )
        try {
            fun zOnlyFeeds(gcode: String): Set<Float> = gcode.lineSequence()
                .filter { line ->
                    (line.startsWith("G0 ") || line.startsWith("G1 ")) &&
                        line.contains(" Z") && !line.contains(" X") &&
                        !line.contains(" Y") && !line.contains(" E")
                }
                .mapNotNull { line ->
                    Regex("(?:^| )F([0-9.]+)").find(line)?.groupValues?.get(1)?.toFloatOrNull()
                }
                .toSet()

            val inheritedGcode = inherited.output.readText()
            val explicitGcode = explicit.output.readText()
            assertTrue(inheritedGcode.contains("; travel_speed_z = 0"))
            assertTrue(explicitGcode.contains("; travel_speed_z = 17"))
            assertTrue(
                "A 17 mm/s vertical speed must emit F1020 on real Z-only motion",
                1_020f in zOnlyFeeds(explicitGcode),
            )
            assertFalse(
                "The inherited travel-speed path must not emit the explicit Z feedrate",
                1_020f in zOnlyFeeds(inheritedGcode),
            )
        } finally {
            inherited.output.delete()
            explicit.output.delete()
        }
    }

    @Test
    fun depthPreviewPrewarmsGestureVboAndReusesItAcrossCameraFrames() {
        val framebufferSize = 256
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertNotEquals("EGL display must be available", EGL14.EGL_NO_DISPLAY, display)
        val version = IntArray(2)
        assertTrue("EGL must initialize", EGL14.eglInitialize(display, version, 0, version, 1))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val configCount = IntArray(1)
        val configAttributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE,
            EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE,
            EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE,
            8,
            EGL14.EGL_GREEN_SIZE,
            8,
            EGL14.EGL_BLUE_SIZE,
            8,
            EGL14.EGL_DEPTH_SIZE,
            24,
            EGL14.EGL_NONE,
        )
        assertTrue(
            "An OpenGL ES 3 pbuffer config must be available",
            EGL14.eglChooseConfig(
                display,
                configAttributes,
                0,
                configs,
                0,
                configs.size,
                configCount,
                0,
            ) && configCount[0] == 1,
        )
        val config = checkNotNull(configs[0])
        val context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
            0,
        )
        assertNotEquals("OpenGL ES 3 context creation must succeed", EGL14.EGL_NO_CONTEXT, context)
        val surface = EGL14.eglCreatePbufferSurface(
            display,
            config,
            intArrayOf(
                EGL14.EGL_WIDTH,
                framebufferSize,
                EGL14.EGL_HEIGHT,
                framebufferSize,
                EGL14.EGL_NONE,
            ),
            0,
        )
        assertNotEquals("EGL pbuffer creation must succeed", EGL14.EGL_NO_SURFACE, surface)
        try {
            assertTrue(
                "The pbuffer must become current",
                EGL14.eglMakeCurrent(display, surface, surface, context),
            )
            val preview = GcodeLayerPreview(
                startLayer = 0,
                endLayer = 0,
                layerCount = 1,
                minZMm = 0.2f,
                maxZMm = 0.2f,
                segments = floatArrayOf(10f, 10f, 20f, 10f, 0.2f, 0f, 0f),
                roleSegmentCounts = intArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            )
            val scene = ToolpathScene(preview, 100f, 100f, 1f, 0.8f, PreviewDetail.BALANCED)
            val renderer = ToolpathRenderer()
            renderer.submit(scene)
            renderer.onSurfaceCreated(null, null)
            renderer.onSurfaceChanged(null, framebufferSize, framebufferSize)
            renderer.onDrawFrame(null)

            assertEquals(
                "The first frame must upload one coherent low-cost geometry set",
                1,
                renderer.geometryUploadCountForTest(),
            )
            assertEquals("The instanced draw must be valid", GLES30.GL_NO_ERROR, GLES30.glGetError())

            renderer.onDrawFrame(null)
            assertEquals(
                "The next idle frame must upload the requested detail geometry set",
                2,
                renderer.geometryUploadCountForTest(),
            )
            assertEquals(
                "Only the requested and gesture geometry sets may stay resident",
                2,
                renderer.cachedGeometryCountForTest(),
            )

            renderer.orbitBy(12f, -7f)
            renderer.zoomBy(1.1f)
            renderer.onDrawFrame(null)
            assertEquals(
                "Camera-only frames must reuse the uploaded GPU buffers",
                2,
                renderer.geometryUploadCountForTest(),
            )
            assertEquals("The reused VBO draw must be valid", GLES30.GL_NO_ERROR, GLES30.glGetError())

            renderer.applyCameraPreset(WorkspaceCameraPreset.RIGHT)
            assertEquals(
                cameraPoseForPreset(WorkspaceCameraPreset.RIGHT),
                renderer.cameraPoseForTest(),
            )
            renderer.onDrawFrame(null)
            assertEquals(
                "Changing a camera preset must not rebuild or upload toolpath geometry",
                2,
                renderer.geometryUploadCountForTest(),
            )

            renderer.setInteractionActive(true)
            renderer.onDrawFrame(null)
            assertEquals(
                "Starting a gesture must reuse the prewarmed lower-detail geometry",
                2,
                renderer.geometryUploadCountForTest(),
            )
            renderer.orbitBy(-8f, 5f)
            renderer.onDrawFrame(null)
            assertEquals(
                "Every subsequent gesture frame must reuse the lower-detail geometry",
                2,
                renderer.geometryUploadCountForTest(),
            )
            assertEquals("The gesture VBO draw must be valid", GLES30.GL_NO_ERROR, GLES30.glGetError())

            renderer.setInteractionActive(false)
            renderer.onDrawFrame(null)
            assertEquals(
                "Settling after a gesture must reuse the requested geometry",
                2,
                renderer.geometryUploadCountForTest(),
            )

            renderer.submit(scene.copy(visibleRoles = setOf(1)))
            renderer.onDrawFrame(null)
            assertEquals(
                "A geometry change must replace the GPU buffers exactly once",
                3,
                renderer.geometryUploadCountForTest(),
            )
            assertEquals(
                "Old-scene GPU buffers must be released before the new gesture tier is prewarmed",
                1,
                renderer.cachedGeometryCountForTest(),
            )
            renderer.onDrawFrame(null)
            assertEquals(
                "Changed geometry must prewarm its gesture VBO without growing the cache",
                4,
                renderer.geometryUploadCountForTest(),
            )
            assertEquals(
                "The GPU cache must remain bounded to two geometry sets",
                2,
                renderer.cachedGeometryCountForTest(),
            )
            assertEquals("The replacement VBO draw must be valid", GLES30.GL_NO_ERROR, GLES30.glGetError())

            val uploadsBeforeTrim = renderer.geometryUploadCountForTest()
            renderer.releaseGpuGeometryForMemoryPressure()
            assertEquals(
                "UI memory pressure must release every reconstructable preview buffer",
                0,
                renderer.cachedGeometryCountForTest(),
            )
            assertEquals("Releasing preview VBOs must be valid", GLES30.GL_NO_ERROR, GLES30.glGetError())
            renderer.onDrawFrame(null)
            assertEquals(
                "The first frame after memory pressure must rebuild the low-cost geometry once",
                uploadsBeforeTrim + 1,
                renderer.geometryUploadCountForTest(),
            )
            assertEquals(1, renderer.cachedGeometryCountForTest())
            assertEquals("The rebuilt VBO draw must be valid", GLES30.GL_NO_ERROR, GLES30.glGetError())

            val withoutToolpath = framebufferRgba(framebufferSize, framebufferSize)
            renderer.submit(scene.copy(visibleRoles = setOf(0)))
            renderer.onDrawFrame(null)
            val withToolpath = framebufferRgba(framebufferSize, framebufferSize)
            assertFalse(
                "Instanced toolpath must change the rendered framebuffer",
                withoutToolpath.contentEquals(withToolpath),
            )
            assertEquals(
                "Instanced toolpath framebuffer readback must be valid",
                GLES30.GL_NO_ERROR,
                GLES30.glGetError(),
            )

            val adaptiveRenderer = ToolpathRenderer()
            adaptiveRenderer.submit(scene.copy(detail = PreviewDetail.AUTOMATIC))
            adaptiveRenderer.onSurfaceCreated(null, null)
            adaptiveRenderer.onSurfaceChanged(null, framebufferSize, framebufferSize)
            val maximumAutomaticCalibrationFrames =
                ADAPTIVE_PREVIEW_FAST_SAMPLE_COUNT * PreviewDetail.entries.size + 10
            repeat(maximumAutomaticCalibrationFrames) { adaptiveRenderer.onDrawFrame(null) }
            assertEquals(
                "A trivial Preview workload must promote Automatic through measured tiers",
                PreviewDetail.DETAIL,
                adaptiveRenderer.effectiveDetailForTest(),
            )
            assertTrue(
                "Automatic calibration must settle after bounded completed-frame samples",
                adaptiveRenderer.automaticCalibrationSettledForTest(),
            )
            assertTrue(
                "Automatic promotion must retain at most settled and gesture VBOs",
                adaptiveRenderer.cachedGeometryCountForTest() <= 2,
            )
            assertEquals(
                "Automatic GPU completion calibration must leave GLES valid",
                GLES30.GL_NO_ERROR,
                GLES30.glGetError(),
            )
            adaptiveRenderer.releaseGpuGeometryForMemoryPressure()

            var requestedGeometry: Pair<ToolpathScene, Int>? = null
            val asynchronousRenderer = ToolpathRenderer(
                requestGeometryBuild = { requested, generation ->
                    requestedGeometry = requested to generation
                },
            )
            asynchronousRenderer.submit(scene)
            asynchronousRenderer.onSurfaceCreated(null, null)
            asynchronousRenderer.onSurfaceChanged(null, framebufferSize, framebufferSize)
            asynchronousRenderer.onDrawFrame(null)
            assertEquals(
                "An asynchronous renderer must not build geometry on its GL thread",
                0,
                asynchronousRenderer.geometryUploadCountForTest(),
            )
            val (requested, requestedGeneration) = checkNotNull(requestedGeometry)
            assertTrue(
                asynchronousRenderer.submitPreparedGeometry(
                    requested,
                    ToolpathMeshBuilder.build(requested),
                    requestedGeneration,
                ),
            )
            asynchronousRenderer.onDrawFrame(null)
            assertEquals(
                "Prepared CPU geometry must upload exactly once on the GL thread",
                1,
                asynchronousRenderer.geometryUploadCountForTest(),
            )
            requestedGeometry = null
            val retainedFrame = framebufferRgba(framebufferSize, framebufferSize)
            asynchronousRenderer.onDrawFrame(null)
            assertTrue("Refined geometry must be requested in the background", requestedGeometry != null)
            assertEquals(
                "The last compatible GPU frame must remain visible during refinement",
                1,
                asynchronousRenderer.fallbackFrameCountForTest(),
            )
            assertTrue(
                "Background refinement must not clear the visible Preview",
                retainedFrame.contentEquals(framebufferRgba(framebufferSize, framebufferSize)),
            )
            assertEquals(
                "Asynchronous geometry upload must leave GLES valid",
                GLES30.GL_NO_ERROR,
                GLES30.glGetError(),
            )
            val staleRequest = checkNotNull(requestedGeometry)
            val stalePayload = ToolpathMeshBuilder.build(staleRequest.first)
            asynchronousRenderer.releaseGpuGeometryForMemoryPressure()
            assertFalse(
                "A geometry result started before memory pressure must not repopulate CPU buffers",
                asynchronousRenderer.submitPreparedGeometry(
                    staleRequest.first,
                    stalePayload,
                    staleRequest.second,
                ),
            )
            assertEquals(0, asynchronousRenderer.preparedGeometryCountForTest())

            var rendererFailures = 0
            val failingRenderer = ToolpathRenderer(
                reportUnavailable = { rendererFailures += 1 },
                programFactory = { _, _ -> 0 },
            )
            failingRenderer.submit(scene)
            failingRenderer.onSurfaceCreated(null, null)
            failingRenderer.onSurfaceChanged(null, framebufferSize, framebufferSize)
            failingRenderer.onDrawFrame(null)
            assertEquals(
                "A failed depth renderer must request compatibility fallback exactly once",
                1,
                rendererFailures,
            )
        } finally {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    private fun framebufferRgba(width: Int, height: Int): ByteArray {
        val pixels = ByteBuffer.allocateDirect(width * height * 4)
        GLES30.glReadPixels(
            0,
            0,
            width,
            height,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            pixels,
        )
        pixels.position(0)
        return ByteArray(pixels.remaining()).also(pixels::get)
    }

    private fun outerWallBounds(gcode: File): ToolpathBounds {
        val preview = loadGcodePreview(gcode.absolutePath, 0, Int.MAX_VALUE)
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        preview.segments.indices.step(GcodeLayerPreview.SEGMENT_STRIDE).forEach { offset ->
            if (preview.segments[offset + 5].toInt() != 0) return@forEach
            minX = minOf(minX, preview.segments[offset], preview.segments[offset + 2])
            minY = minOf(minY, preview.segments[offset + 1], preview.segments[offset + 3])
            maxX = maxOf(maxX, preview.segments[offset], preview.segments[offset + 2])
            maxY = maxOf(maxY, preview.segments[offset + 1], preview.segments[offset + 3])
        }
        check(minX.isFinite() && minY.isFinite() && maxX.isFinite() && maxY.isFinite()) {
            "No outer-wall extrusion coordinates"
        }
        return ToolpathBounds(minX, minY, maxX, maxY)
    }

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

    private fun twoIslandRetractionModel(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val destination = File(context.cacheDir, "two-island-retraction.stl")
        val facets = mutableListOf<List<TestVertex>>()

        fun vertex(x: Float, y: Float, z: Float) = TestVertex(x, y, z)
        fun quad(a: TestVertex, b: TestVertex, c: TestVertex, d: TestVertex) {
            facets += listOf(a, b, c)
            facets += listOf(a, c, d)
        }
        fun cube(x0: Float, x1: Float) {
            val y0 = 0f
            val y1 = 10f
            val z0 = 0f
            val z1 = 10f
            quad(vertex(x0, y0, z0), vertex(x1, y0, z0), vertex(x1, y0, z1), vertex(x0, y0, z1))
            quad(vertex(x1, y0, z0), vertex(x1, y1, z0), vertex(x1, y1, z1), vertex(x1, y0, z1))
            quad(vertex(x1, y1, z0), vertex(x0, y1, z0), vertex(x0, y1, z1), vertex(x1, y1, z1))
            quad(vertex(x0, y1, z0), vertex(x0, y0, z0), vertex(x0, y0, z1), vertex(x0, y1, z1))
            quad(vertex(x0, y0, z1), vertex(x1, y0, z1), vertex(x1, y1, z1), vertex(x0, y1, z1))
            quad(vertex(x0, y1, z0), vertex(x1, y1, z0), vertex(x1, y0, z0), vertex(x0, y0, z0))
        }
        cube(0f, 10f)
        cube(30f, 40f)

        destination.bufferedWriter().use { writer ->
            writer.appendLine("solid two_island_retraction")
            facets.forEach { triangle ->
                writer.appendLine("facet normal 0 0 0")
                writer.appendLine("outer loop")
                triangle.forEach { point ->
                    writer.appendLine("vertex ${point.x} ${point.y} ${point.z}")
                }
                writer.appendLine("endloop")
                writer.appendLine("endfacet")
            }
            writer.appendLine("endsolid two_island_retraction")
        }
        return destination
    }

    private fun tiltedAutoOrientModel(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val destination = File(context.cacheDir, "tilted-auto-orient.stl")
        val facets = mutableListOf<List<TestVertex>>()
        val radians = Math.toRadians(37.0)
        val cosAngle = cos(radians).toFloat()
        val sinAngle = sin(radians).toFloat()

        fun vertex(x: Float, y: Float, z: Float): TestVertex {
            val rotatedY = y * cosAngle - z * sinAngle
            val rotatedZ = y * sinAngle + z * cosAngle
            return TestVertex(x, rotatedY, rotatedZ + 20f)
        }
        fun quad(a: TestVertex, b: TestVertex, c: TestVertex, d: TestVertex) {
            facets += listOf(a, b, c)
            facets += listOf(a, c, d)
        }

        val x0 = -30f
        val x1 = 30f
        val y0 = -15f
        val y1 = 15f
        val z0 = -5f
        val z1 = 5f
        quad(vertex(x0, y0, z0), vertex(x1, y0, z0), vertex(x1, y0, z1), vertex(x0, y0, z1))
        quad(vertex(x1, y0, z0), vertex(x1, y1, z0), vertex(x1, y1, z1), vertex(x1, y0, z1))
        quad(vertex(x1, y1, z0), vertex(x0, y1, z0), vertex(x0, y1, z1), vertex(x1, y1, z1))
        quad(vertex(x0, y1, z0), vertex(x0, y0, z0), vertex(x0, y0, z1), vertex(x0, y1, z1))
        quad(vertex(x0, y0, z1), vertex(x1, y0, z1), vertex(x1, y1, z1), vertex(x0, y1, z1))
        quad(vertex(x0, y1, z0), vertex(x1, y1, z0), vertex(x1, y0, z0), vertex(x0, y0, z0))

        destination.bufferedWriter().use { writer ->
            writer.appendLine("solid tilted_auto_orient")
            facets.forEach { triangle ->
                writer.appendLine("facet normal 0 0 0")
                writer.appendLine("outer loop")
                triangle.forEach { point ->
                    writer.appendLine("vertex ${point.x} ${point.y} ${point.z}")
                }
                writer.appendLine("endloop")
                writer.appendLine("endfacet")
            }
            writer.appendLine("endsolid tilted_auto_orient")
        }
        return destination
    }

    private fun hollowTubeModel(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val destination = File(context.cacheDir, "hollow-tube-topology.stl")
        val facets = mutableListOf<List<TestVertex>>()

        fun vertex(x: Float, y: Float, z: Float) = TestVertex(x, y, z)
        fun quad(a: TestVertex, b: TestVertex, c: TestVertex, d: TestVertex) {
            facets += listOf(a, b, c)
            facets += listOf(a, c, d)
        }

        val z0 = 0f
        val z1 = 20f
        val low = 0f
        val high = 30f
        val holeLow = 10f
        val holeHigh = 20f

        // Outer walls, wound toward the air outside the part.
        quad(vertex(low, low, z0), vertex(high, low, z0), vertex(high, low, z1), vertex(low, low, z1))
        quad(vertex(high, low, z0), vertex(high, high, z0), vertex(high, high, z1), vertex(high, low, z1))
        quad(vertex(high, high, z0), vertex(low, high, z0), vertex(low, high, z1), vertex(high, high, z1))
        quad(vertex(low, high, z0), vertex(low, low, z0), vertex(low, low, z1), vertex(low, high, z1))

        // Cavity walls use the opposite winding, toward the empty center.
        quad(vertex(holeLow, holeLow, z0), vertex(holeLow, holeLow, z1), vertex(holeHigh, holeLow, z1), vertex(holeHigh, holeLow, z0))
        quad(vertex(holeHigh, holeLow, z0), vertex(holeHigh, holeLow, z1), vertex(holeHigh, holeHigh, z1), vertex(holeHigh, holeHigh, z0))
        quad(vertex(holeHigh, holeHigh, z0), vertex(holeHigh, holeHigh, z1), vertex(holeLow, holeHigh, z1), vertex(holeLow, holeHigh, z0))
        quad(vertex(holeLow, holeHigh, z0), vertex(holeLow, holeHigh, z1), vertex(holeLow, holeLow, z1), vertex(holeLow, holeLow, z0))

        // Close the annulus at the top and bottom without filling the cavity.
        val strips = listOf(
            arrayOf(low, high, low, holeLow),
            arrayOf(low, high, holeHigh, high),
            arrayOf(low, holeLow, holeLow, holeHigh),
            arrayOf(holeHigh, high, holeLow, holeHigh),
        )
        strips.forEach { (x0, x1, y0, y1) ->
            quad(vertex(x0, y0, z1), vertex(x1, y0, z1), vertex(x1, y1, z1), vertex(x0, y1, z1))
            quad(vertex(x0, y1, z0), vertex(x1, y1, z0), vertex(x1, y0, z0), vertex(x0, y0, z0))
        }

        destination.bufferedWriter().use { writer ->
            writer.appendLine("solid hollow_tube")
            facets.forEach { triangle ->
                writer.appendLine("  facet normal 0 0 0")
                writer.appendLine("    outer loop")
                triangle.forEach { point ->
                    writer.appendLine("      vertex ${point.x} ${point.y} ${point.z}")
                }
                writer.appendLine("    endloop")
                writer.appendLine("  endfacet")
            }
            writer.appendLine("endsolid hollow_tube")
        }
        return destination
    }

    private fun polyholeModel(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val destination = File(context.cacheDir, "polyhole-ring.stl")
        val facets = mutableListOf<List<TestVertex>>()
        val segments = 32
        val outerRadius = 18f
        val innerRadius = 6f
        val height = 8f

        fun ring(radius: Float, z: Float) = Array(segments) { index ->
            val angle = 2.0 * Math.PI * index / segments
            TestVertex((cos(angle) * radius).toFloat(), (sin(angle) * radius).toFloat(), z)
        }

        val outerBottom = ring(outerRadius, 0f)
        val outerTop = ring(outerRadius, height)
        val innerBottom = ring(innerRadius, 0f)
        val innerTop = ring(innerRadius, height)
        repeat(segments) { index ->
            val next = (index + 1) % segments
            facets += listOf(outerBottom[index], outerBottom[next], outerTop[next])
            facets += listOf(outerBottom[index], outerTop[next], outerTop[index])
            facets += listOf(innerBottom[index], innerTop[next], innerBottom[next])
            facets += listOf(innerBottom[index], innerTop[index], innerTop[next])
            facets += listOf(outerTop[index], outerTop[next], innerTop[next])
            facets += listOf(outerTop[index], innerTop[next], innerTop[index])
            facets += listOf(outerBottom[index], innerBottom[next], outerBottom[next])
            facets += listOf(outerBottom[index], innerBottom[index], innerBottom[next])
        }

        destination.bufferedWriter().use { writer ->
            writer.appendLine("solid polyhole_ring")
            facets.forEach { triangle ->
                writer.appendLine("facet normal 0 0 0")
                writer.appendLine("outer loop")
                triangle.forEach { point ->
                    writer.appendLine("vertex ${point.x} ${point.y} ${point.z}")
                }
                writer.appendLine("endloop")
                writer.appendLine("endfacet")
            }
            writer.appendLine("endsolid polyhole_ring")
        }
        return destination
    }

    private fun cylinderModel(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val destination = File(context.cacheDir, "scarf-seam-cylinder.stl")
        val facets = mutableListOf<List<TestVertex>>()
        val segments = 64
        val radius = 15f
        val height = 20f
        val bottomCenter = TestVertex(0f, 0f, 0f)
        val topCenter = TestVertex(0f, 0f, height)
        val bottom = Array(segments) { index ->
            val angle = 2.0 * Math.PI * index / segments
            TestVertex((cos(angle) * radius).toFloat(), (sin(angle) * radius).toFloat(), 0f)
        }
        val top = Array(segments) { index -> bottom[index].copy(z = height) }

        repeat(segments) { index ->
            val next = (index + 1) % segments
            facets += listOf(bottom[index], bottom[next], top[next])
            facets += listOf(bottom[index], top[next], top[index])
            facets += listOf(bottomCenter, bottom[next], bottom[index])
            facets += listOf(topCenter, top[index], top[next])
        }
        destination.bufferedWriter().use { writer ->
            writer.appendLine("solid scarf_seam_cylinder")
            facets.forEach { triangle ->
                writer.appendLine("facet normal 0 0 0")
                writer.appendLine("outer loop")
                triangle.forEach { point ->
                    writer.appendLine("vertex ${point.x} ${point.y} ${point.z}")
                }
                writer.appendLine("endloop")
                writer.appendLine("endfacet")
            }
            writer.appendLine("endsolid scarf_seam_cylinder")
        }
        return destination
    }

    private fun supportPaintOverhangModel(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val destination = File(context.cacheDir, "support-paint-overhang.stl")
        val facets = mutableListOf<List<TestVertex>>()

        fun vertex(x: Float, y: Float, z: Float) = TestVertex(x, y, z)
        fun quad(a: TestVertex, b: TestVertex, c: TestVertex, d: TestVertex) {
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
            writer.appendLine("solid support_paint_overhang")
            facets.forEach { triangle ->
                writer.appendLine("facet normal 0 0 0")
                writer.appendLine("outer loop")
                triangle.forEach { point ->
                    writer.appendLine("vertex ${point.x} ${point.y} ${point.z}")
                }
                writer.appendLine("endloop")
                writer.appendLine("endfacet")
            }
            writer.appendLine("endsolid support_paint_overhang")
        }
        return destination
    }

    private fun interlockingVolumeModel(name: String, x0: Float, x1: Float): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val destination = File(context.cacheDir, "interlocking-$name.stl")
        val facets = mutableListOf<List<TestVertex>>()

        fun vertex(x: Float, y: Float, z: Float) = TestVertex(x, y, z)
        fun quad(a: TestVertex, b: TestVertex, c: TestVertex, d: TestVertex) {
            facets += listOf(a, b, c)
            facets += listOf(a, c, d)
        }

        val y0 = -10f
        val y1 = 10f
        val z0 = 0f
        val z1 = 20f
        quad(vertex(x0, y0, z0), vertex(x1, y0, z0), vertex(x1, y0, z1), vertex(x0, y0, z1))
        quad(vertex(x1, y0, z0), vertex(x1, y1, z0), vertex(x1, y1, z1), vertex(x1, y0, z1))
        quad(vertex(x1, y1, z0), vertex(x0, y1, z0), vertex(x0, y1, z1), vertex(x1, y1, z1))
        quad(vertex(x0, y1, z0), vertex(x0, y0, z0), vertex(x0, y0, z1), vertex(x0, y1, z1))
        quad(vertex(x0, y0, z1), vertex(x1, y0, z1), vertex(x1, y1, z1), vertex(x0, y1, z1))
        quad(vertex(x0, y1, z0), vertex(x1, y1, z0), vertex(x1, y0, z0), vertex(x0, y0, z0))

        destination.bufferedWriter().use { writer ->
            writer.appendLine("solid interlocking_$name")
            facets.forEach { triangle ->
                writer.appendLine("facet normal 0 0 0")
                writer.appendLine("outer loop")
                triangle.forEach { point ->
                    writer.appendLine("vertex ${point.x} ${point.y} ${point.z}")
                }
                writer.appendLine("endloop")
                writer.appendLine("endfacet")
            }
            writer.appendLine("endsolid interlocking_$name")
        }
        return destination
    }

    private fun meshCorpus(): List<MeshCorpusEntry> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        fun vertex(x: Float, y: Float, z: Float) = TestVertex(x, y, z)
        fun quad(
            facets: MutableList<List<TestVertex>>,
            a: TestVertex,
            b: TestVertex,
            c: TestVertex,
            d: TestVertex,
        ) {
            facets += listOf(a, b, c)
            facets += listOf(a, c, d)
        }
        fun cube(low: Float, high: Float): MutableList<List<TestVertex>> {
            val facets = mutableListOf<List<TestVertex>>()
            val z0 = 0f
            val z1 = high - low
            quad(facets, vertex(low, low, z0), vertex(high, low, z0), vertex(high, low, z1), vertex(low, low, z1))
            quad(facets, vertex(high, low, z0), vertex(high, high, z0), vertex(high, high, z1), vertex(high, low, z1))
            quad(facets, vertex(high, high, z0), vertex(low, high, z0), vertex(low, high, z1), vertex(high, high, z1))
            quad(facets, vertex(low, high, z0), vertex(low, low, z0), vertex(low, low, z1), vertex(low, high, z1))
            quad(facets, vertex(low, low, z1), vertex(high, low, z1), vertex(high, high, z1), vertex(low, high, z1))
            quad(facets, vertex(low, high, z0), vertex(high, high, z0), vertex(high, low, z0), vertex(low, low, z0))
            return facets
        }
        fun write(name: String, facets: List<List<TestVertex>>): File {
            val output = File(context.cacheDir, "mesh-corpus-$name.stl")
            output.bufferedWriter().use { writer ->
                writer.appendLine("solid $name")
                facets.forEach { triangle ->
                    writer.appendLine("  facet normal 0 0 0")
                    writer.appendLine("    outer loop")
                    triangle.forEach { point ->
                        writer.appendLine("      vertex ${point.x} ${point.y} ${point.z}")
                    }
                    writer.appendLine("    endloop")
                    writer.appendLine("  endfacet")
                }
                writer.appendLine("endsolid $name")
            }
            return output
        }

        val closedCube = cube(0f, 20f)
        val openTop = closedCube.filterIndexed { index, _ -> index !in 8..9 }
        val reversedFacet = closedCube.mapIndexed { index, triangle ->
            if (index == 3) triangle.asReversed() else triangle
        }
        val duplicateFacet = closedCube.toMutableList().apply { add(closedCube.first()) }
        val degenerateAttachment = closedCube + listOf(
            listOf(vertex(5f, 5f, 5f), vertex(5f, 5f, 5f), vertex(10f, 5f, 5f)),
        )
        val intersectingShells = cube(0f, 20f) + cube(10f, 30f)
        val degenerateOnly = listOf(
            listOf(vertex(0f, 0f, 0f), vertex(0f, 0f, 0f), vertex(0f, 0f, 0f)),
        )

        return listOf(
            MeshCorpusEntry("open-top", write("open-top", openTop), mustSlice = true),
            MeshCorpusEntry("reversed-facet", write("reversed-facet", reversedFacet), mustSlice = true),
            MeshCorpusEntry("duplicate-facet", write("duplicate-facet", duplicateFacet), mustSlice = true),
            MeshCorpusEntry(
                "degenerate-attachment",
                write("degenerate-attachment", degenerateAttachment),
                mustSlice = true,
            ),
            MeshCorpusEntry(
                "intersecting-shells",
                write("intersecting-shells", intersectingShells),
                mustSlice = true,
            ),
            MeshCorpusEntry("degenerate-only", write("degenerate-only", degenerateOnly), mustSlice = false),
        )
    }

    @Test
    fun projectSurvivesStoreRecreationAndNativeReinspection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "project-store-${System.nanoTime()}")
        val inspector: (File) -> ModelInfo = { model ->
            inspectModel(model.absolutePath)
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
                    printFlowRatio = 0.94f,
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
                    machineMotion = MachineMotionSettings.fromProfile(PrinterProfile.U1_06).copy(
                        maxAccelerationTravel = 4_700f,
                    ),
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
                                scaleY = 0.9f,
                                scaleZ = 1.8f,
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
            assertEquals(0.9f, restored.selectedObject!!.transform.scaleY)
            assertEquals(1.8f, restored.selectedObject!!.transform.scaleZ)
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
            assertEquals(0.94f, restoredDocument.sliceOptions?.printFlowRatio)
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
    fun projectArchiveRoundTripReinspectsAndSlicesOnArm64() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceRoot = File(context.cacheDir, "archive-source-${System.nanoTime()}")
        val destinationRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        val inspector: (File) -> ModelInfo = { model ->
            inspectModel(model.absolutePath)
        }
        var gcode: File? = null
        try {
            destinationRoot.deleteRecursively()
            val source = ProjectStore(sourceRoot, inspector)
            val storedModel = source.createModelDestination("archive-box.stl")
            fixtureModel().copyTo(storedModel)
            val inspected = inspector(storedModel).copy(fileName = "archive-box.stl")
            val transform = ModelTransform(offsetXmm = 8f, offsetYmm = -6f, rotationZdeg = 18f)
            val paint = SupportPaint().paint(0, SupportPaintState.BLOCK)
            val options = SliceOptions()
                .selectQuality(QualityProfile.DRAFT)
                .copy(fillDensity = 0.18f, supportEnabled = true)
            val snapshot = ProjectSnapshot(
                objects = listOf(ProjectObject("archive-object", inspected, transform, paint)),
                selectedObjectId = "archive-object",
            )
            val archive = ByteArrayOutputStream().also { output ->
                source.exportArchive(snapshot, options, output)
            }.toByteArray()

            val imported = ProjectStore(context).importArchive(
                ByteArrayInputStream(archive),
            )
            val restoredObject = imported.snapshot.selectedObject
            assertEquals("archive-object", restoredObject?.id)
            assertEquals(transform, restoredObject?.transform)
            assertEquals(paint, restoredObject?.supportPaint)
            assertEquals(0.18f, imported.sliceOptions?.fillDensity)
            assertTrue(restoredObject?.model?.previewTriangles?.isNotEmpty() == true)

            val outcome = OnDeviceSlicer.slice(
                imported.snapshot.objects,
                requireNotNull(imported.sliceOptions),
            )
            gcode = outcome.output
            assertTrue("A restored project must produce retained G-code", outcome.output.length() > 1_000L)
            assertTrue("A restored project must retain a finite print estimate", outcome.estimatedSeconds.isFinite())
            assertTrue("A restored project must retain a finite filament estimate", outcome.filamentMm.isFinite())
        } finally {
            gcode?.delete()
            sourceRoot.deleteRecursively()
            destinationRoot.deleteRecursively()
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
            .selectFilament(FilamentProfile.PETG.copy(
                filamentStartGcode = "M117 SAVED_FILAMENT_START",
                filamentEndGcode = "M117 SAVED_FILAMENT_END",
                retractLength = 1.2f,
                retractSpeed = 41f,
                deretractSpeed = 36f,
                retractionMinimumTravel = 2.3f,
                retractWhenChangingLayer = true,
                wipeWhileRetracting = true,
                wipeDistance = 2.6f,
                retractBeforeWipe = 64f,
                retractRestartExtra = 0.07f,
                zHop = 0.65f,
                zHopType = "spiral",
                idleTemperature = 135,
                softeningTemperature = 62,
                nozzleTemperatureRangeLow = 195,
                nozzleTemperatureRangeHigh = 245,
                chamberTemperatureControl = true,
                chamberTemperature = 55,
                airFiltration = true,
                duringPrintExhaustFanSpeed = 70,
                completePrintExhaustFanSpeed = 40,
            ))
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
                quality = QualityProfile.FINE_06.copy(travelSpeedZ = 17f),
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
                internalBridgeSpeed = 164f,
                internalBridgeSpeedPercent = true,
                overhangSpeedEnabled = false,
                overhangSpeed1 = 76f,
                overhangSpeed1Percent = true,
                bridgeFlowRatio = 0.92f,
                internalBridgeFlowRatio = 0.95f,
                topSurfaceFlowRatio = 0.97f,
                bottomSurfaceFlowRatio = 0.98f,
                supportFlowRatio = 0.86f,
                supportInterfaceFlowRatio = 1.14f,
                bridgeDensity = 88f,
                internalBridgeDensity = 74f,
                bridgeAngle = 18f,
                internalBridgeAngle = 104f,
                bridgeNoSupport = true,
                thickBridges = true,
                thickInternalBridges = false,
                extraBridgeLayer = "external_bridge_only",
                internalBridgeFilter = "limited",
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
                supportCoverage = SupportCoverageSettings(
                    onBuildPlateOnly = true,
                    criticalRegionsOnly = true,
                    removeSmallOverhangs = false,
                ),
                supportAdvanced = SupportAdvancedSettings(
                    patternAngle = 45f,
                    thresholdOverlap = 37f,
                    thresholdOverlapPercent = true,
                    objectFirstLayerGap = 0.36f,
                    avoidInterfaceFilamentForBase = false,
                    ironingEnabled = true,
                    ironingPattern = "concentric",
                    ironingFlow = 16f,
                    ironingSpacing = 0.19f,
                ),
                supportFilament = 1,
                supportInterfaceFilament = 1,
                featureFilaments = FeatureFilamentSettings(
                    infillOverrideEnabled = true,
                    baseFirstLayers = 3,
                    baseLastLayers = 4,
                    sparseInfillFilament = 2,
                    wallFilament = 1,
                    solidInfillFilament = 3,
                    wipeTowerFilament = 1,
                ),
                wipeTowerEnabled = true,
                wipeTowerWidth = 46f,
                multiMaterial = MultiMaterialSettings(
                    primeVolume = 58f,
                    primeTowerBrimWidth = 5.5f,
                    wipeTowerNoSparseLayers = true,
                    wipeTowerRotationAngle = 73f,
                    wipeTowerBridging = 12.5f,
                    wipeTowerExtraSpacing = 145f,
                    wipeTowerExtraFlow = 118f,
                    wipeTowerMaxPurgeSpeed = 137f,
                    wipeTowerWallType = "rib",
                    wipeTowerConeAngle = 42f,
                    wipeTowerExtraRibLength = 9.5f,
                    wipeTowerRibWidth = 11f,
                    wipeTowerFilletWall = false,
                    singleExtruderMultiMaterialPriming = true,
                    flushIntoInfill = true,
                    flushIntoSupport = false,
                    flushIntoObjects = true,
                    oozePrevention = true,
                    standbyTemperatureDelta = -42,
                    preheatTime = 94.5f,
                    preheatDeltaTemperature = -18,
                    preheatSteps = 7,
                    interfaceShells = true,
                    segmentedRegionMaxWidth = 2.4f,
                    segmentedRegionInterlockingDepth = 0.8f,
                    interlockingBeam = true,
                    interlockingBeamWidth = 1.25f,
                    interlockingOrientation = 67.5f,
                    interlockingBeamLayerCount = 3,
                    interlockingDepth = 4,
                    interlockingBoundaryAvoidance = 1,
                ),
                gcodeSettings = GcodeSettings(
                    arcFitting = true,
                    labelObjects = false,
                    excludeObjects = true,
                    initialLayerTravelSpeed = 35f,
                    initialLayerTravelSpeedPercent = true,
                    slowDownLayers = 5,
                    accelToDecelEnabled = false,
                    accelToDecelFactor = 27f,
                ),
                infillFirst = true,
                infillWallOverlap = 18f,
                topBottomInfillWallOverlap = 32f,
                infillCombination = true,
                infillCombinationMaxLayerHeight = 0.38f,
                infillCombinationMaxLayerHeightPercent = false,
                infillDirection = 36f,
                solidInfillDirection = 124f,
                alignInfillDirectionToModel = true,
                minimumSparseInfillArea = 41f,
                infillAnchor = 322f,
                infillAnchorPercent = true,
                infillAnchorMax = 18f,
                infillAnchorMaxPercent = false,
                gapFillTarget = "topbottom",
                filterOutGapFill = 0.8f,
                reduceCrossingWall = true,
                maxTravelDetourDistance = 154f,
                maxTravelDetourDistancePercent = true,
                reduceInfillRetraction = true,
                initialLayerLineWidth = 0.73f,
                smallPerimeterSpeed = 78f,
                smallPerimeterSpeedPercent = false,
                smallPerimeterThreshold = 6.25f,
                slowdownForCurledPerimeters = false,
                resolution = 0.024f,
                seamPosition = "nearest",
                staggeredInnerSeams = true,
                seamGap = 3.25f,
                seamGapPercent = true,
                wipeBeforeExternalLoop = true,
                wipeOnLoops = true,
                roleBasedWipeSpeed = false,
                wipeSpeed = 67f,
                wipeSpeedPercent = false,
                ironing = IroningSettings(
                    type = "top",
                    pattern = "concentric",
                    flow = 12f,
                    spacing = 0.16f,
                    inset = 0.36f,
                    speed = 26f,
                    angle = 122f,
                ),
                defaultAcceleration = 4_000f,
                outerWallAcceleration = 2_000f,
                innerWallAcceleration = 3_500f,
                topSurfaceAcceleration = 1_200f,
                travelAcceleration = 4_500f,
                firstLayerAcceleration = 600f,
                bridgeAcceleration = 48f,
                bridgeAccelerationPercent = true,
                sparseInfillAcceleration = 4_322f,
                sparseInfillAccelerationPercent = false,
                internalSolidInfillAcceleration = 84f,
                internalSolidInfillAccelerationPercent = true,
                wallGenerator = "classic",
                wallTransitionLength = 140f,
                wallTransitionFilterDeviation = 32f,
                wallTransitionAngle = 23f,
                wallDistributionCount = 3,
                minimumFeatureSize = 21f,
                precision = PrecisionSettings(
                    polyholes = PolyholeSettings(
                        enabled = true,
                        detectionMargin = 7f,
                        detectionMarginPercent = true,
                        twist = false,
                    ),
                    minimumWallWidth = 72f,
                    firstLayerMinimumWallWidth = 118f,
                    printableOverhangs = PrintableOverhangSettings(
                        enabled = true,
                        maximumAngle = 64f,
                        holeArea = 250f,
                    ),
                    brimEars = BrimEarSettings(maximumAngle = 133f, detectionRadius = 1.9f),
                ),
                minimumWallLengthFactor = 0.8f,
                wallSequence = "outer-inner",
                wallDirection = "cw",
                detectThinWalls = true,
                onlyOneWallOnTop = false,
                minWidthTopSurface = 290f,
                minWidthTopSurfacePercent = true,
                onlyOneWallFirstLayer = true,
                extraPerimetersOnOverhangs = true,
                overhangReverse = true,
                overhangReverseInternalOnly = true,
                overhangReverseThreshold = 0.9f,
                overhangReverseThresholdPercent = false,
                counterboreHoleBridging = "partiallybridge",
                alternateExtraWall = true,
                ensureVerticalShellThickness = "ensure_critical_only",
                detectNarrowInternalSolidInfill = false,
                xyHoleCompensation = 0.12f,
                xyContourCompensation = -0.08f,
                elephantFootCompensation = 0.24f,
                elephantFootCompensationLayers = 4,
                maxBridgeLength = 27f,
                preciseOuterWalls = true,
                skirtLoops = 3,
                skirtDistance = 7.5f,
                skirtHeight = 4,
                skirtSpeed = 58f,
                minimumSkirtLength = 13f,
                draftShield = "enabled",
                brimType = "outer_and_inner",
                brimWidth = 6.5f,
                brimObjectGap = 0.16f,
                raftLayers = 3,
                raftContactDistance = 0.14f,
                raftExpansion = 2.6f,
                raftFirstLayerDensity = 87f,
                raftFirstLayerExpansion = 3.6f,
                gcodeFlavor = "reprapfirmware",
                machineMotion = MachineMotionSettings.fromProfile(PrinterProfile.U1_06).copy(
                    maxSpeedX = 320f,
                    maxSpeedY = 330f,
                    maxSpeedZ = 24f,
                    maxSpeedE = 82f,
                    maxAccelerationX = 4_200f,
                    maxAccelerationY = 4_300f,
                    maxAccelerationZ = 620f,
                    maxAccelerationE = 6_400f,
                    maxAccelerationExtruding = 3_800f,
                    maxAccelerationRetracting = 3_900f,
                    maxAccelerationTravel = 5_000f,
                    maxJerkX = 7f,
                    maxJerkY = 7.5f,
                    maxJerkZ = 0.5f,
                    maxJerkE = 4f,
                    maxJunctionDeviation = 0.042f,
                ),
            )
            .copy(
                printerProfile = PrinterProfile.U1_06.copy(
                    machineStartGcode = "M117 SAVED_START",
                    machineEndGcode = "M117 SAVED_END",
                    machinePauseGcode = "M25 ; SAVED_PAUSE",
                    timeLapseGcode = "; SAVED_TIMELAPSE",
                    beforeLayerChangeGcode = "; SAVED_BEFORE_LAYER",
                    layerChangeGcode = "; SAVED_AFTER_LAYER",
                    changeFilamentGcode = "T[next_extruder] ; SAVED_TOOL_CHANGE",
                    printingByObjectGcode = "; SAVED_BETWEEN_OBJECTS",
                    useRelativeEDistances = false,
                    emitMachineLimitsToGcode = false,
                    manualFilamentChange = true,
                    disableM73 = true,
                    machineLoadFilamentTime = 12.5f,
                    machineUnloadFilamentTime = 23.5f,
                    machineToolChangeTime = 4.5f,
                    toolChangeTemperatureWait = false,
                    coolingTubeRetraction = 73.5f,
                    coolingTubeLength = 11f,
                    parkingPosRetraction = 80f,
                    extraLoadingMove = -3.5f,
                    enableFilamentRamming = false,
                    rammingLineWidthRatio = 3.25f,
                    changePressureWhenWiping = false,
                    rammingPressureAdvance = 0.17f,
                    purgeInPrimeTower = false,
                    highCurrentOnFilamentSwap = true,
                    fanSpeedupTime = 0.7f,
                    fanSpeedupOverhangs = false,
                    fanKickstart = 0.25f,
                    supportsChamberTemperatureControl = true,
                    supportsAirFiltration = true,
                ),
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
        assertEquals(
            SupportCoverageSettings(
                onBuildPlateOnly = true,
                criticalRegionsOnly = true,
                removeSmallOverhangs = false,
            ),
            restored.slicing.last().supportCoverage,
        )
        assertEquals(45f, restored.slicing.last().supportAdvanced.patternAngle)
        assertEquals(37f, restored.slicing.last().supportAdvanced.thresholdOverlap)
        assertTrue(restored.slicing.last().supportAdvanced.thresholdOverlapPercent)
        assertTrue(restored.slicing.last().supportAdvanced.ironingEnabled)
        assertEquals("concentric", restored.slicing.last().supportAdvanced.ironingPattern)
        assertEquals(1, restored.slicing.last().supportFilament)
        assertEquals(1, restored.slicing.last().supportInterfaceFilament)
        assertEquals(
            FeatureFilamentSettings(
                infillOverrideEnabled = true,
                baseFirstLayers = 3,
                baseLastLayers = 4,
                sparseInfillFilament = 2,
                wallFilament = 1,
                solidInfillFilament = 3,
                wipeTowerFilament = 1,
            ),
            restored.slicing.last().featureFilaments,
        )
        assertTrue(restored.slicing.last().wipeTowerEnabled)
        assertEquals(46f, restored.slicing.last().wipeTowerWidth)
        assertEquals(58f, restored.slicing.last().multiMaterial.primeVolume)
        assertEquals(5.5f, restored.slicing.last().multiMaterial.primeTowerBrimWidth)
        assertTrue(restored.slicing.last().multiMaterial.wipeTowerNoSparseLayers)
        assertEquals(73f, restored.slicing.last().multiMaterial.wipeTowerRotationAngle)
        assertEquals(12.5f, restored.slicing.last().multiMaterial.wipeTowerBridging)
        assertEquals(145f, restored.slicing.last().multiMaterial.wipeTowerExtraSpacing)
        assertEquals(118f, restored.slicing.last().multiMaterial.wipeTowerExtraFlow)
        assertEquals(137f, restored.slicing.last().multiMaterial.wipeTowerMaxPurgeSpeed)
        assertEquals("rib", restored.slicing.last().multiMaterial.wipeTowerWallType)
        assertEquals(42f, restored.slicing.last().multiMaterial.wipeTowerConeAngle)
        assertEquals(9.5f, restored.slicing.last().multiMaterial.wipeTowerExtraRibLength)
        assertEquals(11f, restored.slicing.last().multiMaterial.wipeTowerRibWidth)
        assertFalse(restored.slicing.last().multiMaterial.wipeTowerFilletWall)
        assertTrue(restored.slicing.last().multiMaterial.singleExtruderMultiMaterialPriming)
        assertTrue(restored.slicing.last().multiMaterial.flushIntoInfill)
        assertFalse(restored.slicing.last().multiMaterial.flushIntoSupport)
        assertTrue(restored.slicing.last().multiMaterial.flushIntoObjects)
        assertTrue(restored.slicing.last().multiMaterial.oozePrevention)
        assertEquals(-42, restored.slicing.last().multiMaterial.standbyTemperatureDelta)
        assertEquals(94.5f, restored.slicing.last().multiMaterial.preheatTime)
        assertEquals(-18, restored.slicing.last().multiMaterial.preheatDeltaTemperature)
        assertEquals(7, restored.slicing.last().multiMaterial.preheatSteps)
        assertTrue(restored.slicing.last().multiMaterial.interfaceShells)
        assertEquals(2.4f, restored.slicing.last().multiMaterial.segmentedRegionMaxWidth)
        assertEquals(0.8f, restored.slicing.last().multiMaterial.segmentedRegionInterlockingDepth)
        assertTrue(restored.slicing.last().multiMaterial.interlockingBeam)
        assertEquals(1.25f, restored.slicing.last().multiMaterial.interlockingBeamWidth)
        assertEquals(67.5f, restored.slicing.last().multiMaterial.interlockingOrientation)
        assertEquals(3, restored.slicing.last().multiMaterial.interlockingBeamLayerCount)
        assertEquals(4, restored.slicing.last().multiMaterial.interlockingDepth)
        assertEquals(1, restored.slicing.last().multiMaterial.interlockingBoundaryAvoidance)
        assertTrue(restored.slicing.last().gcodeSettings.arcFitting)
        assertFalse(restored.slicing.last().gcodeSettings.labelObjects)
        assertTrue(restored.slicing.last().gcodeSettings.excludeObjects)
        assertEquals(35f, restored.slicing.last().gcodeSettings.initialLayerTravelSpeed)
        assertTrue(restored.slicing.last().gcodeSettings.initialLayerTravelSpeedPercent)
        assertEquals(5, restored.slicing.last().gcodeSettings.slowDownLayers)
        assertFalse(restored.slicing.last().gcodeSettings.accelToDecelEnabled)
        assertEquals(27f, restored.slicing.last().gcodeSettings.accelToDecelFactor)
        assertEquals("nearest", restored.slicing.last().seamPosition)
        assertEquals(
            IroningSettings(
                type = "top",
                pattern = "concentric",
                flow = 12f,
                spacing = 0.16f,
                inset = 0.36f,
                speed = 26f,
                angle = 122f,
            ),
            restored.slicing.last().ironing,
        )
        assertEquals(164f, restored.slicing.last().internalBridgeSpeed)
        assertTrue(restored.slicing.last().internalBridgeSpeedPercent)
        assertTrue(restored.slicing.last().infillFirst)
        assertEquals(18f, restored.slicing.last().infillWallOverlap)
        assertEquals(32f, restored.slicing.last().topBottomInfillWallOverlap)
        assertTrue(restored.slicing.last().infillCombination)
        assertEquals(0.38f, restored.slicing.last().infillCombinationMaxLayerHeight)
        assertEquals(false, restored.slicing.last().infillCombinationMaxLayerHeightPercent)
        assertEquals(36f, restored.slicing.last().infillDirection)
        assertEquals(124f, restored.slicing.last().solidInfillDirection)
        assertTrue(restored.slicing.last().alignInfillDirectionToModel)
        assertEquals(41f, restored.slicing.last().minimumSparseInfillArea)
        assertEquals(322f, restored.slicing.last().infillAnchor)
        assertTrue(restored.slicing.last().infillAnchorPercent)
        assertEquals(18f, restored.slicing.last().infillAnchorMax)
        assertEquals(false, restored.slicing.last().infillAnchorMaxPercent)
        assertEquals("topbottom", restored.slicing.last().gapFillTarget)
        assertEquals(0.8f, restored.slicing.last().filterOutGapFill)
        assertTrue(restored.slicing.last().reduceCrossingWall)
        assertEquals(154f, restored.slicing.last().maxTravelDetourDistance)
        assertTrue(restored.slicing.last().maxTravelDetourDistancePercent)
        assertTrue(restored.slicing.last().reduceInfillRetraction)
        assertEquals(88f, restored.slicing.last().bridgeDensity)
        assertEquals(74f, restored.slicing.last().internalBridgeDensity)
        assertEquals(18f, restored.slicing.last().bridgeAngle)
        assertEquals(104f, restored.slicing.last().internalBridgeAngle)
        assertTrue(restored.slicing.last().bridgeNoSupport)
        assertTrue(restored.slicing.last().thickBridges)
        assertEquals(false, restored.slicing.last().thickInternalBridges)
        assertEquals("external_bridge_only", restored.slicing.last().extraBridgeLayer)
        assertEquals("limited", restored.slicing.last().internalBridgeFilter)
        assertEquals(48f, restored.slicing.last().bridgeAcceleration)
        assertTrue(restored.slicing.last().bridgeAccelerationPercent)
        assertEquals(4_322f, restored.slicing.last().sparseInfillAcceleration)
        assertEquals(false, restored.slicing.last().sparseInfillAccelerationPercent)
        assertEquals(84f, restored.slicing.last().internalSolidInfillAcceleration)
        assertTrue(restored.slicing.last().internalSolidInfillAccelerationPercent)
        assertEquals(7, restored.slicing.last().topSolidLayers)
        assertEquals(420f, restored.slicing.last().travelSpeed)
        assertEquals(17f, restored.slicing.last().travelSpeedZ)
        assertEquals(1.2f, restored.filaments.last().retractLength)
        assertEquals(41f, restored.filaments.last().retractSpeed)
        assertEquals(36f, restored.filaments.last().deretractSpeed)
        assertEquals(2.3f, restored.filaments.last().retractionMinimumTravel)
        assertEquals(true, restored.filaments.last().retractWhenChangingLayer)
        assertEquals(true, restored.filaments.last().wipeWhileRetracting)
        assertEquals(2.6f, restored.filaments.last().wipeDistance)
        assertEquals(64f, restored.filaments.last().retractBeforeWipe)
        assertEquals(0.07f, restored.filaments.last().retractRestartExtra)
        assertEquals(0.65f, restored.filaments.last().zHop)
        assertEquals("spiral", restored.filaments.last().zHopType)
        assertEquals("M117 SAVED_FILAMENT_START", restored.filaments.last().filamentStartGcode)
        assertEquals("M117 SAVED_FILAMENT_END", restored.filaments.last().filamentEndGcode)
        assertEquals(135, restored.filaments.last().idleTemperature)
        assertEquals(62, restored.filaments.last().softeningTemperature)
        assertEquals(195, restored.filaments.last().nozzleTemperatureRangeLow)
        assertEquals(245, restored.filaments.last().nozzleTemperatureRangeHigh)
        assertTrue(restored.filaments.last().chamberTemperatureControl)
        assertEquals(55, restored.filaments.last().chamberTemperature)
        assertTrue(restored.filaments.last().airFiltration)
        assertEquals(70, restored.filaments.last().duringPrintExhaustFanSpeed)
        assertEquals(40, restored.filaments.last().completePrintExhaustFanSpeed)
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
        assertEquals(0.86f, restored.slicing.last().supportFlowRatio)
        assertEquals(1.14f, restored.slicing.last().supportInterfaceFlowRatio)
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
        assertEquals(140f, restored.slicing.last().wallTransitionLength)
        assertEquals(32f, restored.slicing.last().wallTransitionFilterDeviation)
        assertEquals(23f, restored.slicing.last().wallTransitionAngle)
        assertEquals(3, restored.slicing.last().wallDistributionCount)
        assertEquals(21f, restored.slicing.last().minimumFeatureSize)
        assertEquals(
            PolyholeSettings(enabled = true, detectionMargin = 7f, detectionMarginPercent = true, twist = false),
            restored.slicing.last().precision.polyholes,
        )
        assertEquals(72f, restored.slicing.last().precision.minimumWallWidth)
        assertEquals(118f, restored.slicing.last().precision.firstLayerMinimumWallWidth)
        assertEquals(
            PrintableOverhangSettings(enabled = true, maximumAngle = 64f, holeArea = 250f),
            restored.slicing.last().printableOverhangs,
        )
        assertEquals(0.8f, restored.slicing.last().minimumWallLengthFactor)
        assertEquals("outer-inner", restored.slicing.last().wallSequence)
        assertEquals("cw", restored.slicing.last().wallDirection)
        assertEquals(78f, restored.slicing.last().smallPerimeterSpeed)
        assertEquals(false, restored.slicing.last().smallPerimeterSpeedPercent)
        assertEquals(6.25f, restored.slicing.last().smallPerimeterThreshold)
        assertEquals(false, restored.slicing.last().slowdownForCurledPerimeters)
        assertEquals(0.024f, restored.slicing.last().resolution)
        assertTrue(restored.slicing.last().staggeredInnerSeams)
        assertEquals(3.25f, restored.slicing.last().seamGap)
        assertTrue(restored.slicing.last().seamGapPercent)
        assertTrue(restored.slicing.last().wipeBeforeExternalLoop)
        assertTrue(restored.slicing.last().wipeOnLoops)
        assertEquals(false, restored.slicing.last().roleBasedWipeSpeed)
        assertEquals(67f, restored.slicing.last().wipeSpeed)
        assertEquals(false, restored.slicing.last().wipeSpeedPercent)
        assertTrue(restored.slicing.last().detectThinWalls)
        assertEquals(false, restored.slicing.last().onlyOneWallOnTop)
        assertTrue(restored.slicing.last().onlyOneWallFirstLayer)
        assertTrue(restored.slicing.last().extraPerimetersOnOverhangs)
        assertEquals(290f, restored.slicing.last().minWidthTopSurface)
        assertTrue(restored.slicing.last().minWidthTopSurfacePercent)
        assertTrue(restored.slicing.last().overhangReverse)
        assertTrue(restored.slicing.last().overhangReverseInternalOnly)
        assertEquals(0.9f, restored.slicing.last().overhangReverseThreshold)
        assertEquals(false, restored.slicing.last().overhangReverseThresholdPercent)
        assertEquals("partiallybridge", restored.slicing.last().counterboreHoleBridging)
        assertTrue(restored.slicing.last().alternateExtraWall)
        assertEquals("ensure_critical_only", restored.slicing.last().ensureVerticalShellThickness)
        assertEquals(false, restored.slicing.last().detectNarrowInternalSolidInfill)
        assertEquals(0.12f, restored.slicing.last().xyHoleCompensation)
        assertEquals(-0.08f, restored.slicing.last().xyContourCompensation)
        assertEquals(0.24f, restored.slicing.last().elephantFootCompensation)
        assertEquals(4, restored.slicing.last().elephantFootCompensationLayers)
        assertEquals(27f, restored.slicing.last().maxBridgeLength)
        assertTrue(restored.slicing.last().preciseOuterWalls)
        assertEquals(3, restored.slicing.last().skirtLoops)
        assertEquals(7.5f, restored.slicing.last().skirtDistance)
        assertEquals(4, restored.slicing.last().skirtHeight)
        assertEquals(58f, restored.slicing.last().skirtSpeed)
        assertEquals(13f, restored.slicing.last().minimumSkirtLength)
        assertEquals("enabled", restored.slicing.last().draftShield)
        assertEquals("outer_and_inner", restored.slicing.last().brimType)
        assertEquals(6.5f, restored.slicing.last().brimWidth)
        assertEquals(0.16f, restored.slicing.last().brimObjectGap)
        assertEquals(133f, restored.slicing.last().precision.brimEars.maximumAngle)
        assertEquals(1.9f, restored.slicing.last().precision.brimEars.detectionRadius)
        assertEquals(3, restored.slicing.last().raftLayers)
        assertEquals(0.14f, restored.slicing.last().raftContactDistance)
        assertEquals(2.6f, restored.slicing.last().raftExpansion)
        assertEquals(87f, restored.slicing.last().raftFirstLayerDensity)
        assertEquals(3.6f, restored.slicing.last().raftFirstLayerExpansion)
        assertEquals("reprapfirmware", restored.printers.last().gcodeFlavor)
        assertEquals(320f, restored.printers.last().maxSpeedX)
        assertEquals(330f, restored.printers.last().maxSpeedY)
        assertEquals(24f, restored.printers.last().maxSpeedZ)
        assertEquals(82f, restored.printers.last().maxSpeedE)
        assertEquals(4_200f, restored.printers.last().maxAccelerationX)
        assertEquals(4_300f, restored.printers.last().maxAccelerationY)
        assertEquals(620f, restored.printers.last().maxAccelerationZ)
        assertEquals(6_400f, restored.printers.last().maxAccelerationE)
        assertEquals(3_800f, restored.printers.last().maxAccelerationExtruding)
        assertEquals(3_900f, restored.printers.last().maxAccelerationRetracting)
        assertEquals(5_000f, restored.printers.last().maxAccelerationTravel)
        assertEquals(7f, restored.printers.last().maxJerkX)
        assertEquals(7.5f, restored.printers.last().maxJerkY)
        assertEquals(0.5f, restored.printers.last().maxJerkZ)
        assertEquals(4f, restored.printers.last().maxJerkE)
        assertEquals(0.042f, restored.printers.last().maxJunctionDeviation)
        assertEquals("M117 SAVED_START", restored.printers.last().machineStartGcode)
        assertEquals("M117 SAVED_END", restored.printers.last().machineEndGcode)
        assertEquals("M25 ; SAVED_PAUSE", restored.printers.last().machinePauseGcode)
        assertEquals("; SAVED_TIMELAPSE", restored.printers.last().timeLapseGcode)
        assertEquals("; SAVED_BEFORE_LAYER", restored.printers.last().beforeLayerChangeGcode)
        assertEquals("; SAVED_AFTER_LAYER", restored.printers.last().layerChangeGcode)
        assertEquals(
            "T[next_extruder] ; SAVED_TOOL_CHANGE",
            restored.printers.last().changeFilamentGcode,
        )
        assertEquals("; SAVED_BETWEEN_OBJECTS", restored.printers.last().printingByObjectGcode)
        assertFalse(restored.printers.last().useRelativeEDistances)
        assertFalse(restored.printers.last().emitMachineLimitsToGcode)
        assertTrue(restored.printers.last().manualFilamentChange)
        assertTrue(restored.printers.last().disableM73)
        assertEquals(12.5f, restored.printers.last().machineLoadFilamentTime)
        assertEquals(23.5f, restored.printers.last().machineUnloadFilamentTime)
        assertEquals(4.5f, restored.printers.last().machineToolChangeTime)
        assertFalse(restored.printers.last().toolChangeTemperatureWait)
        assertEquals(73.5f, restored.printers.last().coolingTubeRetraction)
        assertEquals(11f, restored.printers.last().coolingTubeLength)
        assertEquals(80f, restored.printers.last().parkingPosRetraction)
        assertEquals(-3.5f, restored.printers.last().extraLoadingMove)
        assertFalse(restored.printers.last().enableFilamentRamming)
        assertEquals(3.25f, restored.printers.last().rammingLineWidthRatio)
        assertFalse(restored.printers.last().changePressureWhenWiping)
        assertEquals(0.17f, restored.printers.last().rammingPressureAdvance)
        assertFalse(restored.printers.last().purgeInPrimeTower)
        assertTrue(restored.printers.last().highCurrentOnFilamentSwap)
        assertEquals(0.7f, restored.printers.last().fanSpeedupTime)
        assertFalse(restored.printers.last().fanSpeedupOverhangs)
        assertEquals(0.25f, restored.printers.last().fanKickstart)
        assertTrue(restored.printers.last().supportsChamberTemperatureControl)
        assertTrue(restored.printers.last().supportsAirFiltration)
        assertEquals(null, restored.printers.last().brand)
        assertEquals(null, restored.filaments.last().brand)
        assertEquals(USER_PROFILE_SCHEMA_VERSION, JSONObject(file.readText()).getInt("schemaVersion"))
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
    fun bundledProfileCatalogIsVersionedValidatedAndBroad() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loadStartedAt = SystemClock.elapsedRealtimeNanos()
        val catalog = OrcaProfileCatalog(context).load()
        val loadElapsedMs = (SystemClock.elapsedRealtimeNanos() - loadStartedAt) / 1_000_000
        Log.i("DuckyCatalogPerf", "loadMs=$loadElapsedMs")

        assertEquals(106, catalog.schemaVersion)
        assertTrue("Profile catalog loading took ${loadElapsedMs}ms", loadElapsedMs < 5_000)
        assertEquals("2c8a5385bc53cbc16211b4dd36ef9963ee185f4a", catalog.sourceRevision)
        assertEquals(794, catalog.printers.size)
        assertEquals(3_322, catalog.filaments.size)
        assertEquals(2_331, catalog.slicing.size)
        assertEquals(66, catalog.rejectedCount)
        val repRapFirmwarePrinters = setOf(
            "Construct 1 0.4 nozzle",
            "Construct 1 XL 0.6 nozzle",
            "MyRRF 0.4 nozzle",
            "Troodon 2.0 RRF 0.4 nozzle",
        )
        assertEquals(
            repRapFirmwarePrinters,
            catalog.printers
                .filter { it.name in repRapFirmwarePrinters }
                .onEach { assertEquals("reprapfirmware", it.gcodeFlavor) }
                .map(PrinterProfile::name)
                .toSet(),
        )
        val sharedMultiNozzleProcesses = setOf(
            "0.40mm Standard @MyKlipper",
            "0.40mm Standard @MyToolChanger",
            "0.40mm Standard @M1",
            "0.40mm Standard @The Positron",
            "0.40mm Standard @MK4",
            "0.40mm Standard @Rook MK1 LDO",
        )
        sharedMultiNozzleProcesses.forEach { profileName ->
            val variants = catalog.slicing.filter { it.name == profileName }
            assertEquals(setOf(0.6f, 0.8f), variants.map(QualityProfile::nozzleDiameter).toSet())
            assertEquals(2, variants.map(QualityProfile::id).distinct().size)
            variants.forEach { variant ->
                assertEquals(1, variant.compatiblePrinters.size)
                assertTrue(
                    variant.compatiblePrinters.single().contains("${variant.nozzleDiameter} nozzle"),
                )
            }
        }
        val canonicalSnapmakerProcess = catalog.slicing.single {
            it.name == "0.20 Standard @Snapmaker U1 (0.4 nozzle)"
        }
        assertEquals(0.8f, canonicalSnapmakerProcess.bridgeFlowRatio)
        assertEquals("tree(auto)", canonicalSnapmakerProcess.supportType)
        assertEquals(
            listOf("Prusa MK3S 0.4 nozzle"),
            catalog.slicing.single { it.name == "0.15mm Speed @MK3S" }.compatiblePrinters,
        )
        assertEquals(
            1,
            catalog.filaments.count { it.name == "Snapmaker PLA Basic @U1" },
        )
        val clockwiseSovolProfiles = setOf(
            "0.20mm Standard @Sovol SV08 MAX 0.4 nozzle",
            "0.20mm Standard @Sovol Zero 0.4 nozzle",
            "0.30mm Standard @Sovol SV08 MAX 0.6 nozzle",
            "0.40mm Standard @Sovol SV08 MAX 0.8 nozzle",
        )
        assertEquals(
            clockwiseSovolProfiles,
            catalog.slicing
                .filter { it.brand == "Sovol" && it.name in clockwiseSovolProfiles }
                .onEach { assertEquals("cw", it.wallDirection) }
                .map(QualityProfile::name)
                .toSet(),
        )
        val absoluteBridgeSpeedVivedinoProfiles = setOf(
            "0.08mm Extra Fine @Troodon2",
            "0.12mm Fine @Troodon2",
            "0.15mm Optimal @Troodon2",
            "0.20mm Standard @Troodon2",
            "0.24mm Draft @Troodon2",
            "0.28mm Extra Draft @Troodon2",
        )
        assertEquals(
            absoluteBridgeSpeedVivedinoProfiles,
            catalog.slicing
                .filter { it.brand == "Vivedino" && it.name in absoluteBridgeSpeedVivedinoProfiles }
                .onEach {
                    assertEquals(70f, it.internalBridgeSpeed)
                    assertFalse(it.internalBridgeSpeedPercent)
                }
                .map(QualityProfile::name)
                .toSet(),
        )
        assertEquals(
            mapOf(
                NozzleMaterial.UNDEFINED to 347,
                NozzleMaterial.HARDENED_STEEL to 267,
                NozzleMaterial.STAINLESS_STEEL to 40,
                NozzleMaterial.BRASS to 140,
            ),
            catalog.printers.groupingBy(PrinterProfile::nozzleMaterial).eachCount(),
        )
        assertEquals(
            mapOf(2.5f to 718, 4f to 44, 4.2f to 20, 4.76f to 12),
            catalog.printers.groupingBy(PrinterProfile::nozzleHeight).eachCount(),
        )
        assertEquals(
            mapOf(0 to 1_781, 3 to 1_323, 40 to 218),
            catalog.filaments.groupingBy(FilamentProfile::requiredNozzleHrc).eachCount(),
        )
        val generatedU1 = catalog.printers.single { it.name == "Snapmaker U1 (0.4 nozzle)" }
        assertEquals(
            "0.20 Standard @Snapmaker U1 (0.4 nozzle)",
            generatedU1.defaultPrintProfile,
        )
        assertEquals(listOf("Snapmaker PLA"), generatedU1.defaultFilamentProfiles)
        assertEquals(0.08f, generatedU1.minLayerHeight)
        assertEquals(0.32f, generatedU1.maxLayerHeight)
        assertEquals(listOf(10f, 10f, 10f, 10f), generatedU1.toolChangeRetractLengths)
        assertEquals(listOf(0f, 0f, 0f, 0f), generatedU1.toolChangeRetractRestartExtras)
        assertEquals(2f, generatedU1.rammingLineWidthRatio)
        assertTrue(generatedU1.changePressureWhenWiping)
        assertEquals(0.02f, generatedU1.rammingPressureAdvance)
        assertEquals(5f, generatedU1.machineToolChangeTime)
        assertFalse(generatedU1.toolChangeTemperatureWait)
        assertTrue(generatedU1.resonanceAvoidance)
        assertEquals(40f, generatedU1.minResonanceAvoidanceSpeed)
        assertEquals(90f, generatedU1.maxResonanceAvoidanceSpeed)
        assertEquals("M600", generatedU1.machinePauseGcode)
        assertEquals(2.5f, generatedU1.nozzleHeight)
        assertEquals(143f, generatedU1.nozzleVolume)
        assertEquals(
            4.76f,
            catalog.printers.single { it.name == "Bambu Lab A1 0.4 nozzle" }.nozzleHeight,
        )
        val adaptiveMesh = catalog.printers.single { it.name == "WonderMaker ZR 0.4 nozzle" }
        assertEquals(10f, adaptiveMesh.bedMeshMinX)
        assertEquals(10f, adaptiveMesh.bedMeshMinY)
        assertEquals(290f, adaptiveMesh.bedMeshMaxX)
        assertEquals(290f, adaptiveMesh.bedMeshMaxY)
        assertEquals(40f, adaptiveMesh.bedMeshProbeDistanceX)
        assertEquals(40f, adaptiveMesh.bedMeshProbeDistanceY)
        assertEquals(5f, adaptiveMesh.adaptiveBedMeshMargin)
        val junctionDeviationPrinter = catalog.printers.single {
            it.name == "Cubicon xCeler-I 0.4 nozzle"
        }
        assertEquals(0f, junctionDeviationPrinter.maxJunctionDeviation)
        val adaptivePressureAdvanceModels = catalog.filaments.filter {
            it.adaptivePressureAdvance.model != DEFAULT_ADAPTIVE_PRESSURE_ADVANCE_MODEL
        }
        assertEquals(2, adaptivePressureAdvanceModels.size)
        adaptivePressureAdvanceModels.forEach { filament ->
            assertTrue(filament.pressureAdvanceEnabled)
            assertFalse(filament.adaptivePressureAdvance.enabled)
            assertTrue(adaptivePressureAdvanceModelIsValid(filament.adaptivePressureAdvance.model))
            assertEquals(17, filament.adaptivePressureAdvance.model.lineSequence().count())
        }
        val coreOne = catalog.printers.single { it.name == "Prusa CORE One 0.4 nozzle" }
        val incompatible = SliceOptions()
            .selectFilament(
                FilamentProfile.GENERIC_PLA.copy(
                    id = "instrumented-foreign-filament",
                    name = "Instrumented foreign filament",
                    compatiblePrinters = listOf("Different printer"),
                ),
            )
            .selectQuality(
                QualityProfile.STANDARD.copy(
                    id = "instrumented-foreign-quality",
                    name = "Instrumented foreign quality",
                    compatiblePrinters = listOf("Different printer"),
                ),
            )
        val defaultsSelected = incompatible.selectPrinter(coreOne, catalog)
        assertEquals(coreOne.defaultPrintProfile, defaultsSelected.quality.name)
        assertEquals(
            coreOne.defaultFilamentProfiles.single(),
            defaultsSelected.filamentProfile.name,
        )
        val dual = catalog.printers.single { it.name == "Snapmaker A250 Dual (0.2 nozzle)" }
        val dualDefaults = incompatible.selectPrinter(dual, catalog)
        assertEquals(dual.defaultPrintProfile, dualDefaults.quality.name)
        assertEquals(
            dual.defaultFilamentProfiles,
            dualDefaults.resolvedFilamentSlots().map(FilamentProfile::name),
        )
        val raise3dDual = catalog.printers.single {
            it.name == "Raise3D Pro3 0.4 nozzle (Dual)"
        }
        assertEquals(2, raise3dDual.extruderCount)
        assertTrue(raise3dDual.singleExtruderMultiMaterial)
        assertTrue(raise3dDual.changeFilamentGcode.contains("; layer [layer_num] tool change"))
        assertTrue(
            raise3dDual.changeFilamentGcode.contains(
                "M109 T[next_extruder] S{nozzle_temperature[next_extruder]}",
            ),
        )
        assertEquals(
            "TIMELAPSE_TAKE_FRAME",
            catalog.printers.single { it.name == "Artillery M1 Pro 0.4 nozzle" }.timeLapseGcode,
        )
        val fanResponse = catalog.printers.single { it.name == "Sovol SV07 Plus 0.4 nozzle" }
        assertEquals(0.5f, fanResponse.fanSpeedupTime)
        assertFalse(fanResponse.fanSpeedupOverhangs)
        assertEquals(0.2f, fanResponse.fanKickstart)
        val boundedLift = catalog.printers.single { it.name == "Anycubic Kobra 2 Neo 0.4 nozzle" }
        assertEquals(0.3f, boundedLift.retractLiftAbove)
        assertEquals(258f, boundedLift.retractLiftBelow)
        val firmwareRetraction = catalog.printers.single { it.name == "Kingroon KP3S PRO V2 0.4 nozzle" }
        assertTrue(firmwareRetraction.useFirmwareRetraction)
        val estimatedExchange = catalog.printers.single {
            it.name == "Anycubic Kobra 2 Max 0.4 nozzle"
        }
        assertEquals(25f, estimatedExchange.machineLoadFilamentTime)
        assertEquals(29f, estimatedExchange.machineUnloadFilamentTime)
        assertEquals(0f, estimatedExchange.machineToolChangeTime)
        val filamentLiftOverride = catalog.filaments.single {
            it.name == "Anycubic PLA Silk @Anycubic Kobra S1 0.4 nozzle"
        }
        assertEquals(0.3f, filamentLiftOverride.retractLiftAbove)
        assertEquals(249f, filamentLiftOverride.retractLiftBelow)
        assertEquals("all", filamentLiftOverride.retractLiftEnforce)
        val inheritedOffset = catalog.printers.single { it.name == "Bambu Lab P1P 0.4 nozzle" }
        assertEquals(listOf(0f), inheritedOffset.extruderOffsetsX)
        assertEquals(listOf(2f), inheritedOffset.extruderOffsetsY)
        assertEquals(2, inheritedOffset.longRetractionWhenCutLevel)
        assertFalse(inheritedOffset.longRetractionWhenCut)
        assertEquals(18f, inheritedOffset.retractionDistanceWhenCut)
        assertEquals(
            listOf(0f, 0f, 18f, 0f, 18f, 28f, 0f, 28f),
            inheritedOffset.bedExcludeArea,
        )
        assertTrue(inheritedOffset.layerChangeGcode.contains("M73 L{layer_num+1}"))
        assertTrue(inheritedOffset.changeFilamentGcode.contains("M620 S[next_extruder]A"))
        val filamentCutOverride = catalog.filaments.single {
            it.name == "Bambu PLA Basic @System"
        }
        assertEquals(true, filamentCutOverride.longRetractionWhenCut)
        assertEquals(18f, filamentCutOverride.retractionDistanceWhenCut)
        val idleTemperature = catalog.filaments.single {
            it.name == "Prusa Generic ABS @CORE One"
        }
        assertEquals(130, idleTemperature.idleTemperature)
        val absoluteOutput = catalog.printers.single {
            it.name == "Anycubic Kobra 2 Max 0.4 nozzle"
        }
        assertFalse(absoluteOutput.useRelativeEDistances)
        assertTrue(absoluteOutput.disableM73)
        val limitsDisabled = catalog.printers.single {
            it.name == "Artillery Sidewinder X3 Plus 0.4 nozzle"
        }
        assertFalse(limitsDisabled.emitMachineLimitsToGcode)
        val manualChange = catalog.printers.single { it.name == "Anker M5 0.4 nozzle" }
        assertTrue(manualChange.manualFilamentChange)
        val betweenObjects = catalog.printers.single {
            it.name == "RatRig V-Core 4 300 0.4 nozzle"
        }
        assertEquals(";BETWEEN_OBJECTS\nG92 E0", betweenObjects.printingByObjectGcode)
        val semmExchange = catalog.printers.single {
            it.name == "Artillery M1 Pro 0.4 nozzle"
        }
        assertEquals(91.5f, semmExchange.coolingTubeRetraction)
        assertEquals(5f, semmExchange.coolingTubeLength)
        assertEquals(92f, semmExchange.parkingPosRetraction)
        assertEquals(-2f, semmExchange.extraLoadingMove)
        assertTrue(semmExchange.enableFilamentRamming)
        assertTrue(semmExchange.purgeInPrimeTower)
        assertFalse(semmExchange.highCurrentOnFilamentSwap)
        val highCurrentExchange = catalog.printers.single {
            it.name == "Co Print ChromaSet 0.4 nozzle"
        }
        assertEquals(0f, highCurrentExchange.coolingTubeRetraction)
        assertEquals(0f, highCurrentExchange.coolingTubeLength)
        assertEquals(25f, highCurrentExchange.parkingPosRetraction)
        assertEquals(0f, highCurrentExchange.extraLoadingMove)
        assertTrue(highCurrentExchange.highCurrentOnFilamentSwap)
        val divergentToolChange = catalog.printers.single { it.name == "iQ TiQ2 0.4 Nozzle" }
        assertEquals(listOf(10f, 12f), divergentToolChange.toolChangeRetractLengths)
        assertTrue(
            "Inherited filament G-code templates must survive catalog generation",
            catalog.filaments.any {
                it.filamentStartGcode.isNotBlank() || it.filamentEndGcode.isNotBlank()
            },
        )
        assertTrue("Soluble material semantics must survive catalog generation", catalog.filaments.any { it.soluble })
        assertTrue(
            "Dedicated support material semantics must survive catalog generation",
            catalog.filaments.any { it.supportMaterial },
        )
        assertTrue(
            "Inherited minimum wipe-tower purge values must survive catalog generation",
            catalog.filaments.any { kotlin.math.abs(it.minimalPurgeOnWipeTower - 15f) >= 0.001f },
        )
        assertTrue(
            "Pinned presets without tower-interface overrides must receive engine defaults",
            catalog.filaments.all {
                it.towerInterfacePreExtrusionDistance == 10f &&
                    it.towerInterfacePreExtrusionLength == 0f &&
                    it.towerIroningArea == 4f &&
                    it.towerInterfacePurgeLength == 20f &&
                    it.towerInterfacePrintTemperature == -1
            },
        )
        assertTrue(
            "Inherited auxiliary cooling speeds must survive catalog generation",
            catalog.filaments.any { it.additionalCoolingFanSpeed > 0 },
        )
        assertTrue(
            "Inherited exchange speeds must survive catalog generation",
            catalog.filaments.any { it.loadingSpeed != 28f || it.unloadingSpeed != 90f },
        )
        assertTrue(
            "Inherited cooling moves must survive catalog generation",
            catalog.filaments.any { it.coolingMoves != 4 },
        )
        assertTrue(
            "Inherited stamping motion must survive catalog generation",
            catalog.filaments.any { it.stampingDistance > 0f && it.stampingLoadingSpeed > 0f },
        )
        assertTrue(
            "Inherited ramming curves must survive catalog generation",
            catalog.filaments.any { it.rammingParameters != DEFAULT_FILAMENT_RAMMING_PARAMETERS },
        )
        assertTrue(
            "Inherited multi-tool ramming must survive catalog generation",
            catalog.filaments.any { it.multitoolRamming },
        )
        assertTrue(
            "Inherited layer-time fan thresholds must survive catalog generation",
            catalog.filaments.any { kotlin.math.abs(it.fanCoolingLayerTime - 60f) >= 0.001f },
        )
        assertTrue(
            "Orca plate-specific temperatures must survive catalog generation",
            catalog.filaments.any {
                it.bedTemp != it.texturedPlateTemp ||
                    it.bedTemp != it.coolPlateTemp ||
                    it.texturedPlateTemp != it.engineeringPlateTemp
            },
        )
        assertTrue(
            "Orca XY shrinkage compensation must survive catalog generation",
            catalog.filaments.any { abs(it.shrinkageXyPercent - 100f) >= 0.001f },
        )
        assertTrue(
            "Orca Z shrinkage compensation must survive catalog generation",
            catalog.filaments.any { abs(it.shrinkageZPercent - 100f) >= 0.001f },
        )
        assertTrue(
            "Inherited fan continuity must survive catalog generation",
            catalog.filaments.any { it.keepFanAlwaysOn },
        )
        assertTrue(
            "Inherited overhang cooling thresholds must survive catalog generation",
            catalog.filaments.any { it.overhangFanThreshold != "95%" },
        )
        assertTrue(
            "Inherited role-specific fan speeds must survive catalog generation",
            catalog.filaments.any {
                it.internalBridgeFanSpeed >= 0 || it.supportInterfaceFanSpeed >= 0
            },
        )
        assertTrue(
            "Inherited auxiliary-fan capability must survive catalog generation",
            catalog.printers.any { it.auxiliaryFan },
        )
        assertTrue(
            "Heated-chamber capability must survive printer catalog generation",
            catalog.printers.any { it.supportsChamberTemperatureControl },
        )
        assertTrue(
            "Exhaust-filtration capability must survive printer catalog generation",
            catalog.printers.any { it.supportsAirFiltration },
        )
        assertTrue(
            "Orca chamber material settings must survive filament catalog generation",
            catalog.filaments.any {
                it.chamberTemperatureControl && it.chamberTemperature > 0
            },
        )
        assertTrue(
            "Orca exhaust material settings must survive filament catalog generation",
            catalog.filaments.any {
                it.airFiltration &&
                    (it.duringPrintExhaustFanSpeed > 0 || it.completePrintExhaustFanSpeed > 0)
            },
        )
        assertTrue(
            "Material softening temperatures must retain inherited variation",
            catalog.filaments.map { it.softeningTemperature }.toSet().size > 1,
        )
        assertTrue(
            "Safe nozzle ranges must retain inherited variation",
            catalog.filaments.any {
                it.nozzleTemperatureRangeLow != 190 || it.nozzleTemperatureRangeHigh != 240
            },
        )
        val representativeBrands = setOf(
            "Prusa", "Creality", "Anycubic", "Elegoo", "Snapmaker", "Sovol", "Qidi",
        )
        representativeBrands.forEach { brand ->
            val printers = catalog.printers.filter { it.brand == brand }
            assertTrue("$brand printer profiles must be present", printers.isNotEmpty())
            assertTrue("$brand filament profiles must be present", catalog.filaments.any { it.brand == brand })
            assertTrue("$brand slicing profiles must be present", catalog.slicing.any { it.brand == brand })
            assertTrue(
                "$brand must cover common mobile-selectable nozzle sizes",
                printers.map { it.nozzleDiameter }.toSet().containsAll(setOf(0.2f, 0.4f, 0.6f, 0.8f)),
            )
        }
        assertTrue(catalog.printers.all(ProfileValidation::printer))
        assertTrue(
            "The catalog must retain non-rectangular build plates",
            catalog.printers.any { it.bedPolygon.size > 8 },
        )
        val delta = catalog.printers.single { it.name == "FLSun V400 0.4 nozzle" }
        assertEquals(-150f, delta.bedOriginX, 0.01f)
        assertEquals(-150f, delta.bedOriginY, 0.01f)
        assertTrue(
            "The catalog must retain printer-specific sequential-print clearance",
            catalog.printers.any { it.extruderClearanceRadius != 40f },
        )
        assertTrue(catalog.filaments.all(ProfileValidation::filament))
        assertTrue(
            "Inherited non-standard filament diameters must survive catalog generation",
            catalog.filaments.any { kotlin.math.abs(it.diameter - 1.75f) >= 0.001f },
        )
        assertTrue(
            "Inherited material densities must survive catalog generation",
            catalog.filaments.any { kotlin.math.abs(it.density - 1.24f) >= 0.001f },
        )
        assertTrue(
            "Inherited material prices must survive catalog generation",
            catalog.filaments.any { it.costPerKilogram > 0f },
        )
        assertTrue(
            "Prime-tower process values must survive catalog normalization",
            catalog.slicing.any { it.wipeTowerEnabled && it.wipeTowerWidth != 60f },
        )
        assertTrue(
            "Printable-overhang process values must survive catalog normalization",
            catalog.slicing.any { it.printableOverhangs.maximumAngle == 90f },
        )
        assertTrue(catalog.slicing.all { !it.printableOverhangs.enabled })
        assertTrue(catalog.slicing.all { it.printableOverhangs.holeArea == 0f })
        assertTrue(
            "Pressure Equalizer process values must survive catalog normalization",
            catalog.slicing.any { it.extrusionRateSmoothing.maximumSlope > 0f },
        )
        assertTrue(
            catalog.slicing.any { it.extrusionRateSmoothing.segmentLength == 5f },
        )
        assertTrue(
            "Feature filament routing must survive catalog normalization",
            catalog.slicing.any { it.featureFilaments.wipeTowerFilament != 0 },
        )
        assertTrue(
            "Inherited multi-material process values must survive catalog normalization",
            catalog.slicing.any {
                it.multiMaterial.primeVolume != 45f ||
                    it.multiMaterial.primeTowerBrimWidth != 3f ||
                    it.multiMaterial.wipeTowerNoSparseLayers ||
                    it.multiMaterial.wipeTowerRotationAngle != 0f ||
                    it.multiMaterial.wipeTowerExtraSpacing != 100f ||
                    it.multiMaterial.wipeTowerWallType != "rectangle" ||
                    it.multiMaterial.wipeTowerConeAngle != 30f ||
                    it.multiMaterial.singleExtruderMultiMaterialPriming ||
                    !it.multiMaterial.flushIntoSupport ||
                    it.multiMaterial.oozePrevention ||
                    it.multiMaterial.preheatTime != 30f ||
                    it.multiMaterial.preheatDeltaTemperature != 0 ||
                    it.multiMaterial.preheatSteps != 1 ||
                    it.multiMaterial.interfaceShells ||
                    it.multiMaterial.interlockingBeam
            },
        )
        assertTrue(
            "Pinned presets without new tower-structure overrides must receive engine defaults",
            catalog.slicing.all {
                !it.multiMaterial.primeTowerFramework &&
                    it.multiMaterial.primeTowerSkipPoints &&
                    !it.multiMaterial.primeTowerFlatIroning &&
                    !it.multiMaterial.primeTowerInterfaceFeatures &&
                    !it.multiMaterial.primeTowerInterfaceCooldown &&
                    it.multiMaterial.primeTowerInfillGap == 150f
            },
        )
        assertTrue(
            "Bundled Orca profiles must retain their disabled segmented-region defaults",
            catalog.slicing.all {
                it.multiMaterial.segmentedRegionMaxWidth == 0f &&
                    it.multiMaterial.segmentedRegionInterlockingDepth == 0f
            },
        )
        assertTrue(catalog.slicing.all(ProfileValidation::slicing))
        assertTrue(catalog.slicing.all { it.fillMultiline in 1..5 })
        assertTrue(catalog.slicing.all { filenameFormatIsValid(it.gcodeSettings.filenameFormat) })
        assertTrue(
            "The upstream process catalog must preserve its filename conventions",
            catalog.slicing.map { it.gcodeSettings.filenameFormat }.distinct().size > 10,
        )
        assertTrue(
            "The catalog must retain process-wide flow calibration",
            catalog.slicing.any { it.printFlowRatio != 1f },
        )
        assertTrue(
            "The catalog must retain both arc-fitting policies",
            catalog.slicing.any { it.gcodeSettings.arcFitting } &&
                catalog.slicing.any { !it.gcodeSettings.arcFitting },
        )
        assertTrue(
            "The catalog must retain both object-label policies",
            catalog.slicing.any { it.gcodeSettings.labelObjects } &&
                catalog.slicing.any { !it.gcodeSettings.labelObjects },
        )
        assertTrue(
            "The catalog must retain both object-exclusion policies",
            catalog.slicing.any { it.gcodeSettings.excludeObjects } &&
                catalog.slicing.any { !it.gcodeSettings.excludeObjects },
        )
        assertTrue(
            "The catalog must retain absolute and percentage initial travel speeds",
            catalog.slicing.any { it.gcodeSettings.initialLayerTravelSpeedPercent } &&
                catalog.slicing.any { !it.gcodeSettings.initialLayerTravelSpeedPercent },
        )
        assertTrue(
            "Inherited Orca vertical travel speeds must survive catalog normalization",
            catalog.slicing.any { it.travelSpeedZ > 0f },
        )
        assertTrue(
            "The catalog must retain gradual initial-layer speed ramps",
            catalog.slicing.any { it.gcodeSettings.slowDownLayers > 0 },
        )
        assertTrue(
            "The catalog must retain both acceleration-smoothing policies",
            catalog.slicing.any { it.gcodeSettings.accelToDecelEnabled } &&
                catalog.slicing.any { !it.gcodeSettings.accelToDecelEnabled },
        )
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
        assertTrue(catalog.slicing.any { it.ironing.type == "top" })
        assertTrue(catalog.slicing.any { it.ironing.inset != 0f })
        assertTrue(catalog.slicing.all { it.ironing.angle in -1f..359f })
        assertTrue(catalog.slicing.any { it.supportBasePattern == "rectilinear-grid" })
        assertTrue(catalog.slicing.any { it.supportCoverage.onBuildPlateOnly })
        assertTrue(catalog.slicing.any { it.supportCoverage.criticalRegionsOnly })
        assertTrue(catalog.slicing.any { it.supportCoverage.removeSmallOverhangs })
        assertTrue(catalog.slicing.any { it.supportAdvanced.patternAngle == 45f })
        assertTrue(catalog.slicing.any { it.supportBasePatternSpacing != 2.5f })
        assertTrue(catalog.slicing.any { it.supportExpansion != 0f })
        assertTrue(catalog.slicing.any { it.supportInterfaceLoopPattern })
        assertTrue(catalog.slicing.any { !it.independentSupportLayerHeight })
        assertTrue(catalog.slicing.any { it.supportType == "tree(auto)" })
        assertTrue(catalog.slicing.any { it.treeSupportBranchAngle != 40f })
        assertTrue(catalog.slicing.any { it.treeSupportBranchDistance != 5f })
        assertTrue(catalog.slicing.any { it.treeSupportBranchDiameter != 5f })
        assertTrue(catalog.slicing.any { it.treeSupportWallCount != 0 })
        assertTrue(catalog.slicing.any { it.treeSupportTipDiameter != 0.8f })
        assertTrue(catalog.slicing.any { it.treeSupportPreferredBranchAngle != 25f })
        assertTrue(catalog.slicing.any { it.treeSupportBranchDensity != 30f })
        assertTrue(catalog.slicing.any { it.treeSupportOrganicBranchAngle != 40f })
        assertTrue(catalog.slicing.any { it.treeSupportOrganicBranchDistance != 1f })
        assertTrue(catalog.slicing.any { it.treeSupportOrganicBranchDiameter != 2f })
        assertTrue(catalog.slicing.any { it.treeSupportBranchDiameterAngle != 5f })
        assertTrue(catalog.slicing.any { !it.treeSupportAdaptiveLayerHeight })
        assertTrue(catalog.slicing.any { !it.treeSupportAutoBrim })
        assertTrue(catalog.slicing.any { it.treeSupportBrimWidth != 3f })
        assertTrue(catalog.slicing.any { it.infillFirst })
        assertTrue(catalog.slicing.any { it.wallSequence == "outer-inner" })
        assertTrue(catalog.slicing.any { it.infillCombination })
        assertTrue(
            "Legacy Orca solid-infill rotation must survive catalog normalization",
            catalog.slicing.any { it.solidInfillRotationTemplate == "0,90" },
        )
        assertTrue(
            "Every generated compensation curve must satisfy the native spline contract",
            catalog.slicing.all {
                smallAreaFlowCompensationModelIsValid(it.smallAreaFlowCompensationModel)
            },
        )
        assertTrue(
            "The pinned Orca curve must survive list and serialized profile encodings",
            catalog.slicing.all {
                it.smallAreaFlowCompensationModel == DEFAULT_SMALL_AREA_FLOW_COMPENSATION_MODEL
            },
        )
        assertTrue(catalog.slicing.any { it.internalBridgeSpeedPercent })
        assertTrue(catalog.slicing.any { !it.bridgeAccelerationPercent })
        assertTrue("Inherited Orca jerk profiles must survive catalog normalization", catalog.slicing.any { it.defaultJerk > 0f })
        assertTrue(catalog.slicing.any { it.travelJerk != 12f })
        assertTrue(catalog.slicing.any { it.elephantFootCompensation > 0f })
        assertTrue(catalog.slicing.any { it.xyHoleCompensation != 0f })
        assertTrue(catalog.slicing.any { it.gapFillTarget == "everywhere" })
        assertTrue(catalog.slicing.any { it.gapFillTarget == "topbottom" })
        assertTrue(catalog.slicing.any { it.filterOutGapFill > 0f })
        assertTrue(catalog.slicing.any { it.minimumSparseInfillArea != 15f })
        assertTrue(catalog.slicing.any { it.maxBridgeLength != 10f })
        assertTrue("Vase process presets must retain spiral mode", catalog.slicing.any { it.spiralMode })
        assertTrue("Smooth vase presets must retain smoothing mode", catalog.slicing.any { it.spiralModeSmooth })
        assertTrue(catalog.slicing.any { it.reduceCrossingWall })
        assertTrue(catalog.slicing.any { it.reduceInfillRetraction })
        assertTrue(catalog.slicing.any { it.maxTravelDetourDistancePercent })
        assertTrue(catalog.slicing.any { !it.smallPerimeterSpeedPercent })
        assertTrue(catalog.slicing.any { it.seamGapPercent && it.seamGap != 10f })
        assertTrue("Scarf seam presets must survive catalog normalization", catalog.slicing.any { it.scarfSeam.type != "none" })
        assertTrue(catalog.slicing.any { it.scarfSeam.conditional })
        assertTrue(catalog.slicing.any { it.scarfSeam.speedPercent })
        assertTrue(catalog.slicing.any { it.wipeOnLoops })
        assertTrue(catalog.slicing.any { !it.roleBasedWipeSpeed })
        assertTrue(catalog.slicing.any { it.resolution == 0.012f })
        assertTrue(catalog.slicing.any { it.precision.minimumWallWidth != 85f })
        assertTrue(catalog.slicing.any { it.precision.firstLayerMinimumWallWidth != 85f })
        assertTrue(catalog.slicing.all { it.precision.mode in setOf("regular", "even_odd", "close_holes") })
        assertTrue(catalog.slicing.all { it.precision.closingRadius >= 0f })
        assertTrue(catalog.slicing.any { it.precision.preciseZHeight })
        assertTrue(catalog.slicing.any { it.overhangReverse })
        assertTrue(catalog.slicing.any { it.minWidthTopSurface != 300f })
        assertTrue(catalog.slicing.any { it.internalBridgeFilter == "limited" })
        assertTrue(catalog.slicing.any { it.brimType == "no_brim" })
        assertTrue(catalog.slicing.any { it.brimType == "outer_only" })
        assertTrue(catalog.slicing.any { it.brimObjectGap == 0.1f })
        assertTrue(catalog.slicing.all { it.precision.brimEars.maximumAngle == 125f })
        assertTrue(catalog.slicing.all { it.precision.brimEars.detectionRadius == 1f })
        assertTrue(catalog.slicing.any { it.skirtHeight > 1 })
        assertTrue(catalog.slicing.any { it.minimumSkirtLength > 0f })
        assertTrue(catalog.slicing.any { it.raftFirstLayerExpansion > 2f })
        val legacyDecimalComma = requireNotNull(
            catalog.slicing.find { it.name == "0.05mm Detail @MK3.5" },
        )
        assertEquals(2f, legacyDecimalComma.infillAnchor)
        assertEquals(false, legacyDecimalComma.infillAnchorPercent)
        val legacyInfillFirst = requireNotNull(
            catalog.slicing.find { it.id == "orca-process-118b0a2ab38fdffaa3d4" },
        )
        assertTrue(legacyInfillFirst.infillFirst)
        assertEquals("inner-outer", legacyInfillFirst.wallSequence)
        assertEquals(30f, legacyInfillFirst.infillWallOverlap)
        assertEquals("limited", legacyInfillFirst.internalBridgeFilter)
        val reversedOverhang = requireNotNull(
            catalog.slicing.find { it.id == "orca-process-c664cf6dd495de940c1b" },
        )
        assertTrue(reversedOverhang.overhangReverse)
        assertEquals(50f, reversedOverhang.overhangReverseThreshold)
        assertTrue(reversedOverhang.overhangReverseThresholdPercent)
        val narrowTopThreshold = requireNotNull(
            catalog.slicing.find { it.id == "orca-process-24fb182645fb0995da8d" },
        )
        assertEquals(100f, narrowTopThreshold.minWidthTopSurface)
        assertTrue(narrowTopThreshold.minWidthTopSurfacePercent)
        val legacyOuterFirst = requireNotNull(
            catalog.slicing.find { it.id == "orca-process-358f3384a4aa741baeb8" },
        )
        assertEquals("outer-inner", legacyOuterFirst.wallSequence)
        val distributedArachneWalls = requireNotNull(
            catalog.slicing.find { it.id == "orca-process-ba41bc56a960ec4bf47d" },
        )
        assertEquals(2, distributedArachneWalls.wallDistributionCount)
        val wideArachneTransition = requireNotNull(
            catalog.slicing.find { it.id == "orca-process-1ad5b7e0535fb26f335c" },
        )
        assertEquals(59f, wideArachneTransition.wallTransitionAngle)
        val narrowFeatureProfile = requireNotNull(
            catalog.slicing.find { it.id == "orca-process-118b0a2ab38fdffaa3d4" },
        )
        assertEquals(20f, narrowFeatureProfile.minimumFeatureSize)
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

        val result = inspectModel(model.absolutePath)
        assertTrue("STL must contain triangles", result.triangles > 0)
        assertTrue("STL preview must contain sampled mesh triangles", result.previewTriangles.isNotEmpty())
        assertTrue("STL X dimension must be positive", result.dimensions[0] > 0.0)
        assertTrue("STL Y dimension must be positive", result.dimensions[1] > 0.0)
        assertTrue("STL Z dimension must be positive", result.dimensions[2] > 0.0)
    }

    @Test
    fun malformedInputsFailClosedWithoutKillingJniProcess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "malformed-native-inputs-${System.nanoTime()}")
        assertTrue("Malformed-input fixture directory must be created", root.mkdirs())

        try {
            val oversizedGcode = File(root, "oversized-line.gcode")
            oversizedGcode.writeText("G1 X${"1".repeat(65_537)}\n;LAYER_CHANGE\n;Z:0.2\n")
            val previewOutput = ByteBuffer.allocateDirect(GcodeLayerPreview.MAX_PAYLOAD_BYTES)
                .order(ByteOrder.nativeOrder())
            val gcodeResult = NativeEngine.previewGcodeRangeInto(
                oversizedGcode.absolutePath,
                0,
                Int.MAX_VALUE,
                previewOutput,
            )
            assertTrue("Oversized G-code lines must be rejected", gcodeResult < 0)

            val validGcode = File(root, "valid-preview.gcode")
            validGcode.writeText(";LAYER_CHANGE\n;Z:0.2\nG1 X0 Y0 Z0.2\nG1 X10 Y0 E1\n")
            assertTrue(
                "Heap buffers must be rejected at the Preview JNI boundary",
                NativeEngine.previewGcodeRangeInto(
                    validGcode.absolutePath,
                    0,
                    Int.MAX_VALUE,
                    ByteBuffer.allocate(GcodeLayerPreview.MAX_PAYLOAD_BYTES),
                ) < 0,
            )
            assertTrue(
                "Undersized direct buffers must be rejected at the Preview JNI boundary",
                NativeEngine.previewGcodeRangeInto(
                    validGcode.absolutePath,
                    0,
                    Int.MAX_VALUE,
                    ByteBuffer.allocateDirect(Float.SIZE_BYTES).order(ByteOrder.nativeOrder()),
                ) < 0,
            )

            val extremeStl = File(root, "extreme-coordinate.stl")
            extremeStl.writeText(
                """
                solid extreme
                  facet normal 0 0 1
                    outer loop
                      vertex 3e38 0 0
                      vertex 0 1 0
                      vertex 0 0 1
                    endloop
                  endfacet
                endsolid extreme
                """.trimIndent(),
            )
            assertTrue(
                "Out-of-range STL coordinates must be rejected",
                NativeEngine.inspectStlPayload(extremeStl.absolutePath) == null,
            )

            val samePath = File(root, "same-path.stl")
            fixtureModel().copyTo(samePath)
            val originalBytes = samePath.readBytes()
            val transformResult = JSONObject(
                NativeEngine.transformStl(
                    samePath.absolutePath,
                    samePath.absolutePath,
                    """{"bedCenterMm":[128,128],"offsetMm":[0,0],"rotationDeg":[0,0,0],"scale":1}""",
                ),
            )
            assertTrue("In-place STL transforms must be rejected", !transformResult.optBoolean("ok"))
            assertTrue("Rejected transforms must preserve the source", originalBytes.contentEquals(samePath.readBytes()))

            assertTrue(
                "JNI must remain usable after malformed inputs",
                inspectModel(fixtureModel().absolutePath).triangles > 0,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun automaticLayUsesOrcaInTheIsolatedArm64WorkerAndProducesABedPlacedModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = tiltedAutoOrientModel()
        val sourceInspection = inspectModel(source.absolutePath)
        val output = File(context.cacheDir, "automatic-lay-${System.nanoTime()}.stl")
        try {
            val orientation = SlicerProcessClient.autoOrient(source)
            assertTrue(
                "Orca orientation must contain finite radians",
                orientation.rotationRadians.all { it.isFinite() },
            )
            assertTrue(
                "A tilted rectangular solid must receive a visible automatic orientation",
                orientation.rotationRadians.any { abs(it) > 0.1 },
            )
            assertTrue(
                "Automatic orientation must run outside the application process",
                SlicerProcessClient.lastWorkerPid() > 0 &&
                    SlicerProcessClient.lastWorkerPid() != android.os.Process.myPid(),
            )

            val transform = ModelTransform().withOrcaOrientation(orientation)
            val transformResult = JSONObject(
                NativeEngine.transformStl(
                    source.absolutePath,
                    output.absolutePath,
                    transform.toJson(256f, 256f),
                ),
            )
            assertTrue(
                "Automatic lay transform failed: ${transformResult.optString("error")}",
                transformResult.optBoolean("ok"),
            )
            val inspection = inspectModel(output.absolutePath)
            assertTrue(
                "Automatic lay must put the model on Z=0",
                abs(inspection.minMm[2]) < 0.001,
            )
            assertTrue(
                "Automatic lay must place the tilted solid on its broad stable face: " +
                    "before=${sourceInspection.dimensions[2]} after=${inspection.dimensions[2]}",
                inspection.dimensions[2] < sourceInspection.dimensions[2] * 0.7,
            )
        } finally {
            output.delete()
        }
    }

    @Test
    fun automaticLayAcceptsEveryVolumeOfOneProjectObject() {
        val source = fixtureModel()

        val orientation = SlicerProcessClient.autoOrient(listOf(source, source))

        assertTrue(
            "Multi-volume automatic orientation must return finite radians",
            orientation.rotationRadians.all { it.isFinite() },
        )
        assertTrue(
            "Multi-volume automatic orientation must run outside the application process",
            SlicerProcessClient.lastWorkerPid() > 0 &&
                SlicerProcessClient.lastWorkerPid() != android.os.Process.myPid(),
        )
    }

    @Test
    fun automaticLayReturnsCanonicalIdentityForAnAlreadyStableSolid() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "automatic-lay-stable-${System.nanoTime()}.stl")
        try {
            instrumentation.context.assets.open("20mmbox-LF.stl").use { input ->
                source.outputStream().use(input::copyTo)
            }
            val orientation = SlicerProcessClient.autoOrient(source)
            assertTrue(
                "A stable solid must not return an Euler-equivalent 180/180/180 fake change",
                orientation.rotationRadians.all { abs(Math.toDegrees(it)) < 0.001 },
            )
            assertEquals(ModelTransform(), ModelTransform().withOrcaOrientation(orientation))
        } finally {
            source.delete()
        }
    }

    @Test
    fun selectedFaceUsesRustAlignmentAndStillProducesRealGcodeOnDevice() {
        val source = fixtureModel()
        val model = inspectModel(source.absolutePath)
        val triangle = model.previewTriangles.copyOfRange(0, 9)
        val initialTransform = ModelTransform(
            rotationXdeg = 19f,
            rotationYdeg = -31f,
            rotationZdeg = 12f,
            scale = 1.2f,
            scaleY = 0.85f,
            scaleZ = 1.4f,
            mirrorX = true,
        )
        val projectObject = ProjectObject("selected-face", model, initialTransform)
        val transform = projectObject.withFaceOnBed(triangle)
        val center = FloatArray(3) { axis ->
            ((model.minMm[axis] + model.maxMm[axis]) / 2.0).toFloat()
        }
        val transformed = Array(3) { vertex ->
            transform.transformLocal(
                FloatArray(3) { axis -> triangle[vertex * 3 + axis] - center[axis] },
            )
        }
        val reversesWinding = listOf(transform.mirrorX, transform.mirrorY, transform.mirrorZ)
            .count { it } % 2 == 1
        val secondVertex = if (reversesWinding) transformed[2] else transformed[1]
        val thirdVertex = if (reversesWinding) transformed[1] else transformed[2]
        val first = FloatArray(3) { axis -> secondVertex[axis] - transformed[0][axis] }
        val second = FloatArray(3) { axis -> thirdVertex[axis] - transformed[0][axis] }
        val normal = floatArrayOf(
            first[1] * second[2] - first[2] * second[1],
            first[2] * second[0] - first[0] * second[2],
            first[0] * second[1] - first[1] * second[0],
        )
        val normalLength = sqrt(normal.sumOf { (it * it).toDouble() }).toFloat()
        assertTrue("Selected face normal must point into the bed", normal[2] / normalLength < -0.999f)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val transformedModel = File(context.cacheDir, "lay-on-face-${System.nanoTime()}.stl")
        var slicedOutput: File? = null
        try {
            val options = SliceOptions()
                .selectPrinter(PrinterProfile.U1_04)
                .selectFilament(FilamentProfile.PLA)
                .selectQuality(QualityProfile.DRAFT)
            val result = JSONObject(
                NativeEngine.transformStl(
                    source.absolutePath,
                    transformedModel.absolutePath,
                    transform.toJson(
                        options.bedSizeX,
                        options.bedSizeY,
                        options.bedOriginX,
                        options.bedOriginY,
                    ),
                ),
            )
            assertTrue(result.optString("error"), result.optBoolean("ok"))
            val inspection = inspectModel(transformedModel.absolutePath)
            assertTrue(
                "Placed model must touch Z=0",
                abs(inspection.minMm[2]) < 0.001,
            )

            val outcome = OnDeviceSlicer.slice(transformedModel, options)
            slicedOutput = outcome.output
            assertTrue(
                "Placed model must produce extrusion G-code",
                outcome.output.readText().contains(";TYPE:Outer wall"),
            )
        } finally {
            transformedModel.delete()
            slicedOutput?.delete()
        }
    }

    @Test
    fun selectedFacePlacementDoesNotTrustReversedStlWinding() {
        val model = inspectModel(fixtureModel().absolutePath)
        val triangle = model.previewTriangles.copyOfRange(0, 9)
        val reversed = triangle.copyOf().also { values ->
            repeat(3) { axis ->
                val second = values[3 + axis]
                values[3 + axis] = values[6 + axis]
                values[6 + axis] = second
            }
        }
        val projectObject = ProjectObject(
            id = "reversed-face",
            model = model,
            transform = ModelTransform(
                rotationXdeg = 19f,
                rotationYdeg = -31f,
                rotationZdeg = 12f,
                scale = 1.2f,
                scaleY = 0.85f,
                scaleZ = 1.4f,
                mirrorX = true,
            ),
        )

        val transform = projectObject.withFaceOnBed(reversed)
        val center = projectObject.geometry().center
        val selectedFaceZ = FloatArray(3) { vertex ->
            transform.transformLocal(
                FloatArray(3) { axis -> reversed[vertex * 3 + axis] - center[axis] },
            )[2]
        }

        assertTrue(
            "The tapped plane must be the actual bed-supporting plane even with reversed winding",
            abs(selectedFaceZ.average() - transform.minimumRotatedZ(projectObject)) < 0.05,
        )
        assertTrue(
            "The selected triangle must be horizontal after placement",
            selectedFaceZ.max() - selectedFaceZ.min() < 0.05f,
        )
    }

    @Test
    fun automaticArrangementUsesOrcaInTheIsolatedArm64WorkerAndRecoversAfterNoFit() {
        val source = fixtureModel()
        val model = inspectModel(source.absolutePath)
        val objects = listOf(
            ProjectObject("arrange-first", model, ModelTransform(offsetXmm = -25f)),
            ProjectObject("arrange-second", model, ModelTransform(offsetXmm = 25f)),
        )
        val diamondBed = listOf(50f, 0f, 100f, 50f, 50f, 100f, 0f, 50f)
        val options = SliceOptions().copy(
            bedSizeX = 100f,
            bedSizeY = 100f,
            bedOriginX = -50f,
            bedOriginY = -50f,
            bedPolygon = diamondBed,
        )

        val arrangement = OnDeviceSlicer.arrange(objects, options, minimumGap = 6f)
        assertEquals("Orca must return one placement per object", objects.size, arrangement.objectCount)
        assertTrue(
            "Automatic arrangement must run outside the application process",
            SlicerProcessClient.lastWorkerPid() > 0 &&
                SlicerProcessClient.lastWorkerPid() != android.os.Process.myPid(),
        )
        repeat(arrangement.objectCount) { index ->
            val x = arrangement.lowerLeftMm[index * 2]
            val y = arrangement.lowerLeftMm[index * 2 + 1]
            val width = arrangement.sizesMm[index * 3]
            val depth = arrangement.sizesMm[index * 3 + 1]
            assertTrue("Arranged object must remain inside the bed", x >= -0.05f && y >= -0.05f)
            assertTrue(
                "Arranged object must remain inside the bed",
                x + width <= options.bedSizeX + 0.05f &&
                    y + depth <= options.bedSizeY + 0.05f,
            )
            listOf(x to y, x + width to y, x + width to y + depth, x to y + depth).forEach { corner ->
                assertTrue(
                    "Orca arrangement must honor the non-rectangular printable area: $corner",
                    pointInsideBedPolygon(corner.first, corner.second, diamondBed),
                )
            }
        }
        val firstX = arrangement.lowerLeftMm[0]
        val firstY = arrangement.lowerLeftMm[1]
        val firstWidth = arrangement.sizesMm[0]
        val firstDepth = arrangement.sizesMm[1]
        val secondX = arrangement.lowerLeftMm[2]
        val secondY = arrangement.lowerLeftMm[3]
        val secondWidth = arrangement.sizesMm[3]
        val secondDepth = arrangement.sizesMm[4]
        val horizontalGap = maxOf(
            secondX - (firstX + firstWidth),
            firstX - (secondX + secondWidth),
        )
        val verticalGap = maxOf(
            secondY - (firstY + firstDepth),
            firstY - (secondY + secondDepth),
        )
        val measuredGap = sqrt(
            horizontalGap.coerceAtLeast(0f) * horizontalGap.coerceAtLeast(0f) +
                verticalGap.coerceAtLeast(0f) * verticalGap.coerceAtLeast(0f),
        )
        assertTrue(
            "Orca arrangement must keep the requested object clearance; measured=$measuredGap, " +
                "horizontal=$horizontalGap, vertical=$verticalGap",
            measuredGap >= 5.8f,
        )

        val reservedOptions = options.copy(
            bedSizeX = 150f,
            bedSizeY = 100f,
            bedOriginX = 0f,
            bedOriginY = 0f,
            bedPolygon = rectangularBedPolygon(150f, 100f),
            bedExcludeArea = listOf(0f, 0f, 50f, 0f, 50f, 100f, 0f, 100f),
        )
        val reservedArrangement = OnDeviceSlicer.arrange(
            objects,
            reservedOptions,
            minimumGap = 2f,
        )
        repeat(reservedArrangement.objectCount) { index ->
            assertTrue(
                "Orca arrangement must keep objects out of the printer's unavailable bed area",
                reservedArrangement.lowerLeftMm[index * 2] >= 49.95f,
            )
        }

        val noFit = runCatching {
            OnDeviceSlicer.arrange(
                objects,
                options.copy(
                    bedSizeX = 10f,
                    bedSizeY = 10f,
                    bedOriginX = -5f,
                    bedOriginY = -5f,
                    bedPolygon = listOf(5f, 0f, 10f, 5f, 5f, 10f, 0f, 5f),
                ),
                minimumGap = 6f,
            )
        }
        assertTrue("Orca must reject objects that cannot fit on the bed", noFit.isFailure)
        val healthyWorkerPid = SlicerProcessClient.workerHealthForTest(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        assertTrue("The isolated slicer worker must remain healthy after no-fit", healthyWorkerPid > 0)
        assertNotEquals("Orca must remain isolated from the app", android.os.Process.myPid(), healthyWorkerPid)
    }

    @Test
    fun supportPaintReachesOrcaAndCreatesSupportToolpaths() {
        val modelFile = supportPaintOverhangModel()
        val model = inspectModel(modelFile.absolutePath)
        assertEquals("Support fixture facet order must remain stable", 24, model.triangles)
        val options = SliceOptions()
            .selectQuality(QualityProfile.DRAFT)
            .copy(supportEnabled = false)
        val baseline = OnDeviceSlicer.slice(
            listOf(ProjectObject("baseline", model)),
            options,
        )
        val baselinePreview = loadGcodePreview(baseline.output.absolutePath, 0, Int.MAX_VALUE)

        val paintedFacets = SupportPaint()
            .paint(22, SupportPaintState.ENFORCE)
            .paint(23, SupportPaintState.ENFORCE)
        val painted = OnDeviceSlicer.slice(
            listOf(ProjectObject("painted", model, supportPaint = paintedFacets)),
            options,
        )
        val paintedPreview = loadGcodePreview(painted.output.absolutePath, 0, Int.MAX_VALUE)

        assertEquals("Support-disabled baseline must not create support", 0, baselinePreview.roleSegmentCounts[5])
        assertTrue(
            "Painted enforcer facets must create real Orca support toolpaths",
            paintedPreview.roleSegmentCounts[5] > 0,
        )
    }

    @Test
    fun paintedEnforcersAndBlockersControlRealManualSupportModes() {
        val modelFile = supportPaintOverhangModel()
        val model = inspectModel(modelFile.absolutePath)
        val outputs = mutableListOf<File>()
        val overhangFacets = listOf(22, 23)
        val enforced = overhangFacets.fold(SupportPaint()) { paint, facet ->
            paint.paint(facet, SupportPaintState.ENFORCE)
        }
        val blocked = overhangFacets.fold(SupportPaint()) { paint, facet ->
            paint.paint(facet, SupportPaintState.BLOCK)
        }
        val base = SliceOptions()
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                supportEnabled = true,
                supportType = "normal(auto)",
                supportStyle = "snug",
            )

        fun slice(
            id: String,
            options: SliceOptions,
            paint: SupportPaint = SupportPaint(),
        ): Pair<String, GcodeLayerPreview> {
            val outcome = OnDeviceSlicer.slice(
                listOf(ProjectObject(id, model, supportPaint = paint)),
                options,
            ).also { outputs += it.output }
            return outcome.output.readText() to loadGcodePreview(
                outcome.output.absolutePath,
                0,
                Int.MAX_VALUE,
            )
        }

        try {
            val (automaticGcode, automatic) = slice("automatic-normal", base)
            val (blockedGcode, automaticBlocked) = slice("blocked-normal", base, blocked)
            val (normalManualGcode, normalManual) = slice(
                "painted-normal-manual",
                base.copy(supportEnabled = false),
                enforced,
            )
            val (treeManualGcode, treeManual) = slice(
                "painted-tree-manual",
                base.copy(
                    supportEnabled = false,
                    supportType = "tree(auto)",
                    supportStyle = "tree_slim",
                ),
                enforced,
            )

            assertTrue(automaticGcode.contains("; support_type = normal(auto)"))
            assertTrue(automatic.roleSegmentCounts[5] > 0)
            assertTrue(blockedGcode.contains("; support_type = normal(auto)"))
            assertTrue(
                "Blocking both overhang facets must remove the automatic support beneath them",
                automaticBlocked.roleSegmentCounts[5] == 0,
            )
            assertTrue(normalManualGcode.contains("; enable_support = 1"))
            assertTrue(normalManualGcode.contains("; support_type = normal(manual)"))
            assertTrue(normalManual.roleSegmentCounts[5] > 0)
            assertTrue(treeManualGcode.contains("; enable_support = 1"))
            assertTrue(treeManualGcode.contains("; support_type = tree(manual)"))
            assertTrue(treeManualGcode.contains("; support_style = tree_slim"))
            assertTrue(treeManual.roleSegmentCounts[5] > 0)
            assertNotEquals(
                "Painted normal and tree modes must produce different physical support paths",
                supportExtrusionMotion(normalManualGcode),
                supportExtrusionMotion(treeManualGcode),
            )
        } finally {
            outputs.forEach(File::delete)
            modelFile.delete()
        }
    }

    @Test
    fun everyTreeSupportStyleProducesDistinctPhysicalSupportGeometry() {
        val modelFile = supportPaintOverhangModel()
        val model = inspectModel(modelFile.absolutePath)
        val outputs = mutableListOf<File>()
        val base = SliceOptions()
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                supportEnabled = true,
                supportType = "tree(auto)",
            )
        val styles = compatibleSupportStyles("tree(auto)")

        try {
            val signatures = styles.associateWith { style ->
                val outcome = OnDeviceSlicer.slice(
                    listOf(ProjectObject("tree-style-$style", model)),
                    base.copy(supportStyle = style),
                ).also { outputs += it.output }
                val gcode = outcome.output.readText()
                val preview = loadGcodePreview(outcome.output.absolutePath, 0, Int.MAX_VALUE)
                assertTrue(gcode.contains("; support_type = tree(auto)"))
                assertTrue(gcode.contains("; support_style = $style"))
                assertTrue("$style must generate real support paths", preview.roleSegmentCounts[5] > 0)
                supportExtrusionMotion(gcode).also { motions ->
                    assertTrue("$style support geometry must contain extrusion motion", motions.isNotEmpty())
                }
            }

            assertEquals(
                "Every selectable tree style must produce its own physical support geometry: " +
                    signatures.mapValues { (_, motions) -> motions.hashCode() },
                styles.size,
                signatures.values.toSet().size,
            )
        } finally {
            outputs.forEach(File::delete)
            modelFile.delete()
        }
    }

    @Test
    fun supportFlowRatiosChangePhysicalSupportExtrusion() {
        val modelFile = supportPaintOverhangModel()
        val model = inspectModel(modelFile.absolutePath)
        val outputs = mutableListOf<File>()
        val base = SliceOptions()
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                supportEnabled = true,
                supportType = "normal(auto)",
                supportInterfaceTopLayers = 3,
            )

        try {
            fun slice(options: SliceOptions): String = OnDeviceSlicer.slice(
                listOf(ProjectObject("support-flow", model)),
                options,
            ).also { outputs += it.output }.output.readText()

            val baseline = slice(base.copy(supportFlowRatio = 1f, supportInterfaceFlowRatio = 1f))
            val adjusted = slice(base.copy(supportFlowRatio = 0.72f, supportInterfaceFlowRatio = 1.18f))
            val baselineMotions = supportExtrusionMotion(baseline)
            val adjustedMotions = supportExtrusionMotion(adjusted)

            assertTrue("The fixture must generate physical support extrusion", baselineMotions.isNotEmpty())
            assertTrue(adjusted.contains("; support_flow_ratio = 0.72"))
            assertTrue(adjusted.contains("; support_interface_flow_ratio = 1.18"))
            assertNotEquals(
                "Support flow ratios must change physical extrusion, not only G-code metadata",
                baselineMotions,
                adjustedMotions,
            )
        } finally {
            outputs.forEach(File::delete)
            modelFile.delete()
        }
    }

    @Test
    fun enforcedFirstLayersCreateRealSupportWithAutomaticSupportDisabled() {
        val model = inspectModel(supportPaintOverhangModel().absolutePath)
        val options = SliceOptions()
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                supportEnabled = false,
                supportCoverage = SupportCoverageSettings(enforcedLayers = 0),
                supportType = "normal(auto)",
            )
        val baseline = OnDeviceSlicer.slice(
            listOf(ProjectObject("enforced-support-baseline", model)),
            options,
        )
        val enforced = OnDeviceSlicer.slice(
            listOf(ProjectObject("enforced-support-enabled", model)),
            options.copy(supportCoverage = options.supportCoverage.copy(enforcedLayers = 120)),
        )
        try {
            val baselinePreview = loadGcodePreview(baseline.output.absolutePath, 0, Int.MAX_VALUE)
            val enforcedPreview = loadGcodePreview(enforced.output.absolutePath, 0, Int.MAX_VALUE)
            assertTrue(baseline.output.readText().contains("; enforce_support_layers = 0"))
            assertTrue(enforced.output.readText().contains("; enforce_support_layers = 120"))
            assertEquals(
                "Support-disabled baseline must not create support",
                0,
                baselinePreview.roleSegmentCounts[5],
            )
            assertTrue(
                "Enforced layers must create real Orca support toolpaths",
                enforcedPreview.roleSegmentCounts[5] > 0,
            )
        } finally {
            baseline.output.delete()
            enforced.output.delete()
        }
    }

    @Test
    fun interlockingBeamsChangeTouchingMultiMaterialVolumeToolpaths() {
        val left = inspectModel(interlockingVolumeModel("left", -20f, 0f).absolutePath)
        val right = inspectModel(interlockingVolumeModel("right", 0f, 20f).absolutePath)
        val primary = FilamentProfile.PLA.copy(
            loadingSpeed = 21f,
            loadingSpeedStart = 4f,
            unloadingSpeed = 81f,
            unloadingSpeedStart = 91f,
            toolchangeDelay = 0.7f,
            coolingMoves = 3,
            stampingLoadingSpeed = 29f,
            stampingDistance = 45f,
            coolingInitialSpeed = 2.5f,
            coolingFinalSpeed = 4.5f,
            rammingParameters = "125 95 7 8 9| 0.1 7 0.5 8",
            multitoolRamming = true,
            multitoolRammingVolume = 6f,
            multitoolRammingFlow = 16f,
        )
        val secondary = FilamentProfile.PETG.copy(
            loadingSpeed = 31f,
            loadingSpeedStart = 5f,
            unloadingSpeed = 82f,
            unloadingSpeedStart = 92f,
            toolchangeDelay = 1.2f,
            coolingMoves = 5,
            stampingLoadingSpeed = 0f,
            stampingDistance = 0f,
            coolingInitialSpeed = 3.5f,
            coolingFinalSpeed = 5.5f,
            rammingParameters = "130 90 8 9 10| 0.2 8 0.6 9",
            multitoolRamming = false,
            multitoolRammingVolume = 7f,
            multitoolRammingFlow = 17f,
        )
        val projectObject = ProjectObject(
            id = "interlocking-object",
            volumes = listOf(
                ProjectVolume("interlocking-left", left, filamentSlot = 0),
                ProjectVolume("interlocking-right", right, filamentSlot = 1),
            ),
        )
        val baselineOptions = SliceOptions()
            .selectPrinter(
                PrinterProfile.U1_04.copy(
                    beforeLayerChangeGcode = "; DUCKY_BEFORE_LAYER",
                    layerChangeGcode = "; DUCKY_AFTER_LAYER",
                    changeFilamentGcode = "T[next_extruder]\n; DUCKY_CHANGE_FILAMENT",
                    toolChangeRetractLengths = listOf(1.2f, 2.3f),
                    toolChangeRetractRestartExtras = listOf(-0.1f, 0.2f),
                ),
            )
            .selectFilament(primary)
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                filamentSlots = listOf(primary, secondary),
                wipeTowerEnabled = false,
                multiMaterial = MultiMaterialSettings(interlockingBeam = false),
            )
        val baseline = OnDeviceSlicer.slice(listOf(projectObject), baselineOptions)
        val interlocked = OnDeviceSlicer.slice(
            listOf(projectObject),
            baselineOptions.copy(
                multiMaterial = MultiMaterialSettings(
                    interlockingBeam = true,
                    interlockingBeamWidth = 1.2f,
                    interlockingOrientation = 45f,
                    interlockingBeamLayerCount = 2,
                    interlockingDepth = 3,
                    interlockingBoundaryAvoidance = 1,
                ),
            ),
        )
        val offset = OnDeviceSlicer.slice(
            listOf(projectObject),
            baselineOptions.copy(
                printerProfile = baselineOptions.printerProfile.copy(
                    extruderOffsetsX = listOf(0f, 12.5f),
                    extruderOffsetsY = listOf(0f, -3.25f),
                ),
            ),
        )
        val manual = OnDeviceSlicer.slice(
            listOf(projectObject),
            baselineOptions.copy(
                printerProfile = baselineOptions.printerProfile.copy(manualFilamentChange = true),
            ),
        )
        val baselinePreview = loadGcodePreview(baseline.output.absolutePath, 0, Int.MAX_VALUE)
        val interlockedPreview = loadGcodePreview(interlocked.output.absolutePath, 0, Int.MAX_VALUE)
        val interlockedGcode = interlocked.output.readText()
        val offsetGcode = offset.output.readText()
        val manualGcode = manual.output.readText()

        assertTrue("Touching volumes must use both materials", interlockedGcode.lineSequence().any { it == "T1" })
        assertTrue(
            "The native Preview must preserve the first filament tool",
            baselinePreview.toolSegmentCounts[0] > 0,
        )
        assertTrue(
            "The native Preview must preserve the second filament tool",
            baselinePreview.toolSegmentCounts[1] > 0,
        )
        assertTrue(
            "Tool-preserving Preview must survive interlocking geometry",
            interlockedPreview.toolSegmentCounts[0] > 0 &&
                interlockedPreview.toolSegmentCounts[1] > 0,
        )
        assertTrue(
            "Before-layer G-code must run on repeated real layer transitions",
            interlockedGcode.lineSequence().count { it == "; DUCKY_BEFORE_LAYER" } > 1,
        )
        assertTrue(
            "After-layer G-code must run on repeated real layer transitions",
            interlockedGcode.lineSequence().count { it == "; DUCKY_AFTER_LAYER" } > 1,
        )
        assertTrue(
            "Change-filament G-code must run on a real tool transition",
            interlockedGcode.lineSequence().any { it == "; DUCKY_CHANGE_FILAMENT" },
        )
        assertTrue(
            "Per-tool retract lengths must reach Orca",
            interlockedGcode.contains("; retract_length_toolchange = 1.2,2.3"),
        )
        assertTrue(
            "Per-tool restart extras must reach Orca",
            interlockedGcode.contains("; retract_restart_extra_toolchange = -0.1,0.2"),
        )
        assertTrue(
            "Per-tool XY offsets must reach Orca",
            offsetGcode.contains("; extruder_offset = 0x0,12.5x-3.25"),
        )
        assertTrue(manualGcode.contains("; manual_filament_change = 1"))
        assertTrue(
            "Manual filament mode must replace firmware tool commands with Orca's manual marker",
            manualGcode.lineSequence().any { it.contains("MANUAL_TOOL_CHANGE T1") },
        )
        assertTrue(
            "Manual filament mode must skip the first custom change template invocation",
            manualGcode.lineSequence().count { it == "; DUCKY_CHANGE_FILAMENT" } <
                interlockedGcode.lineSequence().count { it == "; DUCKY_CHANGE_FILAMENT" },
        )
        val baselineToolMove = firstXyMoveAfterToolOne(baseline.output.readText())
        val offsetToolMove = firstXyMoveAfterToolOne(offsetGcode)
        assertTrue(
            "A non-zero second-tool offset must change real G-code coordinates",
            abs(baselineToolMove.first - offsetToolMove.first) > 1f ||
                abs(baselineToolMove.second - offsetToolMove.second) > 1f,
        )
        assertTrue("Interlocking must be active in Orca", interlockedGcode.contains("; interlocking_beam = 1"))
        assertFalse(
            "Interlocking must change real extrusion geometry, not only profile metadata",
            baselinePreview.segments.contentEquals(interlockedPreview.segments),
        )
    }

    @Test
    fun projectFilamentColorsReachOrcaGcodeWithoutLosingToolGeometry() {
        val leftFile = interlockingVolumeModel("color-left", -20f, 0f)
        val rightFile = interlockingVolumeModel("color-right", 0f, 20f)
        val left = inspectModel(leftFile.absolutePath)
        val right = inspectModel(rightFile.absolutePath)
        val projectObject = ProjectObject(
            id = "filament-color-object",
            volumes = listOf(
                ProjectVolume("filament-color-left", left, filamentSlot = 0),
                ProjectVolume("filament-color-right", right, filamentSlot = 1),
            ),
        )
        val options = SliceOptions()
            .selectPrinter(PrinterProfile.CUSTOM_CARTESIAN.copy(extruderCount = 2))
            .selectFilament(FilamentProfile.PLA)
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                filamentSlots = listOf(FilamentProfile.PLA, FilamentProfile.PETG),
                filamentColors = listOf(0x123456, 0xABCDEF),
                wipeTowerEnabled = false,
            )
        val outcome = OnDeviceSlicer.slice(listOf(projectObject), options)
        try {
            val gcode = outcome.output.readText()
            val colorHeader = gcode.lineSequence()
                .firstOrNull { it.startsWith("; filament_colour =") }
                ?.replace(" ", "")
            val preview = loadGcodePreview(outcome.output.absolutePath, 0, Int.MAX_VALUE)

            assertEquals(";filament_colour=#123456;#ABCDEF", colorHeader)
            assertTrue(preview.toolSegmentCounts[0] > 0)
            assertTrue(preview.toolSegmentCounts[1] > 0)
        } finally {
            outcome.output.delete()
            leftFile.delete()
            rightFile.delete()
        }
    }

    @Test
    fun filamentCutLongRetractionControlsRealToolChangeGcode() {
        val left = inspectModel(interlockingVolumeModel("cut-retraction-left", -20f, 0f).absolutePath)
        val right = inspectModel(interlockingVolumeModel("cut-retraction-right", 0f, 20f).absolutePath)
        val projectObject = ProjectObject(
            id = "cut-retraction-object",
            volumes = listOf(
                ProjectVolume("cut-retraction-left", left, filamentSlot = 0),
                ProjectVolume("cut-retraction-right", right, filamentSlot = 1),
            ),
        )
        val changeTemplate = """
            {if previous_extruder >= 0}
            {if long_retractions_when_cut[previous_extruder]}
            ; DUCKY_LONG_CUT E-{retraction_distances_when_cut[previous_extruder]}
            {else}
            ; DUCKY_LONG_CUT_DISABLED
            {endif}
            {else}
            ; DUCKY_LONG_CUT_INITIAL
            {endif}
            T[next_extruder]
        """.trimIndent()
        val printer = PrinterProfile.CUSTOM_CARTESIAN.copy(
            extruderCount = 2,
            machineStartGcode = "",
            machineEndGcode = "",
            changeFilamentGcode = changeTemplate,
            longRetractionWhenCutLevel = 2,
            longRetractionWhenCut = false,
            retractionDistanceWhenCut = 17f,
        )
        val enabledFilament = FilamentProfile.PLA.copy(
            longRetractionWhenCut = true,
            retractionDistanceWhenCut = 16.5f,
        )
        val disabledFilament = FilamentProfile.PETG.copy(
            longRetractionWhenCut = false,
            retractionDistanceWhenCut = 12.5f,
        )
        val options = SliceOptions()
            .selectPrinter(printer)
            .selectFilament(enabledFilament)
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                filamentSlots = listOf(enabledFilament, disabledFilament),
                wipeTowerEnabled = false,
            )

        val enabled = OnDeviceSlicer.slice(listOf(projectObject), options).output.readText()
        val disabled = OnDeviceSlicer.slice(
            listOf(projectObject),
            options.selectFilament(enabledFilament.copy(longRetractionWhenCut = false)).copy(
                filamentSlots = listOf(
                    enabledFilament.copy(longRetractionWhenCut = false),
                    disabledFilament,
                ),
            ),
        ).output.readText()
        val firmwareRetraction = OnDeviceSlicer.slice(
            listOf(projectObject),
            options.copy(printerProfile = printer.copy(useFirmwareRetraction = true)),
        ).output.readText()

        assertTrue(enabled.contains("; enable_long_retraction_when_cut = 2"))
        assertTrue(enabled.contains("; long_retractions_when_cut = 1,0"))
        assertTrue(enabled.contains("; retraction_distances_when_cut = 16.5,12.5"))
        assertTrue(
            "The enabled branch must use the first material's exact distance",
            enabled.lineSequence().any { it == "; DUCKY_LONG_CUT E-16.5" },
        )
        assertTrue("The enabled branch must execute a real tool transition", enabled.lineSequence().any { it == "T1" })
        assertTrue(disabled.contains("; long_retractions_when_cut = 0,0"))
        assertTrue(disabled.lineSequence().any { it == "; DUCKY_LONG_CUT_DISABLED" })
        assertFalse(disabled.lineSequence().any { it.startsWith("; DUCKY_LONG_CUT E-") })
        assertTrue(firmwareRetraction.contains("; use_firmware_retraction = 1"))
        assertTrue(firmwareRetraction.contains("; long_retractions_when_cut = 0,0"))
        assertTrue(firmwareRetraction.lineSequence().any { it == "; DUCKY_LONG_CUT_DISABLED" })
        assertFalse(firmwareRetraction.lineSequence().any { it.startsWith("; DUCKY_LONG_CUT E-") })
    }

    @Test
    fun toolChangeTimingAndIdleTemperatureReachRealTwoToolGcode() {
        val left = inspectModel(interlockingVolumeModel("timing-left", -20f, 0f).absolutePath)
        val right = inspectModel(interlockingVolumeModel("timing-right", 0f, 20f).absolutePath)
        val projectObject = ProjectObject(
            id = "timing-object",
            volumes = listOf(
                ProjectVolume("timing-left", left, filamentSlot = 0),
                ProjectVolume("timing-right", right, filamentSlot = 1),
            ),
        )
        val primary = FilamentProfile.PLA.copy(idleTemperature = 135)
        val secondary = FilamentProfile.PETG.copy(idleTemperature = 145)
        val basePrinter = PrinterProfile.CUSTOM_CARTESIAN.copy(
            extruderCount = 2,
            machineStartGcode = "",
            machineEndGcode = "",
            changeFilamentGcode = "T[next_extruder]",
            machineLoadFilamentTime = 0f,
            machineUnloadFilamentTime = 0f,
            machineToolChangeTime = 0f,
            toolChangeTemperatureWait = true,
        )
        val baseOptions = SliceOptions()
            .selectPrinter(basePrinter)
            .selectFilament(primary)
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                filamentSlots = listOf(primary, secondary),
                wipeTowerEnabled = false,
                multiMaterial = MultiMaterialSettings(
                    oozePrevention = true,
                    standbyTemperatureDelta = -5,
                ),
            )
        val baseline = OnDeviceSlicer.slice(listOf(projectObject), baseOptions)
        val timed = OnDeviceSlicer.slice(
            listOf(projectObject),
            baseOptions.copy(
                printerProfile = basePrinter.copy(
                    machineLoadFilamentTime = 100f,
                    machineUnloadFilamentTime = 110f,
                    machineToolChangeTime = 120f,
                ),
            ),
        )
        val nonBlocking = OnDeviceSlicer.slice(
            listOf(projectObject),
            baseOptions.copy(
                printerProfile = basePrinter.copy(toolChangeTemperatureWait = false),
            ),
        )
        val timedGcode = timed.output.readText()
        val nonBlockingGcode = nonBlocking.output.readText()
        val idleCommand = Regex("""M104(?:\s+T1)?\s+S145|M104\s+S145\s+T1""")
        val blockingReheat = Regex("""M109(?:\s+T1)?\s+S245|M109\s+S245\s+T1""")
        val nonBlockingReheat = Regex("""M104(?:\s+T1)?\s+S245|M104\s+S245\s+T1""")

        assertTrue("Both materials must participate in a real tool transition", timedGcode.lineSequence().any { it == "T1" })
        assertTrue(timedGcode.contains("; machine_load_filament_time = 100"))
        assertTrue(timedGcode.contains("; machine_unload_filament_time = 110"))
        assertTrue(timedGcode.contains("; machine_tool_change_time = 120"))
        assertTrue(timedGcode.contains("; idle_temperature = 135,145"))
        assertTrue("The inactive second tool must receive its exact idle temperature", idleCommand.containsMatchIn(timedGcode))
        assertTrue("Blocking policy must emit an M109 reheat for T1", blockingReheat.containsMatchIn(timedGcode))
        assertTrue("Non-blocking policy must emit an M104 reheat for T1", nonBlockingReheat.containsMatchIn(nonBlockingGcode))
        assertTrue(
            "One real tool change must add the configured load, unload, and switch duration",
            timed.estimatedSeconds - baseline.estimatedSeconds >= 300f,
        )
    }

    @Test
    fun semmMachineExchangeSettingsControlRealWipeTowerGcode() {
        val left = inspectModel(interlockingVolumeModel("semm-left", -20f, 0f).absolutePath)
        val right = inspectModel(interlockingVolumeModel("semm-right", 0f, 20f).absolutePath)
        val projectObject = ProjectObject(
            id = "semm-exchange-object",
            volumes = listOf(
                ProjectVolume("semm-left", left, filamentSlot = 0),
                ProjectVolume("semm-right", right, filamentSlot = 1),
            ),
        )
        val printer = PrinterProfile.CUSTOM_CARTESIAN.copy(
            singleExtruderMultiMaterial = true,
            extruderCount = 2,
            machineStartGcode = "",
            machineEndGcode = "",
            changeFilamentGcode = "T[next_extruder]",
            coolingTubeRetraction = 73.5f,
            coolingTubeLength = 11f,
            parkingPosRetraction = 80f,
            extraLoadingMove = -3.5f,
            enableFilamentRamming = true,
            purgeInPrimeTower = true,
            highCurrentOnFilamentSwap = true,
        )
        val primary = FilamentProfile.PLA.copy(
            loadingSpeed = 21f,
            loadingSpeedStart = 4f,
            unloadingSpeed = 81f,
            unloadingSpeedStart = 91f,
            toolchangeDelay = 0.7f,
            coolingMoves = 3,
            stampingLoadingSpeed = 29f,
            stampingDistance = 45f,
            coolingInitialSpeed = 2.5f,
            coolingFinalSpeed = 4.5f,
            multitoolRamming = true,
            multitoolRammingVolume = 6f,
            multitoolRammingFlow = 16f,
        )
        val secondary = FilamentProfile.PETG.copy(
            loadingSpeed = 31f,
            loadingSpeedStart = 5f,
            unloadingSpeed = 82f,
            unloadingSpeedStart = 92f,
            toolchangeDelay = 1.2f,
            coolingMoves = 5,
            coolingInitialSpeed = 3.5f,
            coolingFinalSpeed = 5.5f,
            multitoolRammingVolume = 7f,
            multitoolRammingFlow = 17f,
        )
        val base = SliceOptions()
            .selectPrinter(printer)
            .selectFilament(primary)
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                filamentSlots = listOf(primary, secondary),
                wipeTowerEnabled = true,
                multiMaterial = MultiMaterialSettings(
                    purgeVolumes = listOf(0f, 140f, 140f, 0f),
                ),
            )
        val enabled = OnDeviceSlicer.slice(listOf(projectObject), base).output.readText()
        val defaultMotion = OnDeviceSlicer.slice(
            listOf(projectObject),
            base.selectFilament(FilamentProfile.PLA).copy(
                filamentSlots = listOf(FilamentProfile.PLA, FilamentProfile.PETG),
            ),
        ).output.readText()
        val disabled = OnDeviceSlicer.slice(
            listOf(projectObject),
            base.copy(
                printerProfile = printer.copy(
                    coolingTubeRetraction = 0f,
                    coolingTubeLength = 0f,
                    parkingPosRetraction = 25f,
                    extraLoadingMove = 0f,
                    enableFilamentRamming = false,
                    purgeInPrimeTower = false,
                    highCurrentOnFilamentSwap = false,
                ),
            ),
        ).output.readText()

        assertTrue(enabled.contains("; cooling_tube_retraction = 73.5"))
        assertTrue(enabled.contains("; cooling_tube_length = 11"))
        assertTrue(enabled.contains("; parking_pos_retraction = 80"))
        assertTrue(enabled.contains("; extra_loading_move = -3.5"))
        assertTrue(enabled.contains("; enable_filament_ramming = 1"))
        assertTrue(enabled.contains("; purge_in_prime_tower = 1"))
        assertTrue(enabled.contains("; high_current_on_filament_swap = 1"))
        assertTrue(
            enabled.lineSequence().filter { it.contains("filament_loading_speed") }.joinToString("\n"),
            enabled.contains("; filament_loading_speed = 21,31"),
        )
        assertTrue(enabled.contains("; filament_loading_speed_start = 4,5"))
        assertTrue(enabled.contains("; filament_unloading_speed = 81,82"))
        assertTrue(enabled.contains("; filament_unloading_speed_start = 91,92"))
        assertTrue(enabled.contains("; filament_toolchange_delay = 0.7,1.2"))
        assertTrue(enabled.contains("; filament_cooling_moves = 3,5"))
        assertTrue(enabled.contains("; filament_stamping_loading_speed = 29,0"))
        assertTrue(enabled.contains("; filament_stamping_distance = 45,0"))
        assertTrue(enabled.contains("; filament_cooling_initial_speed = 2.5,3.5"))
        assertTrue(enabled.contains("; filament_cooling_final_speed = 4.5,5.5"))
        assertTrue(enabled.contains("; filament_multitool_ramming = 1,0"))
        assertTrue(enabled.contains("; filament_multitool_ramming_volume = 6,7"))
        assertTrue(enabled.contains("; filament_multitool_ramming_flow = 16,17"))
        assertTrue("High-current SEMM ramming must emit Orca's current increase", enabled.contains("M907 E750"))
        assertTrue("High-current SEMM ramming must restore the current", enabled.contains("M907 E550"))
        assertTrue("The enabled SEMM path must execute a real tool transition", enabled.lineSequence().any { it == "T1" })
        assertNotEquals(
            "Filament exchange motion must change real WipeTower output",
            defaultMotion,
            enabled,
        )

        assertTrue(disabled.contains("; cooling_tube_retraction = 0"))
        assertTrue(disabled.contains("; cooling_tube_length = 0"))
        assertTrue(disabled.contains("; parking_pos_retraction = 25"))
        assertTrue(disabled.contains("; extra_loading_move = 0"))
        assertTrue(disabled.contains("; enable_filament_ramming = 0"))
        assertTrue(disabled.contains("; purge_in_prime_tower = 0"))
        assertTrue(disabled.contains("; high_current_on_filament_swap = 0"))
        assertFalse(disabled.contains("M907 E750"))
        assertNotEquals("SEMM exchange settings must change real output", enabled, disabled)
    }

    @Test
    fun independentToolchangerRammingControlsChangeRealWipeTowerToolpaths() {
        val left = inspectModel(interlockingVolumeModel("toolchanger-left", -20f, 0f).absolutePath)
        val right = inspectModel(interlockingVolumeModel("toolchanger-right", 0f, 20f).absolutePath)
        val projectObject = ProjectObject(
            id = "toolchanger-ramming-object",
            volumes = listOf(
                ProjectVolume("toolchanger-left", left, filamentSlot = 0),
                ProjectVolume("toolchanger-right", right, filamentSlot = 1),
            ),
        )
        val rammingFilament = FilamentProfile.PLA.copy(
            multitoolRamming = true,
            multitoolRammingVolume = 6f,
            multitoolRammingFlow = 16f,
        )
        val passiveFilament = FilamentProfile.PETG.copy(
            multitoolRamming = false,
            multitoolRammingVolume = 7f,
            multitoolRammingFlow = 17f,
        )
        val printer = PrinterProfile.CUSTOM_CARTESIAN.copy(
            singleExtruderMultiMaterial = false,
            extruderCount = 2,
            machineStartGcode = "",
            machineEndGcode = "",
            changeFilamentGcode = "T[next_extruder]",
            rammingLineWidthRatio = 3.25f,
            changePressureWhenWiping = true,
            rammingPressureAdvance = 0.17f,
        )
        val enabledOptions = SliceOptions()
            .selectPrinter(printer)
            .selectFilament(rammingFilament)
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                filamentSlots = listOf(rammingFilament, passiveFilament),
                wipeTowerEnabled = true,
                multiMaterial = MultiMaterialSettings(
                    purgeVolumes = listOf(0f, 140f, 140f, 0f),
                ),
            )
        val baselineOptions = enabledOptions.copy(
            printerProfile = printer.copy(
                rammingLineWidthRatio = 1f,
                changePressureWhenWiping = false,
                rammingPressureAdvance = 0f,
            ),
        )

        val enabled = OnDeviceSlicer.slice(listOf(projectObject), enabledOptions)
        val baseline = OnDeviceSlicer.slice(listOf(projectObject), baselineOptions)
        val enabledGcode = enabled.output.readText()
        val baselineGcode = baseline.output.readText()
        val enabledPreview = loadGcodePreview(enabled.output.absolutePath, 0, Int.MAX_VALUE)
        val baselinePreview = loadGcodePreview(baseline.output.absolutePath, 0, Int.MAX_VALUE)

        assertTrue(enabledGcode.contains("; single_extruder_multi_material = 0"))
        assertTrue(enabledGcode.contains("; filament_multitool_ramming = 1,0"))
        assertTrue(enabledGcode.contains("; filament_multitool_ramming_volume = 6,7"))
        assertTrue(enabledGcode.contains("; filament_multitool_ramming_flow = 16,17"))
        assertTrue(enabledGcode.contains("; ramming_line_width_ratio = 3.25"))
        assertTrue(enabledGcode.contains("; enable_change_pressure_when_wiping = 1"))
        assertTrue(enabledGcode.contains("; ramming_pressure_advance_value = 0.17"))
        assertTrue(
            "Independent toolchanger ramming must emit the configured pressure advance",
            enabledGcode.contains("M900 K0.170000"),
        )
        assertTrue(baselineGcode.contains("; ramming_line_width_ratio = 1"))
        assertTrue(baselineGcode.contains("; enable_change_pressure_when_wiping = 0"))
        assertFalse(baselineGcode.contains("M900 K0.170000"))
        assertTrue("Independent toolchanger must execute a real tool transition", enabledGcode.lineSequence().any { it == "T1" })
        assertFalse(
            "Ramming line width must change rendered wipe-tower extrusion geometry",
            enabledPreview.segments.contentEquals(baselinePreview.segments),
        )
    }

    @Test
    fun bundledBambuLifecycleTemplatesRunThroughRealToolChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val printer = OrcaProfileCatalog(context).load().printers.single {
            it.name == "Bambu Lab P1P 0.4 nozzle"
        }
        val left = inspectModel(interlockingVolumeModel("bambu-left", -20f, 0f).absolutePath)
        val right = inspectModel(interlockingVolumeModel("bambu-right", 0f, 20f).absolutePath)
        val projectObject = ProjectObject(
            id = "bambu-lifecycle-object",
            volumes = listOf(
                ProjectVolume("bambu-left", left, filamentSlot = 0),
                ProjectVolume("bambu-right", right, filamentSlot = 1),
            ),
        )
        val options = SliceOptions()
            .selectPrinter(printer)
            .selectFilament(FilamentProfile.PLA)
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                filamentSlots = listOf(FilamentProfile.PLA, FilamentProfile.PETG),
                wipeTowerEnabled = false,
            )

        val output = OnDeviceSlicer.slice(listOf(projectObject), options).output.readText()

        assertTrue("Bundled P1P layer G-code must execute", output.contains("M73 L1"))
        assertTrue("Bundled P1P tool-change G-code must execute", output.contains("M620 S1A"))
        assertTrue("The real bundled template must still select tool 1", output.lineSequence().any { it == "T1" })
    }

    @Test
    fun bundledRaise3dLegacyToolchangeTemplateRunsThroughRealDualToolSlice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bundledPrinter = OrcaProfileCatalog(context).load().printers.single {
            it.name == "Raise3D Pro3 0.4 nozzle (Dual)"
        }
        val printer = bundledPrinter.copy(machineStartGcode = "", machineEndGcode = "")
        val left = inspectModel(interlockingVolumeModel("raise3d-left", -20f, 0f).absolutePath)
        val right = inspectModel(interlockingVolumeModel("raise3d-right", 0f, 20f).absolutePath)
        val projectObject = ProjectObject(
            id = "raise3d-legacy-toolchange-object",
            volumes = listOf(
                ProjectVolume("raise3d-left", left, filamentSlot = 0),
                ProjectVolume("raise3d-right", right, filamentSlot = 1),
            ),
        )
        val options = SliceOptions()
            .selectPrinter(printer)
            .selectFilament(FilamentProfile.PLA)
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                filamentSlots = listOf(FilamentProfile.PLA, FilamentProfile.PETG),
                wipeTowerEnabled = false,
            )

        val output = OnDeviceSlicer.slice(listOf(projectObject), options).output.readText()
        val executableLines = output.lineSequence().map(String::trim).toList()
        val resolvedMarker = Regex("""; layer \d+ tool change""")
        val resolvedMarkerIndex = executableLines.indexOfFirst(resolvedMarker::matches)

        assertEquals(2, printer.extruderCount)
        assertTrue(
            "The legacy template marker must be resolved and executed",
            resolvedMarkerIndex >= 0,
        )
        assertTrue(
            "The outgoing tool must receive a resolved standby temperature",
            executableLines.any { Regex("""M104 T[01] S\d+""").matches(it) },
        )
        assertTrue(
            "The incoming tool must receive a resolved blocking temperature",
            executableLines.any { Regex("""M109 T[01] S\d+""").matches(it) },
        )
        assertTrue("The real bundled dual preset must select tool 1", executableLines.any { it == "T1" })
        val resolvedBlock = executableLines.drop(resolvedMarkerIndex).take(5)
        assertFalse(resolvedBlock.any { it.contains("[current_extruder]") })
        assertFalse(resolvedBlock.any { it.contains("[next_extruder]") })
    }

    @Test
    fun primeTowerStructureControlsChangeRealToolpaths() {
        val printer = PrinterProfile.U1_04.copy(
            machineStartGcode = "",
            machineEndGcode = "",
            changeFilamentGcode = "T[next_extruder]",
        )
        val model = inspectModel(fixtureModel().absolutePath)
        val objects = listOf(
            ProjectObject(
                id = "tower-structure-primary",
                model = model,
                transform = ModelTransform(offsetXmm = -20f),
                filamentSlot = 0,
            ),
            ProjectObject(
                id = "tower-structure-secondary",
                model = model,
                transform = ModelTransform(offsetXmm = 20f),
                filamentSlot = 1,
            ),
        )
        val baseOptions = SliceOptions()
            .selectPrinter(printer)
            .selectFilament(FilamentProfile.PLA)
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                filamentSlots = listOf(FilamentProfile.PLA, FilamentProfile.PETG),
                wipeTowerEnabled = true,
                multiMaterial = MultiMaterialSettings(
                    purgeVolumes = listOf(0f, 140f, 140f, 0f),
                    primeTowerFramework = false,
                    primeTowerSkipPoints = false,
                    primeTowerInfillGap = 150f,
                ),
            )

        val baseline = OnDeviceSlicer.slice(objects, baseOptions)
        val framework = OnDeviceSlicer.slice(
            objects,
            baseOptions.copy(
                multiMaterial = baseOptions.multiMaterial.copy(primeTowerFramework = true),
            ),
        )
        val skippedWall = OnDeviceSlicer.slice(
            objects,
            baseOptions.copy(
                multiMaterial = baseOptions.multiMaterial.copy(primeTowerSkipPoints = true),
            ),
        )
        val widerGap = OnDeviceSlicer.slice(
            objects,
            baseOptions.copy(
                multiMaterial = baseOptions.multiMaterial.copy(primeTowerInfillGap = 250f),
            ),
        )

        val baselinePreview = loadGcodePreview(baseline.output.absolutePath, 0, Int.MAX_VALUE)
        val frameworkPreview = loadGcodePreview(framework.output.absolutePath, 0, Int.MAX_VALUE)
        val skippedWallPreview = loadGcodePreview(skippedWall.output.absolutePath, 0, Int.MAX_VALUE)
        val widerGapPreview = loadGcodePreview(widerGap.output.absolutePath, 0, Int.MAX_VALUE)
        val baselineGcode = baseline.output.readText()
        val frameworkGcode = framework.output.readText()

        val frameworkConfigEnabled = frameworkGcode.contains("; prime_tower_enable_framework = 1")
        val frameworkGeometryChanged = !baselinePreview.segments.contentEquals(frameworkPreview.segments)
        val skippedWallGeometryChanged = !baselinePreview.segments.contentEquals(skippedWallPreview.segments)
        val widerGapGeometryChanged = !baselinePreview.segments.contentEquals(widerGapPreview.segments)

        assertTrue("The fixture must execute a real material change", baselineGcode.lineSequence().any { it == "T1" })
        assertTrue(
            "Expected active prime-tower controls; config=$frameworkConfigEnabled, " +
                "frameworkGeometry=$frameworkGeometryChanged, " +
                "skipGeometry=$skippedWallGeometryChanged, gapGeometry=$widerGapGeometryChanged",
            frameworkConfigEnabled &&
                frameworkGeometryChanged &&
                skippedWallGeometryChanged &&
                widerGapGeometryChanged,
        )
    }

    @Test
    fun smoothTimelapseRunsTheBundledOrcaTowerPathOnEveryLayer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val printer = OrcaProfileCatalog(context).load().printers.single {
            it.name == "Bambu Lab P1P 0.4 nozzle"
        }
        val model = inspectModel(fixtureModel().absolutePath)
        val objects = listOf(
            ProjectObject(
                id = "timelapse-object",
                model = model,
            ),
        )
        val base = SliceOptions()
            .selectPrinter(printer)
            .selectFilament(FilamentProfile.PLA)
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                wipeTowerEnabled = true,
            )

        val traditional = OnDeviceSlicer.slice(
            objects,
            base.copy(
                gcodeSettings = base.gcodeSettings.copy(timelapseType = "traditional"),
            ),
        )
        val smooth = OnDeviceSlicer.slice(
            objects,
            base.copy(
                gcodeSettings = base.gcodeSettings.copy(timelapseType = "smooth"),
            ),
        )
        val traditionalGcode = traditional.output.readText()
        val smoothGcode = smooth.output.readText()
        val traditionalPreview = loadGcodePreview(
            traditional.output.absolutePath,
            0,
            Int.MAX_VALUE,
        )
        val smoothPreview = loadGcodePreview(smooth.output.absolutePath, 0, Int.MAX_VALUE)
        val traditionalTowerBlocks = traditionalGcode.lineSequence().count {
            it == "; WIPE_TOWER_START"
        }
        val smoothTowerBlocks = smoothGcode.lineSequence().count { it == "; WIPE_TOWER_START" }
        val traditionalTowerExtrusion = wipeTowerExtrusion(traditionalGcode)
        val smoothTowerExtrusion = wipeTowerExtrusion(smoothGcode)

        assertTrue(traditionalGcode.contains("; timelapse_type = 0"))
        assertTrue(smoothGcode.contains("; timelapse_type = 1"))
        assertFalse(traditionalGcode.contains("; WIPE_TOWER_START"))
        assertTrue(smoothGcode.contains("; WIPE_TOWER_START"))
        assertTrue(
            "Traditional P1P layer-change template must execute",
            traditionalGcode.contains("M971 S11 C10 O0"),
        )
        assertTrue(
            "Smooth P1P layer-change template must execute",
            smoothGcode.contains("M971 S11 C11 O0"),
        )
        assertTrue(
            "Smooth timelapse must schedule additional real wipe-tower work; " +
                "blocks=$traditionalTowerBlocks/$smoothTowerBlocks, " +
                "extrusion=$traditionalTowerExtrusion/$smoothTowerExtrusion, " +
                "segments=${traditionalPreview.segments.size}/${smoothPreview.segments.size}",
            smoothTowerBlocks > traditionalTowerBlocks ||
                smoothTowerExtrusion > traditionalTowerExtrusion ||
                !traditionalPreview.segments.contentEquals(smoothPreview.segments),
        )
    }

    @Test
    fun firstLayerInspectionChangesTheBundledMachineOutput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val printer = OrcaProfileCatalog(context).load().printers.single {
            it.name == "Bambu Lab X1 0.4 nozzle"
        }
        val model = inspectModel(fixtureModel().absolutePath)
        val objects = listOf(ProjectObject(id = "inspection-object", model = model))
        val base = SliceOptions()
            .selectPrinter(printer)
            .selectFilament(FilamentProfile.PLA)
            .selectQuality(QualityProfile.DRAFT)

        assertTrue("The bundled X1 profile must retain inspection", printer.scanFirstLayer)
        assertTrue(base.toNativeConfig().scanFirstLayer)

        val enabled = OnDeviceSlicer.slice(objects, base).output.readText()
        val disabled = OnDeviceSlicer.slice(
            objects,
            base.copy(printerProfile = printer.copy(scanFirstLayer = false)),
        ).output.readText()

        assertTrue(enabled.contains("; scan_first_layer = 1"))
        assertTrue(disabled.contains("; scan_first_layer = 0"))
        val scanRegistration = "M977 S1 P60"
        val secondLayerScan = "M976 S1 P1 ; scan model before printing 2nd layer"
        assertTrue(enabled.lineSequence().any { it.trim() == scanRegistration })
        assertFalse(disabled.lineSequence().any { it.trim() == scanRegistration })
        assertTrue(enabled.lineSequence().any { it.trim() == secondLayerScan })
        assertFalse(disabled.lineSequence().any { it.trim() == secondLayerScan })
    }

    @Test
    fun adaptiveBedMeshSettingsReachBundledKlipperStartGcode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val printer = OrcaProfileCatalog(context).load().printers.single {
            it.name == "WonderMaker ZR 0.4 nozzle"
        }.copy(
            bedMeshMinX = 145f,
            bedMeshMinY = 145f,
            bedMeshMaxX = 155f,
            bedMeshMaxY = 155f,
            bedMeshProbeDistanceX = 4f,
            bedMeshProbeDistanceY = 4f,
            adaptiveBedMeshMargin = 0f,
        )
        val outcome = OnDeviceSlicer.slice(
            fixtureModel(),
            SliceOptions()
                .selectPrinter(printer)
                .selectFilament(FilamentProfile.PLA)
                .selectQuality(QualityProfile.DRAFT),
        )
        try {
            val start = outcome.output.useLines { lines ->
                lines.first { it.startsWith("START_PRINT ") }
            }
            val values = start.split(' ')
                .mapNotNull { token ->
                    val separator = token.indexOf('=')
                    if (separator <= 0) null else token.substring(0, separator) to token.substring(separator + 1)
                }
                .toMap()

            assertEquals(145f, values.getValue("MESH_MIN_X").toFloat(), 0.001f)
            assertEquals(145f, values.getValue("MESH_MIN_Y").toFloat(), 0.001f)
            assertEquals(155f, values.getValue("MESH_MAX_X").toFloat(), 0.001f)
            assertEquals(155f, values.getValue("MESH_MAX_Y").toFloat(), 0.001f)
            assertEquals(4, values.getValue("PROBE_COUNT_X").toInt())
            assertEquals(4, values.getValue("PROBE_COUNT_Y").toInt())
            assertFalse("Native placeholders must be fully resolved", start.contains('{'))
        } finally {
            outcome.output.delete()
        }
    }

    @Test
    fun automaticTreeSupportRetainsItsModeAndCreatesSupportToolpaths() {
        val model = inspectModel(supportPaintOverhangModel().absolutePath)
        val options = SliceOptions()
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                supportEnabled = true,
                supportType = "tree(auto)",
                treeSupportBranchAngle = 47f,
                treeSupportBranchDistance = 6.2f,
                treeSupportBranchDiameter = 2.4f,
                treeSupportWallCount = 2,
                treeSupportTipDiameter = 1.3f,
                treeSupportPreferredBranchAngle = 31f,
                treeSupportBranchDensity = 37f,
                treeSupportOrganicBranchAngle = 45f,
                treeSupportOrganicBranchDistance = 2.2f,
                treeSupportOrganicBranchDiameter = 3.1f,
                treeSupportBranchDiameterAngle = 10f,
                treeSupportAdaptiveLayerHeight = false,
                treeSupportAutoBrim = false,
                treeSupportBrimWidth = 4.6f,
            )

        val outcome = OnDeviceSlicer.slice(listOf(ProjectObject("tree-auto", model)), options)
        val preview = loadGcodePreview(outcome.output.absolutePath, 0, Int.MAX_VALUE)
        val gcode = outcome.output.readText()
        val baseline = OnDeviceSlicer.slice(
            listOf(ProjectObject("tree-auto-baseline", model)),
            options.copy(
                treeSupportOrganicBranchAngle = 40f,
                treeSupportOrganicBranchDistance = 1f,
                treeSupportOrganicBranchDiameter = 2f,
                treeSupportBranchDiameterAngle = 5f,
            ),
        )
        val baselinePreview = loadGcodePreview(baseline.output.absolutePath, 0, Int.MAX_VALUE)

        assertTrue("Automatic tree support must generate support paths", preview.roleSegmentCounts[5] > 0)
        assertTrue("Organic baseline must generate support paths", baselinePreview.roleSegmentCounts[5] > 0)
        assertFalse(
            "Organic geometry controls must change generated toolpaths, not only G-code metadata",
            baselinePreview.segments.contentEquals(preview.segments),
        )
        assertTrue(gcode.contains("; support_type = tree(auto)"))
        assertTrue(gcode.contains("; tree_support_branch_angle = 47"))
        assertTrue(gcode.contains("; tree_support_branch_distance = 6.2"))
        assertTrue(gcode.contains("; tree_support_branch_diameter = 2.4"))
        assertTrue(gcode.contains("; tree_support_wall_count = 2"))
        assertTrue(gcode.contains("; tree_support_tip_diameter = 1.3"))
        assertTrue(gcode.contains("; tree_support_angle_slow = 31"))
        assertTrue(gcode.contains("; tree_support_top_rate = 37%"))
        assertTrue(gcode.contains("; tree_support_branch_angle_organic = 45"))
        assertTrue(gcode.contains("; tree_support_branch_distance_organic = 2.2"))
        assertTrue(gcode.contains("; tree_support_branch_diameter_organic = 3.1"))
        assertTrue(gcode.contains("; tree_support_branch_diameter_angle = 10"))
        assertTrue(gcode.contains("; tree_support_adaptive_layer_height = 0"))
        assertTrue(gcode.contains("; tree_support_auto_brim = 0"))
        assertTrue(gcode.contains("; tree_support_brim_width = 4.6"))
    }

    @Test
    fun supportFilamentRoutingAndPrimeTowerReachOrca() {
        val model = inspectModel(fixtureModel().absolutePath)
        val primary = FilamentProfile.PLA.copy(
            towerInterfacePreExtrusionDistance = 11f,
            towerInterfacePreExtrusionLength = 12f,
            towerIroningArea = 13f,
            towerInterfacePurgeLength = 14f,
            towerInterfacePrintTemperature = 231,
        )
        val secondary = FilamentProfile.PETG.copy(
            towerInterfacePreExtrusionDistance = 21f,
            towerInterfacePreExtrusionLength = 22f,
            towerIroningArea = 23f,
            towerInterfacePurgeLength = 24f,
            towerInterfacePrintTemperature = 241,
        )
        val options = SliceOptions()
            .selectPrinter(PrinterProfile.U1_04)
            .selectFilament(primary)
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                filamentSlots = listOf(primary, secondary),
                supportEnabled = true,
                supportFilament = 1,
                supportInterfaceFilament = 2,
                featureFilaments = FeatureFilamentSettings(
                    infillOverrideEnabled = true,
                    baseFirstLayers = 3,
                    baseLastLayers = 4,
                    sparseInfillFilament = 2,
                    wallFilament = 1,
                    solidInfillFilament = 2,
                    wipeTowerFilament = 1,
                ),
                wipeTowerEnabled = true,
                wipeTowerWidth = 42f,
                multiMaterial = MultiMaterialSettings(
                    primeVolume = 61.5f,
                    purgeVolumes = listOf(0f, 65f, 175f, 0f),
                    primeTowerBrimWidth = 4.5f,
                    primeTowerFramework = true,
                    primeTowerSkipPoints = false,
                    primeTowerFlatIroning = true,
                    primeTowerInterfaceFeatures = true,
                    primeTowerInterfaceCooldown = true,
                    primeTowerInfillGap = 175f,
                    wipeTowerNoSparseLayers = true,
                    wipeTowerRotationAngle = 73f,
                    wipeTowerBridging = 12.5f,
                    wipeTowerExtraSpacing = 145f,
                    wipeTowerExtraFlow = 118f,
                    wipeTowerMaxPurgeSpeed = 137f,
                    wipeTowerWallType = "rib",
                    wipeTowerConeAngle = 42f,
                    wipeTowerExtraRibLength = 9.5f,
                    wipeTowerRibWidth = 11f,
                    wipeTowerFilletWall = false,
                    singleExtruderMultiMaterialPriming = true,
                    flushIntoInfill = true,
                    flushIntoSupport = false,
                    flushIntoObjects = false,
                    oozePrevention = true,
                    standbyTemperatureDelta = -35,
                    preheatTime = 94.5f,
                    preheatDeltaTemperature = -18,
                    preheatSteps = 7,
                    interfaceShells = true,
                    interlockingBeam = true,
                    interlockingBeamWidth = 1.25f,
                    interlockingOrientation = 67.5f,
                    interlockingBeamLayerCount = 3,
                    interlockingDepth = 4,
                    interlockingBoundaryAvoidance = 1,
                ),
            )
        val outcome = OnDeviceSlicer.slice(
            listOf(
                ProjectObject(
                    id = "prime-primary",
                    model = model,
                    transform = ModelTransform(offsetXmm = -20f),
                    filamentSlot = 0,
                ),
                ProjectObject(
                    id = "prime-secondary",
                    model = model,
                    transform = ModelTransform(offsetXmm = 20f),
                    filamentSlot = 1,
                ),
            ),
            options,
        )

        val gcode = outcome.output.readText()
        assertTrue("Support base filament must reach Orca", gcode.contains("; support_filament = 1"))
        assertTrue(
            "Support interface filament must reach Orca",
            gcode.contains("; support_interface_filament = 2"),
        )
        assertTrue(gcode.contains("; enable_infill_filament_override = 1"))
        assertTrue(gcode.contains("; infill_filament_use_base_first_layers = 3"))
        assertTrue(gcode.contains("; infill_filament_use_base_last_layers = 4"))
        assertTrue(gcode.contains("; sparse_infill_filament = 2"))
        assertTrue(gcode.contains("; wall_filament = 1"))
        assertTrue(gcode.contains("; solid_infill_filament = 2"))
        assertTrue(gcode.contains("; wipe_tower_filament = 1"))
        assertTrue("Prime tower must remain enabled for a two-tool plate", gcode.contains("; enable_prime_tower = 1"))
        assertTrue("Prime-tower width must reach Orca", gcode.contains("; prime_tower_width = 42"))
        assertTrue("Prime volume must reach Orca", gcode.contains("; prime_volume = 61.5"))
        assertTrue(
            "Directed purge volumes must reach Orca",
            gcode.contains("; flush_volumes_matrix = 0,65,175,0"),
        )
        assertTrue(
            "Independent tool changers must not be classified as SEMM",
            gcode.contains("; single_extruder_multi_material = 0"),
        )
        assertTrue(
            "Independent tool changers must not consume the SEMM purge matrix",
            gcode.contains("; purge_in_prime_tower = 0"),
        )
        assertTrue("Tower brim width must reach Orca", gcode.contains("; prime_tower_brim_width = 4.5"))
        assertTrue(gcode.contains("; prime_tower_enable_framework = 1"))
        assertTrue(gcode.contains("; prime_tower_skip_points = 0"))
        assertTrue(gcode.contains("; prime_tower_flat_ironing = 1"))
        assertTrue(gcode.contains("; enable_tower_interface_features = 1"))
        assertTrue(gcode.contains("; enable_tower_interface_cooldown_during_tower = 1"))
        assertTrue(gcode.contains("; prime_tower_infill_gap = 175%"))
        assertTrue(gcode.contains("; filament_tower_interface_pre_extrusion_dist = 11,21"))
        assertTrue(gcode.contains("; filament_tower_interface_pre_extrusion_length = 12,22"))
        assertTrue(gcode.contains("; filament_tower_ironing_area = 13,23"))
        assertTrue(gcode.contains("; filament_tower_interface_purge_volume = 14,24"))
        assertTrue(gcode.contains("; filament_tower_interface_print_temp = 231,241"))
        assertTrue("Sparse tower layers must remain disabled", gcode.contains("; wipe_tower_no_sparse_layers = 1"))
        assertTrue("Tower rotation must reach Orca", gcode.contains("; wipe_tower_rotation_angle = 73"))
        assertTrue("Tower bridging must reach Orca", gcode.contains("; wipe_tower_bridging = 12.5"))
        assertTrue("Tower spacing must reach Orca", gcode.contains("; wipe_tower_extra_spacing = 145%"))
        assertTrue("Tower flow must reach Orca", gcode.contains("; wipe_tower_extra_flow = 118%"))
        assertTrue("Tower purge speed must reach Orca", gcode.contains("; wipe_tower_max_purge_speed = 137"))
        assertTrue("Tower wall type must reach Orca", gcode.contains("; wipe_tower_wall_type = rib"))
        assertTrue("Tower cone angle must reach Orca", gcode.contains("; wipe_tower_cone_angle = 42"))
        assertTrue("Tower rib length must reach Orca", gcode.contains("; wipe_tower_extra_rib_length = 9.5"))
        assertTrue("Tower rib width must reach Orca", gcode.contains("; wipe_tower_rib_width = 11"))
        assertTrue("Tower rib fillet must reach Orca", gcode.contains("; wipe_tower_fillet_wall = 0"))
        assertTrue(
            "All-extruder priming must reach Orca",
            gcode.contains("; single_extruder_multi_material_priming = 1"),
        )
        assertTrue("Infill flushing must reach Orca", gcode.contains("; flush_into_infill = 1"))
        assertTrue("Support flushing must reach Orca", gcode.contains("; flush_into_support = 0"))
        assertTrue("Object flushing must remain disabled", gcode.contains("; flush_into_objects = 0"))
        assertTrue("Ooze prevention must reach Orca", gcode.contains("; ooze_prevention = 1"))
        assertTrue("Standby temperature delta must reach Orca", gcode.contains("; standby_temperature_delta = -35"))
        assertTrue("Preheat time must reach Orca", gcode.contains("; preheat_time = 94.5"))
        assertTrue("Preheat temperature adjustment must reach Orca", gcode.contains("; delta_temperature = -18"))
        assertTrue("Preheat steps must reach Orca", gcode.contains("; preheat_steps = 7"))
        assertTrue(
            "The next tool must receive a real early preheat command",
            gcode.lineSequence().any { it.startsWith("M104") && it.contains("preheat T1 time:") },
        )
        assertTrue("Interface shells must reach Orca", gcode.contains("; interface_shells = 1"))
        assertTrue("Interlocking must reach Orca", gcode.contains("; interlocking_beam = 1"))
        assertTrue("Interlocking width must reach Orca", gcode.contains("; interlocking_beam_width = 1.25"))
        assertTrue("Interlocking direction must reach Orca", gcode.contains("; interlocking_orientation = 67.5"))
        assertTrue("Interlocking layers must reach Orca", gcode.contains("; interlocking_beam_layer_count = 3"))
        assertTrue("Interlocking depth must reach Orca", gcode.contains("; interlocking_depth = 4"))
        assertTrue(
            "Interlocking boundary clearance must reach Orca",
            gcode.contains("; interlocking_boundary_avoidance = 1"),
        )
        assertTrue("The second object must produce a real tool change", gcode.lineSequence().any { it == "T1" })

        val featureOnlyOutcome = OnDeviceSlicer.slice(
            listOf(
                ProjectObject(
                    id = "feature-routing",
                    model = model,
                    transform = ModelTransform(),
                    filamentSlot = 0,
                ),
            ),
            options.copy(
                supportEnabled = false,
                wipeTowerEnabled = false,
                featureFilaments = options.featureFilaments.copy(
                    baseFirstLayers = 0,
                    baseLastLayers = 0,
                ),
            ),
        )
        assertTrue(
            "A one-object plate must switch to the selected infill filament",
            featureOnlyOutcome.output.readLines().any { it == "T1" },
        )

        val supportModel = inspectModel(supportPaintOverhangModel().absolutePath)
        val dualSupportOutcome = OnDeviceSlicer.slice(
            listOf(ProjectObject(id = "dual-support", model = supportModel)),
            options.copy(
                supportEnabled = true,
                supportType = "normal(auto)",
                supportFilament = 1,
                supportInterfaceFilament = 2,
                supportInterfaceTopLayers = 3,
                supportInterfaceBottomLayers = 0,
                featureFilaments = FeatureFilamentSettings(),
                wipeTowerEnabled = false,
            ),
        )
        val dualSupportPreview = loadGcodePreview(
            dualSupportOutcome.output.absolutePath,
            0,
            Int.MAX_VALUE,
        )
        val dualSupportGcode = dualSupportOutcome.output.readText()
        assertTrue(
            "The overhang fixture must create real support paths",
            dualSupportPreview.roleSegmentCounts[5] > 0,
        )
        assertTrue(
            "A single-model dual-support slice must switch to the interface filament",
            dualSupportGcode.lineSequence().any { it == "T1" },
        )

        val objectFlushOutcome = OnDeviceSlicer.slice(
            listOf(
                ProjectObject(
                    id = "object-flush-primary",
                    model = model,
                    transform = ModelTransform(offsetXmm = -20f),
                    filamentSlot = 0,
                ),
                ProjectObject(
                    id = "object-flush-secondary",
                    model = model,
                    transform = ModelTransform(offsetXmm = 20f),
                    filamentSlot = 1,
                ),
            ),
            options.copy(
                multiMaterial = options.multiMaterial.copy(flushIntoObjects = true),
            ),
        )
        assertTrue(
            "Object flushing must reach Orca when explicitly enabled",
            objectFlushOutcome.output.readText().contains("; flush_into_objects = 1"),
        )

        val defaultsOutcome = OnDeviceSlicer.slice(
            listOf(
                ProjectObject(
                    id = "defaults-primary",
                    model = model,
                    transform = ModelTransform(offsetXmm = -20f),
                    filamentSlot = 0,
                ),
                ProjectObject(
                    id = "defaults-secondary",
                    model = model,
                    transform = ModelTransform(offsetXmm = 20f),
                    filamentSlot = 1,
                ),
            ),
            options.copy(
                wipeTowerEnabled = false,
                multiMaterial = MultiMaterialSettings(),
            ),
        )
        val defaultsGcode = defaultsOutcome.output.readText()
        assertTrue(defaultsGcode.contains("; prime_tower_enable_framework = 0"))
        assertTrue(defaultsGcode.contains("; prime_tower_skip_points = 1"))
        assertTrue(defaultsGcode.contains("; prime_tower_flat_ironing = 0"))
        assertTrue(defaultsGcode.contains("; enable_tower_interface_features = 0"))
        assertTrue(defaultsGcode.contains("; enable_tower_interface_cooldown_during_tower = 0"))
        assertTrue(defaultsGcode.contains("; prime_tower_infill_gap = 150%"))
        assertTrue("Tower rotation must default to zero", defaultsGcode.contains("; wipe_tower_rotation_angle = 0"))
        assertTrue("Tower bridging must retain its default", defaultsGcode.contains("; wipe_tower_bridging = 10"))
        assertTrue("Tower spacing must retain its default", defaultsGcode.contains("; wipe_tower_extra_spacing = 100%"))
        assertTrue("Tower flow must retain its default", defaultsGcode.contains("; wipe_tower_extra_flow = 100%"))
        assertTrue("Tower purge speed must retain its default", defaultsGcode.contains("; wipe_tower_max_purge_speed = 90"))
        assertTrue("Tower wall type must default to rectangle", defaultsGcode.contains("; wipe_tower_wall_type = rectangle"))
        assertTrue("Tower cone angle must retain its default", defaultsGcode.contains("; wipe_tower_cone_angle = 30"))
        assertTrue("Tower rib length must default to zero", defaultsGcode.contains("; wipe_tower_extra_rib_length = 0"))
        assertTrue("Tower rib width must retain its default", defaultsGcode.contains("; wipe_tower_rib_width = 8"))
        assertTrue("Tower rib fillet must default on", defaultsGcode.contains("; wipe_tower_fillet_wall = 1"))
        assertTrue(
            "All-extruder priming must default off",
            defaultsGcode.contains("; single_extruder_multi_material_priming = 0"),
        )
        assertTrue("Infill flushing must default off", defaultsGcode.contains("; flush_into_infill = 0"))
        assertTrue("Support flushing must default on", defaultsGcode.contains("; flush_into_support = 1"))
        assertTrue("Object flushing must default off", defaultsGcode.contains("; flush_into_objects = 0"))
        assertTrue("Ooze prevention must not be forced on", defaultsGcode.contains("; ooze_prevention = 0"))
        assertTrue("The inherited standby delta must remain intact", defaultsGcode.contains("; standby_temperature_delta = -5"))
        assertTrue("Preheat time must retain Orca's default", defaultsGcode.contains("; preheat_time = 30"))
        assertTrue("Preheat temperature adjustment must default to zero", defaultsGcode.contains("; delta_temperature = 0"))
        assertTrue("Preheat steps must retain Orca's default", defaultsGcode.contains("; preheat_steps = 1"))
        assertTrue("Interface shells must not be forced on", defaultsGcode.contains("; interface_shells = 0"))
        assertTrue("Interlocking must default off", defaultsGcode.contains("; interlocking_beam = 0"))
        assertTrue("Interlocking width must retain Orca's default", defaultsGcode.contains("; interlocking_beam_width = 0.8"))
        assertTrue("Interlocking direction must retain Orca's default", defaultsGcode.contains("; interlocking_orientation = 22.5"))
        assertTrue("Interlocking layers must retain Orca's default", defaultsGcode.contains("; interlocking_beam_layer_count = 2"))
        assertTrue("Interlocking depth must retain Orca's default", defaultsGcode.contains("; interlocking_depth = 2"))
        assertTrue(
            "Interlocking boundary clearance must retain Orca's default",
            defaultsGcode.contains("; interlocking_boundary_avoidance = 2"),
        )
    }

    @Test
    fun directedPurgeVolumeChangesRealWipeTowerExtrusion() {
        val model = inspectModel(fixtureModel().absolutePath)
        val objects = listOf(
            ProjectObject(
                id = "purge-primary",
                model = model,
                transform = ModelTransform(offsetXmm = -20f),
                filamentSlot = 0,
            ),
            ProjectObject(
                id = "purge-secondary",
                model = model,
                transform = ModelTransform(offsetXmm = 20f),
                filamentSlot = 1,
            ),
        )
        val base = SliceOptions()
            .selectPrinter(
                PrinterProfile.CUSTOM_CARTESIAN.copy(
                    id = "test-semm-04",
                    name = "Test SEMM · 0.4 mm",
                    singleExtruderMultiMaterial = true,
                    extruderCount = 2,
                ),
            )
            .selectFilament(FilamentProfile.PLA)
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                filamentSlots = listOf(FilamentProfile.PLA, FilamentProfile.PETG),
                wipeTowerEnabled = true,
                multiMaterial = MultiMaterialSettings(
                    purgeVolumes = listOf(0f, 20f, 75f, 0f),
                ),
            )

        val low = OnDeviceSlicer.slice(objects, base)
        val high = OnDeviceSlicer.slice(
            objects,
            base.copy(
                multiMaterial = base.multiMaterial.withPurgeVolume(2, 0, 1, 260f),
            ),
        )
        try {
            val lowGcode = low.output.readText()
            val highGcode = high.output.readText()
            assertTrue(lowGcode.contains("; flush_volumes_matrix = 0,20,75,0"))
            assertTrue(highGcode.contains("; flush_volumes_matrix = 0,260,75,0"))
            assertTrue(lowGcode.contains("; single_extruder_multi_material = 1"))
            assertTrue(lowGcode.contains("; purge_in_prime_tower = 1"))
            assertTrue(lowGcode.lineSequence().any { it == "T1" })
            assertTrue(highGcode.lineSequence().any { it == "T1" })
            assertTrue(
                "A larger T1 purge must produce more real wipe-tower extrusion",
                wipeTowerExtrusion(highGcode) > wipeTowerExtrusion(lowGcode) + 50f,
            )
        } finally {
            low.output.delete()
            high.output.delete()
        }
    }

    @Test
    fun nozzleVolumeChangesRealPerFilamentMaterialAccounting() {
        val model = inspectModel(fixtureModel().absolutePath)
        val objects = listOf(
            ProjectObject(
                id = "nozzle-volume-primary",
                model = model,
                transform = ModelTransform(offsetXmm = -20f),
                filamentSlot = 0,
            ),
            ProjectObject(
                id = "nozzle-volume-secondary",
                model = model,
                transform = ModelTransform(offsetXmm = 20f),
                filamentSlot = 1,
            ),
        )
        val base = SliceOptions()
            .selectPrinter(
                PrinterProfile.CUSTOM_CARTESIAN.copy(
                    id = "test-nozzle-volume-semm",
                    name = "Nozzle volume SEMM",
                    singleExtruderMultiMaterial = true,
                    extruderCount = 2,
                    nozzleVolume = 0f,
                    changeFilamentGcode = """
                        ; FLUSH_START
                        T[next_extruder]
                        M83
                        G1 E100 F300
                        ; FLUSH_END
                    """.trimIndent(),
                    machineEndGcode = """
                        ; Force a deterministic asymmetric pair of final tool changes so
                        ; nozzle-volume attribution cannot cancel across alternating layers.
                        T1
                        M83
                        ; FLUSH_START
                        T0
                        G1 E40 F300
                        ; FLUSH_END
                        ; FLUSH_START
                        T1
                        G1 E100 F300
                        ; FLUSH_END
                    """.trimIndent(),
                ),
            )
            .selectFilament(FilamentProfile.PLA)
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                filamentSlots = listOf(
                    FilamentProfile.PLA,
                    FilamentProfile.PETG.copy(density = 2.00f),
                ),
                wipeTowerEnabled = true,
                multiMaterial = MultiMaterialSettings(
                    purgeVolumes = listOf(0f, 260f, 75f, 0f),
                ),
            )

        val empty = OnDeviceSlicer.slice(objects, base)
        val retained = OnDeviceSlicer.slice(
            objects,
            base.copy(
                printerProfile = base.printerProfile.copy(nozzleVolume = 183f),
            ),
        )
        try {
            val emptyGcode = empty.output.readText()
            val retainedGcode = retained.output.readText()
            val emptyPerFilament = filamentUsedMm(emptyGcode)
            val retainedPerFilament = filamentUsedMm(retainedGcode)

            assertTrue(emptyGcode.contains("; nozzle_volume = 0"))
            assertTrue(retainedGcode.contains("; nozzle_volume = 183"))
            assertTrue(retainedGcode.lineSequence().any { it == "T1" })
            assertEquals(2, emptyPerFilament.size)
            assertEquals(2, retainedPerFilament.size)
            assertTrue(
                "Nozzle volume must change density-weighted material accounting: " +
                    "empty=${empty.filamentGrams} retained=${retained.filamentGrams}",
                abs(empty.filamentGrams - retained.filamentGrams) > 0.001f,
            )
            assertTrue(
                "Changing attribution must not invent or remove total filament length",
                abs(empty.filamentMm - retained.filamentMm) < 0.2f,
            )
            assertTrue(
                "Nozzle volume is accounting metadata and must not change wipe-tower geometry",
                abs(wipeTowerExtrusion(emptyGcode) - wipeTowerExtrusion(retainedGcode)) < 0.01f,
            )
        } finally {
            empty.output.delete()
            retained.output.delete()
        }
    }

    @Test
    fun towerInterfaceSettingsChangeRealWipeTowerGcode() {
        val model = inspectModel(fixtureModel().absolutePath)
        val objects = listOf(
            ProjectObject(
                id = "tower-interface-primary",
                model = model,
                transform = ModelTransform(offsetXmm = -20f),
                filamentSlot = 0,
            ),
            ProjectObject(
                id = "tower-interface-secondary",
                model = model,
                transform = ModelTransform(offsetXmm = 20f),
                filamentSlot = 1,
            ),
        )
        val primary = FilamentProfile.PLA.copy(
            towerInterfacePreExtrusionDistance = 8f,
            towerInterfacePreExtrusionLength = 15f,
            towerIroningArea = 9f,
            towerInterfacePurgeLength = 100f,
            towerInterfacePrintTemperature = 260,
        )
        val secondary = FilamentProfile.PETG.copy(
            towerInterfacePreExtrusionDistance = 8f,
            towerInterfacePreExtrusionLength = 15f,
            towerIroningArea = 9f,
            towerInterfacePurgeLength = 100f,
            towerInterfacePrintTemperature = 260,
        )
        val base = SliceOptions()
            .selectPrinter(
                PrinterProfile.CUSTOM_CARTESIAN.copy(
                    id = "test-tower-interface-semm-04",
                    name = "Test tower interface SEMM · 0.4 mm",
                    singleExtruderMultiMaterial = true,
                    extruderCount = 2,
                ),
            )
            .selectFilament(primary)
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                filamentSlots = listOf(primary, secondary),
                wipeTowerEnabled = true,
                multiMaterial = MultiMaterialSettings(
                    purgeVolumes = listOf(0f, 20f, 75f, 0f),
                ),
            )
        val enabled = base.copy(
            multiMaterial = base.multiMaterial.copy(
                primeTowerFlatIroning = true,
                primeTowerInterfaceFeatures = true,
                primeTowerInterfaceCooldown = true,
            ),
        )

        val baseline = OnDeviceSlicer.slice(objects, base)
        val enhanced = OnDeviceSlicer.slice(objects, enabled)
        try {
            val baselineGcode = baseline.output.readText()
            val enhancedGcode = enhanced.output.readText()
            assertTrue(enhancedGcode.contains("; enable_tower_interface_features = 1"))
            assertTrue(enhancedGcode.contains("; enable_tower_interface_cooldown_during_tower = 1"))
            assertTrue(enhancedGcode.contains("; filament_tower_interface_purge_volume = 100,100"))
            assertTrue(
                "Interface temperature must reach emitted tower G-code",
                enhancedGcode.lineSequence().any { it.startsWith("M109 S260") },
            )
            assertTrue(
                "Interface purge and pre-extrusion must add real wipe-tower extrusion",
                wipeTowerExtrusion(enhancedGcode) > wipeTowerExtrusion(baselineGcode) + 50f,
            )
        } finally {
            baseline.output.delete()
            enhanced.output.delete()
        }
    }

    @Test
    fun filamentPurgeFloorAndAuxiliaryCoolingReachRealGcode() {
        val model = inspectModel(fixtureModel().absolutePath)
        val objects = listOf(
            ProjectObject(
                id = "purge-floor-primary",
                model = model,
                transform = ModelTransform(offsetXmm = -20f),
                filamentSlot = 0,
            ),
            ProjectObject(
                id = "purge-floor-secondary",
                model = model,
                transform = ModelTransform(offsetXmm = 20f),
                filamentSlot = 1,
            ),
        )
        val lowPrimary = FilamentProfile.PLA.copy(
            minimalPurgeOnWipeTower = 5f,
            additionalCoolingFanSpeed = 70,
            closeFanFirstLayers = 0,
        )
        val lowSecondary = FilamentProfile.PETG.copy(
            minimalPurgeOnWipeTower = 5f,
            additionalCoolingFanSpeed = 70,
            closeFanFirstLayers = 0,
        )
        val base = SliceOptions()
            .selectPrinter(
                PrinterProfile.CUSTOM_CARTESIAN.copy(
                    id = "test-purge-floor-semm-04",
                    name = "Test purge floor SEMM · 0.4 mm",
                    singleExtruderMultiMaterial = true,
                    extruderCount = 2,
                    auxiliaryFan = true,
                ),
            )
            .selectFilament(lowPrimary)
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                filamentSlots = listOf(lowPrimary, lowSecondary),
                wipeTowerEnabled = true,
                multiMaterial = MultiMaterialSettings(
                    purgeVolumes = listOf(0f, 0f, 0f, 0f),
                ),
            )
        val highPrimary = lowPrimary.copy(minimalPurgeOnWipeTower = 80f)
        val highSecondary = lowSecondary.copy(minimalPurgeOnWipeTower = 80f)
        val high = base.selectFilament(highPrimary).copy(
            filamentSlots = listOf(highPrimary, highSecondary),
        )

        val lowResult = OnDeviceSlicer.slice(objects, base)
        val highResult = OnDeviceSlicer.slice(objects, high)
        try {
            val lowGcode = lowResult.output.readText()
            val highGcode = highResult.output.readText()
            assertTrue(lowGcode.contains("; filament_minimal_purge_on_wipe_tower = 5,5"))
            assertTrue(highGcode.contains("; filament_minimal_purge_on_wipe_tower = 80,80"))
            assertTrue(lowGcode.contains("; additional_cooling_fan_speed = 70,70"))
            assertTrue(lowGcode.contains("; auxiliary_fan = 1"))
            assertTrue(
                "Auxiliary cooling must emit the printer's P2 fan command",
                lowGcode.lineSequence().any { it.startsWith("M106 P2 S178") },
            )
            assertTrue(
                "A larger target-material purge floor must increase real wipe-tower extrusion",
                wipeTowerExtrusion(highGcode) > wipeTowerExtrusion(lowGcode) + 50f,
            )
        } finally {
            lowResult.output.delete()
            highResult.output.delete()
        }
    }

    @Test
    fun filamentCoolingSemanticsReachOrcasRealCoolingPath() {
        val baseFilament = FilamentProfile.PLA.copy(
            fanMinSpeed = 15,
            fanMaxSpeed = 100,
            fanCoolingLayerTime = 120f,
            slowDownForLayerCooling = false,
            keepFanAlwaysOn = true,
            dontSlowDownOuterWall = true,
            enableOverhangBridgeFan = true,
            overhangFanSpeed = 90,
            overhangFanThreshold = "25%",
            internalBridgeFanSpeed = 45,
            supportInterfaceFanSpeed = 85,
            ironingFanSpeed = 37,
            slowDownLayerTime = 120f,
            slowDownMinSpeed = 5f,
            closeFanFirstLayers = 0,
        )
        val disabledOptions = SliceOptions()
            .selectFilament(baseFilament)
            .selectQuality(
                QualityProfile.DRAFT.copy(
                    ironing = IroningSettings(type = "top"),
                ),
            )
        val enabledOptions = disabledOptions.selectFilament(
            baseFilament.copy(slowDownForLayerCooling = true),
        )

        val disabled = OnDeviceSlicer.slice(fixtureModel(), disabledOptions)
        val enabled = OnDeviceSlicer.slice(fixtureModel(), enabledOptions)
        try {
            val gcode = disabled.output.readText()
            assertTrue(gcode.contains("; fan_cooling_layer_time = 120"))
            assertTrue(gcode.contains("; slow_down_for_layer_cooling = 0"))
            assertTrue(gcode.contains("; reduce_fan_stop_start_freq = 1"))
            assertTrue(gcode.contains("; dont_slow_down_outer_wall = 1"))
            assertTrue(gcode.contains("; enable_overhang_bridge_fan = 1"))
            assertTrue(gcode.contains("; overhang_fan_threshold = 25%"))
            assertTrue(gcode.contains("; internal_bridge_fan_speed = 45"))
            assertTrue(gcode.contains("; support_material_interface_fan_speed = 85"))
            assertTrue(gcode.contains("; ironing_fan_speed = 37"))
            assertTrue(gcode.contains("; ironing_type = top"))
            assertTrue(
                "Ironing must emit the configured 37% part-cooling command",
                gcode.lineSequence().any { it.startsWith("M106 S94") },
            )
            assertTrue(
                "Layer-cooling slowdown must change Orca's real print-time estimate",
                enabled.estimatedSeconds > disabled.estimatedSeconds + 1f,
            )
        } finally {
            disabled.output.delete()
            enabled.output.delete()
        }
    }

    @Test
    fun solubleAndDedicatedSupportFilamentsReachRealPurging() {
        val model = inspectModel(fixtureModel().absolutePath)
        val objects = listOf(
            ProjectObject(
                id = "material-primary",
                model = model,
                transform = ModelTransform(offsetXmm = -20f),
                filamentSlot = 0,
            ),
            ProjectObject(
                id = "material-secondary",
                model = model,
                transform = ModelTransform(offsetXmm = 20f),
                filamentSlot = 1,
            ),
        )
        val primary = FilamentProfile.PLA.copy(soluble = false, supportMaterial = false)
        val secondary = FilamentProfile.PETG.copy(soluble = false, supportMaterial = false)
        val base = SliceOptions()
            .selectPrinter(
                PrinterProfile.CUSTOM_CARTESIAN.copy(
                    id = "test-material-semm-04",
                    name = "Test material SEMM · 0.4 mm",
                    singleExtruderMultiMaterial = true,
                    extruderCount = 2,
                ),
            )
            .selectFilament(primary)
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                filamentSlots = listOf(primary, secondary),
                wipeTowerEnabled = true,
                multiMaterial = MultiMaterialSettings(
                    purgeVolumes = listOf(0f, 260f, 260f, 0f),
                    singleExtruderMultiMaterialPriming = true,
                    flushIntoInfill = true,
                ),
            )

        val regular = OnDeviceSlicer.slice(objects, base)
        val soluble = OnDeviceSlicer.slice(
            objects,
            base.selectFilament(primary.copy(soluble = true)),
        )
        val support = OnDeviceSlicer.slice(
            objects,
            base.copy(filamentSlots = listOf(primary, secondary.copy(supportMaterial = true))),
        )
        try {
            val regularGcode = regular.output.readText()
            val solubleGcode = soluble.output.readText()
            val supportGcode = support.output.readText()
            assertTrue(regularGcode.contains("; filament_soluble = 0,0"))
            assertTrue(
                "Unexpected soluble header: " + solubleGcode.lineSequence()
                    .firstOrNull { it.contains("filament_soluble") },
                solubleGcode.contains("; filament_soluble = 1,0"),
            )
            assertTrue(regularGcode.contains("; filament_is_support = 0,0"))
            assertTrue(supportGcode.contains("; filament_is_support = 0,1"))
            assertTrue(
                "Soluble material must keep purge out of arbitrary model infill",
                wipeTowerExtrusion(solubleGcode) > wipeTowerExtrusion(regularGcode) + 10f,
            )
            assertTrue(
                "Dedicated support material must keep purge out of arbitrary model infill",
                wipeTowerExtrusion(supportGcode) > wipeTowerExtrusion(regularGcode) + 10f,
            )
        } finally {
            regular.output.delete()
            soluble.output.delete()
            support.output.delete()
        }
    }

    @Test
    fun nativeSlicerWorkerCrashLeavesAppAliveAndRestartsCleanly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val appPid = android.os.Process.myPid()
        val firstOutcome = OnDeviceSlicer.slice(
            fixtureModel(),
            SliceOptions().selectQuality(QualityProfile.DRAFT),
        )
        val firstWorkerPid = SlicerProcessClient.lastWorkerPid()
        assertNotEquals("Orca must run outside the application process", appPid, firstWorkerPid)

        val terminatedWorkerPid = SlicerProcessClient.terminateWorkerForTest(context)

        assertEquals("Application process must survive worker termination", appPid, android.os.Process.myPid())
        assertNotEquals("Only the worker process may be terminated", appPid, terminatedWorkerPid)

        val recoveredOutcome = OnDeviceSlicer.slice(
            fixtureModel(),
            SliceOptions().selectQuality(QualityProfile.DRAFT),
        )
        val restartedWorkerPid = SlicerProcessClient.lastWorkerPid()
        assertTrue("Slicer worker must restart with a new process", restartedWorkerPid > 0)
        assertNotEquals("Restarted worker must not reuse the terminated process", terminatedWorkerPid, restartedWorkerPid)
        assertTrue("Worker restart must produce G-code", recoveredOutcome.output.length() > 1_000L)
        assertTrue("Previous G-code must survive another slice", firstOutcome.output.isFile)
        assertNotEquals(
            "Each slice must retain a distinct G-code artifact",
            firstOutcome.output.canonicalPath,
            recoveredOutcome.output.canonicalPath,
        )
    }

    @Test
    fun activeSliceCancellationKeepsServiceResponsiveAndRestartsCleanly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val appPid = android.os.Process.myPid()
        val started = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val requestId = UUID.randomUUID().toString()
        val probe = Thread {
            runCatching {
                SlicerProcessClient.cancellationProbeForTest(started::countDown, requestId)
            }.onFailure(failure::set)
        }.apply { start() }

        assertTrue("Cancellation probe must start", started.await(10, TimeUnit.SECONDS))
        val busyWorkerPid = SlicerProcessClient.workerHealthForTest(context)
        assertNotEquals("Orca work must not block the service main thread", appPid, busyWorkerPid)

        assertTrue(
            "The exact active request must accept cancellation",
            SlicerProcessClient.cancelRequestForTest(requestId),
        )
        probe.join(10_000)

        assertTrue("Cancellation must promptly release the waiting client", !probe.isAlive)
        assertTrue("Cancellation must have a distinct result", failure.get() is SlicingCancelledException)
        assertEquals("Cancellation must not terminate the app", appPid, android.os.Process.myPid())

        val restartedWorkerPid = SlicerProcessClient.workerHealthForTest(context)
        assertTrue("The isolated worker must restart after cancellation", restartedWorkerPid > 0)
        assertNotEquals("Cancellation must replace the terminated worker", busyWorkerPid, restartedWorkerPid)
        val recovery = OnDeviceSlicer.slice(
            fixtureModel(),
            SliceOptions().selectQuality(QualityProfile.DRAFT),
        )
        assertTrue("A new slice must succeed after cancellation", recovery.output.length() > 1_000L)
    }

    @Test
    fun nativeGcodeWriterHardLimitContainsDiskGrowthAndRecovers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val appPid = android.os.Process.myPid()
        val maximumBytes = 32 * 1_024
        val transientOutputs = listOf(context.filesDir, context.cacheDir).map {
            File(it, SliceArtifactStore.NATIVE_OUTPUT_NAME)
        }
        val limitedWorkerPid = SlicerProcessClient.workerHealthForTest(context)
        assertNotEquals("Orca must run outside the application process", appPid, limitedWorkerPid)

        val failure = runCatching {
            SlicerProcessClient.sliceWithOutputLimitForTest(
                transformedModels = listOf(fixtureModel()),
                options = SliceOptions().selectQuality(QualityProfile.DRAFT),
                maximumGcodeBytes = maximumBytes,
            )
        }

        assertTrue("The native writer must reject a truncated slice", failure.isFailure)
        assertEquals("The output limit must not terminate the app", appPid, android.os.Process.myPid())
        val limitedOutputs = transientOutputs.filter(File::exists)
        limitedOutputs.forEach { output ->
            assertTrue(
                "Native output must stop at the process file-size limit: ${output.length()}",
                output.length() in 1..maximumBytes.toLong(),
            )
        }

        val healthyWorkerPid = SlicerProcessClient.workerHealthForTest(context)
        assertTrue("A worker must be available after the native write failure", healthyWorkerPid > 0)
        assertNotEquals("Orca must remain isolated from the app", appPid, healthyWorkerPid)
        assertNotEquals(
            "The file-size signal must replace the limited worker",
            limitedWorkerPid,
            healthyWorkerPid,
        )

        val recovery = OnDeviceSlicer.slice(
            fixtureModel(),
            SliceOptions().selectQuality(QualityProfile.DRAFT),
        )
        assertTrue(
            "A normal request must restore the production limit and complete",
            recovery.output.length() > maximumBytes,
        )
    }

    @Test
    fun sliceArtifactLeaseProtectsConcurrentReadersAcrossProcesses() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outputRoot = File(context.filesDir, SliceArtifactStore.OUTPUT_DIRECTORY).apply { mkdirs() }
        val probeFiles = ArrayList<File>()
        val leased = outputRoot.resolve("lease-probe-oldest.gcode").apply {
            writeText("G28\n")
            setLastModified(1L)
            probeFiles += this
        }
        repeat(SliceArtifactStore.MAXIMUM_RETAINED_OUTPUTS + 1) { index ->
            probeFiles += outputRoot.resolve("lease-probe-$index.gcode").apply {
                writeText("G1 X$index\n")
                setLastModified(10_000L + index)
            }
        }
        try {
            SliceArtifactLease.acquire(leased).use {
                OnDeviceSlicer.slice(
                    fixtureModel(),
                    SliceOptions().selectQuality(QualityProfile.DRAFT),
                )
                assertTrue("A cross-process reader lease must prevent pruning", leased.isFile)
            }

            OnDeviceSlicer.slice(
                fixtureModel(),
                SliceOptions().selectQuality(QualityProfile.DRAFT),
            )
            assertFalse("The released oldest output must become eligible for pruning", leased.exists())
        } finally {
            probeFiles.forEach(File::delete)
        }
    }

    @Test
    fun imperfectMeshCorpusIsRepairableOrFailsWithoutKillingTheApp() {
        val appPid = android.os.Process.myPid()
        val options = SliceOptions()
            .selectPrinter(PrinterProfile.CUSTOM_CARTESIAN)
            .selectFilament(FilamentProfile.GENERIC_PLA)
            .selectQuality(QualityProfile.DRAFT)
            .copy(perimeters = 2, fillDensity = 0.10f)

        val corpus = meshCorpus()
        try {
            corpus.forEach { entry ->
                val result = runCatching { OnDeviceSlicer.slice(entry.model, options) }

                assertEquals("${entry.name} must not terminate the app process", appPid, android.os.Process.myPid())
                if (entry.mustSlice) {
                    val outcome = result.getOrElse { error ->
                        throw AssertionError("${entry.name} should be repaired and sliced", error)
                    }
                    assertTrue("${entry.name} must produce non-trivial G-code", outcome.output.length() > 1_000L)
                    val gcode = outcome.output.readText()
                    assertTrue("${entry.name} must contain outer-wall extrusion", gcode.contains(";TYPE:Outer wall"))
                    assertTrue("${entry.name} must not emit non-finite coordinates", !NON_FINITE_GCODE.containsMatchIn(gcode))
                } else {
                    assertTrue("${entry.name} must be rejected", result.isFailure)
                }

                val recovery = OnDeviceSlicer.slice(fixtureModel(), options)
                assertEquals("JNI recovery after ${entry.name} must keep the app process", appPid, android.os.Process.myPid())
                assertTrue("A valid model must slice after ${entry.name}", recovery.output.length() > 1_000L)
            }
        } finally {
            corpus.forEach { it.model.delete() }
        }
    }

    @Test
    fun zHopBoundaryControlsRealLiftMoves() {
        val model = twoIslandRetractionModel()
        val basePrinter = PrinterProfile.CUSTOM_CARTESIAN.copy(
            retractLength = 0.8f,
            retractionMinimumTravel = 0f,
            retractWhenChangingLayer = true,
            zHop = 0.6f,
            zHopType = "normal",
            retractLiftAbove = 0f,
            retractLiftBelow = 0f,
            retractLiftEnforce = "all",
            travelSlope = 7f,
            zHopWhenPrime = false,
        )
        val baseOptions = SliceOptions()
            .selectPrinter(basePrinter)
            .selectFilament(FilamentProfile.GENERIC_PLA)
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                fillDensity = 0.15f,
                perimeters = 2,
                gcodeSettings = GcodeSettings(verboseComments = true),
            )

        val enabled = OnDeviceSlicer.slice(model, baseOptions).output.readText()
        val suppressed = OnDeviceSlicer.slice(
            model,
            baseOptions.selectPrinter(basePrinter.copy(retractLiftAbove = 100f)),
        ).output.readText()
        val firmware = OnDeviceSlicer.slice(
            model,
            baseOptions.selectPrinter(basePrinter.copy(useFirmwareRetraction = true)),
        ).output.readText()
        val enabledLifts = enabled.lineSequence().count { it.contains("lift Z") }
        val suppressedLifts = suppressed.lineSequence().count { it.contains("lift Z") }

        assertTrue(
            "The baseline must contain real Z-hop moves; retraction config: " +
                enabled.lineSequence().filter {
                    it.startsWith("; retract_") || it.startsWith("; z_hop") ||
                        it.startsWith("; gcode_comments")
                }.joinToString(" | "),
            enabledLifts > 0,
        )
        assertEquals("A lower boundary above the model must suppress Z-hop moves", 0, suppressedLifts)
        assertTrue(enabled.contains("; retract_lift_above = 0"))
        assertTrue(suppressed.contains("; retract_lift_above = 100"))
        assertTrue(enabled.contains("; retract_lift_enforce = All Surfaces"))
        assertTrue(enabled.contains("; travel_slope = 7"))
        assertTrue(enabled.contains("; z_hop_when_prime = 0"))
        assertTrue(firmware.contains("; use_firmware_retraction = 1"))
        assertTrue("Firmware retraction must emit G10", firmware.lineSequence().any { it.startsWith("G10") })
        assertTrue("Firmware de-retraction must emit G11", firmware.lineSequence().any { it.startsWith("G11") })
    }

    @Test
    fun attachedStlProducesGcodeOnDevice() {
        val model = fixtureModel()
        var highestProgress = 0

        assertTrue("Bundled model fixture must be available", model.isFile)

        val options = SliceOptions()
            .selectPrinter(PrinterProfile.U1_06.copy(
                retractLiftAbove = 0.35f,
                retractLiftBelow = 180f,
                retractLiftEnforce = "top_bottom",
                travelSlope = 7f,
                zHopWhenPrime = false,
            ))
            .selectFilament(FilamentProfile.PETG.copy(
                retractLength = 1.1f,
                retractSpeed = 38f,
                deretractSpeed = 33f,
                retractionMinimumTravel = 2.2f,
                retractWhenChangingLayer = true,
                wipeWhileRetracting = true,
                wipeDistance = 2.4f,
                retractBeforeWipe = 63f,
                retractRestartExtra = 0.09f,
                zHop = 0.6f,
                zHopType = "spiral",
                retractLiftAbove = 0.8f,
                retractLiftBelow = 150f,
                retractLiftEnforce = "top",
                bedTemp = 71,
                firstLayerBedTemp = 72,
                coolPlateTemp = 41,
                firstLayerCoolPlateTemp = 42,
            ))
            .selectBuildPlate(BuildPlateType.COOL)
            .selectQuality(QualityProfile.DRAFT_06)
            .copy(
                topSolidLayers = 6,
                bottomSolidLayers = 5,
                fillPattern = "crosshatch",
                topSurfacePattern = "monotonic",
                bottomSurfacePattern = "concentric",
                internalSolidInfillPattern = "rectilinear",
                travelSpeed = 420f,
                gcodeSettings = GcodeSettings(verboseComments = true),
                firstLayerSpeed = 35f,
                skirtLoops = 2,
                skirtDistance = 7f,
                skirtHeight = 3,
                skirtSpeed = 59f,
                minimumSkirtLength = 14f,
                draftShield = "enabled",
                brimType = "outer_and_inner",
                brimWidth = 6f,
                brimObjectGap = 0.17f,
                raftLayers = 2,
                raftContactDistance = 0.16f,
                raftExpansion = 2.7f,
                raftFirstLayerDensity = 88f,
                raftFirstLayerExpansion = 3.7f,
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
                internalBridgeSpeed = 163f,
                internalBridgeSpeedPercent = true,
                overhangSpeedEnabled = true,
                overhangSpeed1 = 81f,
                overhangSpeed1Percent = true,
                overhangSpeed2 = 52f,
                overhangSpeed2Percent = false,
                overhangSpeed3 = 33f,
                overhangSpeed3Percent = true,
                overhangSpeed4 = 21f,
                overhangSpeed4Percent = false,
                printFlowRatio = 0.94f,
                bridgeFlowRatio = 0.91f,
                internalBridgeFlowRatio = 0.96f,
                topSurfaceFlowRatio = 0.97f,
                bottomSurfaceFlowRatio = 0.98f,
                supportFlowRatio = 0.86f,
                supportInterfaceFlowRatio = 1.14f,
                bridgeDensity = 87f,
                internalBridgeDensity = 73f,
                bridgeAngle = 19f,
                internalBridgeAngle = 107f,
                bridgeNoSupport = true,
                thickBridges = true,
                thickInternalBridges = false,
                extraBridgeLayer = "apply_to_all",
                internalBridgeFilter = "nofilter",
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
                supportStyle = "tree_strong",
                supportCoverage = SupportCoverageSettings(
                    onBuildPlateOnly = true,
                    criticalRegionsOnly = true,
                    removeSmallOverhangs = false,
                    enforcedLayers = 7,
                ),
                supportAdvanced = SupportAdvancedSettings(
                    patternAngle = 73f,
                    thresholdOverlap = 0.33f,
                    thresholdOverlapPercent = false,
                    objectFirstLayerGap = 0.42f,
                    avoidInterfaceFilamentForBase = false,
                    ironingEnabled = true,
                    ironingPattern = "concentric",
                    ironingFlow = 17f,
                    ironingSpacing = 0.18f,
                ),
                supportBasePatternSpacing = 3.2f,
                supportExpansion = -0.4f,
                supportInterfaceLoopPattern = true,
                independentSupportLayerHeight = false,
                supportType = "tree(auto)",
                treeSupportBranchAngle = 47f,
                treeSupportBranchDistance = 6.2f,
                treeSupportBranchDiameter = 2.4f,
                treeSupportWallCount = 2,
                treeSupportTipDiameter = 1.3f,
                treeSupportPreferredBranchAngle = 31f,
                treeSupportBranchDensity = 37f,
                treeSupportOrganicBranchAngle = 45f,
                treeSupportOrganicBranchDistance = 2.2f,
                treeSupportOrganicBranchDiameter = 3.1f,
                treeSupportBranchDiameterAngle = 10f,
                treeSupportAdaptiveLayerHeight = false,
                treeSupportAutoBrim = false,
                treeSupportBrimWidth = 4.6f,
                infillFirst = true,
                infillWallOverlap = 19f,
                topBottomInfillWallOverlap = 31f,
                infillCombination = true,
                infillCombinationMaxLayerHeight = 0.48f,
                infillCombinationMaxLayerHeightPercent = false,
                infillDirection = 37f,
                solidInfillDirection = 123f,
                alignInfillDirectionToModel = true,
                minimumSparseInfillArea = 42f,
                infillAnchor = 321f,
                infillAnchorPercent = true,
                infillAnchorMax = 17.5f,
                infillAnchorMaxPercent = false,
                quality = QualityProfile.DRAFT_06.copy(
                    surfaceDensity = SurfaceDensitySettings(topPercent = 42f, bottomPercent = 68f),
                    fillMultiline = 4,
                    lateralInfill = LateralInfillSettings(-32f, 57f, 68f),
                    skeletonInfillDensity = 31f,
                    skinInfillDensity = 47f,
                    skinInfillDepth = 3.5f,
                    infillLockDepth = 1.25f,
                    infillShiftStep = 1.7f,
                    symmetricInfillYAxis = true,
                    skinInfillLineWidth = 135f,
                    skinInfillLineWidthPercent = true,
                    skeletonInfillLineWidth = 0.62f,
                    skeletonInfillLineWidthPercent = false,
                    skirtStartAngle = -25f,
                    skirtType = "perobject",
                    singleLoopDraftShield = true,
                ),
                gapFillTarget = "everywhere",
                filterOutGapFill = 0.9f,
                reduceCrossingWall = true,
                maxTravelDetourDistance = 123f,
                maxTravelDetourDistancePercent = true,
                reduceInfillRetraction = true,
                initialLayerLineWidth = 0.73f,
                smallPerimeterSpeed = 69f,
                smallPerimeterSpeedPercent = true,
                smallPerimeterThreshold = 7.5f,
                slowdownForCurledPerimeters = false,
                resolution = 0.021f,
                precision = PrecisionSettings(
                    mode = "even_odd",
                    closingRadius = 0.123f,
                    preciseZHeight = true,
                    minimumWallWidth = 71f,
                    firstLayerMinimumWallWidth = 119f,
                    brimEars = BrimEarSettings(maximumAngle = 136f, detectionRadius = 1.7f),
                ),
                seamPosition = "nearest",
                staggeredInnerSeams = true,
                seamGap = 7f,
                seamGapPercent = true,
                wipeBeforeExternalLoop = true,
                wipeOnLoops = true,
                roleBasedWipeSpeed = false,
                wipeSpeed = 61f,
                wipeSpeedPercent = false,
                ironing = IroningSettings(
                    type = "top",
                    pattern = "concentric",
                    flow = 13f,
                    spacing = 0.17f,
                    inset = 0.38f,
                    speed = 27f,
                    angle = 124f,
                ),
                defaultAcceleration = 4_567f,
                outerWallAcceleration = 2_345f,
                innerWallAcceleration = 3_456f,
                topSurfaceAcceleration = 1_234f,
                travelAcceleration = 5_678f,
                firstLayerAcceleration = 678f,
                firstLayerTravelAcceleration = 37f,
                firstLayerTravelAccelerationPercent = true,
                bridgeAcceleration = 47f,
                bridgeAccelerationPercent = true,
                sparseInfillAcceleration = 4_321f,
                sparseInfillAccelerationPercent = false,
                internalSolidInfillAcceleration = 83f,
                internalSolidInfillAccelerationPercent = true,
                wallGenerator = "arachne",
                wallTransitionLength = 137f,
                wallTransitionFilterDeviation = 33f,
                wallTransitionAngle = 26f,
                wallDistributionCount = 4,
                minimumFeatureSize = 19f,
                minimumWallLengthFactor = 0.85f,
                wallSequence = "outer-inner",
                wallDirection = "cw",
                detectThinWalls = true,
                detectOverhangWalls = false,
                onlyOneWallOnTop = false,
                minWidthTopSurface = 285f,
                minWidthTopSurfacePercent = true,
                onlyOneWallFirstLayer = true,
                extraPerimetersOnOverhangs = true,
                overhangReverse = true,
                overhangReverseInternalOnly = true,
                overhangReverseThreshold = 0.75f,
                overhangReverseThresholdPercent = false,
                counterboreHoleBridging = "sacrificiallayer",
                alternateExtraWall = true,
                ensureVerticalShellThickness = "ensure_moderate",
                detectNarrowInternalSolidInfill = false,
                xyHoleCompensation = 0.11f,
                xyContourCompensation = -0.07f,
                elephantFootCompensation = 0.23f,
                elephantFootCompensationLayers = 3,
                maxBridgeLength = 26f,
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
        assertTrue("Selected plate must reach Orca", gcode.contains("; curr_bed_type = Cool Plate"))
        assertTrue("Selected plate temperature must reach Orca", gcode.contains("; cool_plate_temp = 41"))
        assertTrue(
            "Selected first-layer plate temperature must reach Orca",
            gcode.contains("; cool_plate_temp_initial_layer = 42"),
        )
        assertTrue("Selected first-layer bed temperature must reach G-code", gcode.contains("M190 S42"))
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
        assertTrue("Top surface density must reach Orca", gcode.contains("; top_surface_density = 42%"))
        assertTrue("Bottom surface density must reach Orca", gcode.contains("; bottom_surface_density = 68%"))
        assertTrue("Internal solid pattern must remain distinct", gcode.contains("; internal_solid_infill_pattern = rectilinear"))
        assertTrue("Travel speed must reach G-code", gcode.contains("; travel_speed = 420"))
        assertTrue("First layer speed must reach G-code", gcode.contains("; initial_layer_speed = 35"))
        assertTrue("Initial-layer solid speed must reach Orca", gcode.contains("; initial_layer_infill_speed = 63"))
        assertTrue("Bridge speed must reach Orca", gcode.contains("; bridge_speed = 43"))
        assertTrue("Internal bridge speed must preserve percent units", gcode.contains("; internal_bridge_speed = 163%"))
        assertTrue("Gap-infill speed must reach Orca", gcode.contains("; gap_infill_speed = 137"))
        val retractionHeader = gcode.lineSequence()
            .filter { line ->
                line.startsWith("; retraction_") || line.startsWith("; deretraction_") ||
                    line.startsWith("; retract_") || line.startsWith("; wipe") ||
                    line.startsWith("; z_hop")
            }
            .joinToString(" | ")
        assertTrue(
            "Retraction length must reach G-code; actual: $retractionHeader",
            gcode.contains("; retraction_length = 1.1"),
        )
        assertTrue("Retraction speed must reach G-code", gcode.contains("; retraction_speed = 38"))
        assertTrue("De-retraction speed must reach G-code", gcode.contains("; deretraction_speed = 33"))
        assertTrue("Retraction travel threshold must reach G-code", gcode.contains("; retraction_minimum_travel = 2.2"))
        assertTrue("Layer-change retraction must reach G-code", gcode.contains("; retract_when_changing_layer = 1"))
        assertTrue("Retraction wipe must reach G-code", gcode.contains("; wipe = 1"))
        assertTrue("Wipe distance must reach G-code", gcode.contains("; wipe_distance = 2.4"))
        assertTrue("Pre-wipe amount must reach G-code", gcode.contains("; retract_before_wipe = 63%"))
        assertTrue("Restart extra must reach G-code", gcode.contains("; retract_restart_extra = 0.09"))
        assertTrue("Z-hop height must reach G-code", gcode.contains("; z_hop = 0.6"))
        assertTrue("Z-hop type must reach G-code", gcode.contains("; z_hop_types = Spiral Lift"))
        assertTrue("Filament lift lower boundary must override the printer", gcode.contains("; retract_lift_above = 0.8"))
        assertTrue("Filament lift upper boundary must override the printer", gcode.contains("; retract_lift_below = 150"))
        assertTrue("Filament lift surface policy must override the printer", gcode.contains("; retract_lift_enforce = Top Only"))
        assertTrue("Z-hop slope must reach G-code", gcode.contains("; travel_slope = 7"))
        assertTrue("Prime-tower Z-hop policy must reach G-code", gcode.contains("; z_hop_when_prime = 0"))
        assertTrue("Skirt topology must reach Orca", gcode.contains("; skirt_type = perobject"))
        assertTrue("Skirt loops must reach G-code", gcode.contains("; skirt_loops = 2"))
        assertTrue("Skirt distance must reach Orca", gcode.contains("; skirt_distance = 7"))
        assertTrue("Skirt start point must reach Orca", gcode.contains("; skirt_start_angle = -25"))
        assertTrue("Verbose G-code must reach Orca", gcode.contains("; gcode_comments = 1"))
        assertTrue("Skirt height must reach Orca", gcode.contains("; skirt_height = 3"))
        assertTrue("Skirt speed must reach Orca", gcode.contains("; skirt_speed = 59"))
        assertTrue("Minimum skirt extrusion must reach Orca", gcode.contains("; min_skirt_length = 14"))
        assertTrue("Draft shield mode must reach Orca", gcode.contains("; draft_shield = enabled"))
        assertTrue(
            "Single-loop draft shield must reach Orca",
            gcode.contains("; single_loop_draft_shield = 1"),
        )
        assertTrue("Brim topology must reach Orca", gcode.contains("; brim_type = outer_and_inner"))
        assertTrue("Brim width must reach Orca", gcode.contains("; brim_width = 6"))
        assertTrue("Brim gap must reach Orca", gcode.contains("; brim_object_gap = 0.17"))
        assertTrue("Brim ear angle must reach Orca", gcode.contains("; brim_ears_max_angle = 136"))
        assertTrue("Brim ear radius must reach Orca", gcode.contains("; brim_ears_detection_length = 1.7"))
        assertTrue("Raft layer count must reach Orca", gcode.contains("; raft_layers = 2"))
        assertTrue("Raft contact distance must reach Orca", gcode.contains("; raft_contact_distance = 0.16"))
        assertTrue("Raft expansion must reach Orca", gcode.contains("; raft_expansion = 2.7"))
        assertTrue("Raft density must reach Orca", gcode.contains("; raft_first_layer_density = 88%"))
        assertTrue("First raft expansion must reach Orca", gcode.contains("; raft_first_layer_expansion = 3.7"))
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
        assertTrue("Process flow ratio must reach Orca", gcode.contains("; print_flow_ratio = 0.94"))
        assertTrue("Bridge flow must reach Orca", gcode.contains("; bridge_flow = 0.91"))
        assertTrue("Internal bridge flow must reach Orca", gcode.contains("; internal_bridge_flow = 0.96"))
        assertTrue("Top surface flow must reach Orca", gcode.contains("; top_solid_infill_flow_ratio = 0.97"))
        assertTrue("Bottom surface flow must reach Orca", gcode.contains("; bottom_solid_infill_flow_ratio = 0.98"))
        assertTrue("Support flow must reach Orca", gcode.contains("; support_flow_ratio = 0.86"))
        assertTrue(
            "Support interface flow must reach Orca",
            gcode.contains("; support_interface_flow_ratio = 1.14"),
        )
        assertTrue("External bridge density must reach Orca", gcode.contains("; bridge_density = 87%"))
        assertTrue("Internal bridge density must reach Orca", gcode.contains("; internal_bridge_density = 73%"))
        assertTrue("Bridge support policy must reach Orca", gcode.contains("; bridge_no_support = 1"))
        assertTrue("External bridge thickness must reach Orca", gcode.contains("; thick_bridges = 1"))
        assertTrue("Internal bridge thickness must reach Orca", gcode.contains("; thick_internal_bridges = 0"))
        assertTrue("External bridge direction must reach Orca", gcode.contains("; bridge_angle = 19"))
        assertTrue("Internal bridge direction must reach Orca", gcode.contains("; internal_bridge_angle = 107"))
        assertTrue("Extra bridge layers must reach Orca", gcode.contains("; enable_extra_bridge_layer = apply_to_all"))
        assertTrue("Internal bridge filtering must reach Orca", gcode.contains("; dont_filter_internal_bridges = nofilter"))
        assertTrue("Top support interface layers must reach Orca", gcode.contains("; support_interface_top_layers = 4"))
        assertTrue("Bottom support interface layers must reach Orca", gcode.contains("; support_interface_bottom_layers = 2"))
        assertTrue("Enforced support layers must reach Orca", gcode.contains("; enforce_support_layers = 7"))
        assertTrue("Top support interface spacing must reach Orca", gcode.contains("; support_interface_spacing = 0.23"))
        assertTrue("Bottom support interface spacing must reach Orca", gcode.contains("; support_bottom_interface_spacing = 0.27"))
        assertTrue("Support top Z distance must reach Orca", gcode.contains("; support_top_z_distance = 0.18"))
        assertTrue("Support bottom Z distance must reach Orca", gcode.contains("; support_bottom_z_distance = 0.22"))
        assertTrue("Support XY distance must reach Orca", gcode.contains("; support_object_xy_distance = 0.41"))
        assertTrue("Support base pattern must reach Orca", gcode.contains("; support_base_pattern = rectilinear-grid"))
        assertTrue("Support interface pattern must reach Orca", gcode.contains("; support_interface_pattern = rectilinear_interlaced"))
        assertTrue("Support style must reach Orca", gcode.contains("; support_style = tree_strong"))
        assertTrue("Build-plate-only support must reach Orca", gcode.contains("; support_on_build_plate_only = 1"))
        assertTrue("Critical-region support must reach Orca", gcode.contains("; support_critical_regions_only = 1"))
        assertTrue("Small-overhang filtering must reach Orca", gcode.contains("; support_remove_small_overhang = 0"))
        assertTrue("Support pattern angle must reach Orca", gcode.contains("; support_angle = 73"))
        assertTrue("Support threshold overlap must preserve millimeters", gcode.contains("; support_threshold_overlap = 0.33"))
        assertTrue("First-layer support gap must reach Orca", gcode.contains("; support_object_first_layer_gap = 0.42"))
        assertTrue("Support filament policy must reach Orca", gcode.contains("; support_interface_not_for_body = 0"))
        assertTrue("Support ironing must reach Orca", gcode.contains("; support_ironing = 1"))
        assertTrue("Support ironing pattern must reach Orca", gcode.contains("; support_ironing_pattern = concentric"))
        assertTrue("Support ironing flow must reach Orca", gcode.contains("; support_ironing_flow = 17%"))
        assertTrue("Support ironing spacing must reach Orca", gcode.contains("; support_ironing_spacing = 0.18"))
        assertTrue("Support base spacing must reach Orca", gcode.contains("; support_base_pattern_spacing = 3.2"))
        assertTrue("Support expansion must reach Orca", gcode.contains("; support_expansion = -0.4"))
        assertTrue("Support interface loops must reach Orca", gcode.contains("; support_interface_loop_pattern = 1"))
        assertTrue("Independent support layers must reach Orca", gcode.contains("; independent_support_layer_height = 0"))
        assertTrue("Automatic tree support mode must reach Orca", gcode.contains("; support_type = tree(auto)"))
        assertTrue("Tree branch angle must reach Orca", gcode.contains("; tree_support_branch_angle = 47"))
        assertTrue("Tree branch distance must reach Orca", gcode.contains("; tree_support_branch_distance = 6.2"))
        assertTrue("Tree branch diameter must reach Orca", gcode.contains("; tree_support_branch_diameter = 2.4"))
        assertTrue("Tree support wall loops must reach Orca", gcode.contains("; tree_support_wall_count = 2"))
        assertTrue("Tree tip diameter must reach Orca", gcode.contains("; tree_support_tip_diameter = 1.3"))
        assertTrue("Preferred tree branch angle must reach Orca", gcode.contains("; tree_support_angle_slow = 31"))
        assertTrue("Tree branch density must reach Orca", gcode.contains("; tree_support_top_rate = 37%"))
        assertTrue("Organic branch angle must reach Orca", gcode.contains("; tree_support_branch_angle_organic = 45"))
        assertTrue("Organic branch distance must reach Orca", gcode.contains("; tree_support_branch_distance_organic = 2.2"))
        assertTrue("Organic branch diameter must reach Orca", gcode.contains("; tree_support_branch_diameter_organic = 3.1"))
        assertTrue("Branch diameter angle must reach Orca", gcode.contains("; tree_support_branch_diameter_angle = 10"))
        assertTrue("Adaptive tree layers must reach Orca", gcode.contains("; tree_support_adaptive_layer_height = 0"))
        assertTrue("Automatic tree brim must reach Orca", gcode.contains("; tree_support_auto_brim = 0"))
        assertTrue("Tree brim width must reach Orca", gcode.contains("; tree_support_brim_width = 4.6"))
        assertTrue("Seam position must reach Orca", gcode.contains("; seam_position = nearest"))
        assertTrue("Ironing type must reach Orca", gcode.contains("; ironing_type = top"))
        assertTrue("Ironing pattern must reach Orca", gcode.contains("; ironing_pattern = concentric"))
        assertTrue("Ironing flow must reach Orca", gcode.contains("; ironing_flow = 13%"))
        assertTrue("Ironing spacing must reach Orca", gcode.contains("; ironing_spacing = 0.17"))
        assertTrue("Ironing inset must reach Orca", gcode.contains("; ironing_inset = 0.38"))
        assertTrue("Ironing speed must reach Orca", gcode.contains("; ironing_speed = 27"))
        assertTrue("Ironing angle must reach Orca", gcode.contains("; ironing_angle = 124"))
        assertTrue("Overhang stage 1 must preserve percent units", gcode.contains("; overhang_1_4_speed = 81%"))
        assertTrue("Overhang stage 2 must preserve absolute units", gcode.contains("; overhang_2_4_speed = 52"))
        assertTrue("Overhang stage 3 must preserve percent units", gcode.contains("; overhang_3_4_speed = 33%"))
        assertTrue("Overhang stage 4 must preserve absolute units", gcode.contains("; overhang_4_4_speed = 21"))
        assertTrue("Infill-first order must reach Orca", gcode.contains("; is_infill_first = 1"))
        assertTrue("Sparse infill overlap must reach Orca", gcode.contains("; infill_wall_overlap = 19%"))
        assertTrue("Solid surface overlap must reach Orca", gcode.contains("; top_bottom_infill_wall_overlap = 31%"))
        assertTrue("Combined infill must reach Orca", gcode.contains("; infill_combination = 1"))
        assertTrue("Combined infill height must preserve absolute units", gcode.contains("; infill_combination_max_layer_height = 0.48"))
        assertTrue("Sparse infill direction must reach Orca", gcode.contains("; infill_direction = 37"))
        assertTrue("Solid infill direction must reach Orca", gcode.contains("; solid_infill_direction = 123"))
        assertTrue("Fill multiline must reach Orca", gcode.contains("; fill_multiline = 4"))
        assertTrue("First lateral lattice angle must reach Orca", gcode.contains("; lateral_lattice_angle_1 = -32"))
        assertTrue("Second lateral lattice angle must reach Orca", gcode.contains("; lateral_lattice_angle_2 = 57"))
        assertTrue("Infill overhang angle must reach Orca", gcode.contains("; infill_overhang_angle = 68"))
        assertTrue("Model-relative infill must reach Orca", gcode.contains("; align_infill_direction_to_model = 1"))
        assertTrue("Sparse-area threshold must reach Orca", gcode.contains("; minimum_sparse_infill_area = 42"))
        assertTrue("Infill anchor must preserve percent units", gcode.contains("; infill_anchor = 321%"))
        assertTrue("Maximum infill anchor must preserve absolute units", gcode.contains("; infill_anchor_max = 17.5"))
        assertTrue("Locked Zag skeleton density must reach Orca", gcode.contains("; skeleton_infill_density = 31%"))
        assertTrue("Locked Zag skin density must reach Orca", gcode.contains("; skin_infill_density = 47%"))
        assertTrue("Infill shift must reach Orca", gcode.contains("; infill_shift_step = 1.7"))
        assertTrue("Symmetric infill must reach Orca", gcode.contains("; symmetric_infill_y_axis = 1"))
        assertTrue("Locked Zag skin depth must reach Orca", gcode.contains("; skin_infill_depth = 3.5"))
        assertTrue("Locked Zag lock depth must reach Orca", gcode.contains("; infill_lock_depth = 1.25"))
        assertTrue("Locked Zag skin width must preserve percent units", gcode.contains("; skin_infill_line_width = 135%"))
        assertTrue("Locked Zag skeleton width must preserve absolute units", gcode.contains("; skeleton_infill_line_width = 0.62"))
        assertTrue("Gap-fill surface policy must reach Orca", gcode.contains("; gap_fill_target = everywhere"))
        assertTrue("Tiny-gap filter must reach Orca", gcode.contains("; filter_out_gap_fill = 0.9"))
        assertTrue("Wall-crossing avoidance must reach Orca", gcode.contains("; reduce_crossing_wall = 1"))
        assertTrue("Travel detour must preserve percent units", gcode.contains("; max_travel_detour_distance = 123%"))
        assertTrue("Infill retraction policy must reach Orca", gcode.contains("; reduce_infill_retraction = 1"))
        assertTrue("Default acceleration must reach Orca", gcode.contains("; default_acceleration = 4567"))
        assertTrue("Outer-wall acceleration must reach Orca", gcode.contains("; outer_wall_acceleration = 2345"))
        assertTrue("Inner-wall acceleration must reach Orca", gcode.contains("; inner_wall_acceleration = 3456"))
        assertTrue("Top-surface acceleration must reach Orca", gcode.contains("; top_surface_acceleration = 1234"))
        assertTrue("Travel acceleration must reach Orca", gcode.contains("; travel_acceleration = 5678"))
        assertTrue("First-layer acceleration must reach Orca", gcode.contains("; initial_layer_acceleration = 678"))
        assertTrue(
            "First-layer travel acceleration must preserve percent units",
            gcode.contains("; initial_layer_travel_acceleration = 37%"),
        )
        assertTrue("Bridge acceleration must preserve percent units", gcode.contains("; bridge_acceleration = 47%"))
        assertTrue("Sparse infill acceleration must preserve absolute units", gcode.contains("; sparse_infill_acceleration = 4321"))
        assertTrue("Internal solid acceleration must preserve percent units", gcode.contains("; internal_solid_infill_acceleration = 83%"))
        assertTrue("Arachne selection must reach Orca", gcode.contains("; wall_generator = arachne"))
        assertTrue("Arachne transition length must reach Orca", gcode.contains("; wall_transition_length = 137%"))
        assertTrue("Arachne transition filter must reach Orca", gcode.contains("; wall_transition_filter_deviation = 33%"))
        assertTrue("Arachne transition angle must reach Orca", gcode.contains("; wall_transition_angle = 26"))
        assertTrue("Arachne wall distribution must reach Orca", gcode.contains("; wall_distribution_count = 4"))
        assertTrue("Arachne minimum feature size must reach Orca", gcode.contains("; min_feature_size = 19%"))
        assertTrue("Arachne minimum wall width must reach Orca", gcode.contains("; min_bead_width = 71%"))
        assertTrue(
            "Arachne first-layer minimum wall width must reach Orca",
            gcode.contains("; initial_layer_min_bead_width = 119%"),
        )
        assertTrue("Arachne minimum wall length must reach Orca", gcode.contains("; min_length_factor = 0.85"))
        assertTrue("Wall order must reach Orca", gcode.contains("; wall_sequence = outer wall/inner wall"))
        assertTrue("Wall direction must reach Orca", gcode.contains("; wall_direction = cw"))
        assertTrue("Small-perimeter speed must preserve percent units", gcode.contains("; small_perimeter_speed = 69%"))
        assertTrue("Small-perimeter threshold must reach Orca", gcode.contains("; small_perimeter_threshold = 7.5"))
        assertTrue("Curled-perimeter slowdown must reach Orca", gcode.contains("; slowdown_for_curled_perimeters = 0"))
        assertTrue("Toolpath resolution must reach Orca", gcode.contains("; resolution = 0.021"))
        assertTrue("Mesh winding mode must reach Orca", gcode.contains("; slicing_mode = even_odd"))
        assertTrue("Mesh gap closing radius must reach Orca", gcode.contains("; slice_closing_radius = 0.123"))
        assertTrue("Precise Z height must reach Orca", gcode.contains("; precise_z_height = 1"))
        assertTrue("Inner seam staggering must reach Orca", gcode.contains("; staggered_inner_seams = 1"))
        assertTrue("Seam gap must preserve percent units", gcode.contains("; seam_gap = 7%"))
        assertTrue("Outer-wall pre-wipe must reach Orca", gcode.contains("; wipe_before_external_loop = 1"))
        assertTrue("Loop wipe must reach Orca", gcode.contains("; wipe_on_loops = 1"))
        assertTrue("Role-based wipe policy must reach Orca", gcode.contains("; role_based_wipe_speed = 0"))
        assertTrue("Absolute wipe speed must reach Orca", gcode.contains("; wipe_speed = 61"))
        assertTrue("Thin-wall detection must reach Orca", gcode.contains("; detect_thin_wall = 1"))
        assertTrue("Overhang-wall detection must reach Orca", gcode.contains("; detect_overhang_wall = 0"))
        assertTrue("Top-surface wall rule must reach Orca", gcode.contains("; only_one_wall_top = 0"))
        assertTrue("Partial top-surface threshold must preserve percent units", gcode.contains("; min_width_top_surface = 285%"))
        assertTrue("First-layer wall rule must reach Orca", gcode.contains("; only_one_wall_first_layer = 1"))
        assertTrue("Overhang extra-wall policy must reach Orca", gcode.contains("; extra_perimeters_on_overhangs = 1"))
        assertTrue("Overhang direction reversal must reach Orca", gcode.contains("; overhang_reverse = 1"))
        assertTrue("Internal-only reversal must reach Orca", gcode.contains("; overhang_reverse_internal_only = 1"))
        assertTrue("Overhang reversal threshold must preserve absolute units", gcode.contains("; overhang_reverse_threshold = 0.75"))
        assertTrue("Counterbore bridge mode must reach Orca", gcode.contains("; counterbore_hole_bridging = sacrificiallayer"))
        assertTrue("Alternating extra wall must reach Orca", gcode.contains("; alternate_extra_wall = 1"))
        assertTrue("Vertical shell mode must reach Orca", gcode.contains("; ensure_vertical_shell_thickness = ensure_moderate"))
        assertTrue("Narrow internal-solid policy must reach Orca", gcode.contains("; detect_narrow_internal_solid_infill = 0"))
        assertTrue("Hole compensation must reach Orca", gcode.contains("; xy_hole_compensation = 0.11"))
        assertTrue("Contour compensation must reach Orca", gcode.contains("; xy_contour_compensation = -0.07"))
        assertTrue("Elephant-foot compensation must reach Orca", gcode.contains("; elefant_foot_compensation = 0.23"))
        assertTrue("Elephant-foot layer count must reach Orca", gcode.contains("; elefant_foot_compensation_layers = 3"))
        assertTrue("Unsupported bridge limit must reach Orca", gcode.contains("; max_bridge_length = 26"))
        assertTrue("Outer-wall precision must reach Orca", gcode.contains("; precise_outer_wall = 1"))

        val previewPayload = ByteBuffer.allocateDirect(GcodeLayerPreview.MAX_PAYLOAD_BYTES)
            .order(ByteOrder.nativeOrder())
        val usedFloats = NativeEngine.previewGcodeRangeInto(
            outcome.output.absolutePath,
            0,
            Int.MAX_VALUE,
            previewPayload,
        )
        assertTrue(
            "Direct preview payload must remain within the fixed memory budget",
            usedFloats in 1..GcodeLayerPreview.MAX_PAYLOAD_FLOATS,
        )
        val preview = GcodeLayerPreview.fromTrustedNative(previewPayload, usedFloats)
        assertTrue("Preview must report generated layers", preview.layerCount > 0)
        assertTrue("Preview must include the first layer", preview.startLayer == 0)
        assertTrue("Preview must include the final G-code layer", preview.endLayer == preview.layerCount - 1)
        assertTrue("Full preview must contain extrusion paths", preview.segments.isNotEmpty())
        assertEquals(0, preview.segments.size % GcodeLayerPreview.SEGMENT_STRIDE)
        assertTrue("Segment Z coordinates must be positive", preview.segments[4] > 0f)
        assertTrue("Outer-wall paths must be classified", preview.roleSegmentCounts[0] > 0)
        assertTrue("Inner-wall paths must be classified", preview.roleSegmentCounts[1] > 0)
        assertTrue("Visible top surfaces must be classified", preview.roleSegmentCounts[3] > 0)
        assertTrue("Internal solid infill must stay separate", preview.roleSegmentCounts[4] > 0)
        assertTrue("Visible bottom surfaces must stay separate", preview.roleSegmentCounts[9] > 0)
        assertTrue("Preview must report a positive first layer Z", preview.minZMm > 0f)
        assertTrue("Multi-layer preview must span upward in Z", preview.maxZMm > preview.minZMm)

        val gpuStaging = ToolpathMeshBuilder.build(
            ToolpathScene(
                preview = preview,
                bedSizeX = options.bedSizeX,
                bedSizeY = options.bedSizeY,
                opacity = 0.92f,
                depthContrast = 0.78f,
                detail = PreviewDetail.BALANCED,
            ),
        )
        assertTrue("ARM64 GPU bed staging must use direct memory", gpuStaging.bedVertices.isDirect)
        assertTrue(
            "ARM64 GPU instance staging must use direct memory",
            gpuStaging.toolpathInstances.isDirect,
        )
        assertEquals(
            "ARM64 toolpaths must use compact 32-byte instances",
            gpuStaging.instanceCount * ToolpathMeshBuilder.INSTANCE_STRIDE_BYTES,
            gpuStaging.toolpathInstances.remaining(),
        )
        assertTrue(
            "ARM64 compact preview instances must stay below four MiB",
            gpuStaging.toolpathInstances.remaining() < 4 * 1024 * 1024,
        )
    }

    private companion object {
        val NON_FINITE_GCODE = Regex("(?i)(?:^|[\\sXYZEF])(?:nan|[+-]?inf)(?:$|\\s)")
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
    fun scarfJointSeamProducesSlopedExtrusionOnCylinder() {
        val base = SliceOptions()
            .selectPrinter(PrinterProfile.CUSTOM_CARTESIAN)
            .selectFilament(FilamentProfile.GENERIC_PLA)
            .selectQuality(QualityProfile.STANDARD)
            .copy(
                wallGenerator = "classic",
                perimeters = 2,
                fillDensity = 0.10f,
                scarfSeam = ScarfSeamSettings(
                    type = "external",
                    conditional = false,
                    speed = 65f,
                    speedPercent = true,
                    flowRatio = 0.92f,
                    startHeight = 15f,
                    startHeightPercent = true,
                    entireLoop = false,
                    length = 12f,
                    steps = 12,
                    innerWalls = false,
                ),
            )
        val enabled = OnDeviceSlicer.slice(cylinderModel(), base).output.readText()
        val disabled = OnDeviceSlicer.slice(
            cylinderModel(),
            base.copy(scarfSeam = base.scarfSeam.copy(type = "none")),
        ).output.readText()

        fun extrusionZValues(gcode: String): List<Float> = gcode.lineSequence()
            .filter { line ->
                line.startsWith("G1 ") && line.contains(" E") && line.contains(" Z") &&
                    (line.contains(" X") || line.contains(" Y"))
            }
            .mapNotNull { line ->
                Regex("(?:^| )Z(-?[0-9.]+)").find(line)?.groupValues?.get(1)?.toFloatOrNull()
            }
            .distinct()
            .toList()

        assertTrue("Scarf seam mode must reach Orca", enabled.contains("; seam_slope_type = external"))
        assertTrue("Scarf speed must preserve percent units", enabled.contains("; scarf_joint_speed = 65%"))
        assertTrue("Scarf flow ratio must reach Orca", enabled.contains("; scarf_joint_flow_ratio = 0.92"))
        assertTrue("Scarf start height must preserve percent units", enabled.contains("; seam_slope_start_height = 15%"))
        assertTrue("Scarf step count must reach Orca", enabled.contains("; seam_slope_steps = 12"))
        assertTrue("Disabled control must remain disabled", disabled.contains("; seam_slope_type = none"))
        assertTrue(
            "A real cylindrical scarf joint must add multiple within-wall extrusion heights",
            extrusionZValues(enabled).size >= extrusionZValues(disabled).size + 4,
        )
    }

    @Test
    fun spiralVaseProducesContinuousZExtrusionOnDevice() {
        val outcome = OnDeviceSlicer.slice(
            fixtureModel(),
            SliceOptions()
                .selectPrinter(PrinterProfile.U1_04)
                .selectFilament(FilamentProfile.PLA)
                .selectQuality(QualityProfile.DRAFT)
                .withSpiralMode(true)
                .copy(
                    bottomSolidLayers = 3,
                    spiralModeSmooth = true,
                    spiralModeMaxXySmoothing = 250f,
                    spiralModeMaxXySmoothingPercent = true,
                    spiralStartingFlowRatio = 0.35f,
                    spiralFinishingFlowRatio = 0.2f,
                ),
        )

        val gcode = outcome.output.readText()
        val extrusionZValues = gcode.lineSequence()
            .filter { it.startsWith("G1 ") && it.contains(" E") && it.contains(" Z") }
            .mapNotNull { line ->
                line.split(' ').firstOrNull { it.startsWith("Z") }?.drop(1)?.toFloatOrNull()
            }
            .distinct()
            .take(32)
            .toList()

        assertTrue("Spiral mode must reach the slicing engine", gcode.contains("; spiral_mode = 1"))
        assertTrue("Smooth spiral mode must reach the slicing engine", gcode.contains("; spiral_mode_smooth = 1"))
        assertTrue(
            "Spiral XY smoothing must preserve percent units",
            gcode.contains("; spiral_mode_max_xy_smoothing = 250%"),
        )
        assertTrue("Spiral starting flow must reach the slicing engine", gcode.contains("; spiral_starting_flow_ratio = 0.35"))
        assertTrue("Spiral finishing flow must reach the slicing engine", gcode.contains("; spiral_finishing_flow_ratio = 0.2"))
        assertTrue(
            "Spiral vase must raise Z continuously during extrusion instead of stacking closed layer loops",
            extrusionZValues.size >= 16,
        )
    }

    @Test
    fun hollowSolidKeepsExteriorAndCavityContoursDistinctOnDevice() {
        val options = SliceOptions()
            .selectPrinter(PrinterProfile.CUSTOM_CARTESIAN)
            .selectFilament(FilamentProfile.GENERIC_PLA)
            .selectQuality(QualityProfile.STANDARD)
            .copy(
                wallGenerator = "arachne",
                perimeters = 3,
                fillDensity = 0.18f,
                topSolidLayers = 4,
                bottomSolidLayers = 4,
            )
        val outcome = OnDeviceSlicer.slice(hollowTubeModel(), options)
        val middleLayer = (outcome.layers / 2).coerceAtLeast(1)
        val preview = loadGcodePreview(outcome.output.absolutePath, middleLayer, middleLayer)

        val centerX = options.bedSizeX / 2f
        val centerY = options.bedSizeY / 2f
        var exteriorOuterWalls = 0
        var cavityOuterWalls = 0
        var innerWalls = 0
        var cavityCrossings = 0
        preview.segments.indices.step(GcodeLayerPreview.SEGMENT_STRIDE).forEach { offset ->
            val x1 = preview.segments[offset]
            val y1 = preview.segments[offset + 1]
            val x2 = preview.segments[offset + 2]
            val y2 = preview.segments[offset + 3]
            val role = preview.segments[offset + 5].toInt()
            val midpointX = (x1 + x2) / 2f
            val midpointY = (y1 + y2) / 2f
            val centerDistance = maxOf(abs(midpointX - centerX), abs(midpointY - centerY))

            if (role == 0 && centerDistance > 12.5f) exteriorOuterWalls += 1
            if (role == 0 && centerDistance in 4f..7.5f) cavityOuterWalls += 1
            if (role == 1) innerWalls += 1
            if (abs(midpointX - centerX) < 3.5f && abs(midpointY - centerY) < 3.5f) {
                cavityCrossings += 1
            }
        }

        assertTrue("Orca must classify the exterior contour as an outer wall", exteriorOuterWalls > 0)
        assertTrue("Orca must classify the cavity contour as a surface-facing outer wall", cavityOuterWalls > 0)
        assertTrue("Orca must keep structural inner-wall shells separate", innerWalls > 0)
        assertEquals("No extrusion may cross the hollow cavity", 0, cavityCrossings)
    }

    @Test
    fun contourCompensationChangesGeneratedOuterWallGeometry() {
        val model = fixtureModel()
        val base = SliceOptions()
            .selectPrinter(PrinterProfile.CUSTOM_CARTESIAN)
            .selectFilament(FilamentProfile.GENERIC_PLA)
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                skirtLoops = 0,
                brimWidth = 0f,
                elephantFootCompensation = 0f,
                xyContourCompensation = 0f,
            )
        val original = outerWallBounds(OnDeviceSlicer.slice(model, base).output)
        val expanded = outerWallBounds(
            OnDeviceSlicer.slice(model, base.copy(xyContourCompensation = 0.4f)).output,
        )

        assertTrue(
            "Positive contour compensation must expand generated X geometry",
            expanded.maxX - expanded.minX > original.maxX - original.minX + 0.4f,
        )
        assertTrue(
            "Positive contour compensation must expand generated Y geometry",
            expanded.maxY - expanded.minY > original.maxY - original.minY + 0.4f,
        )
    }

    @Test
    fun filamentShrinkageCompensationScalesRealXyAndZGeometry() {
        val model = fixtureModel()
        fun options(shrinkagePercent: Float): SliceOptions = SliceOptions()
            .selectPrinter(PrinterProfile.CUSTOM_CARTESIAN)
            .selectFilament(
                FilamentProfile.GENERIC_PLA.copy(
                    shrinkageXyPercent = shrinkagePercent,
                    shrinkageZPercent = shrinkagePercent,
                ),
            )
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                skirtLoops = 0,
                brimWidth = 0f,
                elephantFootCompensation = 0f,
            )

        val baseline = OnDeviceSlicer.slice(model, options(100f))
        val baselineBounds = outerWallBounds(baseline.output)
        val compensated = OnDeviceSlicer.slice(model, options(50f))
        try {
            val compensatedBounds = outerWallBounds(compensated.output)
            val baselineWidth = baselineBounds.maxX - baselineBounds.minX
            val baselineDepth = baselineBounds.maxY - baselineBounds.minY
            val compensatedWidth = compensatedBounds.maxX - compensatedBounds.minX
            val compensatedDepth = compensatedBounds.maxY - compensatedBounds.minY
            val gcode = compensated.output.readText()

            assertTrue(gcode.contains("; filament_shrink = 50%"))
            assertTrue(gcode.contains("; filament_shrinkage_compensation_z = 50%"))
            assertTrue(
                "50% XY shrinkage compensation must nearly double real X extrusion geometry " +
                    "($baselineWidth -> $compensatedWidth)",
                compensatedWidth > baselineWidth * 1.9f,
            )
            assertTrue(
                "50% XY shrinkage compensation must nearly double real Y extrusion geometry " +
                    "($baselineDepth -> $compensatedDepth)",
                compensatedDepth > baselineDepth * 1.9f,
            )
            assertTrue(
                "50% Z shrinkage compensation must nearly double the real layer stack " +
                    "(${baseline.layers} -> ${compensated.layers})",
                compensated.layers > baseline.layers * 1.9f,
            )

            val primary = FilamentProfile.GENERIC_PLA.copy(
                id = "test-shrink-primary",
                shrinkageXyPercent = 50f,
                shrinkageZPercent = 50f,
            )
            val secondary = FilamentProfile.PETG.copy(
                id = "test-shrink-secondary",
                shrinkageXyPercent = 100f,
                shrinkageZPercent = 100f,
            )
            val mismatched = OnDeviceSlicer.slice(
                model,
                options(50f).copy(
                    printerProfile = PrinterProfile.CUSTOM_CARTESIAN.copy(
                        id = "test-shrink-two-tool-printer",
                        extruderCount = 2,
                    ),
                    filamentProfile = primary,
                    filamentSlots = listOf(primary, secondary),
                    featureFilaments = FeatureFilamentSettings(
                        infillOverrideEnabled = true,
                        sparseInfillFilament = 2,
                        wallFilament = 1,
                        solidInfillFilament = 2,
                    ),
                ),
            )
            try {
                val mismatchedBounds = outerWallBounds(mismatched.output)
                val mismatchedWidth = mismatchedBounds.maxX - mismatchedBounds.minX
                val mismatchedGcode = mismatched.output.readText()
                val shrinkageHeader = mismatchedGcode.lineSequence()
                    .firstOrNull { it.startsWith("; filament_shrink =") }
                assertEquals(";filament_shrink=50%,100%", shrinkageHeader?.replace(" ", ""))
                assertTrue(
                    "Orca must disable compensation when actually used filaments disagree " +
                        "($baselineWidth -> $mismatchedWidth)",
                    abs(mismatchedWidth - baselineWidth) < 0.5f,
                )
            } finally {
                mismatched.output.delete()
            }
        } finally {
            baseline.output.delete()
            compensated.output.delete()
        }
    }

    @Test
    fun skirtStartPointChangesTheFirstRealSkirtExtrusion() {
        val model = fixtureModel()
        val base = SliceOptions()
            .selectPrinter(PrinterProfile.CUSTOM_CARTESIAN)
            .selectFilament(FilamentProfile.GENERIC_PLA)
            .selectQuality(QualityProfile.DRAFT)
            .copy(skirtLoops = 1, skirtDistance = 5f, brimWidth = 0f)

        fun sliceAt(angle: Float): String = OnDeviceSlicer.slice(
            model,
            base.copy(quality = base.quality.copy(skirtStartAngle = angle)),
        ).output.readText()

        fun firstSkirtExtrusion(gcode: String): String? {
            var inSkirt = false
            return gcode.lineSequence().firstOrNull { line ->
                if (line.startsWith(";TYPE:")) inSkirt = line == ";TYPE:Skirt"
                inSkirt && line.startsWith("G1 ") && line.contains(" E") &&
                    (line.contains(" X") || line.contains(" Y"))
            }
        }

        val defaultAngle = sliceAt(-135f)
        val zeroAngle = sliceAt(0f)
        val defaultStart = firstSkirtExtrusion(defaultAngle)
        val zeroStart = firstSkirtExtrusion(zeroAngle)

        assertTrue(defaultAngle.contains("; skirt_start_angle = -135"))
        assertTrue(zeroAngle.contains("; skirt_start_angle = 0"))
        assertTrue("The default skirt needs a real extrusion start", defaultStart != null)
        assertTrue("The rotated skirt needs a real extrusion start", zeroStart != null)
        assertNotEquals(
            "Changing the start angle must move the first physical skirt extrusion",
            defaultStart,
            zeroStart,
        )
    }

    @Test
    fun perObjectAndSingleLoopSkirtsChangeRealExtrusionGeometry() {
        val model = inspectModel(fixtureModel().absolutePath)
        val objects = listOf(
            ProjectObject("skirt-left", model, ModelTransform(offsetXmm = -18f)),
            ProjectObject("skirt-right", model, ModelTransform(offsetXmm = 18f)),
        )
        val base = SliceOptions()
            .selectPrinter(PrinterProfile.CUSTOM_CARTESIAN)
            .selectFilament(FilamentProfile.GENERIC_PLA)
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                skirtLoops = 1,
                skirtDistance = 5f,
                skirtHeight = 1,
                brimType = "no_brim",
                brimWidth = 0f,
            )

        fun skirtMotionsByLayer(gcode: String): List<List<String>> {
            val layers = mutableListOf<MutableList<String>>()
            var inSkirt = false
            for (line in gcode.lineSequence()) {
                if (line == ";LAYER_CHANGE") {
                    layers += mutableListOf<String>()
                    inSkirt = false
                } else if (line.startsWith(";TYPE:")) {
                    inSkirt = line == ";TYPE:Skirt"
                } else if (
                    layers.isNotEmpty() && inSkirt && line.startsWith("G1 ") &&
                    line.contains(" E") && (line.contains(" X") || line.contains(" Y"))
                ) {
                    layers.last() += line.substringBefore(';').trimEnd()
                }
            }
            return layers
        }

        val combined = OnDeviceSlicer.slice(
            objects,
            base.copy(quality = base.quality.copy(skirtType = "combined")),
        )
        val perObject = OnDeviceSlicer.slice(
            objects,
            base.copy(quality = base.quality.copy(skirtType = "perobject")),
        )
        val fullLoops = OnDeviceSlicer.slice(
            listOf(objects.first()),
            base.copy(
                skirtLoops = 3,
                skirtHeight = 3,
                quality = base.quality.copy(singleLoopDraftShield = false),
            ),
        )
        val singleLoop = OnDeviceSlicer.slice(
            listOf(objects.first()),
            base.copy(
                skirtLoops = 3,
                skirtHeight = 3,
                quality = base.quality.copy(singleLoopDraftShield = true),
            ),
        )
        try {
            val combinedGcode = combined.output.readText()
            val perObjectGcode = perObject.output.readText()
            val combinedFirstLayer = skirtMotionsByLayer(combinedGcode).first()
            val perObjectFirstLayer = skirtMotionsByLayer(perObjectGcode).first()
            assertTrue(combinedGcode.contains("; skirt_type = combined"))
            assertTrue(perObjectGcode.contains("; skirt_type = perobject"))
            assertTrue("Combined skirt must extrude", combinedFirstLayer.isNotEmpty())
            assertTrue("Per-object skirts must extrude", perObjectFirstLayer.isNotEmpty())
            assertNotEquals(
                "Per-object skirt topology must change physical extrusion",
                combinedFirstLayer,
                perObjectFirstLayer,
            )

            val fullGcode = fullLoops.output.readText()
            val singleGcode = singleLoop.output.readText()
            val fullLayers = skirtMotionsByLayer(fullGcode)
            val singleLayers = skirtMotionsByLayer(singleGcode)
            assertTrue(fullGcode.contains("; single_loop_draft_shield = 0"))
            assertTrue(singleGcode.contains("; single_loop_draft_shield = 1"))
            assertTrue("Three skirt layers must be generated", fullLayers.take(3).all { it.isNotEmpty() })
            assertEquals(
                "Single-loop mode must retain the configured first-layer loops",
                fullLayers.first().size,
                singleLayers.first().size,
            )
            assertTrue(
                "Single-loop mode must reduce post-first-layer skirt extrusion",
                singleLayers.drop(1).take(2).sumOf { it.size } <
                    fullLayers.drop(1).take(2).sumOf { it.size },
            )
        } finally {
            combined.output.delete()
            perObject.output.delete()
            fullLoops.output.delete()
            singleLoop.output.delete()
        }
    }

    @Test
    fun verboseGcodeAddsCommandDescriptionsWithoutRemovingPreviewRoles() {
        val model = fixtureModel()
        val base = SliceOptions()
            .selectPrinter(PrinterProfile.CUSTOM_CARTESIAN)
            .selectFilament(FilamentProfile.GENERIC_PLA)
            .selectQuality(QualityProfile.DRAFT)
            .copy(skirtLoops = 0, brimWidth = 0f)

        fun slice(verbose: Boolean): String = OnDeviceSlicer.slice(
            model,
            base.copy(
                gcodeSettings = base.gcodeSettings.copy(verboseComments = verbose),
            ),
        ).output.readText()

        fun describedCommands(gcode: String): Int = gcode.lineSequence().count { line ->
            line.startsWith("G") && line.contains(" ; ")
        }

        val compact = slice(false)
        val verbose = slice(true)

        assertTrue(compact.contains("; gcode_comments = 0"))
        assertTrue(verbose.contains("; gcode_comments = 1"))
        assertTrue(compact.contains(";TYPE:Outer wall"))
        assertTrue(verbose.contains(";TYPE:Outer wall"))
        assertTrue(
            "Verbose output must add real per-command descriptions",
            describedCommands(verbose) > describedCommands(compact),
        )
    }

    @Test
    fun topSurfaceDensityChangesRealSurfaceExtrusion() {
        val model = fixtureModel()
        val base = SliceOptions()
            .selectPrinter(PrinterProfile.CUSTOM_CARTESIAN)
            .selectFilament(FilamentProfile.GENERIC_PLA)
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                topSolidLayers = 1,
                bottomSolidLayers = 1,
                skirtLoops = 0,
                brimWidth = 0f,
            )

        fun slice(topPercent: Float): SliceOutcome = OnDeviceSlicer.slice(
            model,
            base.copy(
                quality = base.quality.copy(
                    surfaceDensity = SurfaceDensitySettings(
                        topPercent = topPercent,
                        bottomPercent = 68f,
                    ),
                ),
            ),
        )

        fun topSurfaceMotion(gcode: String): List<String> {
            var topSurface = false
            return gcode.lineSequence().mapNotNull { line ->
                if (line.startsWith(";TYPE:")) topSurface = line == ";TYPE:Top surface"
                line.substringBefore(';').trimEnd().takeIf {
                    topSurface && it.startsWith("G1 ") && it.contains(" E") &&
                        (it.contains(" X") || it.contains(" Y"))
                }
            }.toList()
        }

        val sparse = slice(25f)
        val dense = slice(100f)
        try {
            val sparseGcode = sparse.output.readText()
            val denseGcode = dense.output.readText()
            val sparseMotion = topSurfaceMotion(sparseGcode)
            val denseMotion = topSurfaceMotion(denseGcode)

            assertTrue(sparseGcode.contains("; top_surface_density = 25%"))
            assertTrue(sparseGcode.contains("; bottom_surface_density = 68%"))
            assertTrue(denseGcode.contains("; top_surface_density = 100%"))
            assertTrue("Sparse top surface must retain physical extrusion", sparseMotion.isNotEmpty())
            assertTrue("Dense top surface must retain physical extrusion", denseMotion.isNotEmpty())
            assertNotEquals(
                "Top surface density must change physical surface motion, not only metadata",
                sparseMotion,
                denseMotion,
            )
        } finally {
            sparse.output.delete()
            dense.output.delete()
        }
    }

    @Test
    fun smallAreaFlowCompensationChangesRealShortSurfaceExtrusion() {
        val model = fixtureModel()
        val compensationModel = "0,0.5\n100,1"
        val base = SliceOptions()
            .selectPrinter(PrinterProfile.CUSTOM_CARTESIAN)
            .selectFilament(FilamentProfile.GENERIC_PLA)
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                topSolidLayers = 1,
                bottomSolidLayers = 1,
                topSurfacePattern = "rectilinear",
                internalSolidInfillPattern = "rectilinear",
                skirtLoops = 0,
                brimWidth = 0f,
            )

        fun slice(enabled: Boolean): SliceOutcome = OnDeviceSlicer.slice(
            model,
            base.copy(
                quality = base.quality.copy(
                    smallAreaFlowCompensation = enabled,
                    smallAreaFlowCompensationModel = compensationModel,
                ),
            ),
        )

        fun topSurfaceExtrusion(gcode: String): List<Float> {
            var topSurface = false
            return gcode.lineSequence().mapNotNull { line ->
                if (line.startsWith(";TYPE:")) topSurface = line == ";TYPE:Top surface"
                if (!topSurface || !line.startsWith("G1 ") ||
                    !(line.contains(" X") || line.contains(" Y"))) {
                    return@mapNotNull null
                }
                Regex("(?:^| )E([+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+))")
                    .find(line.substringBefore(';'))?.groupValues?.get(1)?.toFloatOrNull()
                    ?.takeIf { it > 0f }
            }.toList()
        }

        val disabled = slice(false)
        val enabled = slice(true)
        try {
            val disabledGcode = disabled.output.readText()
            val enabledGcode = enabled.output.readText()
            val disabledExtrusion = topSurfaceExtrusion(disabledGcode)
            val enabledExtrusion = topSurfaceExtrusion(enabledGcode)

            assertTrue(enabledGcode.contains("; small_area_infill_flow_compensation = 1"))
            assertTrue("The fixture needs short top-surface extrusion", disabledExtrusion.isNotEmpty())
            assertEquals(
                "Enabled top-surface lines: " + enabledGcode.lineSequence()
                    .dropWhile { it != ";TYPE:Top surface" }
                    .takeWhile { !it.startsWith(";TYPE:") || it == ";TYPE:Top surface" }
                    .take(20).joinToString(" | "),
                disabledExtrusion.size,
                enabledExtrusion.size,
            )
            assertNotEquals(
                "The enabled Orca compensator must alter physical E values",
                disabledExtrusion,
                enabledExtrusion,
            )
            assertTrue(
                "A sub-unity compensation curve must reduce total short-line extrusion",
                enabledExtrusion.sum() < disabledExtrusion.sum(),
            )
        } finally {
            disabled.output.delete()
            enabled.output.delete()
        }
    }

    @Test
    fun malformedSmallAreaFlowCompensationModelIsRejectedByNativeBoundary() {
        val failure = runCatching {
            OnDeviceSlicer.slice(
                fixtureModel(),
                SliceOptions()
                    .selectPrinter(PrinterProfile.CUSTOM_CARTESIAN)
                    .selectFilament(FilamentProfile.GENERIC_PLA)
                    .selectQuality(QualityProfile.DRAFT)
                    .copy(
                        quality = QualityProfile.DRAFT.copy(
                            smallAreaFlowCompensation = true,
                            smallAreaFlowCompensationModel = "0,0\n0.5,0.8\n0.4,1",
                        ),
                    ),
            )
        }.exceptionOrNull()

        assertTrue("A non-increasing compensation curve must not reach Orca", failure != null)
    }

    @Test
    fun infillRotationTemplatesChangeRealExtrusionOrientation() {
        val model = fixtureModel()
        val base = SliceOptions()
            .selectPrinter(PrinterProfile.CUSTOM_CARTESIAN)
            .selectFilament(FilamentProfile.GENERIC_PLA)
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                fillPattern = "rectilinear",
                fillDensity = 0.25f,
                topSolidLayers = 3,
                bottomSolidLayers = 3,
                infillDirection = 0f,
                solidInfillDirection = 0f,
                skirtLoops = 0,
                brimWidth = 0f,
            )

        fun slice(angle: String): SliceOutcome = OnDeviceSlicer.slice(
            model,
            base.copy(
                quality = base.quality.copy(
                    sparseInfillRotationTemplate = angle,
                    solidInfillRotationTemplate = angle,
                ),
            ),
        )

        fun roleMotion(gcode: String, role: String): List<String> {
            var selected = false
            return gcode.lineSequence().mapNotNull { line ->
                if (line.startsWith(";TYPE:")) selected = line == ";TYPE:$role"
                line.substringBefore(';').trimEnd().takeIf {
                    selected && it.startsWith("G1 ") && it.contains(" E") &&
                        (it.contains(" X") || it.contains(" Y"))
                }
            }.toList()
        }

        val zero = slice("0")
        val ninety = slice("90")
        try {
            val zeroGcode = zero.output.readText()
            val ninetyGcode = ninety.output.readText()
            assertTrue(ninetyGcode.contains("; sparse_infill_rotate_template = 90"))
            assertTrue(ninetyGcode.contains("; solid_infill_rotate_template = 90"))
            listOf("Sparse infill", "Internal solid infill").forEach { role ->
                val zeroMotion = roleMotion(zeroGcode, role)
                val ninetyMotion = roleMotion(ninetyGcode, role)
                assertTrue("$role needs physical extrusion", zeroMotion.isNotEmpty())
                assertTrue("$role needs rotated physical extrusion", ninetyMotion.isNotEmpty())
                assertNotEquals(
                    "$role rotation template must change physical motion, not only metadata",
                    zeroMotion,
                    ninetyMotion,
                )
            }
        } finally {
            zero.output.delete()
            ninety.output.delete()
        }
    }

    @Test
    fun extraSolidInfillsCreateRealInternalSolidExtrusion() {
        val base = SliceOptions()
            .selectPrinter(PrinterProfile.CUSTOM_CARTESIAN)
            .selectFilament(FilamentProfile.GENERIC_PLA)
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                fillPattern = "rectilinear",
                fillDensity = 0.10f,
                topSolidLayers = 2,
                bottomSolidLayers = 2,
                skirtLoops = 0,
                brimWidth = 0f,
            )
        val baseline = OnDeviceSlicer.slice(fixtureModel(), base)
        val inserted = OnDeviceSlicer.slice(
            fixtureModel(),
            base.copy(quality = base.quality.copy(extraSolidInfills = "4#2")),
        )
        try {
            fun internalSolidMotion(gcode: String): List<String> {
                var selected = false
                return gcode.lineSequence().mapNotNull { line ->
                    if (line.startsWith(";TYPE:")) selected = line == ";TYPE:Internal solid infill"
                    line.takeIf { selected && it.startsWith("G1 ") && it.contains(" E") }
                }.toList()
            }

            val baselineGcode = baseline.output.readText()
            val insertedGcode = inserted.output.readText()
            assertTrue(insertedGcode.contains("; extra_solid_infills = 4#2"))
            assertTrue(
                "Inserted solid layers must add physical internal-solid extrusion",
                internalSolidMotion(insertedGcode).size > internalSolidMotion(baselineGcode).size,
            )
        } finally {
            baseline.output.delete()
            inserted.output.delete()
        }
    }

    @Test
    fun resonanceAvoidanceClampsRealOuterWallMotion() {
        val basePrinter = PrinterProfile.CUSTOM_CARTESIAN.copy(
            resonanceAvoidance = false,
            minResonanceAvoidanceSpeed = 40f,
            maxResonanceAvoidanceSpeed = 90f,
        )
        val base = SliceOptions()
            .selectPrinter(basePrinter)
            .selectFilament(FilamentProfile.GENERIC_PLA)
            .selectQuality(QualityProfile.DRAFT.copy(printSpeed = 80f))
            .copy(skirtLoops = 0, brimWidth = 0f)
        val baseline = OnDeviceSlicer.slice(fixtureModel(), base)
        val avoided = OnDeviceSlicer.slice(
            fixtureModel(),
            base.selectPrinter(basePrinter.copy(resonanceAvoidance = true)),
        )
        try {
            fun maximumOuterWallFeed(gcode: String): Float {
                var outerWall = false
                var feed = Float.NaN
                var maximum = Float.NEGATIVE_INFINITY
                val feedPattern = Regex("(?:^|\\s)F([0-9.]+)")
                gcode.lineSequence().forEach { line ->
                    if (line.startsWith(";TYPE:")) outerWall = line == ";TYPE:Outer wall"
                    if (!line.startsWith("G1 ")) return@forEach
                    feedPattern.find(line)?.groupValues?.get(1)?.toFloatOrNull()?.let { feed = it }
                    if (outerWall && feed.isFinite() && line.contains(" E") &&
                        (line.contains(" X") || line.contains(" Y"))) {
                        maximum = maxOf(maximum, feed)
                    }
                }
                return maximum
            }

            val baselineGcode = baseline.output.readText()
            val avoidedGcode = avoided.output.readText()
            assertTrue(avoidedGcode.contains("; resonance_avoidance = 1"))
            assertTrue(avoidedGcode.contains("; min_resonance_avoidance_speed = 40"))
            assertTrue(avoidedGcode.contains("; max_resonance_avoidance_speed = 90"))
            assertTrue(maximumOuterWallFeed(baselineGcode) > 2_400f)
            assertEquals(2_400f, maximumOuterWallFeed(avoidedGcode), 0.1f)
        } finally {
            baseline.output.delete()
            avoided.output.delete()
        }
    }

    @Test
    fun multipleObjectsReachTheOrcaProjectAndSliceTogether() {
        val modelFile = fixtureModel()
        val model = inspectModel(modelFile.absolutePath)
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
    fun objectOutputControlsEmitFirmwareSpecificCancellationRanges() {
        val model = inspectModel(fixtureModel().absolutePath)
        val objects = listOf(
            ProjectObject("left-object", model, ModelTransform(offsetXmm = -18f)),
            ProjectObject("right-object", model, ModelTransform(offsetXmm = 18f)),
        )
        val base = SliceOptions()
            .selectPrinter(PrinterProfile.U1_04)
            .selectFilament(FilamentProfile.PLA)
            .selectQuality(QualityProfile.DRAFT)
            .copy(brimWidth = 0f, skirtLoops = 0)

        val klipper = OnDeviceSlicer.slice(
            objects,
            base.copy(
                gcodeFlavor = "klipper",
                gcodeSettings = GcodeSettings(labelObjects = true, excludeObjects = true),
            ),
        ).output.readText()
        val klipperStarts = klipper.lineSequence()
            .filter { it.startsWith("EXCLUDE_OBJECT_START NAME=") }
            .map { it.substringAfter('=') }
            .toSet()
        val klipperEnds = klipper.lineSequence()
            .filter { it.startsWith("EXCLUDE_OBJECT_END NAME=") }
            .map { it.substringAfter('=') }
            .toSet()
        assertEquals("Both Klipper objects need distinct cancellation ranges", 2, klipperStarts.size)
        assertEquals(klipperStarts, klipperEnds)
        assertTrue(klipper.contains("; printing object "))

        val marlin = OnDeviceSlicer.slice(
            objects,
            base.copy(
                gcodeFlavor = "marlin2",
                gcodeSettings = GcodeSettings(labelObjects = false, excludeObjects = true),
            ),
        ).output.readText()
        val marlinStarts = marlin.lineSequence()
            .mapNotNull { line -> Regex("^M486 S(\\d+)$").matchEntire(line)?.groupValues?.get(1) }
            .toSet()
        assertEquals("Both Marlin objects need distinct M486 ranges", 2, marlinStarts.size)
        assertTrue(marlin.lineSequence().any { it == "M486 S-1" })
        assertFalse(marlin.contains("; printing object "))

        val disabled = OnDeviceSlicer.slice(
            objects,
            base.copy(
                gcodeFlavor = "klipper",
                gcodeSettings = GcodeSettings(labelObjects = false, excludeObjects = false),
            ),
        ).output.readText()
        assertFalse(disabled.contains("; printing object "))
        assertFalse(disabled.contains("EXCLUDE_OBJECT_"))
    }

    @Test
    fun printSequenceChangesRealMultiObjectToolpathOrdering() {
        val model = inspectModel(fixtureModel().absolutePath)
        val objects = listOf(
            ProjectObject("first", model, ModelTransform(offsetXmm = -60f)),
            ProjectObject("second", model, ModelTransform(offsetXmm = 60f)),
        )
        val base = SliceOptions()
            .selectPrinter(
                PrinterProfile.U1_04.copy(
                    printingByObjectGcode = "M117 DUCKY_BETWEEN_OBJECTS",
                ),
            )
            .selectFilament(FilamentProfile.PLA)
            .selectQuality(QualityProfile.DRAFT)
            .copy(brimWidth = 0f, skirtLoops = 0)
        val layered = OnDeviceSlicer.slice(
            objects,
            base.copy(printSequence = "by layer", printOrder = "as_obj_list"),
        ).output.readText()
        val sequential = OnDeviceSlicer.slice(
            objects,
            base.copy(printSequence = "by object", printOrder = "default"),
        ).output.readText()
        val layeredStarts = layered.lineSequence().filter { it.startsWith("; printing object ") }.toList()
        val sequentialStarts = sequential.lineSequence().filter { it.startsWith("; printing object ") }.toList()
        fun objectName(marker: String): String = marker
            .removePrefix("; printing object ")
            .substringBefore(" id:")
        fun objectTransitions(markers: List<String>): Int = markers
            .map(::objectName)
            .zipWithNext()
            .count { (left, right) -> left != right }

        assertTrue(layered.contains("; print_sequence = by layer"))
        assertTrue(layered.contains("; print_order = as_obj_list"))
        assertTrue(
            "Object-list order must begin with the first project object",
            objectName(layeredStarts.first()).contains("slicer-input-0-"),
        )
        assertTrue(sequential.contains("; print_sequence = by object"))
        assertFalse(layered.lineSequence().any { it == "M117 DUCKY_BETWEEN_OBJECTS" })
        assertEquals(
            "The selected printer's between-object template must run exactly once",
            1,
            sequential.lineSequence().count { it == "M117 DUCKY_BETWEEN_OBJECTS" },
        )
        assertTrue(sequential.contains("; print_order = default"))
        assertTrue(
            "By-object output must finish an object instead of alternating objects every layer",
            objectTransitions(sequentialStarts) < objectTransitions(layeredStarts),
        )
        assertEquals("Both sequential objects must reach G-code", 2, sequentialStarts.map(::objectName).toSet().size)

        val unsafeSequential = runCatching {
            OnDeviceSlicer.slice(
                listOf(
                    ProjectObject("overlap-first", model, ModelTransform(offsetXmm = -5f)),
                    ProjectObject("overlap-second", model, ModelTransform(offsetXmm = 5f)),
                ),
                base.copy(printSequence = "by object"),
            )
        }
        assertTrue(
            "By-object mode must retain Orca's print-head clearance rejection",
            unsafeSequential.isFailure,
        )
    }

    @Test
    fun nozzleHeightChangesTheRealSequentialClearanceDecision() {
        val model = inspectModel(fixtureModel().absolutePath)
        val objects = listOf(
            ProjectObject("clearance-left", model, ModelTransform(offsetXmm = -32.5f)),
            ProjectObject("clearance-right", model, ModelTransform(offsetXmm = 32.5f)),
        )
        val base = SliceOptions()
            .selectPrinter(PrinterProfile.CUSTOM_CARTESIAN.copy(nozzleHeight = 4.76f))
            .selectFilament(FilamentProfile.GENERIC_PLA)
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                printSequence = "by object",
                skirtLoops = 1,
                skirtDistance = 5f,
                skirtHeight = 10,
                brimType = "no_brim",
                brimWidth = 0f,
                quality = QualityProfile.DRAFT.copy(skirtType = "perobject"),
            )

        val tallNozzle = OnDeviceSlicer.slice(objects, base)
        try {
            val gcode = tallNozzle.output.readText()
            assertTrue(gcode.contains("; nozzle_height = 4.76"))
            assertEquals(
                "The safe tall-nozzle arrangement must print both objects",
                2,
                gcode.lineSequence()
                    .filter { it.startsWith("; printing object ") }
                    .map { it.substringAfter("; printing object ").substringBefore(" id:") }
                    .toSet()
                    .size,
            )
        } finally {
            tallNozzle.output.delete()
        }

        val shortNozzle = runCatching {
            OnDeviceSlicer.slice(
                objects,
                base.copy(
                    printerProfile = base.printerProfile.copy(nozzleHeight = 2.5f),
                ),
            )
        }
        assertTrue(
            "The same by-object placement must be rejected when the shorter nozzle makes the per-object skirt enter the print-head clearance envelope",
            shortNozzle.isFailure,
        )
    }

    @Test
    fun machineOutputModesControlRealGcode() {
        val printer = PrinterProfile.CUSTOM_CARTESIAN.copy(
            gcodeFlavor = "marlin2",
            machineStartGcode = "",
            machineEndGcode = "",
            maxSpeedX = 241f,
            maxSpeedY = 242f,
            maxSpeedZ = 23f,
            maxSpeedE = 84f,
            maxAccelerationX = 4_210f,
            maxAccelerationY = 4_220f,
            maxAccelerationZ = 630f,
            maxAccelerationE = 6_410f,
        )
        val base = SliceOptions()
            .selectPrinter(printer)
            .selectQuality(QualityProfile.DRAFT)
        val enabled = OnDeviceSlicer.slice(
            fixtureModel(),
            base.selectPrinter(
                printer.copy(
                    useRelativeEDistances = true,
                    emitMachineLimitsToGcode = true,
                    disableM73 = false,
                ),
            ),
        ).output.readText()
        val disabled = OnDeviceSlicer.slice(
            fixtureModel(),
            base.selectPrinter(
                printer.copy(
                    useRelativeEDistances = false,
                    emitMachineLimitsToGcode = false,
                    disableM73 = true,
                ),
            ),
        ).output.readText()

        assertTrue(enabled.lineSequence().any { it == "M83 ; use relative distances for extrusion" })
        assertTrue(disabled.lineSequence().any { it == "M82 ; use absolute distances for extrusion" })
        assertTrue(enabled.contains("; use_relative_e_distances = 1"))
        assertTrue(disabled.contains("; use_relative_e_distances = 0"))
        assertTrue(enabled.contains("; emit_machine_limits_to_gcode = 1"))
        assertTrue(disabled.contains("; emit_machine_limits_to_gcode = 0"))
        assertTrue(enabled.lineSequence().any { it.startsWith("M201 ") })
        assertFalse(disabled.lineSequence().any { it.startsWith("M201 ") })
        assertTrue(enabled.contains("; disable_m73 = 0"))
        assertTrue(disabled.contains("; disable_m73 = 1"))
        assertTrue("Enabled remaining-time output must contain M73", enabled.contains("M73"))
        assertFalse(
            "Disabling remaining-time output must remove generated M73 commands",
            disabled.lineSequence().any { it.startsWith("M73 ") },
        )
    }

    @Test
    fun chamberAndFiltrationProfilesEmitOnlyForCapablePrinters() {
        val material = FilamentProfile.GENERIC_PLA.copy(
            softeningTemperature = 62,
            nozzleTemperatureRangeLow = 195,
            nozzleTemperatureRangeHigh = 245,
            chamberTemperatureControl = true,
            chamberTemperature = 55,
            airFiltration = true,
            duringPrintExhaustFanSpeed = 70,
            completePrintExhaustFanSpeed = 40,
        )
        val printer = PrinterProfile.CUSTOM_CARTESIAN.copy(
            gcodeFlavor = "marlin2",
            machineStartGcode = "",
            machineEndGcode = "",
        )
        val base = SliceOptions()
            .selectFilament(material)
            .selectQuality(QualityProfile.DRAFT)
        val capable = OnDeviceSlicer.slice(
            fixtureModel(),
            base.selectPrinter(
                printer.copy(
                    supportsChamberTemperatureControl = true,
                    supportsAirFiltration = true,
                ),
            ),
        )
        val incapable = OnDeviceSlicer.slice(
            fixtureModel(),
            base.selectPrinter(
                printer.copy(
                    supportsChamberTemperatureControl = false,
                    supportsAirFiltration = false,
                ),
            ),
        )

        try {
            val capableGcode = capable.output.readText()
            assertTrue(capableGcode.contains("; support_chamber_temp_control = 1"))
            assertTrue(capableGcode.contains("; support_air_filtration = 1"))
            assertTrue(capableGcode.contains("; temperature_vitrification = 62"))
            assertTrue(capableGcode.contains("; nozzle_temperature_range_low = 195"))
            assertTrue(capableGcode.contains("; nozzle_temperature_range_high = 245"))
            assertTrue(capableGcode.contains("; activate_chamber_temp_control = 1"))
            assertTrue(capableGcode.contains("; chamber_temperature = 55"))
            assertTrue(capableGcode.contains("; activate_air_filtration = 1"))
            assertTrue(capableGcode.contains("; during_print_exhaust_fan_speed = 70"))
            assertTrue(capableGcode.contains("; complete_print_exhaust_fan_speed = 40"))
            assertTrue(
                "A capable printer must receive Orca's blocking chamber warm-up",
                capableGcode.lineSequence().any { it.startsWith("M191 S55 ") },
            )
            assertTrue(
                "A capable printer must turn the chamber heater off after printing",
                capableGcode.lineSequence().any { it.startsWith("M141 S0;") },
            )
            assertTrue(
                "A capable printer must use the selected during-print exhaust speed",
                capableGcode.lineSequence().any { it == "M106 P3 S178" },
            )
            assertTrue(
                "A capable printer must use the selected post-print exhaust speed",
                capableGcode.lineSequence().any { it == "M106 P3 S102" },
            )

            val incapableGcode = incapable.output.readText()
            assertTrue(incapableGcode.contains("; support_chamber_temp_control = 0"))
            assertTrue(incapableGcode.contains("; support_air_filtration = 0"))
            assertTrue(incapableGcode.contains("; activate_chamber_temp_control = 0"))
            assertTrue(incapableGcode.contains("; activate_air_filtration = 0"))
            assertFalse(
                "A material profile must not send chamber commands to unsupported firmware",
                incapableGcode.lineSequence().any {
                    it.startsWith("M191 ") || it.startsWith("M141 ")
                },
            )
            assertFalse(
                "A material profile must not send exhaust commands to unsupported firmware",
                incapableGcode.lineSequence().any { it.startsWith("M106 P3 ") },
            )
        } finally {
            capable.output.delete()
            incapable.output.delete()
        }
    }

    @Test
    fun customPrinterGeometryAndMotionReachOrca() {
        val model = fixtureModel()
        val customPrinter = PrinterProfile.CUSTOM_CARTESIAN.copy(
            bedSizeX = 180f,
            bedSizeY = 190f,
            bedOriginX = -90f,
            bedOriginY = -95f,
            bedPolygon = listOf(90f, 0f, 180f, 95f, 90f, 190f, 0f, 95f),
            bedExcludeArea = listOf(0f, 0f, 18f, 0f, 18f, 28f, 0f, 28f),
            maxPrintHeight = 180f,
            machineStartGcode = "M117 DUCKY_START",
            machineEndGcode = "M117 DUCKY_END",
            gcodeFlavor = "marlin2",
            maxSpeedX = 240f,
            maxSpeedY = 250f,
            maxSpeedZ = 26f,
            maxSpeedE = 88f,
            maxAccelerationX = 4_200f,
            maxAccelerationY = 4_300f,
            maxAccelerationZ = 650f,
            maxAccelerationE = 6_800f,
            maxAccelerationExtruding = 3_100f,
            maxAccelerationRetracting = 3_200f,
            maxAccelerationTravel = 4_000f,
            maxJerkX = 7.1f,
            maxJerkY = 7.2f,
            maxJerkZ = 0.6f,
            maxJerkE = 4.4f,
            maxJunctionDeviation = 0.037f,
            extruderClearanceRadius = 71f,
            extruderClearanceHeightToRod = 29f,
            extruderClearanceHeightToLid = 119f,
        )
        val customFilament = FilamentProfile.GENERIC_PLA.copy(
            filamentStartGcode = "M117 DUCKY_FILAMENT_START",
            filamentEndGcode = "M117 DUCKY_FILAMENT_END",
        )
        val options = SliceOptions()
            .selectPrinter(customPrinter)
            .selectFilament(customFilament)
            .selectQuality(QualityProfile.STANDARD)
        val outcome = OnDeviceSlicer.slice(model, options)
        val gcode = outcome.output.readText()
        val printableArea = gcode.lineSequence().firstOrNull { it.startsWith("; printable_area =") }.orEmpty()
        val excludedArea = gcode.lineSequence()
            .firstOrNull { it.startsWith("; bed_exclude_area =") }
            .orEmpty()

        assertTrue("The original negative X origin must reach Orca", printableArea.contains("-90x0"))
        assertTrue("The original negative Y origin must reach Orca", printableArea.contains("0x-95"))
        assertTrue("Custom bed width must reach Orca", printableArea.contains("90x0"))
        assertTrue("Custom bed depth must reach Orca", printableArea.contains("0x95"))
        assertTrue("Custom unavailable bed X must reach Orca", excludedArea.contains("-72x-95"))
        assertTrue("Custom unavailable bed Y must reach Orca", excludedArea.contains("-90x-67"))
        assertTrue("Custom height must reach Orca", gcode.contains("; printable_height = 180"))
        assertTrue("Custom X speed must reach Orca", gcode.contains("; machine_max_speed_x = 240,240"))
        assertTrue("Custom Y speed must reach Orca", gcode.contains("; machine_max_speed_y = 250,250"))
        assertTrue("Custom Z speed must reach Orca", gcode.contains("; machine_max_speed_z = 26,26"))
        assertTrue("Custom E speed must reach Orca", gcode.contains("; machine_max_speed_e = 88,88"))
        assertTrue("Custom X acceleration must reach Orca", gcode.contains("; machine_max_acceleration_x = 4200,4200"))
        assertTrue("Custom Y acceleration must reach Orca", gcode.contains("; machine_max_acceleration_y = 4300,4300"))
        assertTrue("Custom Z acceleration must reach Orca", gcode.contains("; machine_max_acceleration_z = 650,650"))
        assertTrue("Custom E acceleration must reach Orca", gcode.contains("; machine_max_acceleration_e = 6800,6800"))
        assertTrue(
            "Custom print acceleration must reach Orca",
            gcode.contains("; machine_max_acceleration_extruding = 3100,3100"),
        )
        assertTrue(
            "Custom retracting acceleration must reach Orca",
            gcode.contains("; machine_max_acceleration_retracting = 3200,3200"),
        )
        assertTrue(
            "Custom travel acceleration must reach Orca",
            gcode.contains("; machine_max_acceleration_travel = 4000,4000"),
        )
        assertTrue("Custom X jerk must reach Orca", gcode.contains("; machine_max_jerk_x = 7.1,7.1"))
        assertTrue("Custom Y jerk must reach Orca", gcode.contains("; machine_max_jerk_y = 7.2,7.2"))
        assertTrue("Custom Z jerk must reach Orca", gcode.contains("; machine_max_jerk_z = 0.6,0.6"))
        assertTrue("Custom E jerk must reach Orca", gcode.contains("; machine_max_jerk_e = 4.4,4.4"))
        assertTrue(
            "Custom junction deviation must reach Orca",
            gcode.contains("; machine_max_junction_deviation = 0.037,0.037"),
        )
        assertTrue(
            "Marlin 2 must receive the configured junction deviation",
            gcode.lineSequence().any { it.startsWith("M205 J0.037") },
        )
        assertTrue("Print-head radius must reach Orca", gcode.contains("; extruder_clearance_radius = 71"))
        assertTrue("Print-head rod clearance must reach Orca", gcode.contains("; extruder_clearance_height_to_rod = 29"))
        assertTrue("Print-head lid clearance must reach Orca", gcode.contains("; extruder_clearance_height_to_lid = 119"))
        assertTrue("Custom G-code flavor must reach Orca", gcode.contains("; gcode_flavor = marlin2"))
        assertTrue("Custom start G-code must reach Orca", gcode.lineSequence().any { it == "M117 DUCKY_START" })
        assertTrue("Custom end G-code must reach Orca", gcode.lineSequence().any { it == "M117 DUCKY_END" })
        assertTrue(
            "Filament start G-code must reach Orca",
            gcode.lineSequence().any { it == "M117 DUCKY_FILAMENT_START" },
        )
        assertTrue(
            "Filament end G-code must reach Orca",
            gcode.lineSequence().any { it == "M117 DUCKY_FILAMENT_END" },
        )
        val bounds = outerWallBounds(outcome.output)
        assertTrue("Centered-machine G-code must retain negative X coordinates", bounds.minX < -1f)
        assertTrue("Centered-machine G-code must retain positive X coordinates", bounds.maxX > 1f)
        assertTrue("Centered-machine G-code must retain negative Y coordinates", bounds.minY < -1f)
        assertTrue("Centered-machine G-code must retain positive Y coordinates", bounds.maxY > 1f)
    }

    @Test
    fun repRapFirmwareUsesNativeMotionAndTemperatureCommands() {
        val model = fixtureModel()
        val printer = PrinterProfile.CUSTOM_CARTESIAN.copy(
            id = "instrumented-reprapfirmware",
            name = "Instrumented RepRapFirmware",
            gcodeFlavor = "reprapfirmware",
            machineStartGcode = "",
            maxSpeedX = 301f,
            maxSpeedY = 302f,
            maxSpeedZ = 16f,
            maxSpeedE = 26f,
            maxAccelerationX = 3_100f,
            maxAccelerationY = 3_200f,
            maxAccelerationZ = 210f,
            maxAccelerationE = 2_100f,
            maxAccelerationExtruding = 3_300f,
            maxAccelerationRetracting = 2_200f,
            maxAccelerationTravel = 3_400f,
            maxJerkX = 8.1f,
            maxJerkY = 8.2f,
            maxJerkZ = 0.5f,
            maxJerkE = 5.1f,
        )
        val outcome = OnDeviceSlicer.slice(
            model,
            SliceOptions()
                .selectPrinter(printer)
                .selectFilament(FilamentProfile.GENERIC_PLA)
                .selectQuality(QualityProfile.STANDARD),
        )
        try {
            val gcode = outcome.output.readText()
            assertTrue(gcode.contains("; gcode_flavor = reprapfirmware"))
            assertTrue(gcode.lineSequence().any { it == "M201 X3100 Y3200 Z210 E2100" })
            assertTrue(gcode.lineSequence().any { it == "M203 X18060 Y18120 Z960 E1560" })
            assertTrue(
                gcode.lineSequence().any {
                    it == "M204 P3300 T3400 ; sets acceleration (P, T), mm/sec^2"
                },
            )
            assertTrue(
                gcode.lineSequence().any {
                    it == "M566 X486.00 Y492.00 Z30.00 E306.00 ; sets the jerk limits, mm/min"
                },
            )
            assertTrue(
                "RepRapFirmware must use G10 for tool temperature; commands=" +
                    gcode.lineSequence()
                        .filter { it.startsWith("G10") || it.startsWith("M104") || it.startsWith("M109") }
                        .take(8)
                        .joinToString(" | "),
                gcode.lineSequence().any { it.startsWith("G10 S") },
            )
            assertTrue("RepRapFirmware output must contain extrusion", gcode.contains(";TYPE:Outer wall"))
        } finally {
            outcome.output.delete()
            model.delete()
        }
    }

    @Test
    fun representativeMarlinAndKlipperFirmwareContractsReachOrca() {
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
                    "is_infill_first" to "0",
                    "infill_wall_overlap" to "25%",
                    "top_bottom_infill_wall_overlap" to "25%",
                    "internal_bridge_speed" to "150%",
                    "bridge_acceleration" to "50%",
                    "sparse_infill_acceleration" to "100%",
                    "internal_solid_infill_acceleration" to "100%",
                    "infill_combination" to "0",
                    "thick_internal_bridges" to "1",
                    "minimum_sparse_infill_area" to "10",
                    "infill_anchor" to "400%",
                    "gap_fill_target" to "nowhere",
                    "elefant_foot_compensation" to "0.1",
                    "ensure_vertical_shell_thickness" to "ensure_all",
                    "reduce_infill_retraction" to "1",
                    "small_perimeter_speed" to "50%",
                    "resolution" to "0.012",
                    "seam_gap" to "10%",
                    "wipe_speed" to "80%",
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
                    "is_infill_first" to "0",
                    "infill_wall_overlap" to "25%",
                    "internal_bridge_speed" to "50",
                    "bridge_density" to "100%",
                    "infill_combination_max_layer_height" to "100%",
                    "infill_direction" to "45",
                    "solid_infill_direction" to "45",
                    "infill_anchor_max" to "20",
                    "gap_fill_target" to "nowhere",
                    "detect_narrow_internal_solid_infill" to "1",
                    "reduce_infill_retraction" to "1",
                    "small_perimeter_speed" to "50%",
                    "resolution" to "0.012",
                    "seam_gap" to "10%",
                    "wall_direction" to "auto",
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
                    "support_style" to "grid",
                    "is_infill_first" to "0",
                    "infill_wall_overlap" to "15%",
                    "internal_bridge_speed" to "150%",
                    "internal_bridge_density" to "100%",
                    "bridge_no_support" to "0",
                    "gap_fill_target" to "topbottom",
                    "ensure_vertical_shell_thickness" to "ensure_moderate",
                    "elefant_foot_compensation" to "0.15",
                    "max_bridge_length" to "10",
                    "reduce_infill_retraction" to "1",
                    "small_perimeter_speed" to "50%",
                    "slowdown_for_curled_perimeters" to "0",
                    "resolution" to "0.012",
                    "seam_gap" to "0",
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

    @Test
    fun representativeNozzleAndMaterialProfilesProduceRealGcode() {
        val model = fixtureModel()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = OrcaProfileCatalog(context).load()
        data class Contract(
            val printerName: String,
            val processName: String,
            val filamentName: String,
            val nozzleDiameter: Float,
            val material: String,
            val flavor: String,
        )
        val contracts = listOf(
            Contract(
                printerName = "Creality Ender-3 0.2 nozzle",
                processName = "0.12mm Fine @Creality Ender3 0.2",
                filamentName = "Creality Generic PLA",
                nozzleDiameter = 0.2f,
                material = "PLA",
                flavor = "marlin",
            ),
            Contract(
                printerName = "Elegoo Centauri 0.6 nozzle",
                processName = "0.18mm Fine @Elegoo C 0.6 nozzle",
                filamentName = "Elegoo PETG PRO @EC",
                nozzleDiameter = 0.6f,
                material = "PETG",
                flavor = "klipper",
            ),
            Contract(
                printerName = "Qidi Q1 Pro 0.8 nozzle",
                processName = "0.24mm Standard @Qidi Q1 Pro 0.8 nozzle",
                filamentName = "Generic ABS @System",
                nozzleDiameter = 0.8f,
                material = "ABS",
                flavor = "klipper",
            ),
        )

        contracts.forEach { contract ->
            val printer = catalog.printers.single {
                it.name == contract.printerName && it.id.startsWith("orca-printer-")
            }
            val process = catalog.slicing.single {
                it.name == contract.processName && it.id.startsWith("orca-process-")
            }
            val filament = catalog.filaments.single {
                it.name == contract.filamentName && it.id.startsWith("orca-filament-")
            }
            assertEquals(contract.nozzleDiameter, printer.nozzleDiameter, 0.001f)
            assertEquals(contract.nozzleDiameter, process.nozzleDiameter, 0.001f)
            assertTrue(process.compatiblePrinters.matchesPrinter(printer))
            assertTrue(filament.compatiblePrinters.matchesPrinter(printer))
            assertEquals(contract.material, filament.nativeName)

            val outcome = OnDeviceSlicer.slice(
                model,
                SliceOptions()
                    .selectPrinter(printer)
                    .selectFilament(filament)
                    .selectQuality(process),
            )
            try {
                assertTrue("${contract.printerName} must produce layers", outcome.layers > 0)
                assertTrue("${contract.printerName} must produce non-trivial G-code", outcome.output.length() > 1_000L)
                val gcode = outcome.output.readText()
                val settings = gcode.lineSequence()
                    .filter { it.startsWith("; ") && it.contains(" = ") }
                    .associate { line ->
                        line.removePrefix("; ").split(" = ", limit = 2).let { it[0] to it[1] }
                    }
                assertEquals(contract.flavor, settings["gcode_flavor"])
                assertEquals(contract.material, settings["filament_type"])
                assertEquals(contract.nozzleDiameter, settings.getValue("nozzle_diameter").toFloat(), 0.001f)
                assertEquals(process.layerHeightMm, settings.getValue("layer_height").toFloat(), 0.001f)
                assertEquals(filament.flowRatio, settings.getValue("filament_flow_ratio").toFloat(), 0.001f)
                assertEquals(
                    filament.maxVolumetricSpeed,
                    settings.getValue("filament_max_volumetric_speed").toFloat(),
                    0.001f,
                )
                assertTrue("${contract.printerName} must contain outer-wall extrusion", gcode.contains(";TYPE:Outer wall"))
            } finally {
                outcome.output.delete()
            }
        }
    }

    private fun wipeTowerExtrusion(gcode: String): Float {
        val extrusion = Regex("(?:^|\\s)E(-?[0-9]+(?:\\.[0-9]+)?)")
        var insideToolChange = false
        var total = 0f
        gcode.lineSequence().forEach { line ->
            when (line) {
                "; WIPE_TOWER_START" -> insideToolChange = true
                "; WIPE_TOWER_END" -> insideToolChange = false
                else -> if (insideToolChange) {
                    val value = extrusion.find(line)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                    if (value > 0f) total += value
                }
            }
        }
        return total
    }

    private fun supportExtrusionMotion(gcode: String): List<String> {
        var support = false
        return gcode.lineSequence().mapNotNull { raw ->
            val line = raw.trim()
            if (line.startsWith(";TYPE:") || line.startsWith("; FEATURE:")) {
                support = line.substringAfter(':').contains("support", ignoreCase = true)
            }
            line.substringBefore(';').trim().takeIf { command ->
                support &&
                    (command.startsWith("G1 ") || command.startsWith("G2 ") || command.startsWith("G3 ")) &&
                    command.contains(" E") &&
                    (command.contains(" X") || command.contains(" Y") ||
                        command.contains(" I") || command.contains(" J"))
            }
        }.toList()
    }

    private fun filamentUsedMm(gcode: String): List<Float> = gcode.lineSequence()
        .first { it.startsWith("; filament used [mm] = ") }
        .substringAfter("=")
        .split(',')
        .map { it.trim().toFloat() }

    private fun firstXyMoveAfterToolOne(gcode: String): Pair<Float, Float> {
        var afterToolOne = false
        gcode.lineSequence().forEach { line ->
            if (line == "T1") {
                afterToolOne = true
            } else if (afterToolOne && (line.startsWith("G0 ") || line.startsWith("G1 "))) {
                val values = line.split(' ').mapNotNull { token ->
                    when {
                        token.startsWith("X") -> "X" to token.drop(1).toFloatOrNull()
                        token.startsWith("Y") -> "Y" to token.drop(1).toFloatOrNull()
                        else -> null
                    }
                }.filter { it.second != null }.associate { it.first to requireNotNull(it.second) }
                if (values.containsKey("X") && values.containsKey("Y")) {
                    return values.getValue("X") to values.getValue("Y")
                }
            }
        }
        error("No XY move found after T1")
    }
}
