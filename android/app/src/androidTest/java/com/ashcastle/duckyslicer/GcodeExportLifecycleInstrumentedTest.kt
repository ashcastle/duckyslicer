package com.ashcastle.duckyslicer

import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GcodeExportLifecycleInstrumentedTest {
    @Test
    fun gcodeExportSurvivesActivityRecreationAndCopiesTheExactArtifactOnce() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        resolver.call(BlockingExportProvider.URI, BlockingExportProvider.METHOD_PREPARE, null, null)
        val output = File(context.filesDir, SliceArtifactStore.OUTPUT_DIRECTORY)
            .apply(File::mkdirs)
            .resolve("retained-export.gcode")
        val payload = buildPayload()
        output.writeBytes(payload)
        val outcome = SliceOutcome(output, 10, 12f, 34f, 0.1f)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var retainedModel: GcodeExportViewModel
                scenario.onActivity { activity ->
                    retainedModel = ViewModelProvider(activity)[GcodeExportViewModel::class.java]
                    assertTrue(retainedModel.export(BlockingExportProvider.URI, outcome))
                }
                waitForProvider { status -> status.getBoolean(BlockingExportProvider.KEY_STARTED) }
                assertTrue(retainedModel.state.value.busy)

                scenario.recreate()
                scenario.onActivity { recreated ->
                    assertSame(
                        retainedModel,
                        ViewModelProvider(recreated)[GcodeExportViewModel::class.java],
                    )
                    assertFalse(retainedModel.export(BlockingExportProvider.URI, outcome))
                }
                resolver.call(
                    BlockingExportProvider.URI,
                    BlockingExportProvider.METHOD_RELEASE,
                    null,
                    null,
                )
                waitForExport(retainedModel)
                val status = waitForProvider { value ->
                    value.getBoolean(BlockingExportProvider.KEY_COMPLETED)
                }
                assertFalse(status.getBoolean(BlockingExportProvider.KEY_DELETED))
                assertEquals("", status.getString(BlockingExportProvider.KEY_ERROR))
                assertEquals(payload.size, status.getInt(BlockingExportProvider.KEY_BYTES))
                assertEquals(sha256(payload), status.getString(BlockingExportProvider.KEY_SHA256))
            }
        } finally {
            resolver.call(
                BlockingExportProvider.URI,
                BlockingExportProvider.METHOD_RELEASE,
                null,
                null,
            )
            output.delete()
        }
    }

    private fun waitForExport(model: GcodeExportViewModel) {
        val deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!model.state.value.busy) return
            SystemClock.sleep(WAIT_POLL_MILLIS)
        }
        throw AssertionError("Timed out waiting for retained G-code export")
    }

    private fun waitForProvider(condition: (Bundle) -> Boolean): Bundle {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MILLIS
        var latest = Bundle.EMPTY
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = requireNotNull(
                resolver.call(
                    BlockingExportProvider.URI,
                    BlockingExportProvider.METHOD_STATUS,
                    null,
                    null,
                ),
            )
            if (condition(latest)) return latest
            SystemClock.sleep(WAIT_POLL_MILLIS)
        }
        throw AssertionError("Timed out waiting for blocking export provider: $latest")
    }

    private fun buildPayload(): ByteArray {
        val line = "G1 X10 Y10 E1.25\n".toByteArray()
        return ByteArray(PAYLOAD_BYTES) { index -> line[index % line.size] }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val PAYLOAD_BYTES = 2 * 1_024 * 1_024
        const val WAIT_TIMEOUT_MILLIS = 15_000L
        const val WAIT_POLL_MILLIS = 25L
    }
}
