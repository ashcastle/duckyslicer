from __future__ import annotations

import unittest

from tools.verify_support_diagnostics import (
    EXPECTED_EVENTS,
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
    event_calls = " ".join(f"SupportEvent.{event}" for event in EXPECTED_EVENTS)
    string_calls = " ".join(f"R.string.{name}" for name in REQUIRED_STRINGS)
    diagnostics = "\n".join(
        (
            "internal enum class SupportEvent {",
            event_enum,
            "}",
            "fun createSupportReport(context: Context, settings: AppSettings)",
            "SupportEventJournal(context.applicationContext).snapshot()",
            "MAX_SUPPORT_EVENTS = 32",
            "MAX_SUPPORT_REPORT_BYTES = 16 * 1_024",
            "takeLast(MAX_SUPPORT_EVENTS)",
            "private_content_included=false",
            "models_included=false",
            "gcode_included=false",
            "file_names_included=false",
            "printer_addresses_included=false",
            "access_keys_included=false",
            "OsConstants._SC_PAGESIZE",
            "StatFs(context.filesDir.absolutePath).availableBytes",
        )
    )
    return {
        "SupportDiagnostics.kt": diagnostics,
        "MainActivity.kt": event_calls,
        "AppSettingsSheet.kt": (
            'ActivityResultContracts.CreateDocument("text/plain") '
            "createSupportReport(context.applicationContext, settings) "
            "writeSupportReport(output, report) "
            'SUPPORT_REPORT_FILE_NAME = "DuckySlicer-support.txt" '
            f"{string_calls}"
        ),
        "SupportDiagnosticsTest.kt": (
            "supportReportContainsOnlyBoundedEnvironmentSettingsAndFixedProblemCodes "
            "supportEventCodecRejectsMalformedUnknownAndOversizedHistory "
            "supportReportWriterProducesExactUtf8AndRejectsOversizedInput"
        ),
        "SupportDiagnosticsInstrumentedTest.kt": (
            "supportDetailsUseRealDeviceFactsWithoutPrivateAppContent page_size_bytes=16384"
        ),
        "AccessibilityInstrumentedTest.kt": "appSettingsExposeAVisibleSupportDetailsAction",
        "strings.xml": string_resources(),
        "strings-ko.xml": string_resources(),
        "PRIVACY.md": (
            "Support details are written only to a location you select.\n"
            "They do not contain models, G-code, file names, printer\n"
            "지원 정보는 사용자가 선택한 위치에만 저장됩니다.\n"
            "사용자가 직접 공유한 경우에만 DuckySlicer 프로젝트가 이 정보를 받습니다."
        ),
        "SUPPORT.md": "Settings > Help > Save support details SECURITY.md",
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

    def test_rejects_missing_problem_category_recording(self) -> None:
        sources = valid_sources()
        sources["MainActivity.kt"] = sources["MainActivity.kt"].replace(
            "SupportEvent.SLICE_FAILED", ""
        )
        with self.assertRaisesRegex(VerificationError, "never recorded"):
            verify_support_diagnostics(sources)

    def test_rejects_non_user_chosen_export(self) -> None:
        sources = valid_sources()
        sources["AppSettingsSheet.kt"] = sources["AppSettingsSheet.kt"].replace(
            'ActivityResultContracts.CreateDocument("text/plain")',
            "automaticUpload()",
        )
        with self.assertRaisesRegex(VerificationError, "user-chosen"):
            verify_support_diagnostics(sources)

    def test_rejects_missing_privacy_disclosure(self) -> None:
        sources = valid_sources()
        sources["PRIVACY.md"] = ""
        with self.assertRaisesRegex(VerificationError, "privacy policy"):
            verify_support_diagnostics(sources)


if __name__ == "__main__":
    unittest.main()
