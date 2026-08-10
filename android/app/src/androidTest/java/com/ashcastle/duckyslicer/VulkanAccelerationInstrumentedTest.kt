package com.ashcastle.duckyslicer

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VulkanAccelerationInstrumentedTest {
    @Test
    fun vulkanProbeRejectsSoftwareDevicesFromAutomaticAcceleration() {
        val capabilities = JSONObject(NativeEngine.vulkanCapabilities())
        Log.i(TAG, "VULKAN_CAPABILITIES=$capabilities")

        assertFalse(
            "A capability probe alone must never enable an unimplemented kernel",
            capabilities.getBoolean("autoEnabled"),
        )
        if (capabilities.optBoolean("apiAvailable") && capabilities.optInt("physicalDeviceCount") > 0) {
            assertTrue(
                "The selected Vulkan device must create a logical device before it is considered",
                capabilities.getBoolean("driverProbePassed"),
            )
        }
        if (capabilities.optBoolean("softwareDevice")) {
            assertFalse(
                "SwiftShader and other CPU Vulkan devices must be rejected",
                capabilities.getBoolean("autoCandidate"),
            )
            assertTrue(
                "A rejected software device must report its reason",
                capabilities.getString("reason") == "software_vulkan_device",
            )
        }
    }

    @Test
    fun largeModelSlicesThroughTheMeasuredFallbackPath() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val requestedPath = InstrumentationRegistry.getArguments().getString(LARGE_MODEL_ARGUMENT)
        assumeTrue("Pass -e $LARGE_MODEL_ARGUMENT with a staged STL path", !requestedPath.isNullOrBlank())
        val model = File(requireNotNull(requestedPath))
        assumeTrue("The staged large model must exist", model.isFile)

        val inspectStarted = SystemClock.elapsedRealtime()
        val info = ModelInfo.fromJson(NativeEngine.inspectStl(model.absolutePath), model.absolutePath)
        val inspectMillis = SystemClock.elapsedRealtime() - inspectStarted
        val longestDimension = info.dimensions.maxOrNull()?.toFloat() ?: 0f
        assertTrue("The large model must have printable dimensions", longestDimension > 0f)
        val scale = TARGET_LONGEST_DIMENSION_MM / longestDimension
        val capabilities = JSONObject(NativeEngine.vulkanCapabilities())

        var outcome: SliceOutcome? = null
        try {
            val sliceStarted = SystemClock.elapsedRealtime()
            outcome = OnDeviceSlicer.slice(
                model = model,
                options = SliceOptions()
                    .selectPrinter(PrinterProfile.U1_04)
                    .selectQuality(QualityProfile.DRAFT)
                    .copy(fillDensity = 0.15f, supportEnabled = false),
                modelTransform = ModelTransform(scale = scale),
            )
            val sliceMillis = SystemClock.elapsedRealtime() - sliceStarted
            val result = JSONObject()
                .put("serial", android.os.Build.MODEL)
                .put("sourceBytes", model.length())
                .put("triangles", info.triangles)
                .put("sourceDimensionsMm", info.dimensions)
                .put("scale", scale)
                .put("inspectMillis", inspectMillis)
                .put("sliceMillis", sliceMillis)
                .put("layers", outcome.layers)
                .put("gcodeBytes", outcome.output.length())
                .put("gcodeSha256", sha256(outcome.output))
                .put("toolpathSha256", toolpathSha256(outcome.output))
                .put("vulkanDevice", capabilities.optString("deviceName"))
                .put("vulkanAutoCandidate", capabilities.optBoolean("autoCandidate"))
                .put("vulkanAutoEnabled", capabilities.optBoolean("autoEnabled"))
                .put("fallback", !capabilities.optBoolean("autoEnabled"))
            Log.i(TAG, "LARGE_MODEL_RESULT=$result")

            assertTrue("The benchmark input must remain large", info.triangles >= 1_000_000)
            assertTrue("The large model must produce layers", outcome.layers > 0)
            assertTrue("The large model must produce non-empty G-code", outcome.output.length() > 1_000L)
        } finally {
            outcome?.output?.delete()
            File(model.parentFile, SliceArtifactStore.NATIVE_OUTPUT_NAME).delete()
            instrumentation.waitForIdleSync()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun toolpathSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.bufferedReader().useLines { lines ->
            lines.map(String::trim)
                .filter { line ->
                    line.startsWith("G0 ") ||
                        line.startsWith("G1 ") ||
                        line.startsWith("G2 ") ||
                        line.startsWith("G3 ") ||
                        TOOL_COMMAND.matches(line)
                }
                .forEach { line ->
                    digest.update(line.toByteArray(Charsets.UTF_8))
                    digest.update('\n'.code.toByte())
                }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val TAG = "DuckyVulkanTest"
        const val LARGE_MODEL_ARGUMENT = "largeModelPath"
        const val TARGET_LONGEST_DIMENSION_MM = 160f
        val TOOL_COMMAND = Regex("T\\d+")
    }
}
