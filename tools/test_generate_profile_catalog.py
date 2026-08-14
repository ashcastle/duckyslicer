from __future__ import annotations

import math
import unittest

from tools.generate_profile_catalog import build_filament, build_printer, build_process, printable_geometry, support_type


class GenerateProfileCatalogTest(unittest.TestCase):
    def test_legacy_support_modes_remain_automatic(self) -> None:
        self.assertEqual("normal(auto)", support_type("normal"))
        self.assertEqual("tree(auto)", support_type("tree"))
        self.assertEqual("tree(auto)", support_type("hybrid(auto)"))

    def test_preserves_and_normalizes_orca_printable_polygon(self) -> None:
        area = [
            "0x-100",
            "100x0",
            "0x100",
            "-100x0",
        ]

        width, depth, origin_x, origin_y, polygon = printable_geometry(area)

        self.assertEqual(200.0, width)
        self.assertEqual(200.0, depth)
        self.assertEqual(-100.0, origin_x)
        self.assertEqual(-100.0, origin_y)
        self.assertEqual(
            [100.0, 0.0, 200.0, 100.0, 100.0, 200.0, 0.0, 100.0],
            polygon,
        )

    def test_rejects_degenerate_or_unbounded_printable_polygon(self) -> None:
        for area in (
            ["0x0", "100x0", "200x0"],
            ["0x0", "nanx0", "100x100"],
            [f"{math.cos(index)}x{math.sin(index)}" for index in range(257)],
        ):
            with self.assertRaises(ValueError):
                printable_geometry(area)

    def test_preserves_support_routing_and_prime_tower_process_values(self) -> None:
        profile = build_process(
            "Example",
            {
                "name": "Multi material",
                "layer_height": "0.2",
                "initial_layer_print_height": "0.2",
                "print_flow_ratio": "0.97",
                "support_filament": "1",
                "support_interface_filament": "2",
                "enable_infill_filament_override": "1",
                "infill_filament_use_base_first_layers": "3",
                "infill_filament_use_base_last_layers": "4",
                "sparse_infill_filament": "2",
                "wall_filament": "1",
                "solid_infill_filament": "2",
                "wipe_tower_filament": "1",
                "enable_prime_tower": "1",
                "prime_tower_width": "42",
                "prime_volume": "61.5",
                "prime_tower_brim_width": "4.5",
                "wipe_tower_no_sparse_layers": "1",
                "wipe_tower_rotation_angle": "73",
                "wipe_tower_bridging": "12.5",
                "wipe_tower_extra_spacing": "145%",
                "wipe_tower_extra_flow": "118%",
                "wipe_tower_max_purge_speed": "137",
                "wipe_tower_wall_type": "rib",
                "wipe_tower_cone_angle": "42",
                "wipe_tower_extra_rib_length": "9.5",
                "wipe_tower_rib_width": "11",
                "wipe_tower_fillet_wall": "0",
                "single_extruder_multi_material_priming": "1",
                "flush_into_infill": "1",
                "flush_into_support": "0",
                "flush_into_objects": "1",
                "ooze_prevention": "1",
                "standby_temperature_delta": "-35",
                "interface_shells": "1",
                "enable_arc_fitting": "1",
                "gcode_label_objects": "0",
                "exclude_object": "1",
                "initial_layer_travel_speed": "35%",
                "accel_to_decel_enable": "0",
                "accel_to_decel_factor": "27%",
                "seam_slope_type": "all",
                "seam_slope_conditional": "1",
                "scarf_angle_threshold": "142",
                "scarf_overhang_threshold": "37%",
                "scarf_joint_speed": "63%",
                "scarf_joint_flow_ratio": "0.92",
                "seam_slope_start_height": "18%",
                "seam_slope_entire_loop": "1",
                "seam_slope_min_length": "24.5",
                "seam_slope_steps": "13",
                "seam_slope_inner_walls": "1",
            },
            {},
        )

        self.assertEqual(1, profile["supportFilament"])
        self.assertEqual(0.97, profile["printFlowRatio"])
        self.assertEqual(2, profile["supportInterfaceFilament"])
        self.assertTrue(profile["infillFilamentOverrideEnabled"])
        self.assertEqual(3, profile["infillFilamentBaseFirstLayers"])
        self.assertEqual(4, profile["infillFilamentBaseLastLayers"])
        self.assertEqual(2, profile["sparseInfillFilament"])
        self.assertEqual(1, profile["wallFilament"])
        self.assertEqual(2, profile["solidInfillFilament"])
        self.assertEqual(1, profile["wipeTowerFilament"])
        self.assertTrue(profile["wipeTowerEnabled"])
        self.assertEqual(42.0, profile["wipeTowerWidth"])
        self.assertEqual(61.5, profile["primeVolume"])
        self.assertEqual(4.5, profile["primeTowerBrimWidth"])
        self.assertTrue(profile["wipeTowerNoSparseLayers"])
        self.assertEqual(73.0, profile["wipeTowerRotationAngle"])
        self.assertEqual(12.5, profile["wipeTowerBridging"])
        self.assertEqual(145.0, profile["wipeTowerExtraSpacing"])
        self.assertEqual(118.0, profile["wipeTowerExtraFlow"])
        self.assertEqual(137.0, profile["wipeTowerMaxPurgeSpeed"])
        self.assertEqual("rib", profile["wipeTowerWallType"])
        self.assertEqual(42.0, profile["wipeTowerConeAngle"])
        self.assertEqual(9.5, profile["wipeTowerExtraRibLength"])
        self.assertEqual(11.0, profile["wipeTowerRibWidth"])
        self.assertFalse(profile["wipeTowerFilletWall"])
        self.assertTrue(profile["singleExtruderMultiMaterialPriming"])
        self.assertTrue(profile["flushIntoInfill"])
        self.assertFalse(profile["flushIntoSupport"])
        self.assertTrue(profile["flushIntoObjects"])
        self.assertTrue(profile["oozePrevention"])
        self.assertEqual(-35, profile["standbyTemperatureDelta"])
        self.assertTrue(profile["interfaceShells"])
        self.assertTrue(profile["enableArcFitting"])
        self.assertFalse(profile["gcodeLabelObjects"])
        self.assertTrue(profile["excludeObject"])
        self.assertEqual(35.0, profile["initialLayerTravelSpeed"])
        self.assertTrue(profile["initialLayerTravelSpeedPercent"])
        self.assertFalse(profile["accelToDecelEnabled"])
        self.assertEqual(27.0, profile["accelToDecelFactor"])
        self.assertEqual("all", profile["scarfSeamType"])
        self.assertTrue(profile["scarfSeamConditional"])
        self.assertEqual(142, profile["scarfAngleThreshold"])
        self.assertEqual(37.0, profile["scarfOverhangThreshold"])
        self.assertEqual(63.0, profile["scarfJointSpeed"])
        self.assertTrue(profile["scarfJointSpeedPercent"])
        self.assertEqual(0.92, profile["scarfJointFlowRatio"])
        self.assertEqual(18.0, profile["scarfStartHeight"])
        self.assertTrue(profile["scarfStartHeightPercent"])
        self.assertTrue(profile["scarfEntireLoop"])
        self.assertEqual(24.5, profile["scarfLength"])
        self.assertEqual(13, profile["scarfSteps"])
        self.assertTrue(profile["scarfInnerWalls"])

    def test_preserves_spiral_vase_process_values(self) -> None:
        profile = build_process(
            "Example",
            {
                "name": "Smooth vase",
                "layer_height": "0.2",
                "initial_layer_print_height": "0.2",
                "spiral_mode": "1",
                "spiral_mode_smooth": "1",
                "spiral_mode_max_xy_smoothing": "250%",
                "spiral_starting_flow_ratio": "0.35",
                "spiral_finishing_flow_ratio": "0.2",
            },
            {},
        )

        self.assertTrue(profile["spiralMode"])
        self.assertTrue(profile["spiralModeSmooth"])
        self.assertEqual(250.0, profile["spiralModeMaxXySmoothing"])
        self.assertTrue(profile["spiralModeMaxXySmoothingPercent"])
        self.assertEqual(0.35, profile["spiralStartingFlowRatio"])
        self.assertEqual(0.2, profile["spiralFinishingFlowRatio"])

    def test_normalizes_legacy_zero_feature_filaments_to_first_tool(self) -> None:
        profile = build_process(
            "Example",
            {
                "name": "First tool routing",
                "layer_height": "0.2",
                "initial_layer_print_height": "0.2",
                "wall_filament": "0",
                "sparse_infill_filament": "0",
                "solid_infill_filament": "0",
            },
            {},
        )

        self.assertEqual(1, profile["wallFilament"])
        self.assertEqual(1, profile["sparseInfillFilament"])
        self.assertEqual(1, profile["solidInfillFilament"])

    def test_preserves_advanced_support_process_values(self) -> None:
        profile = build_process(
            "Example",
            {
                "name": "Advanced support",
                "layer_height": "0.2",
                "initial_layer_print_height": "0.2",
                "support_angle": "73",
                "support_threshold_overlap": "0.33",
                "support_object_first_layer_gap": "0.42",
                "support_interface_not_for_body": "0",
                "support_ironing": "1",
                "support_ironing_pattern": "concentric",
                "support_ironing_flow": "17%",
                "support_ironing_spacing": "0.18",
            },
            {},
        )

        self.assertEqual(73.0, profile["supportPatternAngle"])
        self.assertEqual(0.33, profile["supportThresholdOverlap"])
        self.assertFalse(profile["supportThresholdOverlapPercent"])
        self.assertEqual(0.42, profile["supportObjectFirstLayerGap"])
        self.assertFalse(profile["avoidSupportInterfaceFilamentForBase"])
        self.assertTrue(profile["supportIroning"])
        self.assertEqual("concentric", profile["supportIroningPattern"])
        self.assertEqual(17.0, profile["supportIroningFlow"])
        self.assertEqual(0.18, profile["supportIroningSpacing"])

    def test_preserves_multi_object_print_sequence(self) -> None:
        profile = build_process(
            "Example",
            {
                "name": "Sequential objects",
                "layer_height": "0.2",
                "initial_layer_print_height": "0.2",
                "print_sequence": "by object",
                "print_order": "as_obj_list",
            },
            {},
        )

        self.assertEqual("by object", profile["printSequence"])
        self.assertEqual("as_obj_list", profile["printOrder"])

    def test_preserves_arachne_minimum_wall_widths(self) -> None:
        profile = build_process(
            "Example",
            {
                "name": "Arachne width tuned",
                "layer_height": "0.2",
                "initial_layer_print_height": "0.2",
                "min_bead_width": "73%",
                "initial_layer_min_bead_width": "112%",
            },
            {},
        )

        self.assertEqual(73.0, profile["minimumWallWidth"])
        self.assertEqual(112.0, profile["firstLayerMinimumWallWidth"])

    def test_preserves_ironing_inset_and_angle(self) -> None:
        profile = build_process(
            "Example",
            {
                "name": "Ironing tuned",
                "layer_height": "0.2",
                "initial_layer_print_height": "0.2",
                "ironing_inset": "0.37",
                "ironing_angle": "123",
            },
            {},
        )

        self.assertEqual(0.37, profile["ironingInset"])
        self.assertEqual(123.0, profile["ironingAngle"])

    def test_preserves_per_feature_jerk_values(self) -> None:
        profile = build_process(
            "Example",
            {
                "name": "Jerk tuned",
                "layer_height": "0.2",
                "initial_layer_print_height": "0.2",
                "default_jerk": "8.5",
                "outer_wall_jerk": "7.5",
                "inner_wall_jerk": "8",
                "top_surface_jerk": "6.5",
                "infill_jerk": "9.5",
                "initial_layer_jerk": "5.5",
                "travel_jerk": "12.5",
            },
            {},
        )

        self.assertEqual(8.5, profile["defaultJerk"])
        self.assertEqual(7.5, profile["outerWallJerk"])
        self.assertEqual(8.0, profile["innerWallJerk"])
        self.assertEqual(6.5, profile["topSurfaceJerk"])
        self.assertEqual(9.5, profile["infillJerk"])
        self.assertEqual(5.5, profile["firstLayerJerk"])
        self.assertEqual(12.5, profile["travelJerk"])

    def test_preserves_sequential_print_head_clearance(self) -> None:
        profile = build_printer(
            "Example",
            {
                "name": "Safe sequential printer",
                "printable_area": ["0x0", "200x0", "200x200", "0x200"],
                "printable_height": "220",
                "nozzle_diameter": "0.4",
                "gcode_flavor": "marlin",
                "extruder_clearance_radius": "71.5",
                "extruder_clearance_height_to_rod": "28.5",
                "extruder_clearance_height_to_lid": "118",
            },
        )

        self.assertEqual(71.5, profile["extruderClearanceRadius"])
        self.assertEqual(28.5, profile["extruderClearanceHeightToRod"])
        self.assertEqual(118.0, profile["extruderClearanceHeightToLid"])

    def test_preserves_printer_retraction_and_nullable_filament_overrides(self) -> None:
        printer = build_printer(
            "Example",
            {
                "name": "Retraction printer",
                "printable_area": ["0x0", "200x0", "200x200", "0x200"],
                "printable_height": "220",
                "nozzle_diameter": "0.4",
                "gcode_flavor": "marlin",
                "retraction_length": ["1.3"],
                "retraction_speed": ["42"],
                "deretraction_speed": ["37"],
                "retraction_minimum_travel": ["2.4"],
                "retract_when_changing_layer": ["1"],
                "wipe": ["1"],
                "wipe_distance": ["3.2"],
                "retract_before_wipe": ["65%"],
                "retract_restart_extra": ["0.08"],
                "z_hop": ["0.7"],
                "z_hop_types": ["Spiral Lift"],
            },
        )
        inherited = build_filament(
            "Example",
            {
                "name": "Inherited PLA",
                "filament_type": ["PLA"],
                "nozzle_temperature": ["220"],
                "hot_plate_temp": ["60"],
                "filament_retraction_length": ["nil"],
            },
        )
        overridden = build_filament(
            "Example",
            {
                "name": "Override PLA",
                "filament_type": ["PLA"],
                "nozzle_temperature": ["220"],
                "hot_plate_temp": ["60"],
                "filament_retraction_length": ["0.55"],
                "filament_z_hop_types": ["Normal Lift"],
                "filament_wipe": ["0"],
            },
        )

        self.assertEqual(1.3, printer["retractLength"])
        self.assertEqual(37.0, printer["deretractSpeed"])
        self.assertTrue(printer["retractWhenChangingLayer"])
        self.assertEqual(65.0, printer["retractBeforeWipe"])
        self.assertEqual("spiral", printer["zHopType"])
        self.assertIsNone(inherited["retractLength"])
        self.assertIsNone(inherited["zHopType"])
        self.assertEqual(0.55, overridden["retractLength"])
        self.assertEqual("normal", overridden["zHopType"])
        self.assertFalse(overridden["wipeWhileRetracting"])

    def test_preserves_advanced_support_generation_values(self) -> None:
        profile = build_process(
            "Example",
            {
                "name": "Advanced support",
                "layer_height": "0.2",
                "initial_layer_print_height": "0.2",
                "support_on_build_plate_only": "1",
                "support_critical_regions_only": "1",
                "support_remove_small_overhang": "0",
                "support_base_pattern_spacing": "3.2",
                "support_expansion": "-0.4",
                "support_interface_loop_pattern": "1",
                "independent_support_layer_height": "0",
            },
            {},
        )

        self.assertTrue(profile["supportOnBuildPlateOnly"])
        self.assertTrue(profile["supportCriticalRegionsOnly"])
        self.assertFalse(profile["supportRemoveSmallOverhangs"])
        self.assertEqual(3.2, profile["supportBasePatternSpacing"])
        self.assertEqual(-0.4, profile["supportExpansion"])
        self.assertTrue(profile["supportInterfaceLoopPattern"])
        self.assertFalse(profile["independentSupportLayerHeight"])

    def test_preserves_printable_overhang_geometry_values(self) -> None:
        profile = build_process(
            "Example",
            {
                "name": "Printable overhangs",
                "layer_height": "0.2",
                "initial_layer_print_height": "0.2",
                "make_overhang_printable": "1",
                "make_overhang_printable_angle": "63",
                "make_overhang_printable_hole_size": "240",
            },
            {},
        )

        self.assertTrue(profile["makeOverhangPrintable"])
        self.assertEqual(63.0, profile["makeOverhangPrintableAngle"])
        self.assertEqual(240.0, profile["makeOverhangPrintableHoleSize"])

    def test_preserves_tree_support_mode_and_geometry(self) -> None:
        profile = build_process(
            "Example",
            {
                "name": "Automatic tree support",
                "layer_height": "0.2",
                "initial_layer_print_height": "0.2",
                "support_type": "tree(auto)",
                "tree_support_branch_angle": "47",
                "tree_support_branch_distance": "6.2",
                "tree_support_branch_diameter": "2.4",
                "tree_support_wall_count": "2",
                "tree_support_tip_diameter": "1.3",
                "tree_support_angle_slow": "31",
                "tree_support_top_rate": "37%",
                "tree_support_branch_angle_organic": "45",
                "tree_support_branch_distance_organic": "2.2",
                "tree_support_branch_diameter_organic": "3.1",
                "tree_support_branch_diameter_angle": "10",
                "tree_support_adaptive_layer_height": "0",
                "tree_support_auto_brim": "0",
                "tree_support_brim_width": "4.6",
            },
            {},
        )

        self.assertEqual("tree(auto)", profile["supportType"])
        self.assertEqual(47, profile["treeSupportBranchAngle"])
        self.assertEqual(6.2, profile["treeSupportBranchDistance"])
        self.assertEqual(2.4, profile["treeSupportBranchDiameter"])
        self.assertEqual(2, profile["treeSupportWallCount"])
        self.assertEqual(1.3, profile["treeSupportTipDiameter"])
        self.assertEqual(31, profile["treeSupportPreferredBranchAngle"])
        self.assertEqual(37, profile["treeSupportBranchDensity"])
        self.assertEqual(45, profile["treeSupportOrganicBranchAngle"])
        self.assertEqual(2.2, profile["treeSupportOrganicBranchDistance"])
        self.assertEqual(3.1, profile["treeSupportOrganicBranchDiameter"])
        self.assertEqual(10, profile["treeSupportBranchDiameterAngle"])
        self.assertFalse(profile["treeSupportAdaptiveLayerHeight"])
        self.assertFalse(profile["treeSupportAutoBrim"])
        self.assertEqual(4.6, profile["treeSupportBrimWidth"])

    def test_normalizes_support_style_for_the_selected_algorithm(self) -> None:
        normal = build_process(
            "Example",
            {
                "name": "Normal support",
                "layer_height": "0.2",
                "initial_layer_print_height": "0.2",
                "support_type": "normal(auto)",
                "support_style": "organic",
            },
            {},
        )
        tree = build_process(
            "Example",
            {
                "name": "Tree support",
                "layer_height": "0.2",
                "initial_layer_print_height": "0.2",
                "support_type": "tree(auto)",
                "support_style": "tree_strong",
            },
            {},
        )

        self.assertEqual("default", normal["supportStyle"])
        self.assertEqual("tree_strong", tree["supportStyle"])

    def test_preserves_fuzzy_skin_engine_settings(self) -> None:
        profile = build_process(
            "Example",
            {
                "name": "Textured surface",
                "layer_height": "0.2",
                "initial_layer_print_height": "0.2",
                "fuzzy_skin": "allwalls",
                "fuzzy_skin_first_layer": "1",
                "fuzzy_skin_point_distance": "0.65",
                "fuzzy_skin_thickness": "0.28",
                "fuzzy_skin_mode": "combined",
                "fuzzy_skin_noise_type": "billow",
                "fuzzy_skin_scale": "3.5",
                "fuzzy_skin_octaves": "6",
                "fuzzy_skin_persistence": "0.7",
            },
            {},
        )

        self.assertEqual("allwalls", profile["fuzzySkinType"])
        self.assertTrue(profile["fuzzySkinFirstLayer"])
        self.assertEqual(0.65, profile["fuzzySkinPointDistance"])
        self.assertEqual(0.28, profile["fuzzySkinThickness"])
        self.assertEqual("combined", profile["fuzzySkinMode"])
        self.assertEqual("billow", profile["fuzzySkinNoiseType"])
        self.assertEqual(3.5, profile["fuzzySkinScale"])
        self.assertEqual(6, profile["fuzzySkinOctaves"])
        self.assertEqual(0.7, profile["fuzzySkinPersistence"])


if __name__ == "__main__":
    unittest.main()
