#!/usr/bin/env python3
"""Verify DuckySlicer's device-locale and inherited Orca translation policy."""

from __future__ import annotations

import tempfile
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parent.parent
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))

from tools.generate_android_translations import (
    EXPECTED_TRANSLATION_COUNTS,
    ORCA_LOCALE_TO_ANDROID,
    ROOT,
    SUPPORTED_LANGUAGE_TAGS,
    SUPPORTED_ORCA_LOCALES,
    generate_translation_resources,
    read_android_strings,
)


ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
DEFAULT_STRINGS = ROOT / "android/app/src/main/res/values/strings.xml"
KOREAN_STRINGS = ROOT / "android/app/src/main/res/values-ko/strings.xml"
LOCALES_CONFIG = ROOT / "android/app/src/main/res/xml/locales_config.xml"
ORCA_I18N = ROOT / "localization/i18n"
BUILD_GRADLE = ROOT / "android/app/build.gradle.kts"
REQUIRED_COMMON_KEYS = {
    "cut_model",
    "prepare",
    "preview",
    "scale",
    "settings",
    "split_to_objects",
    "support_erase",
    "tab_device",
    "toolpath_outer_wall",
    "variable_layer_height",
}


class LocalizationVerificationError(ValueError):
    """The Android localization surface diverges from the reviewed Orca policy."""


def configured_language_tags(source: Path) -> tuple[str, ...]:
    try:
        root = ET.parse(source).getroot()
    except (OSError, ET.ParseError) as error:
        raise LocalizationVerificationError(f"invalid locale config: {source}") from error
    if root.tag != "locale-config":
        raise LocalizationVerificationError("locale config root changed")
    attribute = f"{{{ANDROID_NAMESPACE}}}name"
    tags = tuple(element.attrib.get(attribute, "") for element in root.findall("locale"))
    if not tags or "" in tags or len(tags) != len(set(tags)):
        raise LocalizationVerificationError("locale config has missing or duplicate tags")
    return tags


def verify_hand_maintained_strings(default_source: Path, korean_source: Path) -> int:
    default = dict(read_android_strings(default_source))
    korean = dict(read_android_strings(korean_source))
    if set(default) != set(korean):
        missing = sorted(set(default) - set(korean))
        extra = sorted(set(korean) - set(default))
        raise LocalizationVerificationError(
            f"English/Korean string keys differ: missing={missing}, extra={extra}"
        )
    if len(default) < 500:
        raise LocalizationVerificationError("the mobile string surface is unexpectedly small")
    if any(not value.strip() for value in default.values()) or any(
        not value.strip() for value in korean.values()
    ):
        raise LocalizationVerificationError("hand-maintained Android strings cannot be blank")
    return len(default)


def verify_build_integration(source: str) -> None:
    markers = (
        'tasks.register<Exec>("generateAndroidTranslations")',
        'repositoryRoot.resolve("tools/generate_android_translations.py")',
        'layout.buildDirectory.dir("generated/android-translations/res")',
        'dependsOn(buildRustNative, generateAndroidTranslations, prepareOpenSourceNotices)',
        'sourceSets.getByName("main").res.directories.add(',
        "localeFilters += listOf(",
        'disable += "MissingTranslation"',
    )
    missing = [marker for marker in markers if marker not in source]
    if missing:
        raise LocalizationVerificationError(
            "Android translation build integration changed: " + ", ".join(missing)
        )


def verify_localization() -> tuple[int, int, int]:
    string_count = verify_hand_maintained_strings(DEFAULT_STRINGS, KOREAN_STRINGS)
    if configured_language_tags(LOCALES_CONFIG) != SUPPORTED_LANGUAGE_TAGS:
        raise LocalizationVerificationError("configured app languages differ from Orca")

    source_locales = {path.name for path in ORCA_I18N.iterdir() if path.is_dir()}
    if source_locales != set(SUPPORTED_ORCA_LOCALES):
        raise LocalizationVerificationError(
            f"Orca locale set changed: expected={sorted(SUPPORTED_ORCA_LOCALES)}, "
            f"found={sorted(source_locales)}"
        )
    tracked_overlays = {
        path.name
        for path in (ROOT / "android/app/src/main/res").glob("values-*")
        if path.is_dir() and (path / "strings.xml").is_file()
    }
    if tracked_overlays != {"values-ko"}:
        raise LocalizationVerificationError(
            f"generated locale resources must not be checked in: {sorted(tracked_overlays)}"
        )
    verify_build_integration(BUILD_GRADLE.read_text(encoding="utf-8"))

    common_keys: set[str] | None = None
    with tempfile.TemporaryDirectory() as directory:
        output = Path(directory) / "res"
        counts = generate_translation_resources(DEFAULT_STRINGS, ORCA_I18N, output)
        if counts != EXPECTED_TRANSLATION_COUNTS:
            raise LocalizationVerificationError("reviewed exact translation counts changed")
        for _, qualifier in ORCA_LOCALE_TO_ANDROID.values():
            resource = output / f"values-{qualifier}/strings.xml"
            keys = {
                element.attrib["name"]
                for element in ET.parse(resource).getroot().findall("string")
            }
            common_keys = keys if common_keys is None else common_keys & keys
    common_keys = common_keys or set()
    if not REQUIRED_COMMON_KEYS <= common_keys or len(common_keys) < 100:
        raise LocalizationVerificationError(
            f"shared translated mobile surface is incomplete: {len(common_keys)} keys"
        )
    return string_count, sum(EXPECTED_TRANSLATION_COUNTS.values()), len(common_keys)


def main() -> None:
    try:
        strings, translations, common = verify_localization()
    except (OSError, LocalizationVerificationError) as error:
        raise SystemExit(f"Localization verification failed: {error}") from error
    print(
        f"Verified {len(SUPPORTED_LANGUAGE_TAGS)} Orca languages: {strings} English/Korean "
        f"strings, {translations} inherited translations, {common} shared core labels"
    )


if __name__ == "__main__":
    main()
