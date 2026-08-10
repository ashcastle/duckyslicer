package com.ashcastle.duckyslicer

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SupportDiagnosticsInstrumentedTest {
    @Test
    fun concurrentJournalInstancesRetainEveryBoundedFixedEvent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("support_events", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().clear().commit())
        val expected = (0 until MAX_SUPPORT_EVENTS).associate { index ->
            (1_000L + index) to SupportEvent.entries[index % SupportEvent.entries.size]
        }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            expected.forEach { (timestamp, event) ->
                executor.submit {
                    start.await()
                    SupportEventJournal(context) { timestamp }.record(event)
                }
            }
            start.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))

            val retained = SupportEventJournal(context).snapshot()
            assertEquals(MAX_SUPPORT_EVENTS, retained.size)
            assertEquals(expected, retained.associate { it.timestampMillis to it.event })
        } finally {
            executor.shutdownNow()
            preferences.edit().clear().commit()
        }
    }

    @Test
    fun supportDetailsUseRealDeviceFactsWithoutPrivateAppContent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = createSupportReport(
            context,
            AppSettings(
                previewDetail = PreviewDetail.DETAIL,
                previewRenderingMode = PreviewRenderingMode.COMPATIBILITY,
                connectionTimeoutSeconds = 25,
            ),
        )
        val output = ByteArrayOutputStream()
        writeSupportReport(output, report)

        assertEquals(report, output.toString(Charsets.UTF_8.name()))
        assertTrue(report.contains("app_version=${BuildConfig.VERSION_NAME}"))
        assertTrue(report.contains("android_api=${Build.VERSION.SDK_INT}"))
        val pageSizeBytes = Os.sysconf(OsConstants._SC_PAGESIZE)
        assertTrue(pageSizeBytes == 4_096L || pageSizeBytes == 16_384L)
        assertTrue(report.contains("page_size_bytes=$pageSizeBytes"))
        assertTrue(report.contains("preview_detail_requested=DETAIL"))
        assertTrue(report.contains("preview_display=COMPATIBILITY"))
        assertTrue(report.contains("connection_timeout_seconds=25"))
        assertTrue(report.contains("previous_exit_count="))
        assertTrue(report.contains("raw_process_names_included=false"))
        assertTrue(report.contains("exit_descriptions_included=false"))
        assertTrue(report.contains("exit_traces_included=false"))
        assertTrue(report.contains("exit_memory_samples_included=false"))
        assertTrue(report.contains("private_content_included=false"))
        assertFalse(report.contains(context.filesDir.absolutePath))
        assertFalse(report.contains("/storage/emulated/"))
        assertFalse(report.contains("http://"))
        assertFalse(report.contains("G1 X"))
        assertTrue(report.toByteArray().size <= MAX_SUPPORT_REPORT_BYTES)
    }

    @Test
    fun recentProcessExitHistoryUsesOnlyFixedBoundedValues() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val platformExits = manager.getHistoricalProcessExitReasons(
            context.packageName,
            0,
            MAX_SUPPORT_PROCESS_EXITS,
        )
        val exits = readRecentProcessExits(context)
        val report = createSupportReport(context, AppSettings())

        assertEquals(platformExits.take(MAX_SUPPORT_PROCESS_EXITS).size, exits.size)
        assertTrue(report.contains("previous_exit_count=${exits.size}"))
        platformExits.zip(exits).forEachIndexed { index, (platformExit, exit) ->
            assertTrue(exit.timestampMillis >= 0L)
            assertEquals(platformExit.timestamp.coerceAtLeast(0L), exit.timestampMillis)
            assertEquals(
                supportProcessKind(context.packageName, platformExit.processName),
                exit.process,
            )
            assertEquals(SupportExitReason.fromPlatformCode(platformExit.reason), exit.reason)
            assertTrue(report.contains("previous_exit.$index.process=${exit.process.name}"))
            assertTrue(report.contains("previous_exit.$index.reason=${exit.reason.name}"))
            assertFalse(report.contains(platformExit.processName))
            platformExit.description?.takeIf(String::isNotBlank)?.let { description ->
                assertFalse(report.contains(description))
            }
        }
    }
}
