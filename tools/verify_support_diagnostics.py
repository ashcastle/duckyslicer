#!/usr/bin/env python3
"""Keep user-exported support details bounded, local, and free of private app content."""

from __future__ import annotations

import re
import xml.etree.ElementTree as ElementTree
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
REQUIRED_STRINGS = {
    "help",
    "support_details_summary",
    "save_support_details",
    "support_details_saved",
    "support_details_save_error",
}
EXPECTED_EVENTS = {
    "ARRANGE_FAILED",
    "AUTO_LAY_FAILED",
    "FILAMENT_PROFILE_SAVE_FAILED",
    "GCODE_EXPORT_FAILED",
    "LAY_ON_FACE_FAILED",
    "MODEL_IMPORT_FAILED",
    "MODEL_TOO_LARGE",
    "PREVIEW_FAILED",
    "PRINTER_PROFILE_SAVE_FAILED",
    "PROFILE_STORAGE_UNAVAILABLE",
    "PROJECT_ARCHIVE_EXPORT_FAILED",
    "PROJECT_ARCHIVE_IMPORT_FAILED",
    "PROJECT_SAVE_FAILED",
    "PROJECT_STORAGE_UNAVAILABLE",
    "REMOTE_AUTH_FAILED",
    "REMOTE_COMMAND_FAILED",
    "REMOTE_CONNECTION_FAILED",
    "REMOTE_PROFILE_SAVE_FAILED",
    "REMOTE_STORAGE_UNAVAILABLE",
    "SLICE_FAILED",
    "SLICING_PROFILE_SAVE_FAILED",
}
EXPECTED_EXIT_REASONS = {
    "UNKNOWN": 0,
    "EXIT_SELF": 1,
    "SIGNALED": 2,
    "LOW_MEMORY": 3,
    "CRASH": 4,
    "CRASH_NATIVE": 5,
    "ANR": 6,
    "INITIALIZATION_FAILURE": 7,
    "PERMISSION_CHANGE": 8,
    "EXCESSIVE_RESOURCE_USAGE": 9,
    "USER_REQUESTED": 10,
    "USER_STOPPED": 11,
    "DEPENDENCY_DIED": 12,
    "OTHER": 13,
    "FREEZER": 14,
    "PACKAGE_STATE_CHANGE": 15,
    "PACKAGE_UPDATED": 16,
}
FORBIDDEN_DIAGNOSTIC_MARKERS = {
    ".message",
    "ACTION_SEND",
    "HttpURLConnection",
    "Log.",
    "Throwable",
    "baseUrl",
    "credential",
    "fileName",
    "localPath",
    "printStackTrace",
    "stackTrace",
}
FORBIDDEN_EXIT_MARKERS = {
    ".description",
    ".pss",
    ".rss",
    ".status",
    "getDescription",
    "getProcessStateSummary",
    "getPss",
    "getRss",
    "getStatus",
    "getTraceInputStream",
    "processStateSummary",
    "traceInputStream",
}


class VerificationError(ValueError):
    pass


def _strings(name: str, source: str) -> dict[str, str]:
    try:
        root = ElementTree.fromstring(source)
    except ElementTree.ParseError as error:
        raise VerificationError(f"{name} is not valid XML: {error}") from error
    return {
        element.attrib["name"]: "".join(element.itertext()).strip()
        for element in root.findall("string")
        if "name" in element.attrib
    }


def _event_names(source: str) -> set[str]:
    match = re.search(
        r"internal enum class SupportEvent\s*\{(?P<body>.*?)\n\}",
        source,
        flags=re.DOTALL,
    )
    if not match:
        return set()
    return {
        line.strip().removesuffix(",")
        for line in match.group("body").splitlines()
        if line.strip()
    }


def _exit_reason_codes(source: str) -> dict[str, int]:
    match = re.search(
        r"internal enum class SupportExitReason\(val platformCode: Int\)\s*\{"
        r"(?P<body>.*?)\n\s*;",
        source,
        flags=re.DOTALL,
    )
    if not match:
        return {}
    return {
        name: int(code)
        for name, code in re.findall(
            r"^\s*([A-Z][A-Z_]*)\((\d+)\),?\s*$",
            match.group("body"),
            flags=re.MULTILINE,
        )
    }


