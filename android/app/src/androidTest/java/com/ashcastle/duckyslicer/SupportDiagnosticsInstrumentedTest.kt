package com.ashcastle.duckyslicer

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class SupportDiagnosticsInstrumentedTest {
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
        assertTrue(report.contains("page_size_bytes=16384"))
        assertTrue(report.contains("preview_detail_requested=DETAIL"))
        assertTrue(report.contains("preview_display=COMPATIBILITY"))
        assertTrue(report.contains("connection_timeout_seconds=25"))
        assertTrue(report.contains("private_content_included=false"))
        assertFalse(report.contains(context.filesDir.absolutePath))
        assertFalse(report.contains("/storage/emulated/"))
        assertFalse(report.contains("http://"))
        assertFalse(report.contains("G1 X"))
        assertTrue(report.toByteArray().size <= MAX_SUPPORT_REPORT_BYTES)
    }
}
