#!/usr/bin/env python3
"""Enforce the DuckySlicer mobile profile editor structure."""

from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent


class VerificationError(ValueError):
    pass


def verify_profile_editor(sources: dict[str, str]) -> None:
    required = {
        "DeviceSheet.kt",
        "ProfileSettingsSheet.kt",
        "ProfileRecents.kt",
        "ProfileRecentsTest.kt",
        "ProfileEditSessionTest.kt",
        "ProfileSettingsSearchTest.kt",
        "SlicingSettingsSectionTest.kt",
        "SpiralVaseSettingsTest.kt",
        "strings.xml",
        "strings-ko.xml",
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
    profile_button_position = slicing_editor.find("CurrentProfileButton(")
    tab_position = slicing_editor.find("SlicingSettingsTabs(")
    settings_position = slicing_editor.find("renderedSections.forEach")
    chooser_position = slicing_editor.find("ProfileChooserSheet(")
    if not (
        0 <= profile_button_position < tab_position < settings_position < chooser_position
    ):
        raise VerificationError(
            "the current-profile button, slicing tabs, settings, and separate profile list are out of order"
        )
    for marker in (
        "settingQuery = settingsQuery",
        "onSettingQueryChanged = { settingsQuery = it }",
        "CurrentProfileButton(",
        "ProfileChooserSheet(",
        "private fun <T> SettingChoices(",
        "LocalSettingsQuery provides",
        "settingQueryMatches(",
        "SlicingSettingsSection.entries",
        "renderedSections.forEach",
    ):
        if marker not in editor:
            raise VerificationError(f"separate profile and setting search is missing: {marker}")
    if editor.count("ProfileChooserSheet(") < 4:
        raise VerificationError("printer, filament, and slicing editors need separate profile lists")
    if editor.count("SettingChoices(") < 20:
        raise VerificationError("choice-based settings must participate in setting-name search")
    for marker in (
        "options.withSpiralMode(enabled)",
        "R.string.spiral_vase",
        "R.string.smooth_spiral",
        "R.string.max_xy_smoothing",
        "R.string.spiral_starting_flow",
        "R.string.spiral_finishing_flow",
        "R.string.print_sequence",
        "R.string.intra_layer_order",
        "R.string.print_by_layer",
        "R.string.print_by_object",
        "R.string.order_default",
        "R.string.order_as_object_list",
        "R.string.sequential_printing_clearance",
        "R.string.extruder_clearance_radius",
        "R.string.extruder_clearance_height_to_rod",
        "R.string.extruder_clearance_height_to_lid",
        "R.string.make_overhangs_printable",
        "R.string.maximum_overhang_angle",
        "R.string.overhang_base_hole_area",
        "R.string.number_of_slow_layers",
        "R.string.brim_ear_maximum_angle",
        "R.string.brim_ear_detection_radius",
        "R.string.top_surface_density",
        "R.string.bottom_surface_density",
        "R.string.infill_shift_step",
        "R.string.symmetric_infill_y_axis",
        "R.string.sparse_infill_rotation_template",
        "R.string.solid_infill_rotation_template",
        "RotationTemplateSetting(",
        "infillShiftStep = (it * 10f)",
        "symmetricInfillYAxis = it",
        "R.string.support_on_build_plate_only",
        "R.string.support_base_pattern_spacing",
        "R.string.support_expansion",
        "R.string.support_interface_loop_pattern",
        "R.string.independent_support_layer_height",
        "R.string.normal_support_auto",
        "R.string.tree_support_auto",
        "R.string.normal_support_manual",
        "R.string.tree_support_manual",
        "R.string.tree_support_branch_angle",
        "R.string.tree_support_branch_distance",
        "R.string.tree_support_branch_diameter",
        "R.string.tree_support_wall_count",
        "R.string.tree_support_tip_diameter",
        "R.string.tree_support_organic_branch_angle",
        "R.string.tree_support_organic_branch_distance",
        "R.string.tree_support_organic_branch_diameter",
        "R.string.tree_support_branch_diameter_angle",
        "R.string.tree_support_preferred_branch_angle",
        "R.string.tree_support_branch_density",
        "R.string.tree_support_adaptive_layer_height",
        "R.string.tree_support_auto_brim",
        "R.string.tree_support_brim_width",
        "R.string.segmented_region_max_width",
        "R.string.segmented_region_interlocking_depth",
        "segmentedRegionMaxWidth =",
        "segmentedRegionInterlockingDepth =",
        "segmentedRegionInterlockingDepth = if (it)",
        "R.string.machine_gcode",
        "R.string.machine_start_gcode",
        "R.string.machine_end_gcode",
        "R.string.before_layer_change_gcode",
        "R.string.layer_change_gcode",
        "R.string.change_filament_gcode",
        "machineStartGcode =",
        "machineEndGcode =",
        "beforeLayerChangeGcode =",
        "layerChangeGcode =",
        "changeFilamentGcode =",
        "GcodeTemplateSetting(",
        "MAX_GCODE_TEMPLATE_BYTES",
        "R.string.filament_gcode",
        "R.string.filament_start_gcode",
        "R.string.filament_end_gcode",
        "R.string.filament_diameter",
        "activeProfile.copy(diameter =",
        "R.string.filament_density",
        "activeProfile.copy(density =",
        "R.string.filament_price_per_kilogram",
        "activeProfile.copy(costPerKilogram =",
        "R.string.filament_shrinkage_xy",
        "activeProfile.copy(shrinkageXyPercent =",
        "R.string.filament_shrinkage_z",
        "activeProfile.copy(shrinkageZPercent =",
        "R.string.filament_soluble_material",
        "activeProfile.copy(soluble =",
        "R.string.filament_support_material",
        "activeProfile.copy(supportMaterial =",
        "R.string.filament_minimal_purge_on_wipe_tower",
        "activeProfile.copy(minimalPurgeOnWipeTower =",
        "R.string.auxiliary_part_cooling_fan",
        "activeProfile.copy(additionalCoolingFanSpeed =",
        "activeProfile.copy(fanCoolingLayerTime =",
        "activeProfile.copy(slowDownForLayerCooling =",
        "activeProfile.copy(keepFanAlwaysOn =",
        "activeProfile.copy(dontSlowDownOuterWall =",
        "activeProfile.copy(enableOverhangBridgeFan =",
        "activeProfile.copy(overhangFanThreshold =",
        "activeProfile.copy(internalBridgeFanSpeed =",
        "activeProfile.copy(supportInterfaceFanSpeed =",
        "options.printerProfile.copy(auxiliaryFan =",
        "R.string.minimum_layer_height",
        "options.printerProfile.copy(minLayerHeight =",
        "R.string.maximum_layer_height",
        "options.printerProfile.copy(maxLayerHeight =",
        "R.string.extruder_offsets",
        "R.string.extruder_offset_x",
        "R.string.extruder_offset_y",
        "extruderOffsetsX = updated",
        "extruderOffsetsY = updated",
        "R.string.tool_change_retraction",
        "R.string.tool_change_retraction_length",
        "options.printerProfile.copy(toolChangeRetractLengths =",
        "R.string.tool_change_retract_restart_extra",
        "options.printerProfile.copy(toolChangeRetractRestartExtras =",
        "filamentStartGcode =",
        "filamentEndGcode =",
        "R.string.maximum_z_speed",
        "R.string.maximum_e_speed",
        "R.string.maximum_x_acceleration",
        "R.string.maximum_y_acceleration",
        "R.string.maximum_z_acceleration",
        "R.string.maximum_e_acceleration",
        "R.string.maximum_retracting_acceleration",
        "R.string.maximum_x_jerk",
        "R.string.maximum_y_jerk",
        "R.string.maximum_z_jerk",
        "R.string.maximum_e_jerk",
        "QuantizedSettingSlider(",
        "R.string.feature_jerk",
        "options.jerk.copy(defaultJerk",
        "options.jerk.copy(outerWallJerk",
        "options.jerk.copy(innerWallJerk",
        "options.jerk.copy(topSurfaceJerk",
        "options.jerk.copy(infillJerk",
        "options.jerk.copy(firstLayerJerk",
        "options.jerk.copy(travelJerk",
    ):
        if marker not in editor:
            raise VerificationError(f"advanced process controls are missing: {marker}")

    for marker in (
        "recentIds: List<String>",
        "val recentProfilesKey = \"recent-profiles\"",
        "add(ProfileChoiceGroup(recentProfilesKey, recentProfiles, recentEntries))",
    ):
        if marker not in editor:
            raise VerificationError(f"recent profile group is missing: {marker}")

    for marker in (
        "data class ProfileEditSession(",
        "val isDirty: Boolean get() = working != opening",
        "fun revert(): ProfileEditSession",
        "fun applied(): ProfileEditSession",
        "ProfileDirtyActionBar(",
        "Modifier.weight(3f)",
        "Modifier.weight(7f)",
        ".imePadding()",
    ):
        if marker not in editor:
            raise VerificationError(f"sticky profile action bar is missing: {marker}")

    for marker in (
        "onClickLabel = editDetailsLabel",
        ".heightIn(min = 48.dp)",
        ".selectable(",
        "role = Role.RadioButton",
        "onClick = null",
        ".semantics { stateDescription = groupState }",
        "LocalWindowInfo.current.containerSize.height.toDp()",
    ):
        if marker not in editor:
            raise VerificationError(f"accessible profile interaction is missing: {marker}")
    if editor.count(".selectable(") < 2 or editor.count("onClick = null") < 2:
        raise VerificationError(
            "both compact and grouped profile choices must expose one radio target per row"
        )
    if ".clickable { onSelected(entry) }" in editor:
        raise VerificationError("profile choice rows must not expose duplicate click targets")
    if "LocalConfiguration.current.screenHeightDp" in editor:
        raise VerificationError("profile sheet height must follow the current window container")

    settings_switch = editor.split("internal fun SettingsSwitch(", 1)[-1].split(
        "@Composable", 1
    )[0]
    for marker in (
        ".heightIn(min = 48.dp)",
        ".toggleable(",
        "role = Role.Switch",
        ".semantics(mergeDescendants = true)",
        "contentDescription = label",
        "Switch(checked = checked, enabled = enabled, onCheckedChange = null)",
    ):
        if marker not in settings_switch:
            raise VerificationError(f"profile switch accessibility is missing: {marker}")

    settings_slider = editor.split("internal fun SettingSlider(", 1)[-1].split(
        "@Composable", 1
    )[0]
    for marker in (
        "modifier = Modifier.semantics",
        "contentDescription = label",
        "stateDescription = valueText",
    ):
        if marker not in settings_slider:
            raise VerificationError(f"profile slider accessibility is missing: {marker}")

    device_choices = sources["DeviceSheet.kt"].split("profiles.forEach", 1)[-1].split(
        "if (selected != null)", 1
    )[0]
    for marker in (
        ".selectable(",
        "role = Role.RadioButton",
        "RadioButton(selected = isSelected, onClick = null",
        "enabled = !busy",
    ):
        if marker not in device_choices:
            raise VerificationError(f"remote device selection accessibility is missing: {marker}")
    if ".clickable { onSelect(profile.id) }" in device_choices:
        raise VerificationError("remote device profiles must expose one radio target per row")

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
        for resource in ('name="profile_list"', 'name="search_settings"', 'name="support_type"'):
            if resource not in strings:
                raise VerificationError(
                    f"localized separated profile/settings search label is missing from {source_name}: {resource}"
                )
        for resource in ('name="revert_changes"', 'name="apply_changes"'):
            if resource not in strings:
                raise VerificationError(f"localized profile action is missing from {source_name}: {resource}")
        for resource in ('name="expanded_state"', 'name="collapsed_state"'):
            if resource not in strings:
                raise VerificationError(
                    f"localized profile disclosure state is missing from {source_name}: {resource}"
                )
        for resource in (
            'name="spiral_vase"',
            'name="spiral_vase_summary"',
            'name="smooth_spiral"',
            'name="max_xy_smoothing"',
            'name="spiral_starting_flow"',
            'name="spiral_finishing_flow"',
            'name="print_sequence"',
            'name="intra_layer_order"',
            'name="print_by_layer"',
            'name="print_by_object"',
            'name="order_default"',
            'name="order_as_object_list"',
            'name="sequential_printing_clearance"',
            'name="extruder_clearance_radius"',
            'name="extruder_clearance_height_to_rod"',
            'name="extruder_clearance_height_to_lid"',
            'name="make_overhangs_printable"',
            'name="maximum_overhang_angle"',
            'name="overhang_base_hole_area"',
            'name="number_of_slow_layers"',
            'name="brim_ear_maximum_angle"',
            'name="brim_ear_detection_radius"',
            'name="top_surface_density"',
            'name="bottom_surface_density"',
            'name="infill_shift_step"',
            'name="symmetric_infill_y_axis"',
            'name="support_on_build_plate_only"',
            'name="support_base_pattern_spacing"',
            'name="support_expansion"',
            'name="support_interface_loop_pattern"',
            'name="independent_support_layer_height"',
            'name="normal_support_auto"',
            'name="tree_support_auto"',
            'name="normal_support_manual"',
            'name="tree_support_manual"',
            'name="tree_support_branch_angle"',
            'name="tree_support_branch_distance"',
            'name="tree_support_branch_diameter"',
            'name="tree_support_wall_count"',
            'name="tree_support_tip_diameter"',
            'name="tree_support_organic_branch_angle"',
            'name="tree_support_organic_branch_distance"',
            'name="tree_support_organic_branch_diameter"',
            'name="tree_support_branch_diameter_angle"',
            'name="tree_support_preferred_branch_angle"',
            'name="tree_support_branch_density"',
            'name="tree_support_adaptive_layer_height"',
            'name="tree_support_auto_brim"',
            'name="tree_support_brim_width"',
            'name="segmented_region_max_width"',
            'name="segmented_region_interlocking_depth"',
            'name="machine_gcode"',
            'name="machine_start_gcode"',
            'name="machine_end_gcode"',
            'name="minimum_layer_height"',
            'name="maximum_layer_height"',
            'name="tool_change_retraction"',
            'name="tool_change_retraction_length"',
            'name="tool_change_retract_restart_extra"',
            'name="filament_gcode"',
            'name="filament_start_gcode"',
            'name="filament_end_gcode"',
            'name="maximum_z_speed"',
            'name="maximum_e_speed"',
            'name="maximum_x_acceleration"',
            'name="maximum_y_acceleration"',
            'name="maximum_z_acceleration"',
            'name="maximum_e_acceleration"',
            'name="maximum_retracting_acceleration"',
            'name="maximum_x_jerk"',
            'name="maximum_y_jerk"',
            'name="maximum_z_jerk"',
            'name="maximum_e_jerk"',
            'name="feature_jerk"',
            'name="jerk_value"',
            'name="initial_layer"',
            'name="travel"',
        ):
            if resource not in strings:
                raise VerificationError(
                    f"localized spiral-vase setting is missing from {source_name}: {resource}"
                )

    test = sources["SlicingSettingsSectionTest.kt"]
    for marker in (
        "processEditorUsesExpectedSectionOrder",
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

    edit_test = sources["ProfileEditSessionTest.kt"]
    for marker in (
        "changesStayStagedUntilApplied",
        "revertRestoresTheOpeningSnapshot",
        "applyPromotesWorkingValuesWithoutClosingTheSession",
    ):
        if marker not in edit_test:
            raise VerificationError(f"profile edit-session regression is missing: {marker}")

    search_test = sources["ProfileSettingsSearchTest.kt"]
    for marker in (
        "settingSearchTargetsOptionLabelsInsteadOfProfileNames",
        'settingQueryMatches("Z distance", "Top Z distance")',
        "blankSettingSearchKeepsTheWholeEditorVisible",
    ):
        if marker not in search_test:
            raise VerificationError(f"profile setting-search regression is missing: {marker}")

    spiral_test = sources["SpiralVaseSettingsTest.kt"]
    for marker in (
        "enablingSpiralModeAppliesRequiredCompanionSettings",
        "disablingSpiralModePreservesCompanionSettings",
        ".withSpiralMode(true)",
        ".withSpiralMode(false)",
    ):
        if marker not in spiral_test:
            raise VerificationError(f"spiral-vase editor regression is missing: {marker}")

    for document in ("CONTRIBUTING.md",):
        lowered = sources[document].lower()
        if not all(term in lowered for term in ("quality", "strength", "speed", "support", "others")):
            raise VerificationError(f"Slicing profile sections are not documented in {document}")


def read_sources() -> dict[str, str]:
    main = ROOT / "android/app/src/main/java/com/ashcastle/duckyslicer"
    tests = ROOT / "android/app/src/test/java/com/ashcastle/duckyslicer"
    resources = ROOT / "android/app/src/main/res"
    return {
        "DeviceSheet.kt": (main / "DeviceSheet.kt").read_text(encoding="utf-8"),
        "ProfileSettingsSheet.kt": (main / "ProfileSettingsSheet.kt").read_text(encoding="utf-8"),
        "ProfileRecents.kt": (main / "ProfileRecents.kt").read_text(encoding="utf-8"),
        "ProfileRecentsTest.kt": (tests / "ProfileRecentsTest.kt").read_text(encoding="utf-8"),
        "ProfileEditSessionTest.kt": (tests / "ProfileEditSessionTest.kt").read_text(
            encoding="utf-8"
        ),
        "ProfileSettingsSearchTest.kt": (tests / "ProfileSettingsSearchTest.kt").read_text(
            encoding="utf-8"
        ),
        "SlicingSettingsSectionTest.kt": (tests / "SlicingSettingsSectionTest.kt").read_text(
            encoding="utf-8"
        ),
        "SpiralVaseSettingsTest.kt": (tests / "SpiralVaseSettingsTest.kt").read_text(
            encoding="utf-8"
        ),
        "strings.xml": (resources / "values/strings.xml").read_text(encoding="utf-8"),
        "strings-ko.xml": (resources / "values-ko/strings.xml").read_text(encoding="utf-8"),
        "CONTRIBUTING.md": (ROOT / "CONTRIBUTING.md").read_text(encoding="utf-8"),
    }


def main() -> None:
    try:
        verify_profile_editor(read_sources())
    except (OSError, VerificationError) as error:
        raise SystemExit(f"Profile editor verification failed: {error}") from error
    print("Verified localized, responsive, accessible mobile slicing profiles")


if __name__ == "__main__":
    main()
