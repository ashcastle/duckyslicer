from __future__ import annotations

import unittest

from tools.verify_profile_editor import VerificationError, verify_profile_editor


def valid_sources() -> dict[str, str]:
    return {
        "ProfileSettingsSheet.kt": (
            "private enum class SlicingSettingsSection { "
            "QUALITY(R.string.quality) STRENGTH(R.string.strength) SPEED(R.string.speed) "
            "SUPPORT(R.string.supports) OTHERS(R.string.others) } "
            "private fun SlicingSettingsSheet( SlicingSettingsTabs( "
            "SearchableGroupedProfileChoices( when (selectedSection) "
            "SecondaryScrollableTabRow( "
            "selectedTabIndex = SlicingSettingsSection.entries.indexOf(selected) "
            "scrollKey = selectedSection SlicingSettingsSection.entries.forEach"
            " recentIds: List<String> val recentProfilesKey = \"recent-profiles\" "
            "add(ProfileChoiceGroup(recentProfilesKey, recentProfiles, recentEntries))"
        ),
        "ProfileRecents.kt": (
            "data class ProfileRecents( MAX_RECENT_PROFILES = 5 class ProfileRecentStore "
            "DurableJsonFile fun record(options: SliceOptions)"
        ),
        "ProfileRecentsTest.kt": (
            "usageMovesProfilesToTheFrontWithoutDuplicatesAndCapsHistory "
            "threeProfileKindsRoundTripIndependently "
            "corruptPrimaryRecoversLastKnownGoodRecentProfiles"
        ),
        "SlicingSettingsSectionTest.kt": (
            "processEditorUsesOrcaStyleSectionOrder "
            'listOf("QUALITY", "STRENGTH", "SPEED", "SUPPORT", "OTHERS") '
            "Every section must have its own localized title"
        ),
        "strings.xml": (
            'name="quality" name="strength" name="speed" name="supports" name="others" '
            'name="recent_profiles"'
        ),
        "strings-ko.xml": (
            'name="quality" name="strength" name="speed" name="supports" name="others" '
            'name="recent_profiles"'
        ),
        "README.md": "Quality Strength Speed Support Others",
        "CONTRIBUTING.md": "Quality Strength Speed Support Others",
    }


class VerifyProfileEditorTest(unittest.TestCase):
    def test_accepts_orca_style_mobile_sections(self) -> None:
        verify_profile_editor(valid_sources())

    def test_rejects_reordered_sections(self) -> None:
        sources = valid_sources()
        sources["ProfileSettingsSheet.kt"] = sources["ProfileSettingsSheet.kt"].replace(
            "QUALITY(R.string.quality) STRENGTH(R.string.strength)",
            "STRENGTH(R.string.strength) QUALITY(R.string.quality)",
        )
        with self.assertRaisesRegex(VerificationError, "section order"):
            verify_profile_editor(sources)

    def test_rejects_non_scrollable_mobile_tabs(self) -> None:
        sources = valid_sources()
        sources["ProfileSettingsSheet.kt"] = sources["ProfileSettingsSheet.kt"].replace(
            "SecondaryScrollableTabRow(", "Row("
        )
        with self.assertRaisesRegex(VerificationError, "SecondaryScrollableTabRow"):
            verify_profile_editor(sources)

    def test_rejects_missing_korean_title(self) -> None:
        sources = valid_sources()
        sources["strings-ko.xml"] = sources["strings-ko.xml"].replace('name="strength"', 'name="missing"')
        with self.assertRaisesRegex(VerificationError, "strength"):
            verify_profile_editor(sources)

    def test_rejects_missing_recent_profile_persistence(self) -> None:
        sources = valid_sources()
        sources["ProfileRecents.kt"] = sources["ProfileRecents.kt"].replace(
            "DurableJsonFile", "VolatileMemory"
        )
        with self.assertRaisesRegex(VerificationError, "DurableJsonFile"):
            verify_profile_editor(sources)


if __name__ == "__main__":
    unittest.main()
