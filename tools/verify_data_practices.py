#!/usr/bin/env python3
"""Keep the in-app data explanation aligned with Android behavior."""

from __future__ import annotations

import xml.etree.ElementTree as ElementTree
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
ANDROID_NAME = "{http://schemas.android.com/apk/res/android}name"
EXPECTED_PERMISSIONS = {
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
    "android.permission.INTERNET",
    "android.permission.POST_NOTIFICATIONS",
}
DATA_STRING_NAMES = {
    "data_privacy",
    "data_privacy_summary",
    "data_handling_details",
    "privacy_policy",
    "data_handling_title",
    "data_stored_title",
    "data_stored_body",
    "background_slicing_title",
    "background_slicing_body",
    "slice_notifications_on",
    "slice_notifications_off",
    "slice_notifications_summary",
    "manage_slice_notifications",
    "printer_connection_data_title",
    "printer_connection_data_body",
    "no_tracking_title",
    "no_tracking_body",
    "removing_data_title",
    "removing_data_body",
}
PRIVACY_POLICY_MARKERS = {
    "DuckySlicer Privacy Policy",
    "Effective date: August 11, 2026",
    "does not collect, sell, or share",
    "Data stored on your device",
    "Actions that send data elsewhere",
    "Retention and deletion",
    "Privacy questions and changes",
    "Exported profile bundles contain only user-created printer, filament, and slicing",
    "They do not contain projects, models, G-code, recent choices, app settings",
    "https://github.com/ashcastle/duckyslicer/issues/new",
    "DuckySlicer 개인정보처리방침",
    "시행일: 2026년 8월 11일",
    "수집·판매·공유하지 않습니다",
    "내보낸 프로필 묶음에는 사용자가 만든 프린터·필라멘트·슬라이싱 설정만",
    "프린터 주소 또는 접속 키는 포함되지 않습니다",
    "보관 및 삭제",
}
TRACKING_MARKERS = {
    "adjust-android",
    "appcenter",
    "appsflyer",
    "com.facebook.android",
    "datadog",
    "firebase-analytics",
    "firebase-crashlytics",
    "newrelic",
    "play-services-ads",
    "sentry-android",
}


class VerificationError(ValueError):
    pass


def _xml_root(name: str, source: str) -> ElementTree.Element:
    try:
        return ElementTree.fromstring(source)
    except ElementTree.ParseError as error:
        raise VerificationError(f"{name} is not valid XML: {error}") from error


def _string_values(name: str, source: str) -> dict[str, str]:
    root = _xml_root(name, source)
    return {
        element.attrib["name"]: "".join(element.itertext()).strip()
        for element in root.findall("string")
        if "name" in element.attrib
    }


