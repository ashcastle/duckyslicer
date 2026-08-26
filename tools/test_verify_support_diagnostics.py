from __future__ import annotations

import unittest

from tools.verify_support_diagnostics import (
    EXPECTED_EVENTS,
    EXPECTED_EXIT_REASONS,
    REQUIRED_STRINGS,
    VerificationError,
    verify_support_diagnostics,
)


def string_resources() -> str:
    values = "".join(
        f'<string name="{name}">{name} value</string>' for name in REQUIRED_STRINGS
    )
    return f"<resources>{values}</resources>"


def valid_sources() -> dict[str, str]:
    event_enum = "\n".join(f"    {event}," for event in sorted(EXPECTED_EVENTS))
    remote_events = {event for event in EXPECTED_EVENTS if event.startswith("REMOTE_")}
    project_events = {
        "PROJECT_ARCHIVE_EXPORT_FAILED",
        "PROJECT_SAVE_FAILED",
        "PROJECT_STORAGE_UNAVAILABLE",
    }
    profile_events = {
        "FILAMENT_PROFILE_SAVE_FAILED",
        "PRINTER_PROFILE_SAVE_FAILED",
        "PROFILE_BUNDLE_EXPORT_FAILED",
        "PROFILE_BUNDLE_IMPORT_FAILED",
        "PROFILE_STORAGE_UNAVAILABLE",
        "SLICING_PROFILE_SAVE_FAILED",
    }
    settings_events = {"APP_SETTINGS_SAVE_FAILED"}
    gcode_events = {"GCODE_EXPORT_FAILED"}
    support_export_events = {"SUPPORT_REPORT_EXPORT_FAILED"}
    slice_events = {"SLICE_FAILED", "PREVIEW_FAILED"}
    event_calls = " ".join(
        f"SupportEvent.{event}"
        for event in EXPECTED_EVENTS
        - remote_events
        - project_events
        - profile_events
        - settings_events
        - gcode_events
        - support_export_events
        - slice_events
    )
    project_event_calls = " ".join(f"SupportEvent.{event}" for event in project_events)
    remote_event_calls = " ".join(f"SupportEvent.{event}" for event in remote_events)
    profile_event_calls = " ".join(f"SupportEvent.{event}" for event in profile_events)
    settings_event_calls = " ".join(f"SupportEvent.{event}" for event in settings_events)
    gcode_event_calls = " ".join(f"SupportEvent.{event}" for event in gcode_events)
    support_export_event_calls = " ".join(
        f"SupportEvent.{event}" for event in support_export_events
    )
    string_calls = " ".join(f"R.string.{name}" for name in REQUIRED_STRINGS)
    exit_reasons = "\n".join(
        f"    {name}({code})," for name, code in EXPECTED_EXIT_REASONS.items()
    )
    diagnostics = "\n".join(
        (
            "internal enum class SupportEvent {",
            event_enum,
            "}",
            "fun createSupportReport(context: Context, settings: AppSettings)",
            "SupportEventJournal(context.applicationContext).snapshot()",
            "MAX_SUPPORT_EVENTS = 32",
            "MAX_SUPPORT_REPORT_BYTES = 16 * 1_024",
            'appendLine("schema=2")',
            "takeLast(MAX_SUPPORT_EVENTS)",
            "snapshot.processExits.take(MAX_SUPPORT_PROCESS_EXITS)",
            "previous_exit_count=",
            "previous_exit.$index.process=${record.process.name}",
            "previous_exit.$index.reason=${record.reason.name}",
            "private_content_included=false",
            "models_included=false",
            "gcode_included=false",
            "file_names_included=false",
            "printer_addresses_included=false",
            "access_keys_included=false",
            "raw_process_names_included=false",
            "exit_descriptions_included=false",
            "exit_traces_included=false",
            "exit_memory_samples_included=false",
            "OsConstants._SC_PAGESIZE",
            "StatFs(context.filesDir.absolutePath).availableBytes",
            "synchronized(SUPPORT_EVENT_LOCK)",
            "private val SUPPORT_EVENT_LOCK = Any()",
        )
    )
    process_history = "\n".join(
        (
            "internal enum class SupportExitReason(val platformCode: Int) {",
            exit_reasons,
            "    ;",
            "}",
            "Build.VERSION.SDK_INT >= Build.VERSION_CODES.R",
            "MAX_SUPPORT_PROCESS_EXITS = 4",
            "SupportProcessKind.APP SupportProcessKind.SLICER SupportProcessKind.OTHER",
            "Api30ProcessExitHistory.read(context.applicationContext)",
        )
    )
    return {
        "SupportDiagnostics.kt": diagnostics,
        "ProcessExitHistory.kt": process_history,
        "ProcessExitHistoryApi30.kt": (
            "@RequiresApi(30) getHistoricalProcessExitReasons( context.packageName "
            "MAX_SUPPORT_PROCESS_EXITS info.timestamp "
            "supportProcessKind(context.packageName, info.processName) "
            "SupportExitReason.fromPlatformCode(info.reason)"
        ),
        "MainActivity.kt": (
            event_calls +
            " ViewModelProvider(this)[SupportReportExportViewModel::class.java] "
            "supportReportExportModel.export(uri, appSettings) "
            "supportReportExportModel::cancel"
        ),
        "PlateSliceBatchEffect.kt": " ".join(
            f"SupportEvent.{event}" for event in slice_events
        ),
        "ProjectTransfer.kt": project_event_calls,
        "RemoteOperationViewModel.kt": remote_event_calls,
        "ProfileLibraryViewModel.kt": profile_event_calls,
        "AppSettingsViewModel.kt": settings_event_calls,
        "GcodeExportViewModel.kt": gcode_event_calls,
        "SupportReportExportViewModel.kt": (
            "class SupportReportExportViewModel(application: Application) : "
            "AndroidViewModel(application) viewModelScope.launch(Dispatchers.IO) "
            "uri.scheme != ContentResolver.SCHEME_CONTENT "
            "SupportReportExportOutcome.CANCELED "
            "withSupportReportCancellationRequested( ActiveSupportReportExport( "
            "DocumentTransferCancellation() "
            "application.contentResolver.openAssetFileDescriptor( "
            "uri,\n                        \"wt\",\n                        cancellation.providerSignal "
            "cancellation.attachOutput(output) cancellation.detachOutput(output) "
            "cancellation.complete() cancellation.wasRequested() "
            "failure is DocumentTransferCancelledException cancellation.close() "
            "fun cancel(): Boolean override fun onCleared() active?.cancellation?.cancel() "
            "createSupportReport(application, snapshot) writeSupportReport( "
            "deleteFailedCreatedDocument(application, uri) "
            f"{support_export_event_calls}"
        ),
        "CreatedDocument.kt": (
            "class DocumentTransferCancellation "
            "val providerSignal = CancellationSignal() fun cancel(): Boolean "
            "fun attachOutput(value: OutputStream) fun complete() "
            "fun deleteFailedCreatedDocument(context: Context, uri: Uri) "
            "DocumentsContract.deleteDocument resolver.delete(uri, null, null)"
        ),
        "AppSettingsSheet.kt": (
            'ActivityResultContracts.CreateDocument("text/plain") '
            "supportReportExportState onSupportReportExport onCancelSupportReportExport "
            "supportReportExportState.cancellationRequested "
            'SUPPORT_REPORT_FILE_NAME = "DuckySlicer-support.txt" '
            f"{string_calls}"
        ),
        "SupportDiagnosticsTest.kt": (
            "supportReportContainsOnlyBoundedEnvironmentSettingsAndFixedProblemCodes "
            "supportEventCodecRejectsMalformedUnknownAndOversizedHistory "
            "supportReportWriterProducesExactUtf8AndRejectsOversizedInput "
            "processExitMappingNeverExportsAnUnexpectedRawProcessNameOrReason"
        ),
        "SupportDiagnosticsInstrumentedTest.kt": (
            "supportDetailsUseRealDeviceFactsWithoutPrivateAppContent "
            "recentProcessExitHistoryUsesOnlyFixedBoundedValues "
            "concurrentJournalInstancesRetainEveryBoundedFixedEvent "
            "Executors.newFixedThreadPool(8) "
            "assertEquals(MAX_SUPPORT_EVENTS, retained.size) "
            "Os.sysconf(OsConstants._SC_PAGESIZE) "
            "pageSizeBytes == 4_096L || pageSizeBytes == 16_384L "
            "page_size_bytes=$pageSizeBytes"
        ),
        "CreatedDocumentLifecycleInstrumentedTest.kt": (
            "supportReportExportSurvivesActivityRecreationAndRejectsDuplicateWork "
            "supportReportCancellationSurvivesRecreationAndDeletesThePartialDocument "
            "finalSupportReportOwnerStopsProviderOpenAndDeletesThePartialDocument "
            "assertSame( assertFalse(retained.export( assertTrue(retained.cancel()) "
            "assertFalse(retained.cancel()) store.clear() "
            "BlockingExportProvider.KEY_DELETED BlockingExportProvider.KEY_COMPLETED "
            '"OperationCanceledException" MAX_SUPPORT_REPORT_BYTES'
        ),
        "AccessibilityInstrumentedTest.kt": (
            "appSettingsExposeAVisibleSupportDetailsAction "
            "cancelSupportDetailsSaveActionIsReachable R.string.stop_support_details_save"
        ),
        "strings.xml": string_resources(),
        "strings-ko.xml": string_resources(),
        "PRIVACY.md": (
            "Support details are written only to a location you select.\n"
            "contain models, G-code, file names, printer addresses\n"
            "On Android 11 and later, they also contain up to four\n"
            "raw process names, memory samples, or stack traces\n"
            "지원 정보는 사용자가 선택한 위치에만 저장됩니다.\n"
            "프로세스 종료 시각, 고정 프로세스 분류 및 고정 종료 원인을 최대 4건 포함합니다.\n"
            "사용자가 직접 공유한 경우에만 DuckySlicer 프로젝트가 이 정보를 받습니다."
        ),
        "SUPPORT.md": (
            "Settings > Help > Save support details up to four fixed prior-exit SECURITY.md"
        ),
        "bug_report.yml": "Settings > Help > Save support details",
    }


