package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class SupportDiagnosticsTest {
    @Test
    fun supportReportContainsOnlyBoundedEnvironmentSettingsAndFixedProblemCodes() {
        val report = renderSupportReport(
            snapshot(
                deviceModel = "Duck\nPhone\u0000" + "x".repeat(200),
                events = listOf(
                    SupportEventRecord(1_000L, SupportEvent.MODEL_IMPORT_FAILED),
                    SupportEventRecord(2_000L, SupportEvent.SLICE_FAILED),
                ),
                processExits = List(MAX_SUPPORT_PROCESS_EXITS + 2) { index ->
                    SupportProcessExit(
                        timestampMillis = index.toLong(),
                        process = if (index % 2 == 0) {
                            SupportProcessKind.APP
                        } else {
                            SupportProcessKind.SLICER
                        },
                        reason = SupportExitReason.CRASH_NATIVE,
                    )
                },
            ),
        )

        assertTrue(report.startsWith("DuckySlicer support details\nschema=2\n"))
        assertTrue(report.contains("device_model=Duck Phone"))
        assertTrue(report.contains("recent_problem.0.code=MODEL_IMPORT_FAILED"))
        assertTrue(report.contains("recent_problem.1.code=SLICE_FAILED"))
        assertTrue(report.contains("previous_exit_count=4"))
        assertTrue(report.contains("previous_exit.0.process=APP"))
        assertTrue(report.contains("previous_exit.1.process=SLICER"))
        assertTrue(report.contains("previous_exit.0.reason=CRASH_NATIVE"))
        assertFalse(report.contains("previous_exit.4."))
        assertTrue(report.contains("private_content_included=false"))
        assertTrue(report.contains("raw_process_names_included=false"))
        assertTrue(report.contains("exit_descriptions_included=false"))
        assertTrue(report.contains("exit_traces_included=false"))
        assertTrue(report.contains("exit_memory_samples_included=false"))
        assertFalse(report.contains("/storage/emulated"))
        assertFalse(report.contains("printer.local"))
        assertFalse(report.contains("secret-access-key"))
        assertTrue(report.toByteArray().size <= MAX_SUPPORT_REPORT_BYTES)
    }

    @Test
    fun supportEventCodecRejectsMalformedUnknownAndOversizedHistory() {
        val valid = List(MAX_SUPPORT_EVENTS + 5) { index ->
            SupportEventRecord(index.toLong(), SupportEvent.PREVIEW_FAILED)
        }
        val encoded = encodeSupportEvents(valid)
        val decoded = decodeSupportEvents(
            "broken\n-1|SLICE_FAILED\n1|UNKNOWN\n$encoded\ntrailing|",
        )

        assertEquals(MAX_SUPPORT_EVENTS, decoded.size)
        assertEquals(5L, decoded.first().timestampMillis)
        assertEquals(SupportEvent.PREVIEW_FAILED, decoded.last().event)
        assertTrue(decodeSupportEvents("x".repeat(8 * 1_024 + 1)).isEmpty())
    }

    @Test
    fun supportReportWriterProducesExactUtf8AndRejectsOversizedInput() {
        val report = renderSupportReport(snapshot())
        val output = ByteArrayOutputStream()
        writeSupportReport(output, report)

        assertEquals(report, output.toString(Charsets.UTF_8.name()))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            writeSupportReport(ByteArrayOutputStream(), "x".repeat(MAX_SUPPORT_REPORT_BYTES + 1))
        }
    }

    @Test
    fun processExitMappingNeverExportsAnUnexpectedRawProcessNameOrReason() {
        assertEquals(SupportProcessKind.APP, supportProcessKind("com.ducky", "com.ducky"))
        assertEquals(
            SupportProcessKind.SLICER,
            supportProcessKind("com.ducky", "com.ducky:slicer"),
        )
        assertEquals(
            SupportProcessKind.OTHER,
            supportProcessKind("com.ducky", "private.file-name.stl"),
        )
        SupportExitReason.entries.forEach { reason ->
            assertEquals(reason, SupportExitReason.fromPlatformCode(reason.platformCode))
        }
        assertEquals(SupportExitReason.UNKNOWN, SupportExitReason.fromPlatformCode(Int.MAX_VALUE))
    }

    private fun snapshot(
        deviceModel: String = "Ducky Phone",
        events: List<SupportEventRecord> = emptyList(),
        processExits: List<SupportProcessExit> = emptyList(),
    ) = SupportReportSnapshot(
        generatedAtMillis = 0L,
        appVersion = "0.1.0-test",
        buildType = "debug",
        androidRelease = "15",
        androidApi = 35,
        deviceManufacturer = "Ducky",
        deviceModel = deviceModel,
        primaryAbi = "arm64-v8a",
        pageSizeBytes = 16_384L,
        appMemoryClassMb = 256,
        lowRamDevice = false,
        maxHeapMb = 256L,
        availableStorageMb = 1_024L,
        localeTag = "en-US",
        requestedPreviewDetail = PreviewDetail.AUTOMATIC,
        effectivePreviewDetail = PreviewDetail.BALANCED,
        previewRenderingMode = PreviewRenderingMode.DEPTH_TESTED,
        toolpathOpacityPercent = 92,
        toolpathDepthContrastPercent = 78,
        keepScreenAwake = true,
        confirmRemotePrint = true,
        connectionTimeoutSeconds = 15,
        events = events,
        processExits = processExits,
    )
}
