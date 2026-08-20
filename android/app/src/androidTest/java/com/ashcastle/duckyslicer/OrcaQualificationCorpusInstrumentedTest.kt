package com.ashcastle.duckyslicer

import android.content.Context
import android.content.Intent
import android.os.Debug
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrcaQualificationCorpusInstrumentedTest {
    @Test
    fun corpusProducesComparablePinnedOrcaReport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val reportFile = File(context.filesDir, REPORT_PATH)
        reportFile.delete()
        val manifestBytes = instrumentation.context.assets.open(MANIFEST_ASSET).use { it.readBytes() }
        val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
        assertEquals(1, manifest.getInt("schemaVersion"))
        assertEquals(
            BuildConfig.ORCA_ENGINE_REVISION,
            manifest.getJSONObject("engine").getString("revision"),
        )

        val profile = manifest.getJSONObject("effectiveProfile")
        val baseOptions = qualificationOptions(profile)
        val fingerprintKeys = manifest.getJSONArray("profileFingerprintKeys").strings()
        val requestedCase = InstrumentationRegistry.getArguments().getString("corpusCase")
            ?: "simple-part"
        val arguments = InstrumentationRegistry.getArguments()
        val retainGcode = arguments
            .getString("retainCorpusGcode")
            ?.toBooleanStrictOrNull()
            ?: false
        val measurePhysical = arguments
            .getString("measurePhysical")
            ?.toBooleanStrictOrNull()
            ?: false
        val measurementWidth = arguments.boundedInt(
            "measurementWidth",
            DEFAULT_FRAMEBUFFER_WIDTH,
            128,
            DEFAULT_FRAMEBUFFER_WIDTH,
        )
        val measurementHeight = arguments.boundedInt(
            "measurementHeight",
            DEFAULT_FRAMEBUFFER_HEIGHT,
            128,
            DEFAULT_FRAMEBUFFER_HEIGHT,
        )
        val measurementFrameCount = arguments.boundedInt(
            "measurementFrameCount",
            MEASURED_FRAME_COUNT,
            2,
            MEASURED_FRAME_COUNT,
        )
        val qualificationCycles = arguments.boundedInt(
            "qualificationCycles",
            1,
            1,
            MAXIMUM_QUALIFICATION_CYCLES,
        )
        require(
            qualificationCycles == 1 ||
                (measurePhysical && requestedCase == "dense-preview" && !retainGcode),
        ) { "Repeated qualification is available only for physical dense Preview measurement" }
        val cases = manifest.getJSONArray("cases").objects().filter { case ->
            case.getString("id") == requestedCase
        }
        assertEquals("Unknown qualification case", 1, cases.size)
        val results = JSONArray()
        val temporaryModels = mutableListOf<File>()
        try {
            cases.forEach { case ->
                val identifier = case.getString("id")
                val modelPaths = case.getJSONArray("models").strings()
                val offsets = case.optJSONArray("offsetsXmm")
                val modelFilamentSlots = case.optJSONArray("modelFilamentSlots")
                val objects = modelPaths.mapIndexed { index, assetPath ->
                    val source = File(context.cacheDir, "qualification-$identifier-$index.stl")
                    instrumentation.context.assets.open(assetPath).use { input ->
                        source.outputStream().use(input::copyTo)
                    }
                    temporaryModels += source
                    val model = inspectModel(source.absolutePath)
                    ProjectObject(
                        id = "$identifier-$index",
                        model = model,
                        transform = ModelTransform(
                            offsetXmm = offsets?.getDouble(index)?.toFloat() ?: 0f,
                        ),
                        filamentSlot = modelFilamentSlots?.getInt(index) ?: 0,
                    )
                }
                val options = qualificationOptions(baseOptions, case)
                val soakCycles = JSONArray()
                var finalMetrics: JSONObject? = null
                repeat(qualificationCycles) { cycleIndex ->
                    val metrics = qualificationCycle(
                        context = context,
                        identifier = identifier,
                        objects = objects,
                        options = options,
                        fingerprintKeys = fingerprintKeys,
                        expected = case.getJSONObject("expected"),
                        retainGcode = retainGcode,
                        measurePhysical = measurePhysical,
                        measurementWidth = measurementWidth,
                        measurementHeight = measurementHeight,
                        measurementFrameCount = measurementFrameCount,
                    )
                    if (qualificationCycles > 1) {
                        Runtime.getRuntime().gc()
                        System.runFinalization()
                        SystemClock.sleep(SOAK_SETTLE_MILLIS)
                        val uiPssKb = Debug.getPss()
                        check(uiPssKb > 0) { "Qualification could not measure UI process PSS" }
                        soakCycles.put(
                            JSONObject(metrics.toString())
                                .put("cycle", cycleIndex + 1)
                                .put("workerPid", SlicerProcessClient.lastWorkerPid())
                                .put("uiPssKb", uiPssKb),
                        )
                    }
                    finalMetrics = metrics
                }
                val completed = checkNotNull(finalMetrics)
                if (qualificationCycles > 1) completed.put("soakCycles", soakCycles)
                results.put(completed)
            }

            val report = JSONObject()
                .put("schemaVersion", 1)
                .put("source", "android")
                .put("engineRevision", BuildConfig.ORCA_ENGINE_REVISION)
                .put("runtimeVersion", NativeLibrary().getCoreVersion())
                .put("manifestSha256", sha256(manifestBytes))
                .put("effectiveProfile", profile)
                .put("physicalMeasurementRequested", measurePhysical)
                .put("cases", results)
            reportFile.parentFile?.mkdirs()
            reportFile.writeText(report.toString(2) + "\n")
            assertTrue("Qualification report must be retained for the local runner", reportFile.isFile)
        } finally {
            temporaryModels.forEach(File::delete)
        }
    }

    private fun qualificationCycle(
        context: Context,
        identifier: String,
        objects: List<ProjectObject>,
        options: SliceOptions,
        fingerprintKeys: List<String>,
        expected: JSONObject,
        retainGcode: Boolean,
        measurePhysical: Boolean,
        measurementWidth: Int,
        measurementHeight: Int,
        measurementFrameCount: Int,
    ): JSONObject {
        val sliceStarted = SystemClock.elapsedRealtimeNanos()
        val outcome = OnDeviceSlicer.slice(objects, options)
        return try {
            val sliceElapsedMs = elapsedMillis(sliceStarted)
            if (retainGcode) {
                val retained = File(context.filesDir, "$GCODE_DIRECTORY/$identifier.gcode")
                retained.parentFile?.mkdirs()
                val staging = File(retained.parentFile, ".${retained.name}.tmp")
                staging.delete()
                outcome.output.copyTo(staging, overwrite = true)
                check(staging.renameTo(retained)) { "Could not retain qualification G-code" }
            }
            val previewStarted = SystemClock.elapsedRealtimeNanos()
            val preview = loadGcodePreview(outcome.output.absolutePath, 0, Int.MAX_VALUE)
            val previewParseElapsedMs = elapsedMillis(previewStarted)
            val renderMetrics = if (measurePhysical && identifier == "dense-preview") {
                benchmarkDensePreview(
                    preview,
                    options,
                    measurementWidth,
                    measurementHeight,
                    measurementFrameCount,
                )
            } else {
                null
            }
            metrics(
                identifier,
                objects.size,
                outcome,
                preview,
                fingerprintKeys,
                sliceElapsedMs,
                previewParseElapsedMs,
                renderMetrics,
            ).also { metrics ->
                enforce(expected, metrics)
            }
        } finally {
            outcome.output.delete()
        }
    }

    private fun qualificationOptions(profile: JSONObject): SliceOptions {
        assertEquals(PrinterProfile.U1_04.id, profile.getString("printerId"))
        assertEquals(QualityProfile.STANDARD.id, profile.getString("processId"))
        assertEquals(FilamentProfile.GENERIC_PLA.id, profile.getString("filamentId"))
        val filament = FilamentProfile.GENERIC_PLA.copy(
            compatiblePrinters = listOf(PrinterProfile.U1_04.name),
        )
        val options = SliceOptions()
            .selectPrinter(PrinterProfile.U1_04)
            .selectFilament(filament)
            .selectQuality(QualityProfile.STANDARD)
            .copy(brimType = "no_brim", brimWidth = 0f, skirtLoops = 0)
        assertEquals(profile.getDouble("nozzleDiameterMm").toFloat(), options.nozzleDiameter)
        assertEquals(profile.getDouble("layerHeightMm").toFloat(), options.layerHeight)
        assertEquals(profile.getDouble("firstLayerHeightMm").toFloat(), options.firstLayerHeight)
        assertEquals(profile.getInt("perimeters"), options.perimeters)
        assertEquals(profile.getDouble("fillDensity").toFloat(), options.fillDensity)
        assertEquals(profile.getString("fillPattern"), options.fillPattern)
        assertEquals(profile.getInt("topSolidLayers"), options.topSolidLayers)
        assertEquals(profile.getInt("bottomSolidLayers"), options.bottomSolidLayers)
        assertEquals(profile.getInt("nozzleTemperatureC"), options.nozzleTemp)
        assertEquals(profile.getInt("bedTemperatureC"), options.bedTemp)
        assertEquals(profile.getDouble("flowRatio").toFloat(), options.flowRatio)
        assertEquals(profile.getDouble("maxVolumetricSpeed").toFloat(), options.maxVolumetricSpeed)
        assertEquals(profile.getString("wallGenerator"), options.wallGenerator)
        assertEquals(profile.getString("brimType"), options.brimType)
        assertEquals(profile.getDouble("brimWidthMm").toFloat(), options.brimWidth)
        assertEquals(profile.getInt("skirtLoops"), options.skirtLoops)
        return options
    }

    private fun qualificationOptions(base: SliceOptions, case: JSONObject): SliceOptions {
        val filamentIds = case.optJSONArray("filamentIds")?.strings() ?: listOf("generic-pla")
        val filaments = filamentIds.map { identifier ->
            val filament = when (identifier) {
                "generic-pla" -> FilamentProfile.GENERIC_PLA
                "generic-petg" -> FilamentProfile.GENERIC_PETG
                else -> error("Unsupported qualification filament: $identifier")
            }
            filament.copy(compatiblePrinters = listOf(PrinterProfile.U1_04.name))
        }
        val feature = case.optJSONObject("featureFilaments")?.let { routing ->
            FeatureFilamentSettings(
                infillOverrideEnabled = routing.getBoolean("infillOverrideEnabled"),
                baseFirstLayers = routing.getInt("baseFirstLayers"),
                baseLastLayers = routing.getInt("baseLastLayers"),
                sparseInfillFilament = routing.getInt("sparseInfillFilament"),
                wallFilament = routing.getInt("wallFilament"),
                solidInfillFilament = routing.getInt("solidInfillFilament"),
            )
        } ?: FeatureFilamentSettings()
        return base
            .selectFilament(filaments.first())
            .copy(
                filamentSlots = filaments,
                supportEnabled = case.optBoolean("supportEnabled", false),
                supportType = case.optString("supportType", "normal(auto)"),
                supportStyle = case.optString("supportStyle", "default"),
                supportFilament = case.optInt("supportFilament", 0),
                supportInterfaceFilament = case.optInt("supportInterfaceFilament", 0),
                supportInterfaceTopLayers = case.optInt(
                    "supportInterfaceTopLayers",
                    base.supportInterfaceTopLayers,
                ),
                supportInterfaceBottomLayers = case.optInt(
                    "supportInterfaceBottomLayers",
                    base.supportInterfaceBottomLayers,
                ),
                featureFilaments = feature,
                wipeTowerEnabled = false,
            )
    }

    private fun metrics(
        identifier: String,
        modelCount: Int,
        outcome: SliceOutcome,
        preview: GcodeLayerPreview,
        fingerprintKeys: List<String>,
        sliceElapsedMs: Double,
        previewParseElapsedMs: Double,
        renderMetrics: JSONObject?,
    ): JSONObject {
        val analysis = analyzeGcode(outcome.output, fingerprintKeys)
        val roleSegments = JSONObject()
        ROLE_NAMES.forEachIndexed { index, role ->
            roleSegments.put(role, preview.roleSegmentCounts[index])
        }
        val roleMotionJson = JSONObject()
        val roleLayerJson = JSONObject()
        val roleFirstLayerJson = JSONObject()
        val roleLastLayerJson = JSONObject()
        val roleExtrusionJson = JSONObject()
        val roleToolsJson = JSONObject()
        val roleToolExtrusionJson = JSONObject()
        ROLE_NAMES.forEach { role ->
            roleMotionJson.put(role, analysis.roleMotions.getValue(role))
            roleLayerJson.put(role, analysis.roleLayers.getValue(role))
            roleFirstLayerJson.put(role, analysis.roleFirstLayers.getValue(role))
            roleLastLayerJson.put(role, analysis.roleLastLayers.getValue(role))
            roleExtrusionJson.put(role, analysis.roleExtrusionMm.getValue(role))
            roleToolsJson.put(role, JSONArray(analysis.roleTools.getValue(role).sorted()))
            roleToolExtrusionJson.put(
                role,
                JSONObject().also { tools ->
                    analysis.roleToolExtrusionMm.getValue(role).toSortedMap().forEach { (tool, value) ->
                        tools.put(tool.toString(), value)
                    }
                },
            )
        }
        val coveredZ = HashSet<Int>()
        preview.segments.indices.step(GcodeLayerPreview.SEGMENT_STRIDE).forEach { offset ->
            coveredZ += (preview.segments[offset + 4] * 1_000f).roundToInt()
        }
        assertEquals(
            "Every pinned profile key must be observable in G-code",
            fingerprintKeys.sorted(),
            analysis.profileValues.keys.sorted(),
        )
        val profileJson = JSONObject().also { output ->
            fingerprintKeys.sorted().forEach { key ->
                output.put(key, analysis.profileValues.getValue(key))
            }
        }
        val fingerprint = fingerprintKeys.sorted().joinToString("\n") { key ->
            "$key=${analysis.profileValues.getValue(key)}"
        }.toByteArray()
        return JSONObject()
            .put("id", identifier)
            .put("modelCount", modelCount)
            .put("layers", outcome.layers)
            .put("sliceElapsedMs", sliceElapsedMs)
            .put("estimatedSeconds", outcome.estimatedSeconds.toDouble())
            .put("filamentMm", outcome.filamentMm.toDouble())
            .put("filamentGrams", outcome.filamentGrams.toDouble())
            .put("gcodeBytes", outcome.output.length())
            .put("gcodeSha256", sha256(outcome.output))
            .put("extrusionMotions", analysis.extrusionMotions)
            .put("extrusionXSpanMm", analysis.extrusionXSpanMm)
            .put("emittedLayers", analysis.emittedLayers)
            .put("roleMotions", roleMotionJson)
            .put("roleLayers", roleLayerJson)
            .put("roleFirstLayers", roleFirstLayerJson)
            .put("roleLastLayers", roleLastLayerJson)
            .put("roleExtrusionMm", roleExtrusionJson)
            .put("usedTools", JSONArray(analysis.usedTools.sorted()))
            .put("toolChanges", analysis.toolChanges)
            .put("roleTools", roleToolsJson)
            .put("roleToolExtrusionMm", roleToolExtrusionJson)
            .put("supportGeometryFingerprint", analysis.supportGeometryFingerprint)
            .put("previewLayerCount", preview.layerCount)
            .put("previewStartLayer", preview.startLayer)
            .put("previewEndLayer", preview.endLayer)
            .put("previewSegments", preview.segments.size / GcodeLayerPreview.SEGMENT_STRIDE)
            .put("previewParseElapsedMs", previewParseElapsedMs)
            .put("previewCoveredZ", coveredZ.size)
            .put(
                "previewLayerCoverage",
                if (preview.layerCount == 0) 0.0 else coveredZ.size.toDouble() / preview.layerCount,
            )
            .put("roleSegments", roleSegments)
            .put("profileFingerprint", sha256(fingerprint))
            .put("profileValues", profileJson)
            .also { metrics -> renderMetrics?.let { metrics.put("previewRender", it) } }
    }

    private fun benchmarkDensePreview(
        preview: GcodeLayerPreview,
        options: SliceOptions,
        width: Int,
        height: Int,
        frameCount: Int,
    ): JSONObject {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val session = PreviewPerformanceHarness.begin(
            PreviewPerformanceRequest(preview, options, width, height, frameCount),
        )
        val intent = Intent(instrumentation.targetContext, PreviewPerformanceHarnessActivity::class.java)
        val result = ActivityScenario.launch<PreviewPerformanceHarnessActivity>(intent).use {
            session.await(PREVIEW_MEASUREMENT_TIMEOUT_SECONDS)
        }
        val tiers = JSONObject()
        result.tiers.forEach { (detail, metrics) ->
            tiers.put(
                detail.name,
                JSONObject()
                    .put("framebufferWidth", metrics.framebufferWidth)
                    .put("framebufferHeight", metrics.framebufferHeight)
                    .put("interactionFramebufferWidth", metrics.interactionFramebufferWidth)
                    .put("interactionFramebufferHeight", metrics.interactionFramebufferHeight)
                    .put("firstFrameMs", metrics.firstFrameMs)
                    .put("settledFrameP50Ms", metrics.settledFrameP50Ms)
                    .put("settledFrameP95Ms", metrics.settledFrameP95Ms)
                    .put("interactionFrameP50Ms", metrics.interactionFrameP50Ms)
                    .put("interactionFrameP95Ms", metrics.interactionFrameP95Ms)
                    .put("settledCompletionP50Ms", metrics.settledCompletionP50Ms)
                    .put("settledCompletionP95Ms", metrics.settledCompletionP95Ms)
                    .put("interactionCompletionP50Ms", metrics.interactionCompletionP50Ms)
                    .put("interactionCompletionP95Ms", metrics.interactionCompletionP95Ms)
                    .put("settledDrawSubmitP50Ms", metrics.settledDrawSubmitP50Ms)
                    .put("settledDrawSubmitP95Ms", metrics.settledDrawSubmitP95Ms)
                    .put("interactionDrawSubmitP50Ms", metrics.interactionDrawSubmitP50Ms)
                    .put("interactionDrawSubmitP95Ms", metrics.interactionDrawSubmitP95Ms)
                    .put("geometryBuildMs", metrics.geometryBuildMs)
                    .put("renderPlanMs", metrics.renderPlanMs)
                    .put("geometryPackMs", metrics.geometryPackMs)
                    .put("geometryUploadMs", metrics.geometryUploadMs)
                    .put("geometryUploads", metrics.geometryUploads),
            )
        }
        val automaticMetrics = JSONObject(tiers.getJSONObject(result.automaticDetail.name).toString())
        return automaticMetrics
            .put("framebufferWidth", result.framebufferWidth)
            .put("framebufferHeight", result.framebufferHeight)
            .put("frameCountPerPhase", result.frameCountPerPhase)
            .put("detail", result.automaticDetail.name)
            .put("automaticDetail", result.automaticDetail.name)
            .put("gpuRenderer", result.gpuRenderer)
            .put("measurementSurface", "foreground-glsurfaceview")
            .put("tiers", tiers)
    }

    private fun elapsedMillis(startedNanos: Long): Double =
        (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000.0

    private fun android.os.Bundle.boundedInt(
        key: String,
        default: Int,
        minimum: Int,
        maximum: Int,
    ): Int = getString(key)?.toIntOrNull()?.coerceIn(minimum, maximum) ?: default

    private fun enforce(expected: JSONObject, metrics: JSONObject) {
        val identifier = metrics.getString("id")
        fun minimum(key: String, actualKey: String = key) {
            val actual = metrics.getDouble(actualKey)
            val required = expected.getDouble(key)
            assertTrue(
                "$identifier must satisfy $key: actual=$actual required=$required",
                actual >= required,
            )
        }
        val layers = metrics.getInt("layers")
        val minimumLayers = expected.getInt("minLayers")
        val maximumLayers = expected.getInt("maxLayers")
        assertTrue("$identifier has too few layers: actual=$layers minimum=$minimumLayers", layers >= minimumLayers)
        assertTrue("$identifier has too many layers: actual=$layers maximum=$maximumLayers", layers <= maximumLayers)
        minimum("minGcodeBytes", "gcodeBytes")
        minimum("minExtrusionMotions", "extrusionMotions")
        minimum("minPreviewSegments", "previewSegments")
        minimum("minPreviewLayerCoverage", "previewLayerCoverage")
        minimum("minExtrusionXSpanMm", "extrusionXSpanMm")
        assertTrue(
            "$identifier Preview must expose a bounded non-empty layer range",
            metrics.getInt("previewLayerCount") in 1..metrics.getInt("layers"),
        )
        assertEquals(metrics.getInt("emittedLayers"), metrics.getInt("previewLayerCount"))
        assertEquals(0, metrics.getInt("previewStartLayer"))
        assertEquals(metrics.getInt("previewLayerCount") - 1, metrics.getInt("previewEndLayer"))
        val roleSegments = metrics.getJSONObject("roleSegments")
        expected.getJSONArray("requiredRoles").strings().forEach { role ->
            assertTrue("$identifier must retain $role Preview paths", roleSegments.getInt(role) > 0)
        }
        val roleLayers = metrics.getJSONObject("roleLayers")
        val roleFirstLayers = metrics.getJSONObject("roleFirstLayers")
        val roleLastLayers = metrics.getJSONObject("roleLastLayers")
        val roleExtrusion = metrics.getJSONObject("roleExtrusionMm")
        expected.getJSONObject("minRoleLayers").keys().forEach { role ->
            val required = expected.getJSONObject("minRoleLayers").getInt(role)
            assertTrue(
                "$identifier must emit $role on enough distinct layers",
                roleLayers.getInt(role) >= required,
            )
        }
        expected.getJSONObject("minRoleExtrusionMm").keys().forEach { role ->
            val required = expected.getJSONObject("minRoleExtrusionMm").getDouble(role)
            assertTrue(
                "$identifier must emit enough positive $role extrusion",
                roleExtrusion.getDouble(role) >= required,
            )
        }
        expected.getJSONArray("forbiddenRoles").strings().forEach { role ->
            assertEquals("$identifier must not emit $role", 0, roleLayers.getInt(role))
            assertEquals("$identifier must not extrude $role", 0.0, roleExtrusion.getDouble(role), 0.0)
        }
        val finalLayer = metrics.getInt("emittedLayers") - 1
        expected.getJSONArray("firstLayerRoles").strings().forEach { role ->
            assertEquals("$identifier $role must begin on the first layer", 0, roleFirstLayers.getInt(role))
        }
        expected.getJSONArray("lastLayerRoles").strings().forEach { role ->
            assertEquals(
                "$identifier $role must reach the final emitted layer",
                finalLayer,
                roleLastLayers.getInt(role),
            )
        }
        expected.getJSONArray("interiorRoles").strings().forEach { role ->
            assertTrue(
                "$identifier $role must remain between the first and final layers",
                roleFirstLayers.getInt(role) > 0 && roleLastLayers.getInt(role) < finalLayer,
            )
        }
        expected.getJSONArray("rolePrecedence").objects().forEach { rule ->
            val before = rule.getString("before")
            val after = rule.getString("after")
            assertTrue(
                "$identifier $before must finish before $after begins",
                roleLastLayers.getInt(before) < roleFirstLayers.getInt(after),
            )
        }
        val usedTools = metrics.getJSONArray("usedTools").ints().toSet()
        (expected.optJSONArray("requiredTools")?.ints() ?: listOf(0)).forEach { tool ->
            assertTrue("$identifier must positively extrude with T$tool", tool in usedTools)
        }
        assertTrue(
            "$identifier must perform enough real tool changes",
            metrics.getInt("toolChanges") >= expected.optInt("minToolChanges", 0),
        )
        expected.optJSONObject("exactRoleTools")?.let { exactRoleTools ->
            exactRoleTools.keys().forEach { role ->
                val expectedTools = exactRoleTools.getJSONArray(role).ints().toSet()
                val actualTools = metrics.getJSONObject("roleTools").getJSONArray(role).ints().toSet()
                assertEquals("$identifier must route $role to the selected tools", expectedTools, actualTools)
                val toolExtrusion = metrics.getJSONObject("roleToolExtrusionMm").getJSONObject(role)
                expectedTools.forEach { tool ->
                    assertTrue(
                        "$identifier must positively extrude $role with T$tool",
                        toolExtrusion.getDouble(tool.toString()) > MINIMUM_POSITIVE_EXTRUSION_MM,
                    )
                }
            }
        }
    }

    private fun analyzeGcode(
        file: File,
        fingerprintKeys: List<String>,
    ): QualificationGcodeAnalysis {
        val roleMotions = ROLE_NAMES.associateWith { 0 }.toMutableMap()
        val roleLayerSets = ROLE_NAMES.associateWith { mutableSetOf<Int>() }
        val roleExtrusionMm = ROLE_NAMES.associateWith { 0.0 }.toMutableMap()
        val roleTools = ROLE_NAMES.associateWith { mutableSetOf<Int>() }
        val roleToolExtrusionMm = ROLE_NAMES.associateWith { mutableMapOf<Int, Double>() }
        val profileValues = mutableMapOf<String, String>()
        val supportGeometry = MessageDigest.getInstance("SHA-256")
        var activeRole = "other"
        var activeTool = 0
        var toolChanges = 0
        var relativeExtrusion = false
        val absoluteExtruders = mutableMapOf(0 to 0f)
        var emittedLayer = -1
        var extrusionMotions = 0
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var currentX = 0f
        var currentY = 0f
        var currentZ = 0f
        file.useLines { lines ->
            lines.forEach { line ->
                val selectedTool = toolIndex(line)
                if (line.startsWith(";") && " = " in line) {
                    val key = line.removePrefix(";").trim().substringBefore(" = ")
                    if (key in fingerprintKeys) {
                        profileValues[key] = line.substringAfter(" = ").trim()
                    }
                }
                when {
                    line.startsWith(";LAYER_CHANGE") -> emittedLayer += 1
                    line.startsWith(";TYPE:") -> activeRole = roleName(line.substringAfter(";TYPE:"))
                    line.startsWith("; FEATURE:") ->
                        activeRole = roleName(line.substringAfter("; FEATURE:"))
                    selectedTool != null -> {
                        val tool = selectedTool
                        if (tool != activeTool) toolChanges += 1
                        activeTool = tool
                        absoluteExtruders.putIfAbsent(tool, 0f)
                    }
                    line.startsWith("M82") -> relativeExtrusion = false
                    line.startsWith("M83") -> relativeExtrusion = true
                    line.startsWith("G92") ->
                        axisValue(line, 'E')?.let { absoluteExtruders[activeTool] = it }
                    line.startsWith("G1 ") || line.startsWith("G2 ") || line.startsWith("G3 ") -> {
                        val x = axisValue(line, 'X')
                        val y = axisValue(line, 'Y')
                        val z = axisValue(line, 'Z')
                        x?.let { currentX = it }
                        y?.let { currentY = it }
                        z?.let { currentZ = it }
                        val encodedExtrusion = axisValue(line, 'E') ?: return@forEach
                        val absoluteExtruder = absoluteExtruders.getValue(activeTool)
                        val extrusionDelta = if (relativeExtrusion) {
                            encodedExtrusion
                        } else {
                            encodedExtrusion - absoluteExtruder
                        }
                        if (!relativeExtrusion) absoluteExtruders[activeTool] = encodedExtrusion
                        val spatialMotion = x != null || y != null || z != null ||
                            listOf('I', 'J').any { axis -> axisValue(line, axis) != null }
                        if (
                            extrusionDelta > MINIMUM_POSITIVE_EXTRUSION_MM &&
                            spatialMotion && emittedLayer >= 0
                        ) {
                            extrusionMotions += 1
                            roleMotions[activeRole] = roleMotions.getValue(activeRole) + 1
                            roleLayerSets.getValue(activeRole) += emittedLayer
                            roleExtrusionMm[activeRole] =
                                roleExtrusionMm.getValue(activeRole) + extrusionDelta
                            roleTools.getValue(activeRole) += activeTool
                            val extrusionByTool = roleToolExtrusionMm.getValue(activeRole)
                            extrusionByTool[activeTool] =
                                extrusionByTool.getOrDefault(activeTool, 0.0) + extrusionDelta
                            if (activeRole == "support") {
                                val signature = String.format(
                                    Locale.ROOT,
                                    "%d|%d|%.4f|%.4f|%.4f|%.7f\n",
                                    emittedLayer,
                                    activeTool,
                                    currentX,
                                    currentY,
                                    currentZ,
                                    extrusionDelta,
                                )
                                supportGeometry.update(signature.toByteArray(Charsets.UTF_8))
                            }
                            x?.let {
                                minX = minOf(minX, it)
                                maxX = maxOf(maxX, it)
                            }
                        }
                    }
                }
            }
        }
        val roleFirstLayers = ROLE_NAMES.associateWith { role ->
            roleLayerSets.getValue(role).minOrNull() ?: -1
        }
        val roleLastLayers = ROLE_NAMES.associateWith { role ->
            roleLayerSets.getValue(role).maxOrNull() ?: -1
        }
        return QualificationGcodeAnalysis(
            extrusionMotions = extrusionMotions,
            extrusionXSpanMm = if (minX.isFinite() && maxX.isFinite()) maxX - minX else 0f,
            emittedLayers = emittedLayer + 1,
            roleMotions = roleMotions,
            roleLayers = ROLE_NAMES.associateWith { role -> roleLayerSets.getValue(role).size },
            roleFirstLayers = roleFirstLayers,
            roleLastLayers = roleLastLayers,
            roleExtrusionMm = roleExtrusionMm,
            usedTools = roleTools.values.flatten().toSet(),
            toolChanges = toolChanges,
            roleTools = roleTools.mapValues { (_, tools) -> tools.toSet() },
            roleToolExtrusionMm = roleToolExtrusionMm.mapValues { (_, values) -> values.toMap() },
            supportGeometryFingerprint = supportGeometry.digest()
                .joinToString("") { byte -> "%02x".format(byte) },
            profileValues = profileValues,
        )
    }

    private fun roleName(label: String): String {
        val normalized = label.trim().lowercase()
        return when {
            "outer wall" in normalized || "external perimeter" in normalized -> "outerWall"
            "inner wall" in normalized || "perimeter" in normalized -> "innerWall"
            "bridge" in normalized || "overhang" in normalized -> "bridge"
            "support" in normalized -> "support"
            "skirt" in normalized || "brim" in normalized || "raft" in normalized -> "adhesion"
            "top surface" in normalized -> "topSurface"
            "bottom surface" in normalized -> "bottomSurface"
            "solid" in normalized -> "internalSolid"
            "infill" in normalized -> "sparseInfill"
            else -> "other"
        }
    }

    private fun axisValue(line: String, axis: Char): Float? {
        var index = 0
        while (index < line.length) {
            if (line[index] != axis || index > 0 && !line[index - 1].isWhitespace()) {
                index += 1
                continue
            }
            var cursor = index + 1
            var sign = 1f
            if (cursor < line.length && line[cursor] == '-') {
                sign = -1f
                cursor += 1
            } else if (cursor < line.length && line[cursor] == '+') {
                cursor += 1
            }
            var value = 0f
            var divisor = 0f
            var digits = 0
            while (cursor < line.length) {
                val character = line[cursor]
                when {
                    character in '0'..'9' -> {
                        value = value * 10f + (character - '0')
                        if (divisor > 0f) divisor *= 10f
                        digits += 1
                    }
                    character == '.' && divisor == 0f -> divisor = 1f
                    else -> break
                }
                cursor += 1
            }
            if (digits == 0) return null
            return sign * if (divisor > 1f) value / divisor else value
        }
        return null
    }

    private fun toolIndex(line: String): Int? {
        val command = line.substringBefore(';').trim()
        if (!command.startsWith('T') || command.length < 2) return null
        return command.substring(1).toIntOrNull()?.takeIf { it in 0..15 }
    }

    private fun sha256(payload: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(payload)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun JSONArray.objects(): List<JSONObject> = List(length()) { index -> getJSONObject(index) }

    private fun JSONArray.strings(): List<String> = List(length()) { index -> getString(index) }

    private fun JSONArray.ints(): List<Int> = List(length()) { index -> getInt(index) }

    companion object {
        private const val MANIFEST_ASSET = "manifest.json"
        private const val REPORT_PATH = "qualification/corpus-report.json"
        private const val GCODE_DIRECTORY = "qualification/gcode"
        private const val DEFAULT_FRAMEBUFFER_WIDTH = 720
        private const val DEFAULT_FRAMEBUFFER_HEIGHT = 1280
        private const val MEASURED_FRAME_COUNT = 30
        private const val PREVIEW_MEASUREMENT_TIMEOUT_SECONDS = 90L
        private const val MAXIMUM_QUALIFICATION_CYCLES = 3
        private const val SOAK_SETTLE_MILLIS = 250L
        private const val MINIMUM_POSITIVE_EXTRUSION_MM = 0.000_000_1f
        private val ROLE_NAMES = listOf(
            "outerWall",
            "innerWall",
            "sparseInfill",
            "topSurface",
            "internalSolid",
            "support",
            "bridge",
            "adhesion",
            "other",
            "bottomSurface",
        )
    }
}

private data class QualificationGcodeAnalysis(
    val extrusionMotions: Int,
    val extrusionXSpanMm: Float,
    val emittedLayers: Int,
    val roleMotions: Map<String, Int>,
    val roleLayers: Map<String, Int>,
    val roleFirstLayers: Map<String, Int>,
    val roleLastLayers: Map<String, Int>,
    val roleExtrusionMm: Map<String, Double>,
    val usedTools: Set<Int>,
    val toolChanges: Int,
    val roleTools: Map<String, Set<Int>>,
    val roleToolExtrusionMm: Map<String, Map<Int, Double>>,
    val supportGeometryFingerprint: String,
    val profileValues: Map<String, String>,
)