class VerifySupportDiagnosticsTest(unittest.TestCase):
    def test_accepts_bounded_user_chosen_support_details(self) -> None:
        verify_support_diagnostics(valid_sources())

    def test_rejects_free_form_exception_capture(self) -> None:
        sources = valid_sources()
        sources["SupportDiagnostics.kt"] += " Throwable stackTrace"
        with self.assertRaisesRegex(VerificationError, "private or free-form"):
            verify_support_diagnostics(sources)

    def test_rejects_system_exit_trace_capture(self) -> None:
        sources = valid_sources()
        sources["ProcessExitHistoryApi30.kt"] += " info.getTraceInputStream()"
        with self.assertRaisesRegex(VerificationError, "raw system details"):
            verify_support_diagnostics(sources)

    def test_rejects_unbounded_process_exit_history(self) -> None:
        sources = valid_sources()
        sources["ProcessExitHistory.kt"] = sources["ProcessExitHistory.kt"].replace(
            "MAX_SUPPORT_PROCESS_EXITS = 4", "MAX_SUPPORT_PROCESS_EXITS = 400"
        )
        with self.assertRaisesRegex(VerificationError, "bounded process-exit"):
            verify_support_diagnostics(sources)

    def test_rejects_missing_problem_category_recording(self) -> None:
        sources = valid_sources()
        sources["PlateSliceBatchEffect.kt"] = sources["PlateSliceBatchEffect.kt"].replace(
            "SupportEvent.SLICE_FAILED", ""
        )
        with self.assertRaisesRegex(VerificationError, "never recorded"):
            verify_support_diagnostics(sources)

    def test_rejects_instance_local_support_event_locking(self) -> None:
        sources = valid_sources()
        sources["SupportDiagnostics.kt"] = sources["SupportDiagnostics.kt"].replace(
            "synchronized(SUPPORT_EVENT_LOCK)", "@Synchronized", 1
        )
        with self.assertRaisesRegex(VerificationError, "bounded support report"):
            verify_support_diagnostics(sources)

    def test_rejects_non_user_chosen_export(self) -> None:
        sources = valid_sources()
        sources["AppSettingsSheet.kt"] = sources["AppSettingsSheet.kt"].replace(
            'ActivityResultContracts.CreateDocument("text/plain")',
            "automaticUpload()",
        )
        with self.assertRaisesRegex(VerificationError, "user-chosen"):
            verify_support_diagnostics(sources)

    def test_rejects_composition_owned_support_export(self) -> None:
        sources = valid_sources()
        sources["AppSettingsSheet.kt"] += " rememberCoroutineScope openOutputStream(uri)"
        with self.assertRaisesRegex(VerificationError, "Settings composition"):
            verify_support_diagnostics(sources)

    def test_rejects_missing_support_export_rollback(self) -> None:
        sources = valid_sources()
        sources["SupportReportExportViewModel.kt"] = sources[
            "SupportReportExportViewModel.kt"
        ].replace("deleteFailedCreatedDocument(application, uri)", "leave partial report")
        with self.assertRaisesRegex(VerificationError, "retained support export"):
            verify_support_diagnostics(sources)

    def test_rejects_uncancelable_support_output_stream(self) -> None:
        sources = valid_sources()
        sources["SupportReportExportViewModel.kt"] += ' openOutputStream(uri, "wt")'
        with self.assertRaisesRegex(VerificationError, "bypasses provider"):
            verify_support_diagnostics(sources)

    def test_rejects_support_provider_open_without_exact_signal(self) -> None:
        sources = valid_sources()
        sources["SupportReportExportViewModel.kt"] = sources[
            "SupportReportExportViewModel.kt"
        ].replace("cancellation.providerSignal", "no provider cancellation")
        with self.assertRaisesRegex(VerificationError, "retained support export"):
            verify_support_diagnostics(sources)

    def test_rejects_unbound_support_output_stream(self) -> None:
        sources = valid_sources()
        sources["SupportReportExportViewModel.kt"] = sources[
            "SupportReportExportViewModel.kt"
        ].replace("cancellation.attachOutput(output)", "unbound output")
        with self.assertRaisesRegex(VerificationError, "retained support export"):
            verify_support_diagnostics(sources)

    def test_rejects_missing_final_support_owner_cancellation(self) -> None:
        sources = valid_sources()
        sources["SupportReportExportViewModel.kt"] = sources[
            "SupportReportExportViewModel.kt"
        ].replace("override fun onCleared()", "owner leaked")
        with self.assertRaisesRegex(VerificationError, "retained support export"):
            verify_support_diagnostics(sources)

    def test_rejects_missing_support_cancel_action(self) -> None:
        sources = valid_sources()
        sources["AppSettingsSheet.kt"] = sources["AppSettingsSheet.kt"].replace(
            "onCancelSupportReportExport", "cancel callback missing"
        )
        with self.assertRaisesRegex(VerificationError, "user-chosen support export"):
            verify_support_diagnostics(sources)

    def test_rejects_missing_support_cancellation_lifecycle_regression(self) -> None:
        sources = valid_sources()
        sources["CreatedDocumentLifecycleInstrumentedTest.kt"] = sources[
            "CreatedDocumentLifecycleInstrumentedTest.kt"
        ].replace(
            "supportReportCancellationSurvivesRecreationAndDeletesThePartialDocument",
            "cancellation regression missing",
        )
        with self.assertRaisesRegex(VerificationError, "retained support export regression"):
            verify_support_diagnostics(sources)

    def test_rejects_fixed_page_size_device_regression(self) -> None:
        sources = valid_sources()
        sources["SupportDiagnosticsInstrumentedTest.kt"] = (
            "supportDetailsUseRealDeviceFactsWithoutPrivateAppContent "
            "recentProcessExitHistoryUsesOnlyFixedBoundedValues page_size_bytes=16384"
        )
        with self.assertRaisesRegex(VerificationError, "support ARM64 regression"):
            verify_support_diagnostics(sources)

    def test_rejects_missing_privacy_disclosure(self) -> None:
        sources = valid_sources()
        sources["PRIVACY.md"] = ""
        with self.assertRaisesRegex(VerificationError, "privacy policy"):
            verify_support_diagnostics(sources)


if __name__ == "__main__":
    unittest.main()