def verify_support_diagnostics(sources: dict[str, str]) -> None:
    required = {
        "SupportDiagnostics.kt",
        "ProcessExitHistory.kt",
        "ProcessExitHistoryApi30.kt",
        "MainActivity.kt",
        "ProjectTransfer.kt",
        "RemoteOperationViewModel.kt",
        "AppSettingsSheet.kt",
        "SupportDiagnosticsTest.kt",
        "SupportDiagnosticsInstrumentedTest.kt",
        "AccessibilityInstrumentedTest.kt",
        "strings.xml",
        "strings-ko.xml",
        "PRIVACY.md",
        "SUPPORT.md",
        "bug_report.yml",
    }
    missing = sorted(required - sources.keys())
    if missing:
        raise VerificationError(f"support-diagnostic sources are missing: {missing}")

    diagnostics = sources["SupportDiagnostics.kt"]
    for marker in (
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
    ):
        if marker not in diagnostics:
            raise VerificationError(f"bounded support report is missing: {marker}")
    found_forbidden = sorted(
        marker for marker in FORBIDDEN_DIAGNOSTIC_MARKERS if marker in diagnostics
    )
    if found_forbidden:
        raise VerificationError(
            "support details may capture private or free-form data: "
            f"{found_forbidden}"
        )

    history = sources["ProcessExitHistory.kt"]
    api30_history = sources["ProcessExitHistoryApi30.kt"]
    for marker in (
        "Build.VERSION.SDK_INT >= Build.VERSION_CODES.R",
        "SupportProcessKind.APP",
        "SupportProcessKind.SLICER",
        "SupportProcessKind.OTHER",
        "Api30ProcessExitHistory.read(context.applicationContext)",
    ):
        if marker not in history:
            raise VerificationError(f"bounded process-exit history is missing: {marker}")
    if not re.search(r"\bMAX_SUPPORT_PROCESS_EXITS\s*=\s*4\b", history):
        raise VerificationError("bounded process-exit history is missing: exact four-entry limit")
    if _exit_reason_codes(history) != EXPECTED_EXIT_REASONS:
        raise VerificationError(
            "process-exit reason mapping changed without a privacy review: "
            f"expected={EXPECTED_EXIT_REASONS}, found={_exit_reason_codes(history)}"
        )
    for marker in (
        "@RequiresApi(30)",
        "getHistoricalProcessExitReasons(",
        "context.packageName",
        "MAX_SUPPORT_PROCESS_EXITS",
        "info.timestamp",
        "supportProcessKind(context.packageName, info.processName)",
        "SupportExitReason.fromPlatformCode(info.reason)",
    ):
        if marker not in api30_history:
            raise VerificationError(f"Android process-exit history is missing: {marker}")
    found_exit_forbidden = sorted(
        marker for marker in FORBIDDEN_EXIT_MARKERS if marker in api30_history
    )
    if found_exit_forbidden:
        raise VerificationError(
            "process-exit history may expose raw system details: "
            f"{found_exit_forbidden}"
        )

    events = _event_names(diagnostics)
    if events != EXPECTED_EVENTS:
        raise VerificationError(
            "support event allowlist changed without a privacy review: "
            f"expected={sorted(EXPECTED_EVENTS)}, found={sorted(events)}"
        )
    event_recorders = (
        sources["MainActivity.kt"] +
        sources["ProjectTransfer.kt"] +
        sources["RemoteOperationViewModel.kt"]
    )
    unrecorded = sorted(
        event for event in events if f"SupportEvent.{event}" not in event_recorders
    )
    if unrecorded:
        raise VerificationError(f"support problem categories are never recorded: {unrecorded}")
    for retained_event in ("PROJECT_SAVE_FAILED", "PROJECT_STORAGE_UNAVAILABLE"):
        if f"SupportEvent.{retained_event}" not in sources["ProjectTransfer.kt"]:
            raise VerificationError(
                "retained project diagnostics are missing: " + retained_event
            )

    settings = sources["AppSettingsSheet.kt"]
    for marker in (
        'ActivityResultContracts.CreateDocument("text/plain")',
        "createSupportReport(context.applicationContext, settings)",
        "writeSupportReport(output, report)",
        'SUPPORT_REPORT_FILE_NAME = "DuckySlicer-support.txt"',
    ):
        if marker not in settings:
            raise VerificationError(f"user-chosen support export is missing: {marker}")
    for resource in REQUIRED_STRINGS:
        if f"R.string.{resource}" not in settings:
            raise VerificationError(f"support UI does not use localized copy: {resource}")

    for name in ("strings.xml", "strings-ko.xml"):
        values = _strings(name, sources[name])
        missing_strings = sorted(resource for resource in REQUIRED_STRINGS if not values.get(resource))
        if missing_strings:
            raise VerificationError(f"{name} is missing support copy: {missing_strings}")

    policy = sources["PRIVACY.md"]
    for marker in (
        "Support details are written only to a location you select.",
        "contain models, G-code, file names, printer addresses",
        "On Android 11 and later, they also contain up to four",
        "raw process names, memory samples, or stack traces",
        "지원 정보는 사용자가 선택한 위치에만 저장됩니다.",
        "프로세스 종료 시각, 고정 프로세스 분류 및 고정 종료 원인을 최대 4건 포함합니다.",
        "사용자가 직접 공유한 경우에만 DuckySlicer 프로젝트가 이 정보를 받습니다.",
    ):
        if marker not in policy:
            raise VerificationError(f"privacy policy does not disclose support details: {marker}")

    support = sources["SUPPORT.md"]
    issue = sources["bug_report.yml"]
    for marker in (
        "Settings > Help > Save support details",
        "up to four fixed prior-exit",
        "SECURITY.md",
    ):
        if marker not in support:
            raise VerificationError(f"support guidance is missing: {marker}")
    if "Settings > Help > Save support details" not in issue:
        raise VerificationError("bug report template does not request optional support details")

    host_test = sources["SupportDiagnosticsTest.kt"]
    device_test = sources["SupportDiagnosticsInstrumentedTest.kt"]
    accessibility_test = sources["AccessibilityInstrumentedTest.kt"]
    for marker in (
        "supportReportContainsOnlyBoundedEnvironmentSettingsAndFixedProblemCodes",
        "supportEventCodecRejectsMalformedUnknownAndOversizedHistory",
        "supportReportWriterProducesExactUtf8AndRejectsOversizedInput",
        "processExitMappingNeverExportsAnUnexpectedRawProcessNameOrReason",
    ):
        if marker not in host_test:
            raise VerificationError(f"support host regression is missing: {marker}")
    for marker in (
        "supportDetailsUseRealDeviceFactsWithoutPrivateAppContent",
        "recentProcessExitHistoryUsesOnlyFixedBoundedValues",
        "Os.sysconf(OsConstants._SC_PAGESIZE)",
        "pageSizeBytes == 4_096L || pageSizeBytes == 16_384L",
        'page_size_bytes=$pageSizeBytes',
    ):
        if marker not in device_test:
            raise VerificationError(f"support ARM64 regression is missing: {marker}")
    if "appSettingsExposeAVisibleSupportDetailsAction" not in accessibility_test:
        raise VerificationError("support accessibility regression is missing")


