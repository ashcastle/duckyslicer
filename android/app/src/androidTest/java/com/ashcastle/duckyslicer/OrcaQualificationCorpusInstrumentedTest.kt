package com.ashcastle.duckyslicer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import java.io.File
import java.security.MessageDigest
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
        val retainGcode = InstrumentationRegistry.getArguments()
            .getString("retainCorpusGcode")
            ?.toBooleanStrictOrNull()
            ?: false
        val cases = manifest.getJSONArray("cases").objects().filter { case ->
            case.getString("id") == requestedCase
        }
        assertEquals("Unknown qualification case", 1, cases.size)
        val results = JSONArray()
        val temporaryModels = mutableListOf<File>()
        val outputs = mutableListOf<File>()
        try {
            cases.forEach { case ->
                val identifier = case.getString("id")
                val modelPaths = case.getJSONArray("models").strings()
                val offsets = case.optJSONArray("offsetsXmm")
                val objects = modelPaths.mapIndexed { index, assetPath ->
                    val source = File(context.cacheDir, "qualification-$identifier-$index.stl")
                    instrumentation.context.assets.open(assetPath).use { input ->
                        source.outputStream().use(input::copyTo)
                    }
                    temporaryModels += source
                    val model = ModelInfo.fromJson(
                        NativeEngine.inspectStl(source.absolutePath),
                        source.absolutePath,
                    )
                    ProjectObject(
                        id = "$identifier-$index",
                        model = model,
                        transform = ModelTransform(
                            offsetXmm = offsets?.getDouble(index)?.toFloat() ?: 0f,
                        ),
                    )
                }
                val options = baseOptions.copy(
                    supportEnabled = case.optBoolean("supportEnabled", false),
                    supportType = "normal",
                )
                val outcome = OnDeviceSlicer.slice(objects, options)
                outputs += outcome.output
                if (retainGcode) {
                    val retained = File(context.filesDir, "$GCODE_DIRECTORY/$identifier.gcode")
                    retained.parentFile?.mkdirs()
                    val staging = File(retained.parentFile, ".${retained.name}.tmp")
                    staging.delete()
                    outcome.output.copyTo(staging, overwrite = true)
                    check(staging.renameTo(retained)) { "Could not retain qualification G-code" }
                }
                val preview = GcodeLayerPreview.fromNative(
                    NativeEngine.previewGcodeRange(outcome.output.absolutePath, 0, Int.MAX_VALUE),
                )
                val metrics = metrics(identifier, objects.size, outcome, preview, fingerprintKeys)
                enforce(case.getJSONObject("expected"), metrics)
                results.put(metrics)
            }

            val report = JSONObject()
                .put("schemaVersion", 1)
                .put("source", "android")
                .put("engineRevision", BuildConfig.ORCA_ENGINE_REVISION)
                .put("runtimeVersion", NativeLibrary().getCoreVersion())
                .put("manifestSha256", sha256(manifestBytes))
                .put("effectiveProfile", profile)
                .put("cases", results)
            reportFile.parentFile?.mkdirs()
            reportFile.writeText(report.toString(2) + "\n")
            assertTrue("Qualification report must be retained for the local runner", reportFile.isFile)
        } finally {
            outputs.forEach(File::delete)
            temporaryModels.forEach(File::delete)
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

    private fun metrics(
        identifier: String,
        modelCount: Int,
        outcome: SliceOutcome,
        preview: GcodeLayerPreview,
        fingerprintKeys: List<String>,
    ): JSONObject {
        val roleMotions = ROLE_NAMES.associateWith { 0 }.toMutableMap()
        var activeRole = "other"
        var extrusionMotions = 0
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        val profileValues = mutableMapOf<String, String>()
        outcome.output.useLines { lines -> lines.forEach { raw ->
            val line = raw
            if (line.startsWith(";") && " = " in line) {
                val key = line.removePrefix(";").trim().substringBefore(" = ")
                if (key in fingerprintKeys) profileValues[key] = line.substringAfter(" = ").trim()
            }
            val label = when {
                line.startsWith(";TYPE:") -> line.substringAfter(";TYPE:")
                line.startsWith("; FEATURE:") -> line.substringAfter("; FEATURE:")
                else -> null
            }
            if (label != null) activeRole = roleName(label)
            val isExtrusion = (line.startsWith("G1 ") || line.startsWith("G2 ") || line.startsWith("G3 ")) &&
                axisValue(line, 'E') != null
            if (isExtrusion) {
                extrusionMotions += 1
                roleMotions[activeRole] = roleMotions.getValue(activeRole) + 1
                axisValue(line, 'X')?.let { x ->
                    minX = minOf(minX, x)
                    maxX = maxOf(maxX, x)
                }
            }
        } }
        val roleSegments = JSONObject()
        ROLE_NAMES.forEachIndexed { index, role ->
            roleSegments.put(role, preview.roleSegmentCounts[index])
        }
        val roleMotionJson = JSONObject()
        ROLE_NAMES.forEach { role -> roleMotionJson.put(role, roleMotions.getValue(role)) }
        val coveredZ = HashSet<Int>()
        preview.segments.indices.step(GcodeLayerPreview.SEGMENT_STRIDE).forEach { offset ->
            coveredZ += (preview.segments[offset + 4] * 1_000f).roundToInt()
        }
        assertEquals(
            "Every pinned profile key must be observable in G-code",
            fingerprintKeys.sorted(),
            profileValues.keys.sorted(),
        )
        val profileJson = JSONObject().also { output ->
            fingerprintKeys.sorted().forEach { key -> output.put(key, profileValues.getValue(key)) }
        }
        val fingerprint = fingerprintKeys.sorted().joinToString("\n") { key ->
            "$key=${profileValues.getValue(key)}"
        }.toByteArray()
        return JSONObject()
            .put("id", identifier)
            .put("modelCount", modelCount)
            .put("layers", outcome.layers)
            .put("estimatedSeconds", outcome.estimatedSeconds.toDouble())
            .put("filamentMm", outcome.filamentMm.toDouble())
            .put("filamentGrams", outcome.filamentGrams.toDouble())
            .put("gcodeBytes", outcome.output.length())
            .put("gcodeSha256", sha256(outcome.output))
            .put("extrusionMotions", extrusionMotions)
            .put("extrusionXSpanMm", if (minX.isFinite() && maxX.isFinite()) maxX - minX else 0f)
            .put("roleMotions", roleMotionJson)
            .put("previewLayerCount", preview.layerCount)
            .put("previewStartLayer", preview.startLayer)
            .put("previewEndLayer", preview.endLayer)
            .put("previewSegments", preview.segments.size / GcodeLayerPreview.SEGMENT_STRIDE)
            .put("previewCoveredZ", coveredZ.size)
            .put(
                "previewLayerCoverage",
                if (preview.layerCount == 0) 0.0 else coveredZ.size.toDouble() / preview.layerCount,
            )
            .put("roleSegments", roleSegments)
            .put("profileFingerprint", sha256(fingerprint))
            .put("profileValues", profileJson)
    }

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
        assertEquals(0, metrics.getInt("previewStartLayer"))
        assertEquals(metrics.getInt("previewLayerCount") - 1, metrics.getInt("previewEndLayer"))
        val roleSegments = metrics.getJSONObject("roleSegments")
        expected.getJSONArray("requiredRoles").strings().forEach { role ->
            assertTrue("$identifier must retain $role Preview paths", roleSegments.getInt(role) > 0)
        }
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

    companion object {
        private const val MANIFEST_ASSET = "manifest.json"
        private const val REPORT_PATH = "qualification/corpus-report.json"
        private const val GCODE_DIRECTORY = "qualification/gcode"
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
