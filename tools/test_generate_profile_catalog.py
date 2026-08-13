from __future__ import annotations

import math
import unittest

from tools.generate_profile_catalog import build_printer, build_process, printable_geometry, support_type


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
                "support_filament": "1",
                "support_interface_filament": "2",
                "enable_prime_tower": "1",
                "prime_tower_width": "42",
            },
            {},
        )

        self.assertEqual(1, profile["supportFilament"])
        self.assertEqual(2, profile["supportInterfaceFilament"])
        self.assertTrue(profile["wipeTowerEnabled"])
        self.assertEqual(42.0, profile["wipeTowerWidth"])

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

    def test_preserves_advanced_support_generation_values(self) -> None:
        profile = build_process(
            "Example",
            {
                "name": "Advanced support",
                "layer_height": "0.2",
                "initial_layer_print_height": "0.2",
                "support_on_build_plate_only": "1",
                "support_base_pattern_spacing": "3.2",
                "support_expansion": "-0.4",
                "support_interface_loop_pattern": "1",
                "independent_support_layer_height": "0",
            },
            {},
        )

        self.assertTrue(profile["supportOnBuildPlateOnly"])
        self.assertEqual(3.2, profile["supportBasePatternSpacing"])
        self.assertEqual(-0.4, profile["supportExpansion"])
        self.assertTrue(profile["supportInterfaceLoopPattern"])
        self.assertFalse(profile["independentSupportLayerHeight"])

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


if __name__ == "__main__":
    unittest.main()
