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
    "data_handling_title",
    "data_stored_title",
    "data_stored_body",
    "background_slicing_title",
    "background_slicing_body",
    "printer_connection_data_title",
    "printer_connection_data_body",
    "no_tracking_title",
    "no_tracking_body",
    "removing_data_title",
    "removing_data_body",
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
        "RemoteDevice.kt",
        "AndroidManifest.xml",
        "build.gradle.kts",
        "strings.xml",
        "strings-ko.xml",
    }
    missing_files = sorted(required_files - sources.keys())
    if missing_files:
        raise VerificationError(f"data-practice sources are missing: {missing_files}")

    settings = sources["AppSettingsSheet.kt"]
    for marker in ("showDataPractices", "DataPracticesDialog("):
        if marker not in settings:
            raise VerificationError(f"data-practice UI is missing: {marker}")
    for resource_name in DATA_STRING_NAMES:
        if f"R.string.{resource_name}" not in settings:
            raise VerificationError(
                f"data-practice UI does not use required copy: {resource_name}"
            )

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

    remote = sources["RemoteDevice.kt"]
    for marker in (
        'KeyStore.getInstance("AndroidKeyStore")',
        "secrets.remove(profileId)",
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
        "RemoteDevice.kt": (package / "RemoteDevice.kt").read_text(encoding="utf-8"),
        "AndroidManifest.xml": (main / "AndroidManifest.xml").read_text(encoding="utf-8"),
        "build.gradle.kts": (ROOT / "android/app/build.gradle.kts").read_text(
            encoding="utf-8"
        ),
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
    print("Verified bilingual data explanation against permissions and connection behavior")


if __name__ == "__main__":
    main()