def verify_data_practices(sources: dict[str, str]) -> None:
    required_files = {
        "AppSettingsSheet.kt",
        "MainActivity.kt",
        "RemoteDevice.kt",
        "AndroidManifest.xml",
        "build.gradle.kts",
        "PRIVACY.md",
        "strings.xml",
        "strings-ko.xml",
    }
    missing_files = sorted(required_files - sources.keys())
    if missing_files:
        raise VerificationError(f"data-practice sources are missing: {missing_files}")

    settings = sources["AppSettingsSheet.kt"]
    for marker in (
        "showDataPractices",
        "DataPracticesDialog(",
        "LegalDocument.PRIVACY",
        'PRIVACY("legal/PRIVACY.md", R.string.privacy_policy)',
        "sliceNotificationsEnabled(context)",
        "openSliceNotificationSettings(context)",
        "Settings.ACTION_APP_NOTIFICATION_SETTINGS",
        "Settings.EXTRA_APP_PACKAGE",
        "Lifecycle.Event.ON_RESUME",
        "stateDescription = notificationState",
    ):
        if marker not in settings:
            raise VerificationError(f"data-practice UI is missing: {marker}")
    for resource_name in DATA_STRING_NAMES:
        if f"R.string.{resource_name}" not in settings:
            raise VerificationError(
                f"data-practice UI does not use required copy: {resource_name}"
            )

    activity = sources["MainActivity.kt"]
    for marker in (
        "ActivityResultContracts.RequestPermission()",
        "Manifest.permission.POST_NOTIFICATIONS",
        "SLICE_NOTIFICATION_PERMISSION_ASKED",
        ".putBoolean(SLICE_NOTIFICATION_PERMISSION_ASKED, true)",
    ):
        if marker not in activity:
            raise VerificationError(f"slice notification permission flow is missing: {marker}")

    for source_name in ("strings.xml", "strings-ko.xml"):
        values = _string_values(source_name, sources[source_name])
        missing_strings = sorted(DATA_STRING_NAMES - values.keys())
        if missing_strings:
            raise VerificationError(
                f"{source_name} is missing data-practice copy: {missing_strings}"
            )
        blank_strings = sorted(name for name in DATA_STRING_NAMES if not values[name])
        if blank_strings:
            raise VerificationError(
                f"{source_name} has blank data-practice copy: {blank_strings}"
            )

    manifest = _xml_root("AndroidManifest.xml", sources["AndroidManifest.xml"])
    permissions = {
        permission.attrib.get(ANDROID_NAME, "")
        for permission in manifest.findall("uses-permission")
    }
    permissions.discard("")
    if permissions != EXPECTED_PERMISSIONS:
        raise VerificationError(
            "Android permissions no longer match the in-app data explanation: "
            f"expected {sorted(EXPECTED_PERMISSIONS)}, found {sorted(permissions)}"
        )

    gradle = sources["build.gradle.kts"].lower()
    found_tracking = sorted(marker for marker in TRACKING_MARKERS if marker in gradle)
    if found_tracking:
        raise VerificationError(
            "tracking, advertising, or crash-reporting dependency needs a disclosure review: "
            f"{found_tracking}"
        )
    for marker in ('repositoryroot.resolve("privacy.md")', 'into("legal")'):
        if marker not in gradle:
            raise VerificationError(f"offline privacy-policy packaging is missing: {marker}")

    policy = sources["PRIVACY.md"]
    missing_policy_markers = sorted(
        marker for marker in PRIVACY_POLICY_MARKERS if marker not in policy
    )
    if missing_policy_markers:
        raise VerificationError(
            "privacy policy is incomplete or no longer matches the app: "
            f"{missing_policy_markers}"
        )

    remote = sources["RemoteDevice.kt"]
    for marker in (
        'KeyStore.getInstance("AndroidKeyStore")',
        "removedCredentialKey?.let(secrets::remove)",
        '"print" to "false"',
        "instanceFollowRedirects = false",
    ):
        if marker not in remote:
            raise VerificationError(
                f"printer-connection behavior no longer supports the data explanation: {marker}"
            )


def read_sources() -> dict[str, str]:
    main = ROOT / "android/app/src/main"
    package = main / "java/com/ashcastle/duckyslicer"
    return {
        "AppSettingsSheet.kt": (package / "AppSettingsSheet.kt").read_text(
            encoding="utf-8"
        ),
        "MainActivity.kt": (package / "MainActivity.kt").read_text(encoding="utf-8"),
        "RemoteDevice.kt": (package / "RemoteDevice.kt").read_text(encoding="utf-8"),
        "AndroidManifest.xml": (main / "AndroidManifest.xml").read_text(encoding="utf-8"),
        "build.gradle.kts": (ROOT / "android/app/build.gradle.kts").read_text(
            encoding="utf-8"
        ),
        "PRIVACY.md": (ROOT / "PRIVACY.md").read_text(encoding="utf-8"),
        "strings.xml": (main / "res/values/strings.xml").read_text(encoding="utf-8"),
        "strings-ko.xml": (main / "res/values-ko/strings.xml").read_text(
            encoding="utf-8"
        ),
    }


def main() -> None:
    try:
        verify_data_practices(read_sources())
    except (OSError, VerificationError) as error:
        raise SystemExit(f"Data-practice verification failed: {error}") from error
    print(
        "Verified bilingual data explanation, offline privacy policy, permissions, "
        "and connection behavior"
    )


if __name__ == "__main__":
    main()