def read_sources() -> dict[str, str]:
    main = ROOT / "android/app/src/main"
    package = main / "java/com/ashcastle/duckyslicer"
    return {
        "SupportDiagnostics.kt": (package / "SupportDiagnostics.kt").read_text(encoding="utf-8"),
        "ProcessExitHistory.kt": (package / "ProcessExitHistory.kt").read_text(encoding="utf-8"),
        "ProcessExitHistoryApi30.kt": (package / "ProcessExitHistoryApi30.kt").read_text(
            encoding="utf-8"
        ),
        "MainActivity.kt": (package / "MainActivity.kt").read_text(encoding="utf-8"),
        "ProjectTransfer.kt": (package / "ProjectTransfer.kt").read_text(encoding="utf-8"),
        "RemoteOperationViewModel.kt": (package / "RemoteOperationViewModel.kt").read_text(
            encoding="utf-8"
        ),
        "AppSettingsSheet.kt": (package / "AppSettingsSheet.kt").read_text(encoding="utf-8"),
        "SupportDiagnosticsTest.kt": (
            ROOT
            / "android/app/src/test/java/com/ashcastle/duckyslicer/SupportDiagnosticsTest.kt"
        ).read_text(encoding="utf-8"),
        "SupportDiagnosticsInstrumentedTest.kt": (
            ROOT
            / "android/app/src/androidTest/java/com/ashcastle/duckyslicer/SupportDiagnosticsInstrumentedTest.kt"
        ).read_text(encoding="utf-8"),
        "AccessibilityInstrumentedTest.kt": (
            ROOT
            / "android/app/src/androidTest/java/com/ashcastle/duckyslicer/AccessibilityInstrumentedTest.kt"
        ).read_text(encoding="utf-8"),
        "strings.xml": (main / "res/values/strings.xml").read_text(encoding="utf-8"),
        "strings-ko.xml": (main / "res/values-ko/strings.xml").read_text(encoding="utf-8"),
        "PRIVACY.md": (ROOT / "PRIVACY.md").read_text(encoding="utf-8"),
        "SUPPORT.md": (ROOT / "SUPPORT.md").read_text(encoding="utf-8"),
        "bug_report.yml": (ROOT / ".github/ISSUE_TEMPLATE/bug_report.yml").read_text(
            encoding="utf-8"
        ),
    }


def main() -> None:
    try:
        verify_support_diagnostics(read_sources())
    except (OSError, VerificationError) as error:
        raise SystemExit(f"Support-diagnostic verification failed: {error}") from error
    print("Verified bounded, user-chosen support details without private app content")


if __name__ == "__main__":
    main()
