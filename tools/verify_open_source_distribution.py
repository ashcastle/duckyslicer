#!/usr/bin/env python3
"""Verify that every APK build exposes complete offline open-source notices."""

from __future__ import annotations

import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
SOURCE_URL = "https://github.com/ashcastle/duckyslicer"
LEGAL_ASSETS = (
    "legal/AGPL-3.0.txt",
    "legal/THIRD_PARTY_NOTICES.md",
    "legal/THIRD_PARTY_LICENSES.txt",
)
SETTINGS_LEGAL_ASSETS = (
    "legal/AGPL-3.0.txt",
    "legal/THIRD_PARTY_LICENSES.txt",
)
REQUIRED_STRINGS = {
    "about",
    "app_version",
    "open_source_summary",
    "open_source_license",
    "third_party_notices",
    "view_source_code",
    "close",
}


class VerificationError(ValueError):
    """The source distribution or in-app legal-notice path is incomplete."""


def parse_versions(source: str) -> dict[str, str]:
    return {
        key: value
        for line in source.splitlines()
        if line and not line.lstrip().startswith("#") and "=" in line
        for key, value in (line.split("=", 1),)
    }


def parse_strings(source: str, language: str) -> dict[str, str]:
    try:
        root = ET.fromstring(source)
    except ET.ParseError as error:
        raise VerificationError(f"invalid {language} string resources: {error}") from error
    return {
        element.get("name", ""): "".join(element.itertext()).strip()
        for element in root.findall("string")
    }


def verify_distribution(sources: dict[str, str]) -> None:
    required_files = {
        "LICENSE.txt",
        "THIRD_PARTY_NOTICES.md",
        "README.md",
        "native/slicer-runtime/versions.env",
        "tools/generate_offline_licenses.py",
        "tools/native_license_policy.py",
        "android/app/build.gradle.kts",
        "android/app/src/main/java/com/ashcastle/duckyslicer/AppSettingsSheet.kt",
        "android/app/src/main/res/values/strings.xml",
        "android/app/src/main/res/values-ko/strings.xml",
    }
    missing_files = sorted(required_files - sources.keys())
    if missing_files:
        raise VerificationError(f"required distribution files are missing: {missing_files}")

    license_text = sources["LICENSE.txt"]
    if "GNU AFFERO GENERAL PUBLIC LICENSE" not in license_text or "WITHOUT ANY WARRANTY" not in license_text:
        raise VerificationError("LICENSE.txt is not a complete AGPL v3 license")

    readme = sources["README.md"]
    if "[GNU Affero General Public License v3](LICENSE.txt)" not in readme:
        raise VerificationError("README must link to the tracked LICENSE.txt file")
    if "[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)" not in readme:
        raise VerificationError("README must link to the third-party notices")

    gradle = sources["android/app/build.gradle.kts"]
    for marker in (
        "prepareOpenSourceNotices",
        "registerOfflineLicenseBundle",
        "generate_offline_licenses.py",
        "LICENSE.txt",
        "THIRD_PARTY_NOTICES.md",
        "THIRD_PARTY_LICENSES.txt",
        "generatedLegalAssets",
    ):
        if marker not in gradle:
            raise VerificationError(f"Gradle offline legal packaging is missing: {marker}")

    generator = sources["tools/generate_offline_licenses.py"]
    policy = sources["tools/native_license_policy.py"]
    for marker in ("verify_vendored_policy", "native_notice_sources", "render_bundle"):
        if marker not in generator:
            raise VerificationError(f"offline license generator is incomplete: {marker}")
    for marker in ("VENDORED_COMPONENTS", "native_components", "native_notice_sources"):
        if marker not in policy:
            raise VerificationError(f"native license policy is incomplete: {marker}")

    settings = sources["android/app/src/main/java/com/ashcastle/duckyslicer/AppSettingsSheet.kt"]
    for marker in (*SETTINGS_LEGAL_ASSETS, SOURCE_URL, "BuildConfig.VERSION_NAME", "open_source_summary"):
        if marker not in settings:
            raise VerificationError(f"Settings legal-notice access is missing: {marker}")

    english = parse_strings(sources["android/app/src/main/res/values/strings.xml"], "English")
    korean = parse_strings(sources["android/app/src/main/res/values-ko/strings.xml"], "Korean")
    for language, strings in (("English", english), ("Korean", korean)):
        missing = sorted(name for name in REQUIRED_STRINGS if not strings.get(name))
        if missing:
            raise VerificationError(f"{language} legal strings are missing: {missing}")
    if "without any warranty" not in english["open_source_summary"].lower():
        raise VerificationError("English settings must display the no-warranty notice")
    if "보증" not in korean["open_source_summary"]:
        raise VerificationError("Korean settings must display the no-warranty notice")

    versions = parse_versions(sources["native/slicer-runtime/versions.env"])
    notices = sources["THIRD_PARTY_NOTICES.md"]
    for key in ("ANDROID_SLICER_RUNTIME_COMMIT", "SLICER_ENGINE_COMMIT"):
        revision = versions.get(key)
        if not revision or revision not in notices:
            raise VerificationError(f"third-party notices do not match {key}")


def read_sources() -> dict[str, str]:
    paths = (
        "LICENSE.txt",
        "THIRD_PARTY_NOTICES.md",
        "README.md",
        "native/slicer-runtime/versions.env",
        "tools/generate_offline_licenses.py",
        "tools/native_license_policy.py",
        "android/app/build.gradle.kts",
        "android/app/src/main/java/com/ashcastle/duckyslicer/AppSettingsSheet.kt",
        "android/app/src/main/res/values/strings.xml",
        "android/app/src/main/res/values-ko/strings.xml",
    )
    return {name: (ROOT / name).read_text(encoding="utf-8") for name in paths}


def main() -> None:
    try:
        verify_distribution(read_sources())
    except (OSError, VerificationError) as error:
        raise SystemExit(f"Open-source distribution verification failed: {error}") from error
    print("Verified offline AGPL notice, matching third-party revisions, source link, and bilingual Settings access")


if __name__ == "__main__":
    main()
