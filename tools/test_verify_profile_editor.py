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
            " R.string.infill_lateral_honeycomb R.string.infill_lateral_lattice"
            " R.string.infill_cross_hatch R.string.infill_line R.string.infill_triangles"
            " R.string.infill_tri_hexagon R.string.infill_cubic R.string.infill_adaptive_cubic"
            " R.string.infill_quarter_cubic R.string.infill_support_cubic"
            " R.string.infill_lightning R.string.infill_3d_honeycomb"
            " R.string.infill_tpms_d R.string.infill_tpms_fk R.string.infill_concentric"
            " R.string.infill_hilbert_curve R.string.infill_archimedean_chords"
            " R.string.infill_octagram_spiral"
            " R.string.lateral_lattice_angle_1 R.string.lateral_lattice_angle_2"
            " R.string.infill_overhang_angle entries = SPARSE_INFILL_PATTERNS"
            " MULTILINE_INFILL_PATTERNS fillMultilineForPattern( R.string.fill_multiline"
            " R.string.skirt_type R.string.skirt_combined R.string.skirt_per_object"
            " R.string.single_loop_after_first_layer skirtType = it"
            " singleLoopDraftShield = it enabled = options.skirtLoops > 0"
            " R.string.filename_format R.string.filename_format_hint"
            " R.string.filename_format_invalid FilenameFormatSetting( filenameFormat = it"
            " LateralInfillGeometrySettings("
            " quality = options.quality.copy(lateralInfill = it)"
            " stringResource(R.string.degrees_value, settings.firstAngle)"
            " stringResource(R.string.degrees_value, settings.secondAngle)"
            " stringResource(R.string.degrees_value, settings.overhangAngle)"
            " R.string.sparse_infill_rotation_template R.string.solid_infill_rotation_template"
            " RotationTemplateSetting("
            " infillShiftStep = (it * 10f) symmetricInfillYAxis = it"
            " R.string.support_on_build_plate_only R.string.enforce_support_layers"
            " R.string.support_base_pattern_spacing"
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
            " R.string.prime_tower_position R.string.prime_tower_position_x"
            " R.string.prime_tower_position_y"
            " primeTowerPositionX = (it * 2f) primeTowerPositionY = (it * 2f)"
            " R.string.prime_tower_brim_chamfer R.string.prime_tower_brim_chamfer_max_width"
            " primeTowerBrimChamfer = it primeTowerBrimChamferMaxWidth ="
            " R.string.custom_purge_multiplier R.string.purge_multiplier"
            " flushMultiplierOverrideEnabled = it flushMultiplier = it.roundToInt() / 100f"
            " R.string.segmented_region_max_width R.string.segmented_region_interlocking_depth"
            " segmentedRegionMaxWidth = segmentedRegionInterlockingDepth ="
            " segmentedRegionInterlockingDepth = if (it)"
            " R.string.machine_gcode R.string.machine_start_gcode R.string.machine_end_gcode"
            " R.string.before_layer_change_gcode R.string.layer_change_gcode"
            " R.string.change_filament_gcode"
            " R.string.printing_by_object_gcode R.string.use_relative_e_distances"
            " R.string.emit_machine_limits_to_gcode R.string.manual_filament_change"
            " R.string.disable_m73 R.string.scan_first_layer"
            " machineStartGcode = machineEndGcode = beforeLayerChangeGcode ="
            " layerChangeGcode = changeFilamentGcode = printingByObjectGcode ="
            " useRelativeEDistances = emitMachineLimitsToGcode ="
            " manualFilamentChange = disableM73 = scanFirstLayer = GcodeTemplateSetting("
            " R.string.cooling_tube_position R.string.cooling_tube_length"
            " R.string.filament_parking_position R.string.extra_loading_distance"
            " R.string.enable_filament_ramming R.string.purge_in_prime_tower"
            " R.string.wipe_tower_ramming R.string.ramming_line_width_ratio"
            " R.string.change_pressure_when_wiping R.string.ramming_pressure_advance"
            " R.string.high_current_on_filament_swap"
            " coolingTubeRetraction = coolingTubeLength = parkingPosRetraction ="
            " extraLoadingMove = enableFilamentRamming = purgeInPrimeTower ="
            " rammingLineWidthRatio = changePressureWhenWiping = rammingPressureAdvance ="
            " highCurrentOnFilamentSwap ="
            " R.string.printer_environment_capabilities"
            " R.string.supports_chamber_temperature_control R.string.supports_air_filtration"
            " supportsChamberTemperatureControl = supportsAirFiltration ="
            ' entries = listOf("marlin", "marlin2", "klipper", "reprapfirmware")'
            ' "reprapfirmware" -> "RepRapFirmware"'
            " R.string.adaptive_bed_mesh R.string.bed_mesh_min R.string.bed_mesh_max"
            " R.string.probe_point_distance R.string.mesh_margin"
            " bedMeshMinX = bedMeshMinY = bedMeshMaxX = bedMeshMaxY ="
            " bedMeshProbeDistanceX = bedMeshProbeDistanceY = adaptiveBedMeshMargin ="
            " CoordinatePairSettingField("
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
            " R.string.filament_exchange_motion R.string.filament_loading_speed"
            " R.string.filament_loading_speed_start R.string.filament_unloading_speed"
            " R.string.filament_unloading_speed_start R.string.filament_toolchange_delay"
            " R.string.filament_cooling_moves R.string.filament_cooling_initial_speed"
            " R.string.filament_cooling_final_speed R.string.filament_stamping_speed"
            " R.string.filament_stamping_distance R.string.filament_multitool_ramming"
            " R.string.filament_multitool_ramming_volume R.string.filament_multitool_ramming_flow"
            " activeProfile.copy(loadingSpeed = activeProfile.copy(loadingSpeedStart ="
            " activeProfile.copy(unloadingSpeed = activeProfile.copy(unloadingSpeedStart ="
            " activeProfile.copy(toolchangeDelay = activeProfile.copy(coolingMoves ="
            " activeProfile.copy(coolingInitialSpeed = activeProfile.copy(coolingFinalSpeed ="
            " activeProfile.copy(stampingLoadingSpeed = activeProfile.copy(stampingDistance ="
            " activeProfile.copy(multitoolRamming = activeProfile.copy(multitoolRammingVolume ="
            " activeProfile.copy(multitoolRammingFlow ="
            " R.string.filament_material_environment R.string.filament_softening_temperature"
            " R.string.filament_nozzle_temperature_minimum"
            " R.string.filament_nozzle_temperature_maximum"
            " R.string.filament_chamber_temperature_control R.string.filament_chamber_temperature"
            " R.string.filament_air_filtration R.string.filament_exhaust_during_print"
            " R.string.filament_exhaust_after_print"
            " activeProfile.copy(softeningTemperature = nozzleTemperatureRangeLow ="
            " nozzleTemperatureRangeHigh = activeProfile.copy(chamberTemperatureControl ="
            " activeProfile.copy(chamberTemperature = activeProfile.copy(airFiltration ="
            " activeProfile.copy(duringPrintExhaustFanSpeed ="
            " activeProfile.copy(completePrintExhaustFanSpeed ="
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
            " R.string.fan_speedup_time options.printerProfile.copy(fanSpeedupTime ="
            " R.string.fan_speedup_overhangs options.printerProfile.copy(fanSpeedupOverhangs ="
            " R.string.fan_kickstart options.printerProfile.copy(fanKickstart ="
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
            " R.string.silent_mode R.string.silent_motion_limits R.string.silent_setting_label"
            " SILENT_MOTION_LIMIT_CONTROLS silentMode = enabled silentMotionLimits = limits"
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
            "blankSettingSearchKeepsTheWholeEditorVisible "
            "coordinatePairEditorParsesOnlyTwoFiniteCoordinates "
            'settingQueryMatches("mesh min", "Bed mesh min") '
            'settingQueryMatches("silent speed", "Silent · Maximum X speed")'
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
        "InfillPatternTest.kt": (
            "sparsePatternListExactlyMatchesPinnedOrcaEnum "
            "assertEquals(26, SPARSE_INFILL_PATTERNS.size) "
            "multilineCompatibilityMatchesOrcaDesktopDependencies "
            "fillMultilineForPattern(pattern, 5)"
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
            ' name="infill_lateral_honeycomb" name="infill_lateral_lattice"'
            ' name="infill_cross_hatch" name="infill_line" name="infill_triangles"'
            ' name="infill_tri_hexagon" name="infill_cubic" name="infill_adaptive_cubic"'
            ' name="infill_quarter_cubic" name="infill_support_cubic"'
            ' name="infill_lightning" name="infill_3d_honeycomb"'
            ' name="infill_tpms_d" name="infill_tpms_fk" name="infill_concentric"'
            ' name="infill_hilbert_curve" name="infill_archimedean_chords"'
            ' name="infill_octagram_spiral" name="fill_multiline" name="fill_multiline_value"'
            ' name="skirt_type" name="skirt_combined" name="skirt_per_object"'
            ' name="single_loop_after_first_layer"'
            ' name="filename_format" name="filename_format_hint" name="filename_format_invalid"'
            ' name="lateral_lattice_angle_1" name="lateral_lattice_angle_2"'
            ' name="infill_overhang_angle"'
            ' name="support_on_build_plate_only" name="enforce_support_layers"'
            ' name="support_base_pattern_spacing"'
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
            ' name="prime_tower_position" name="prime_tower_position_x"'
            ' name="prime_tower_position_y"'
            ' name="prime_tower_brim_chamfer" name="prime_tower_brim_chamfer_max_width"'
            ' name="wipe_tower_ramming" name="ramming_line_width_ratio"'
            ' name="change_pressure_when_wiping" name="ramming_pressure_advance"'
            ' name="segmented_region_max_width" name="segmented_region_interlocking_depth"'
            ' name="machine_gcode" name="machine_start_gcode" name="machine_end_gcode"'
            ' name="printing_by_object_gcode" name="use_relative_e_distances"'
            ' name="emit_machine_limits_to_gcode" name="manual_filament_change"'
            ' name="disable_m73"'
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
            ' name="silent_mode" name="silent_motion_limits" name="silent_setting_label"'
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
            ' name="infill_lateral_honeycomb" name="infill_lateral_lattice"'
            ' name="infill_cross_hatch" name="infill_line" name="infill_triangles"'
            ' name="infill_tri_hexagon" name="infill_cubic" name="infill_adaptive_cubic"'
            ' name="infill_quarter_cubic" name="infill_support_cubic"'
            ' name="infill_lightning" name="infill_3d_honeycomb"'
            ' name="infill_tpms_d" name="infill_tpms_fk" name="infill_concentric"'
            ' name="infill_hilbert_curve" name="infill_archimedean_chords"'
            ' name="infill_octagram_spiral" name="fill_multiline" name="fill_multiline_value"'
            ' name="skirt_type" name="skirt_combined" name="skirt_per_object"'
            ' name="single_loop_after_first_layer"'
            ' name="filename_format" name="filename_format_hint" name="filename_format_invalid"'
            ' name="lateral_lattice_angle_1" name="lateral_lattice_angle_2"'
            ' name="infill_overhang_angle"'
            ' name="support_on_build_plate_only" name="enforce_support_layers"'
            ' name="support_base_pattern_spacing"'
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
            ' name="prime_tower_position" name="prime_tower_position_x"'
            ' name="prime_tower_position_y"'
            ' name="prime_tower_brim_chamfer" name="prime_tower_brim_chamfer_max_width"'
            ' name="wipe_tower_ramming" name="ramming_line_width_ratio"'
            ' name="change_pressure_when_wiping" name="ramming_pressure_advance"'
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
            ' name="silent_mode" name="silent_motion_limits" name="silent_setting_label"'
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

    def test_rejects_integer_argument_for_floating_point_degree_format(self) -> None:
        sources = valid_sources()
        sources["ProfileSettingsSheet.kt"] = sources["ProfileSettingsSheet.kt"].replace(
            "stringResource(R.string.degrees_value, settings.firstAngle)",
            "stringResource(R.string.degrees_value, settings.firstAngle.roundToInt())",
        )
        with self.assertRaisesRegex(VerificationError, "floating-point degrees format"):
            verify_profile_editor(sources)

    def test_rejects_incomplete_sparse_infill_enum_regression(self) -> None:
        sources = valid_sources()
        sources["InfillPatternTest.kt"] = sources["InfillPatternTest.kt"].replace(
            "assertEquals(26, SPARSE_INFILL_PATTERNS.size)",
            "assertEquals(13, SPARSE_INFILL_PATTERNS.size)",
        )
        with self.assertRaisesRegex(VerificationError, "sparse-infill editor regression"):
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
