#!/usr/bin/env python3
"""Enforce the mobile Orca-style slicing profile editor structure."""

from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent


class VerificationError(ValueError):
    pass


def verify_profile_editor(sources: dict[str, str]) -> None:
    required = {
        "ProfileSettingsSheet.kt",
        "ProfileRecents.kt",
        "ProfileRecentsTest.kt",
        "SlicingSettingsSectionTest.kt",
        "strings.xml",
        "strings-ko.xml",
        "README.md",
        "CONTRIBUTING.md",
    }
    missing = sorted(required - sources.keys())
    if missing:
        raise VerificationError(f"profile editor sources are missing: {missing}")

    editor = sources["ProfileSettingsSheet.kt"]
    ordered_sections = (
        "QUALITY(R.string.quality)",
        "STRENGTH(R.string.strength)",
        "SPEED(R.string.speed)",
        "SUPPORT(R.string.supports)",
        "OTHERS(R.string.others)",
    )
    positions = [editor.find(marker) for marker in ordered_sections]
    if any(position < 0 for position in positions) or positions != sorted(positions):
        raise VerificationError("slicing editor section order must be Quality, Strength, Speed, Support, Others")
    for marker in (
        "SecondaryScrollableTabRow(",
        "selectedTabIndex = SlicingSettingsSection.entries.indexOf(selected)",
        "scrollKey = selectedSection",
        "SlicingSettingsSection.entries.forEach",
    ):
        if marker not in editor:
            raise VerificationError(f"mobile slicing tabs are missing: {marker}")
    slicing_editor = editor.split("private fun SlicingSettingsSheet(", 1)[-1]
    tab_position = slicing_editor.find("SlicingSettingsTabs(")
    profile_position = slicing_editor.find("SearchableGroupedProfileChoices(")
    settings_position = slicing_editor.find("when (selectedSection)")
    if not (0 <= tab_position < profile_position < settings_position):
        raise VerificationError("slicing tabs and profile selection must precede section settings")

    for marker in (
        "recentIds: List<String>",
        "val recentProfilesKey = \"recent-profiles\"",
        "add(ProfileChoiceGroup(recentProfilesKey, recentProfiles, recentEntries))",
    ):
        if marker not in editor:
            raise VerificationError(f"recent profile group is missing: {marker}")

    recents = sources["ProfileRecents.kt"]
    for marker in (
        "data class ProfileRecents(",
        "MAX_RECENT_PROFILES = 5",
        "class ProfileRecentStore",
        "DurableJsonFile",
        "fun record(options: SliceOptions)",
    ):
        if marker not in recents:
            raise VerificationError(f"recent profile persistence is missing: {marker}")

    for source_name in ("strings.xml", "strings-ko.xml"):
        strings = sources[source_name]
        for resource in ('name="quality"', 'name="strength"', 'name="speed"', 'name="supports"', 'name="others"'):
            if resource not in strings:
                raise VerificationError(f"localized slicing tab title is missing from {source_name}: {resource}")
        if 'name="recent_profiles"' not in strings:
            raise VerificationError(f"localized recent profile title is missing from {source_name}")

    test = sources["SlicingSettingsSectionTest.kt"]
    for marker in (
        "processEditorUsesOrcaStyleSectionOrder",
        'listOf("QUALITY", "STRENGTH", "SPEED", "SUPPORT", "OTHERS")',
        "Every section must have its own localized title",
    ):
        if marker not in test:
            raise VerificationError(f"slicing section host regression is missing: {marker}")

    recent_test = sources["ProfileRecentsTest.kt"]
    for marker in (
        "usageMovesProfilesToTheFrontWithoutDuplicatesAndCapsHistory",
        "threeProfileKindsRoundTripIndependently",
        "corruptPrimaryRecoversLastKnownGoodRecentProfiles",
    ):
        if marker not in recent_test:
            raise VerificationError(f"recent profile host regression is missing: {marker}")

    for document in ("README.md", "CONTRIBUTING.md"):
        lowered = sources[document].lower()
        if not all(term in lowered for term in ("quality", "strength", "speed", "support", "others")):
            raise VerificationError(f"Orca-style slicing sections are not documented in {document}")


def read_sources() -> dict[str, str]:
    main = ROOT / "android/app/src/main/java/com/ashcastle/duckyslicer"
    tests = ROOT / "android/app/src/test/java/com/ashcastle/duckyslicer"
    resources = ROOT / "android/app/src/main/res"
    return {
        "ProfileSettingsSheet.kt": (main / "ProfileSettingsSheet.kt").read_text(encoding="utf-8"),
        "ProfileRecents.kt": (main / "ProfileRecents.kt").read_text(encoding="utf-8"),
        "ProfileRecentsTest.kt": (tests / "ProfileRecentsTest.kt").read_text(encoding="utf-8"),
        "SlicingSettingsSectionTest.kt": (tests / "SlicingSettingsSectionTest.kt").read_text(
            encoding="utf-8"
        ),
        "strings.xml": (resources / "values/strings.xml").read_text(encoding="utf-8"),
        "strings-ko.xml": (resources / "values-ko/strings.xml").read_text(encoding="utf-8"),
        "README.md": (ROOT / "README.md").read_text(encoding="utf-8"),
        "CONTRIBUTING.md": (ROOT / "CONTRIBUTING.md").read_text(encoding="utf-8"),
    }


def main() -> None:
    try:
        verify_profile_editor(read_sources())
    except (OSError, VerificationError) as error:
        raise SystemExit(f"Profile editor verification failed: {error}") from error
    print("Verified localized Orca-style mobile slicing profile sections")


if __name__ == "__main__":
    main()
