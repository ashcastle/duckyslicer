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
            " data class ProfileEditSession( val isDirty: Boolean get() = working != opening "
            "fun revert(): ProfileEditSession fun applied(): ProfileEditSession "
            "ProfileDirtyActionBar( Modifier.weight(3f) Modifier.weight(7f) .imePadding() "
            "onClickLabel = editDetailsLabel .heightIn(min = 48.dp) "
            ".selectable( role = Role.RadioButton onClick = null "
            ".selectable( role = Role.RadioButton onClick = null "
            ".semantics { stateDescription = groupState }"
            " internal fun SettingsSwitch( .heightIn(min = 48.dp) .toggleable( "
            "role = Role.Switch .semantics(mergeDescendants = true) "
            "contentDescription = label Switch(checked = checked, onCheckedChange = null) "
            "@Composable internal fun SettingSlider( modifier = Modifier.semantics "
            "contentDescription = label stateDescription = valueText @Composable"
            " LocalWindowInfo.current.containerSize.height.toDp()"
        ),
        "DeviceSheet.kt": (
            "profiles.forEach .selectable( role = Role.RadioButton "
            "RadioButton(selected = isSelected, onClick = null) if (selected != null)"
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
        "ProfileEditSessionTest.kt": (
            "changesStayStagedUntilApplied revertRestoresTheOpeningSnapshot "
            "applyPromotesWorkingValuesWithoutClosingTheSession"
        ),
        "SlicingSettingsSectionTest.kt": (
            "processEditorUsesOrcaStyleSectionOrder "
            'listOf("QUALITY", "STRENGTH", "SPEED", "SUPPORT", "OTHERS") '
            "Every section must have its own localized title"
        ),
        "strings.xml": (
            'name="quality" name="strength" name="speed" name="supports" name="others" '
            'name="recent_profiles" name="revert_changes" name="apply_changes"'
            ' name="expanded_state" name="collapsed_state"'
        ),
        "strings-ko.xml": (
            'name="quality" name="strength" name="speed" name="supports" name="others" '
            'name="recent_profiles" name="revert_changes" name="apply_changes"'
            ' name="expanded_state" name="collapsed_state"'
        ),
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

    def test_rejects_non_sticky_equal_width_profile_actions(self) -> None:
        sources = valid_sources()
        sources["ProfileSettingsSheet.kt"] = sources["ProfileSettingsSheet.kt"].replace(
            "Modifier.weight(7f)", "Modifier.weight(3f)"
        )
        with self.assertRaisesRegex(VerificationError, r"weight\(7f\)"):
            verify_profile_editor(sources)

    def test_rejects_duplicate_profile_radio_click_targets(self) -> None:
        sources = valid_sources()
        sources["ProfileSettingsSheet.kt"] = sources["ProfileSettingsSheet.kt"].replace(
            "onClick = null", "onClick = { onSelected(entry) }", 1
        )
        with self.assertRaisesRegex(VerificationError, "one radio target per row"):
            verify_profile_editor(sources)

    def test_rejects_missing_profile_touch_target_minimum(self) -> None:
        sources = valid_sources()
        sources["ProfileSettingsSheet.kt"] = sources["ProfileSettingsSheet.kt"].replace(
            ".heightIn(min = 48.dp)", ".heightIn(min = 32.dp)"
        )
        with self.assertRaisesRegex(VerificationError, r"heightIn\(min = 48\.dp\)"):
            verify_profile_editor(sources)

    def test_rejects_screen_metrics_for_resizable_profile_sheet(self) -> None:
        sources = valid_sources()
        sources["ProfileSettingsSheet.kt"] = sources["ProfileSettingsSheet.kt"].replace(
            "LocalWindowInfo.current.containerSize.height.toDp()",
            "LocalConfiguration.current.screenHeightDp.dp",
        )
        with self.assertRaisesRegex(VerificationError, "LocalWindowInfo"):
            verify_profile_editor(sources)

    def test_rejects_unnamed_profile_slider(self) -> None:
        sources = valid_sources()
        sources["ProfileSettingsSheet.kt"] = sources["ProfileSettingsSheet.kt"].replace(
            "stateDescription = valueText", "missingState = valueText"
        )
        with self.assertRaisesRegex(VerificationError, "profile slider accessibility"):
            verify_profile_editor(sources)

    def test_rejects_switch_with_duplicate_nested_action(self) -> None:
        sources = valid_sources()
        sources["ProfileSettingsSheet.kt"] = sources["ProfileSettingsSheet.kt"].replace(
            "Switch(checked = checked, onCheckedChange = null)",
            "Switch(checked = checked, onCheckedChange = onCheckedChange)",
        )
        with self.assertRaisesRegex(VerificationError, "profile switch accessibility"):
            verify_profile_editor(sources)

    def test_rejects_duplicate_remote_device_radio_action(self) -> None:
        sources = valid_sources()
        sources["DeviceSheet.kt"] = sources["DeviceSheet.kt"].replace(
            "RadioButton(selected = isSelected, onClick = null)",
            "RadioButton(selected = isSelected, onClick = { onSelect(profile.id) })",
        )
        with self.assertRaisesRegex(VerificationError, "remote device selection accessibility"):
            verify_profile_editor(sources)


if __name__ == "__main__":
    unittest.main()
