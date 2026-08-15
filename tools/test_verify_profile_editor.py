from __future__ import annotations

import unittest

from tools.verify_profile_editor import VerificationError, verify_profile_editor


def valid_sources() -> dict[str, str]:
    return {
        "ProfileSettingsSheet.kt": (
            "private enum class SlicingSettingsSection { "
            "QUALITY(R.string.quality) STRENGTH(R.string.strength) SPEED(R.string.speed) "
            "SUPPORT(R.string.supports) OTHERS(R.string.others) } "
            "private fun SlicingSettingsSheet( CurrentProfileButton( SlicingSettingsTabs( "
            "renderedSections.forEach ProfileChooserSheet( ProfileChooserSheet( "
            "ProfileChooserSheet( ProfileChooserSheet( "
            "settingQuery = settingsQuery onSettingQueryChanged = { settingsQuery = it } "
            "LocalSettingsQuery provides settingQueryMatches( private fun <T> SettingChoices( "
            + ("SettingChoices( " * 19)
            + "SecondaryScrollableTabRow( "
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
            "contentDescription = label Switch(checked = checked, enabled = enabled, onCheckedChange = null) "
            "@Composable internal fun SettingSlider( modifier = Modifier.semantics "
            "contentDescription = label stateDescription = valueText @Composable"
            " LocalWindowInfo.current.containerSize.height.toDp()"
            " options.withSpiralMode(enabled) R.string.spiral_vase R.string.smooth_spiral"
            " R.string.max_xy_smoothing R.string.spiral_starting_flow"
            " R.string.spiral_finishing_flow"
            " R.string.print_sequence R.string.intra_layer_order"
            " R.string.print_by_layer R.string.print_by_object"
            " R.string.order_default R.string.order_as_object_list"
            " R.string.sequential_printing_clearance R.string.extruder_clearance_radius"
            " R.string.extruder_clearance_height_to_rod R.string.extruder_clearance_height_to_lid"
            " R.string.make_overhangs_printable R.string.maximum_overhang_angle"
            " R.string.overhang_base_hole_area"
            " R.string.number_of_slow_layers"
            " R.string.brim_ear_maximum_angle R.string.brim_ear_detection_radius"
            " R.string.top_surface_density R.string.bottom_surface_density"
            " R.string.infill_shift_step R.string.symmetric_infill_y_axis"
            " R.string.sparse_infill_rotation_template R.string.solid_infill_rotation_template"
            " RotationTemplateSetting("
            " infillShiftStep = (it * 10f) symmetricInfillYAxis = it"
            " R.string.support_on_build_plate_only R.string.support_base_pattern_spacing"
            " R.string.support_expansion R.string.support_interface_loop_pattern"
            " R.string.independent_support_layer_height"
            " R.string.normal_support_auto R.string.tree_support_auto"
            " R.string.normal_support_manual R.string.tree_support_manual"
            " R.string.tree_support_branch_angle R.string.tree_support_branch_distance"
            " R.string.tree_support_branch_diameter R.string.tree_support_wall_count"
            " R.string.tree_support_tip_diameter R.string.tree_support_preferred_branch_angle"
            " R.string.tree_support_organic_branch_angle R.string.tree_support_organic_branch_distance"
            " R.string.tree_support_organic_branch_diameter R.string.tree_support_branch_diameter_angle"
            " R.string.tree_support_branch_density R.string.tree_support_adaptive_layer_height"
            " R.string.tree_support_auto_brim R.string.tree_support_brim_width"
            " R.string.segmented_region_max_width R.string.segmented_region_interlocking_depth"
            " segmentedRegionMaxWidth = segmentedRegionInterlockingDepth ="
            " segmentedRegionInterlockingDepth = if (it)"
            " R.string.machine_gcode R.string.machine_start_gcode R.string.machine_end_gcode"
            " R.string.before_layer_change_gcode R.string.layer_change_gcode"
            " R.string.change_filament_gcode"
            " machineStartGcode = machineEndGcode = beforeLayerChangeGcode ="
            " layerChangeGcode = changeFilamentGcode = GcodeTemplateSetting("
            " MAX_GCODE_TEMPLATE_BYTES"
            " R.string.filament_gcode R.string.filament_start_gcode R.string.filament_end_gcode"
            " R.string.filament_diameter activeProfile.copy(diameter ="
            " R.string.filament_density activeProfile.copy(density ="
            " R.string.filament_price_per_kilogram activeProfile.copy(costPerKilogram ="
            " R.string.filament_shrinkage_xy activeProfile.copy(shrinkageXyPercent ="
            " R.string.filament_shrinkage_z activeProfile.copy(shrinkageZPercent ="
            " R.string.filament_soluble_material activeProfile.copy(soluble ="
            " R.string.filament_support_material activeProfile.copy(supportMaterial ="
            " R.string.filament_minimal_purge_on_wipe_tower"
            " activeProfile.copy(minimalPurgeOnWipeTower ="
            " R.string.auxiliary_part_cooling_fan"
            " activeProfile.copy(additionalCoolingFanSpeed ="
            " activeProfile.copy(fanCoolingLayerTime ="
            " activeProfile.copy(slowDownForLayerCooling ="
            " activeProfile.copy(keepFanAlwaysOn ="
            " activeProfile.copy(dontSlowDownOuterWall ="
            " activeProfile.copy(enableOverhangBridgeFan ="
            " activeProfile.copy(overhangFanThreshold ="
            " activeProfile.copy(internalBridgeFanSpeed ="
            " activeProfile.copy(supportInterfaceFanSpeed ="
            " options.printerProfile.copy(auxiliaryFan ="
            " R.string.minimum_layer_height options.printerProfile.copy(minLayerHeight ="
            " R.string.maximum_layer_height options.printerProfile.copy(maxLayerHeight ="
            " R.string.extruder_offsets R.string.extruder_offset_x R.string.extruder_offset_y"
            " extruderOffsetsX = updated"
            " extruderOffsetsY = updated"
            " R.string.tool_change_retraction R.string.tool_change_retraction_length"
            " options.printerProfile.copy(toolChangeRetractLengths ="
            " R.string.tool_change_retract_restart_extra"
            " options.printerProfile.copy(toolChangeRetractRestartExtras ="
            " filamentStartGcode = filamentEndGcode ="
            " R.string.maximum_z_speed R.string.maximum_e_speed"
            " R.string.maximum_x_acceleration R.string.maximum_y_acceleration"
            " R.string.maximum_z_acceleration R.string.maximum_e_acceleration"
            " R.string.maximum_retracting_acceleration"
            " R.string.maximum_x_jerk R.string.maximum_y_jerk"
            " R.string.maximum_z_jerk R.string.maximum_e_jerk"
            " QuantizedSettingSlider("
            " R.string.feature_jerk"
            " options.jerk.copy(defaultJerk options.jerk.copy(outerWallJerk"
            " options.jerk.copy(innerWallJerk options.jerk.copy(topSurfaceJerk"
            " options.jerk.copy(infillJerk options.jerk.copy(firstLayerJerk"
            " options.jerk.copy(travelJerk"
        ),
        "DeviceSheet.kt": (
            "profiles.forEach .selectable( role = Role.RadioButton "
            "RadioButton(selected = isSelected, onClick = null, enabled = !busy) "
            "if (selected != null)"
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
        "ProfileSettingsSearchTest.kt": (
            "settingSearchTargetsOptionLabelsInsteadOfProfileNames "
            'settingQueryMatches("Z distance", "Top Z distance") '
            "blankSettingSearchKeepsTheWholeEditorVisible"
        ),
        "SlicingSettingsSectionTest.kt": (
            "processEditorUsesExpectedSectionOrder "
            'listOf("QUALITY", "STRENGTH", "SPEED", "SUPPORT", "OTHERS") '
            "Every section must have its own localized title"
        ),
        "SpiralVaseSettingsTest.kt": (
            "enablingSpiralModeAppliesRequiredCompanionSettings "
            "disablingSpiralModePreservesCompanionSettings "
            ".withSpiralMode(true) .withSpiralMode(false)"
        ),
        "strings.xml": (
            'name="quality" name="strength" name="speed" name="supports" name="others" '
            'name="recent_profiles" name="profile_list" name="search_settings" name="support_type" '
            'name="revert_changes" name="apply_changes"'
            ' name="expanded_state" name="collapsed_state"'
            ' name="spiral_vase" name="spiral_vase_summary" name="smooth_spiral"'
            ' name="max_xy_smoothing" name="spiral_starting_flow" name="spiral_finishing_flow"'
            ' name="print_sequence" name="intra_layer_order"'
            ' name="print_by_layer" name="print_by_object"'
            ' name="order_default" name="order_as_object_list"'
            ' name="sequential_printing_clearance" name="extruder_clearance_radius"'
            ' name="extruder_clearance_height_to_rod" name="extruder_clearance_height_to_lid"'
            ' name="make_overhangs_printable" name="maximum_overhang_angle"'
            ' name="overhang_base_hole_area"'
            ' name="number_of_slow_layers"'
            ' name="brim_ear_maximum_angle" name="brim_ear_detection_radius"'
            ' name="top_surface_density" name="bottom_surface_density"'
            ' name="infill_shift_step" name="symmetric_infill_y_axis"'
            ' name="support_on_build_plate_only" name="support_base_pattern_spacing"'
            ' name="support_expansion" name="support_interface_loop_pattern"'
            ' name="independent_support_layer_height"'
            ' name="normal_support_auto" name="tree_support_auto"'
            ' name="normal_support_manual" name="tree_support_manual"'
            ' name="tree_support_branch_angle" name="tree_support_branch_distance"'
            ' name="tree_support_branch_diameter" name="tree_support_wall_count"'
            ' name="tree_support_tip_diameter" name="tree_support_preferred_branch_angle"'
            ' name="tree_support_organic_branch_angle" name="tree_support_organic_branch_distance"'
            ' name="tree_support_organic_branch_diameter" name="tree_support_branch_diameter_angle"'
            ' name="tree_support_branch_density" name="tree_support_adaptive_layer_height"'
            ' name="tree_support_auto_brim" name="tree_support_brim_width"'
            ' name="segmented_region_max_width" name="segmented_region_interlocking_depth"'
            ' name="machine_gcode" name="machine_start_gcode" name="machine_end_gcode"'
            ' name="minimum_layer_height" name="maximum_layer_height"'
            ' name="tool_change_retraction" name="tool_change_retraction_length"'
            ' name="tool_change_retract_restart_extra"'
            ' name="filament_gcode" name="filament_start_gcode" name="filament_end_gcode"'
            ' name="filament_diameter"'
            ' name="filament_density" name="filament_price_per_kilogram"'
            ' name="filament_shrinkage_xy" name="filament_shrinkage_z"'
            ' name="filament_soluble_material" name="filament_support_material"'
            ' name="filament_minimal_purge_on_wipe_tower"'
            ' name="auxiliary_part_cooling_fan" name="cubic_millimeters_suffix"'
            ' name="maximum_z_speed" name="maximum_e_speed"'
            ' name="maximum_x_acceleration" name="maximum_y_acceleration"'
            ' name="maximum_z_acceleration" name="maximum_e_acceleration"'
            ' name="maximum_retracting_acceleration"'
            ' name="maximum_x_jerk" name="maximum_y_jerk"'
            ' name="maximum_z_jerk" name="maximum_e_jerk"'
            ' name="feature_jerk" name="jerk_value" name="initial_layer" name="travel"'
        ),
        "strings-ko.xml": (
            'name="quality" name="strength" name="speed" name="supports" name="others" '
            'name="recent_profiles" name="profile_list" name="search_settings" name="support_type" '
            'name="revert_changes" name="apply_changes"'
            ' name="expanded_state" name="collapsed_state"'
            ' name="spiral_vase" name="spiral_vase_summary" name="smooth_spiral"'
            ' name="max_xy_smoothing" name="spiral_starting_flow" name="spiral_finishing_flow"'
            ' name="print_sequence" name="intra_layer_order"'
            ' name="print_by_layer" name="print_by_object"'
            ' name="order_default" name="order_as_object_list"'
            ' name="sequential_printing_clearance" name="extruder_clearance_radius"'
            ' name="extruder_clearance_height_to_rod" name="extruder_clearance_height_to_lid"'
            ' name="make_overhangs_printable" name="maximum_overhang_angle"'
            ' name="overhang_base_hole_area"'
            ' name="number_of_slow_layers"'
            ' name="brim_ear_maximum_angle" name="brim_ear_detection_radius"'
            ' name="top_surface_density" name="bottom_surface_density"'
            ' name="infill_shift_step" name="symmetric_infill_y_axis"'
            ' name="support_on_build_plate_only" name="support_base_pattern_spacing"'
            ' name="support_expansion" name="support_interface_loop_pattern"'
            ' name="independent_support_layer_height"'
            ' name="normal_support_auto" name="tree_support_auto"'
            ' name="normal_support_manual" name="tree_support_manual"'
            ' name="tree_support_branch_angle" name="tree_support_branch_distance"'
            ' name="tree_support_branch_diameter" name="tree_support_wall_count"'
            ' name="tree_support_tip_diameter" name="tree_support_preferred_branch_angle"'
            ' name="tree_support_organic_branch_angle" name="tree_support_organic_branch_distance"'
            ' name="tree_support_organic_branch_diameter" name="tree_support_branch_diameter_angle"'
            ' name="tree_support_branch_density" name="tree_support_adaptive_layer_height"'
            ' name="tree_support_auto_brim" name="tree_support_brim_width"'
            ' name="segmented_region_max_width" name="segmented_region_interlocking_depth"'
            ' name="machine_gcode" name="machine_start_gcode" name="machine_end_gcode"'
            ' name="minimum_layer_height" name="maximum_layer_height"'
            ' name="tool_change_retraction" name="tool_change_retraction_length"'
            ' name="tool_change_retract_restart_extra"'
            ' name="filament_gcode" name="filament_start_gcode" name="filament_end_gcode"'
            ' name="filament_diameter"'
            ' name="filament_density" name="filament_price_per_kilogram"'
            ' name="filament_shrinkage_xy" name="filament_shrinkage_z"'
            ' name="filament_soluble_material" name="filament_support_material"'
            ' name="filament_minimal_purge_on_wipe_tower"'
            ' name="auxiliary_part_cooling_fan" name="cubic_millimeters_suffix"'
            ' name="maximum_z_speed" name="maximum_e_speed"'
            ' name="maximum_x_acceleration" name="maximum_y_acceleration"'
            ' name="maximum_z_acceleration" name="maximum_e_acceleration"'
            ' name="maximum_retracting_acceleration"'
            ' name="maximum_x_jerk" name="maximum_y_jerk"'
            ' name="maximum_z_jerk" name="maximum_e_jerk"'
            ' name="feature_jerk" name="jerk_value" name="initial_layer" name="travel"'
        ),
        "CONTRIBUTING.md": "Quality Strength Speed Support Others",
    }


class VerifyProfileEditorTest(unittest.TestCase):
    def test_accepts_mobile_slicing_sections(self) -> None:
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
            "Switch(checked = checked, enabled = enabled, onCheckedChange = null)",
            "Switch(checked = checked, onCheckedChange = onCheckedChange)",
        )
        with self.assertRaisesRegex(VerificationError, "profile switch accessibility"):
            verify_profile_editor(sources)

    def test_rejects_duplicate_remote_device_radio_action(self) -> None:
        sources = valid_sources()
        sources["DeviceSheet.kt"] = sources["DeviceSheet.kt"].replace(
            "RadioButton(selected = isSelected, onClick = null",
            "RadioButton(selected = isSelected, onClick = { onSelect(profile.id) })",
        )
        with self.assertRaisesRegex(VerificationError, "remote device selection accessibility"):
            verify_profile_editor(sources)


if __name__ == "__main__":
    unittest.main()
