from __future__ import annotations

import math
import unittest

from tools.generate_profile_catalog import (
    adaptive_pressure_advance_model,
    bed_exclude_geometry,
    build_filament,
    build_printer,
    build_process,
    coordinate_pair,
    nozzle_material,
    printable_geometry,
    small_area_flow_compensation_model,
    support_type,
    thumbnail_definitions,
    timelapse_type,
)


class GenerateProfileCatalogTest(unittest.TestCase):
    def test_normalizes_and_bounds_gcode_thumbnail_definitions(self) -> None:
        self.assertEqual("", thumbnail_definitions(None))
        self.assertEqual(
            "48x48/PNG,300x300/QOI",
            thumbnail_definitions(["48x48", "300x300/QOI"], "PNG"),
        )
        self.assertEqual(
            "230x110/JPG",
            thumbnail_definitions("230x110", "jpg"),
        )
        for invalid in (
            "0x100/PNG",
            "1000x100/PNG",
            "100x100/GIF",
            "100 by 100",
            ",".join(["16x16/PNG"] * 9),
        ):
            with self.assertRaises(ValueError):
                thumbnail_definitions(invalid)

        profile = build_printer(
            "Example",
            {
                "name": "Thumbnail printer",
                "printable_area": ["0x0", "220x0", "220x220", "0x220"],
                "printable_height": "250",
                "nozzle_diameter": ["0.4"],
                "gcode_flavor": "marlin",
                "thumbnails": ["64x64", "400x300/QOI"],
                "thumbnails_format": "PNG",
            },
        )
        self.assertEqual("64x64/PNG,400x300/QOI", profile["gcodeThumbnails"])

    def test_preserves_and_validates_nozzle_hardness_contract(self) -> None:
        self.assertEqual("undefine", nozzle_material(None))
        self.assertEqual("hardened_steel", nozzle_material(["HARDENED_STEEL"]))
        with self.assertRaises(ValueError):
            nozzle_material("ruby")

        printer = build_printer(
            "Example",
            {
                "name": "Hardened printer",
                "printable_area": ["0x0", "220x0", "220x220", "0x220"],
                "printable_height": "250",
                "nozzle_diameter": ["0.4"],
                "nozzle_type": "hardened_steel",
                "nozzle_hrc": "62",
                "gcode_flavor": "marlin",
            },
        )
        self.assertEqual("hardened_steel", printer["nozzleMaterial"])
        self.assertEqual(62, printer["nozzleHrc"])

        filament = build_filament(
            "Example",
            {
                "name": "Abrasive filament",
                "filament_type": ["PA-CF"],
                "nozzle_temperature": ["280"],
                "hot_plate_temp": ["100"],
                "required_nozzle_HRC": ["40"],
            },
        )
        self.assertEqual(40, filament["requiredNozzleHrc"])

        with self.assertRaises(ValueError):
            build_printer(
                "Example",
                {
                    "name": "Invalid HRC printer",
                    "printable_area": ["0x0", "220x0", "220x220", "0x220"],
                    "printable_height": "250",
                    "nozzle_diameter": ["0.4"],
                    "nozzle_hrc": "501",
                    "gcode_flavor": "marlin",
                },
            )

    def test_preserves_and_validates_nozzle_height(self) -> None:
        base = {
            "name": "Tall nozzle printer",
            "printable_area": ["0x0", "220x0", "220x220", "0x220"],
            "printable_height": "250",
            "nozzle_diameter": ["0.4"],
            "gcode_flavor": "marlin",
        }
        self.assertEqual(2.5, build_printer("Example", base)["nozzleHeight"])
        self.assertEqual(
            4.76,
            build_printer("Example", {**base, "nozzle_height": ["4.76"]})["nozzleHeight"],
        )
        for invalid in ("0", "0.09", "100.1"):
            with self.assertRaises(ValueError):
                build_printer("Example", {**base, "nozzle_height": [invalid]})
        for non_finite in ("nan", "inf"):
            self.assertEqual(
                2.5,
                build_printer(
                    "Example",
                    {**base, "nozzle_height": [non_finite]},
                )["nozzleHeight"],
            )

    def test_preserves_and_validates_nozzle_volume(self) -> None:
        base = {
            "name": "Cutter-equipped printer",
            "printable_area": ["0x0", "220x0", "220x220", "0x220"],
            "printable_height": "250",
            "nozzle_diameter": ["0.4"],
            "gcode_flavor": "marlin",
        }
        self.assertEqual(0, build_printer("Example", base)["nozzleVolume"])
        self.assertEqual(
            143,
            build_printer("Example", {**base, "nozzle_volume": ["143"]})["nozzleVolume"],
        )
        for invalid in ("-0.1", "1000.1"):
            with self.assertRaises(ValueError):
                build_printer("Example", {**base, "nozzle_volume": [invalid]})
        for non_finite in ("nan", "inf"):
            self.assertEqual(
                0,
                build_printer(
                    "Example",
                    {**base, "nozzle_volume": [non_finite]},
                )["nozzleVolume"],
            )

    def test_validates_adaptive_pressure_advance_models(self) -> None:
        model = "0.06,2,1000\n0.04,12,1000\n0.07,2,5000\n0.05,12,5000"
        self.assertEqual(model, adaptive_pressure_advance_model(model, True))
        self.assertEqual("0,0,0\n0,0,0", adaptive_pressure_advance_model(None, False))

        for invalid in (
            "0.06,2,1000",
            "0.06,2,1000\n0.04,2,1000",
            "0.06,12,1000\n0.04,2,1000",
            "0.06,2,1000\n0.04,12,2000",
            "nan,2,1000\n0.04,12,1000",
            "0.06,0,1000\n0.04,12,1000",
        ):
            with self.assertRaises(ValueError):
                adaptive_pressure_advance_model(invalid, True)

    def test_preserves_adaptive_pressure_advance_filament_fields(self) -> None:
        model = "0.06,2,1000\n0.04,12,1000"
        profile = build_filament(
            "Example",
            {
                "name": "Adaptive PLA",
                "filament_type": ["PLA"],
                "nozzle_temperature": ["220"],
                "hot_plate_temp": ["60"],
                "enable_pressure_advance": ["1"],
                "pressure_advance": ["0.04"],
                "adaptive_pressure_advance": ["1"],
                "adaptive_pressure_advance_model": [model],
                "adaptive_pressure_advance_overhangs": ["1"],
                "adaptive_pressure_advance_bridges": ["0.065"],
            },
        )

        self.assertTrue(profile["pressureAdvanceEnabled"])
        self.assertTrue(profile["adaptivePressureAdvanceEnabled"])
        self.assertEqual(model, profile["adaptivePressureAdvanceModel"])
        self.assertTrue(profile["adaptivePressureAdvanceOverhangs"])
        self.assertEqual(0.065, profile["adaptivePressureAdvanceBridge"])

        with self.assertRaises(ValueError):
            build_filament(
                "Example",
                {
                    "name": "Invalid adaptive PLA",
                    "filament_type": ["PLA"],
                    "nozzle_temperature": ["220"],
                    "hot_plate_temp": ["60"],
                    "adaptive_pressure_advance": ["1"],
                    "adaptive_pressure_advance_model": [model],
                },
            )

    def test_parses_adaptive_bed_mesh_coordinate_pairs(self) -> None:
        self.assertEqual((10.0, 20.0), coordinate_pair(["10", "20"], 1, 2))
        self.assertEqual((10.5, -20.25), coordinate_pair("10.5,-20.25", 1, 2))
        self.assertEqual((10.5, -20.25), coordinate_pair("10.5x-20.25", 1, 2))
        self.assertEqual((12.0, 12.0), coordinate_pair("12", 1, 2))
        self.assertEqual((1, 2), coordinate_pair(None, 1, 2))
        with self.assertRaises(ValueError):
            coordinate_pair(["1", "2", "3"], 1, 2)

    def test_preserves_and_validates_adaptive_bed_mesh(self) -> None:
        base = {
            "name": "Adaptive mesh printer",
            "printable_area": ["0x0", "300x0", "300x300", "0x300"],
            "printable_height": "300",
            "nozzle_diameter": ["0.4"],
            "gcode_flavor": "klipper",
            "bed_mesh_min": "10,11",
            "bed_mesh_max": ["290", "291"],
            "bed_mesh_probe_distance": "40x41",
            "adaptive_bed_mesh_margin": "5",
            "machine_max_junction_deviation": ["0.032", "0"],
        }

        profile = build_printer("Example", base)

        self.assertEqual(10.0, profile["bedMeshMinX"])
        self.assertEqual(11.0, profile["bedMeshMinY"])
        self.assertEqual(290.0, profile["bedMeshMaxX"])
        self.assertEqual(291.0, profile["bedMeshMaxY"])
        self.assertEqual(40.0, profile["bedMeshProbeDistanceX"])
        self.assertEqual(41.0, profile["bedMeshProbeDistanceY"])
        self.assertEqual(5.0, profile["adaptiveBedMeshMargin"])
        self.assertEqual(0.032, profile["maxJunctionDeviation"])
        with self.assertRaises(ValueError):
            build_printer("Example", base | {"bed_mesh_min": "295,11"})
        with self.assertRaises(ValueError):
            build_printer("Example", base | {"machine_max_junction_deviation": "11"})

    def test_preserves_first_layer_inspection(self) -> None:
        enabled = build_printer(
            "Example",
            {
                "name": "Camera printer",
                "printable_area": ["0x0", "220x0", "220x220", "0x220"],
                "printable_height": "250",
                "nozzle_diameter": ["0.4"],
                "gcode_flavor": "marlin",
                "scan_first_layer": "1",
            },
        )
        disabled = build_printer(
            "Example",
            {
                "name": "Plain printer",
                "printable_area": ["0x0", "220x0", "220x220", "0x220"],
                "printable_height": "250",
                "nozzle_diameter": ["0.4"],
                "gcode_flavor": "marlin",
            },
        )

        self.assertTrue(enabled["scanFirstLayer"])
        self.assertFalse(disabled["scanFirstLayer"])

    def test_preserves_orca_timelapse_mode_and_rejects_smooth_by_object(self) -> None:
        self.assertEqual("traditional", timelapse_type(None))
        self.assertEqual("traditional", timelapse_type("0"))
        self.assertEqual("smooth", timelapse_type("1"))
        self.assertEqual("traditional", timelapse_type("unexpected"))

        smooth = build_process(
            "Example",
            {
                "name": "Smooth timelapse",
                "layer_height": "0.2",
                "initial_layer_print_height": "0.2",
                "timelapse_type": "1",
                "print_sequence": "by layer",
            },
            {},
        )
        self.assertEqual("smooth", smooth["timelapseType"])

        with self.assertRaises(ValueError):
            build_process(
                "Example",
                {
                    "name": "Unsafe smooth timelapse",
                    "layer_height": "0.2",
                    "initial_layer_print_height": "0.2",
                    "timelapse_type": "1",
                    "print_sequence": "by object",
                },
                {},
            )

    def test_normalizes_and_validates_small_area_flow_compensation(self) -> None:
        serialized = '0,0;"\\n0.5,0.6";"\\n10,1"'
        self.assertEqual(
            "0,0\n0.5,0.6\n10,1",
            small_area_flow_compensation_model(serialized),
        )
        profile = build_process(
            "Example",
            {
                "name": "Small area compensation",
                "layer_height": "0.2",
                "initial_layer_print_height": "0.2",
                "small_area_infill_flow_compensation": "1",
                "small_area_infill_flow_compensation_model": [
                    "0,0",
                    "\n0.5,0.6",
                    "\n10,1",
                ],
            },
            {},
        )
        self.assertTrue(profile["smallAreaFlowCompensation"])
        self.assertEqual("0,0\n0.5,0.6\n10,1", profile["smallAreaFlowCompensationModel"])
        for invalid in (
            "0,0\n0.5,0.6\n0.4,1",
            "1,0\n10,1",
            "0,0\n10,0.9",
            "0,0\n10,3",
        ):
            with self.assertRaises(ValueError):
                small_area_flow_compensation_model(invalid)

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

    def test_preserves_bed_exclusion_geometry_and_offset_sentinel(self) -> None:
        self.assertEqual(
            [0.0, 0.0, 18.0, 0.0, 18.0, 28.0, 0.0, 28.0],
            bed_exclude_geometry(
                ["-10x-20", "8x-20", "8x8", "-10x8"],
                -10.0,
                -20.0,
                200.0,
                200.0,
            ),
        )
        self.assertEqual(
            [0.0, 0.0],
            bed_exclude_geometry(["0x0"], -10.0, -20.0, 200.0, 200.0),
        )
        with self.assertRaises(ValueError):
            bed_exclude_geometry(["0x0", "220x0", "220x10"], 0.0, 0.0, 200.0, 200.0)

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
                "wipe_tower_x": ["123.5"],
                "wipe_tower_y": ["87.5"],
                "prime_tower_brim_width": "4.5",
                "prime_tower_enable_framework": "1",
                "prime_tower_skip_points": "0",
                "prime_tower_flat_ironing": "1",
                "enable_tower_interface_features": "1",
                "enable_tower_interface_cooldown_during_tower": "1",
                "prime_tower_infill_gap": "175",
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
                "preheat_time": "94.5",
                "delta_temperature": "-18",
                "preheat_steps": "7",
                "interface_shells": "1",
                "mmu_segmented_region_max_width": "2.4",
                "mmu_segmented_region_interlocking_depth": "0.8",
                "interlocking_beam": "1",
                "interlocking_beam_width": "1.25",
                "interlocking_orientation": "67.5",
                "interlocking_beam_layer_count": "3",
                "interlocking_depth": "4",
                "interlocking_boundary_avoidance": "1",
                "max_volumetric_extrusion_rate_slope": "20",
                "max_volumetric_extrusion_rate_slope_segment_length": "5",
                "extrusion_rate_smoothing_external_perimeter_only": "1",
                "enable_arc_fitting": "1",
                "gcode_label_objects": "0",
                "exclude_object": "1",
                "gcode_comments": "1",
                "top_surface_density": "42%",
                "bottom_surface_density": "68%",
                "infill_shift_step": "1.7",
                "symmetric_infill_y_axis": "1",
                "travel_speed_z": "17",
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
        self.assertEqual(123.5, profile["primeTowerPositionX"])
        self.assertEqual(87.5, profile["primeTowerPositionY"])
        self.assertEqual(4.5, profile["primeTowerBrimWidth"])
        self.assertTrue(profile["primeTowerFramework"])
        self.assertFalse(profile["primeTowerSkipPoints"])
        self.assertTrue(profile["primeTowerFlatIroning"])
        self.assertTrue(profile["primeTowerInterfaceFeatures"])
        self.assertTrue(profile["primeTowerInterfaceCooldown"])
        self.assertEqual(175.0, profile["primeTowerInfillGap"])
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
        self.assertEqual(94.5, profile["preheatTime"])
        self.assertEqual(-18, profile["preheatDeltaTemperature"])
        self.assertEqual(7, profile["preheatSteps"])
        self.assertTrue(profile["interfaceShells"])
        self.assertEqual(2.4, profile["segmentedRegionMaxWidth"])
        self.assertEqual(0.8, profile["segmentedRegionInterlockingDepth"])
        self.assertTrue(profile["interlockingBeam"])
        self.assertEqual(1.25, profile["interlockingBeamWidth"])
        self.assertEqual(67.5, profile["interlockingOrientation"])
        self.assertEqual(3, profile["interlockingBeamLayerCount"])
        self.assertEqual(4, profile["interlockingDepth"])
        self.assertEqual(1, profile["interlockingBoundaryAvoidance"])
        self.assertEqual(20.0, profile["maxVolumetricExtrusionRateSlope"])
        self.assertEqual(5.0, profile["maxVolumetricExtrusionRateSlopeSegmentLength"])
        self.assertTrue(profile["extrusionRateSmoothingExternalOnly"])
        self.assertTrue(profile["enableArcFitting"])
        self.assertFalse(profile["gcodeLabelObjects"])
        self.assertTrue(profile["excludeObject"])
        self.assertTrue(profile["gcodeComments"])
        self.assertEqual(42.0, profile["topSurfaceDensity"])
        self.assertEqual(68.0, profile["bottomSurfaceDensity"])
        self.assertEqual(1.7, profile["infillShiftStep"])
        self.assertTrue(profile["symmetricInfillYAxis"])
        self.assertEqual(17.0, profile["travelSpeedZ"])
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

    def test_rejects_segmented_region_depth_larger_than_its_width(self) -> None:
        with self.assertRaises(ValueError):
            build_process(
                "Example",
                {
                    "name": "Unsafe segmented region",
                    "layer_height": "0.2",
                    "initial_layer_print_height": "0.2",
                    "mmu_segmented_region_max_width": "1.5",
                    "mmu_segmented_region_interlocking_depth": "2",
                },
                {},
            )

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

    def test_preserves_locked_zag_process_values(self) -> None:
        profile = build_process(
            "Example",
            {
                "name": "Locked Zag",
                "layer_height": "0.2",
                "initial_layer_print_height": "0.2",
                "sparse_infill_pattern": "lockedzag",
                "lateral_lattice_angle_1": "-32",
                "lateral_lattice_angle_2": "57",
                "infill_overhang_angle": "68",
                "skeleton_infill_density": "31%",
                "skin_infill_density": "47%",
                "skin_infill_depth": "3.5",
                "infill_lock_depth": "1.25",
                "skin_infill_line_width": "135%",
                "skeleton_infill_line_width": "0.62",
                "sparse_infill_rotate_template": "0,60,120",
                "rotate_solid_infill_direction": "1",
            },
            {},
        )

        self.assertEqual("lockedzag", profile["fillPattern"])
        self.assertEqual(1, profile["fillMultiline"])
        self.assertEqual(-32.0, profile["lateralLatticeAngle1"])
        self.assertEqual(57.0, profile["lateralLatticeAngle2"])
        self.assertEqual(68.0, profile["infillOverhangAngle"])
        self.assertEqual(31.0, profile["skeletonInfillDensity"])
        self.assertEqual(47.0, profile["skinInfillDensity"])
        self.assertEqual(3.5, profile["skinInfillDepth"])
        self.assertEqual(1.25, profile["infillLockDepth"])
        self.assertEqual(135.0, profile["skinInfillLineWidth"])
        self.assertTrue(profile["skinInfillLineWidthPercent"])
        self.assertEqual(0.62, profile["skeletonInfillLineWidth"])
        self.assertFalse(profile["skeletonInfillLineWidthPercent"])
        self.assertEqual("0,60,120", profile["sparseInfillRotationTemplate"])
        self.assertEqual("0,90", profile["solidInfillRotationTemplate"])

    def test_preserves_fill_multiline(self) -> None:
        profile = build_process(
            "Example",
            {
                "name": "Multiline cross hatch",
                "layer_height": "0.2",
                "initial_layer_print_height": "0.2",
                "sparse_infill_pattern": "crosshatch",
                "fill_multiline": "4",
            },
            {},
        )

        self.assertEqual("crosshatch", profile["fillPattern"])
        self.assertEqual(4, profile["fillMultiline"])

    def test_preserves_skirt_start_point(self) -> None:
        profile = build_process(
            "Example",
            {
                "name": "Directed skirt",
                "layer_height": "0.2",
                "initial_layer_print_height": "0.2",
                "skirt_start_angle": "-25",
            },
            {},
        )

        self.assertEqual(-25.0, profile["skirtStartAngle"])

    def test_preserves_per_object_and_single_loop_skirt_behavior(self) -> None:
        profile = build_process(
            "Example",
            {
                "name": "Per-object skirt",
                "layer_height": "0.2",
                "initial_layer_print_height": "0.2",
                "skirt_type": "perobject",
                "single_loop_draft_shield": "1",
            },
            {},
        )

        self.assertEqual("perobject", profile["skirtType"])
        self.assertTrue(profile["singleLoopDraftShield"])

        defaults = build_process(
            "Example",
            {"name": "Default skirt", "layer_height": "0.2", "initial_layer_print_height": "0.2"},
            {},
        )
        self.assertEqual("combined", defaults["skirtType"])
        self.assertFalse(defaults["singleLoopDraftShield"])

    def test_preserves_safe_output_filename_format_and_repairs_blank_legacy_value(self) -> None:
        template = (
            "{input_filename_base}_{filament_type[initial_tool]}_"
            "{layer_height}mm_{printer_model}_{print_time}.gcode"
        )
        profile = build_process(
            "Example",
            {
                "name": "Named output",
                "layer_height": "0.2",
                "initial_layer_print_height": "0.2",
                "filename_format": template,
            },
            {},
        )
        self.assertEqual(template, profile["filenameFormat"])

        whitespace_template = "  {input_filename_base}.gcode  "
        whitespace_profile = build_process(
            "Example",
            {
                "name": "Whitespace output",
                "layer_height": "0.2",
                "initial_layer_print_height": "0.2",
                "filename_format": whitespace_template,
            },
            {},
        )
        self.assertEqual(whitespace_template, whitespace_profile["filenameFormat"])

        repaired = build_process(
            "Example",
            {
                "name": "Legacy blank output",
                "layer_height": "0.2",
                "initial_layer_print_height": "0.2",
                "filename_format": "",
            },
            {},
        )
        self.assertEqual(
            "{input_filename_base}_{filament_type[initial_tool]}_{print_time}.gcode",
            repaired["filenameFormat"],
        )

        with self.assertRaisesRegex(ValueError, "unsafe filename format"):
            build_process(
                "Example",
                {
                    "name": "Unsafe output",
                    "layer_height": "0.2",
                    "initial_layer_print_height": "0.2",
                    "filename_format": "bad\nname.gcode",
                },
                {},
            )

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

    def test_preserves_polyhole_geometry_settings(self) -> None:
        profile = build_process(
            "Example",
            {
                "name": "Polyhole tuned",
                "layer_height": "0.2",
                "initial_layer_print_height": "0.2",
                "hole_to_polyhole": "1",
                "hole_to_polyhole_threshold": "7%",
                "hole_to_polyhole_twisted": "0",
            },
            {},
        )

        self.assertTrue(profile["holeToPolyhole"])
        self.assertEqual(7.0, profile["holeToPolyholeThreshold"])
        self.assertTrue(profile["holeToPolyholeThresholdPercent"])
        self.assertFalse(profile["holeToPolyholeTwisted"])

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
                "bed_exclude_area": ["0x0", "18x0", "18x28", "0x28"],
                "printable_height": "220",
                "nozzle_diameter": ["0.4", "0.4"],
                "min_layer_height": ["0.08"],
                "max_layer_height": ["0.32"],
                "gcode_flavor": "marlin",
                "machine_pause_gcode": "M25 ; PROFILE_PAUSE",
                "time_lapse_gcode": "; DUCKY_TIMELAPSE",
                "auxiliary_fan": "1",
                "fan_speedup_time": "0.5",
                "fan_speedup_overhangs": "0",
                "fan_kickstart": "0.2",
                "extruder_clearance_radius": "71.5",
                "extruder_clearance_height_to_rod": "28.5",
                "extruder_clearance_height_to_lid": "118",
                "retract_length_toolchange": ["1.5", "2.5"],
                "retract_restart_extra_toolchange": ["-0.1", "0.2"],
                "extruder_offset": ["0x0", "12.5x-3.25"],
                "default_print_profile": "0.20 mm Standard @Example 0.4 nozzle",
                "default_filament_profile": [
                    "Example PLA @Example",
                    "Example Support @Example",
                ],
            },
        )

        self.assertEqual(71.5, profile["extruderClearanceRadius"])
        self.assertEqual(28.5, profile["extruderClearanceHeightToRod"])
        self.assertEqual(118.0, profile["extruderClearanceHeightToLid"])
        self.assertFalse(profile["singleExtruderMultiMaterial"])
        self.assertEqual(2, profile["extruderCount"])
        self.assertTrue(profile["auxiliaryFan"])
        self.assertEqual(0.5, profile["fanSpeedupTime"])
        self.assertFalse(profile["fanSpeedupOverhangs"])
        self.assertEqual(0.2, profile["fanKickstart"])
        self.assertEqual("M25 ; PROFILE_PAUSE", profile["machinePauseGcode"])
        self.assertEqual("; DUCKY_TIMELAPSE", profile["timeLapseGcode"])
        self.assertEqual(0.08, profile["minLayerHeight"])
        self.assertEqual(0.32, profile["maxLayerHeight"])
        self.assertEqual([0.0, 12.5], profile["extruderOffsetsX"])
        self.assertEqual([0.0, -3.25], profile["extruderOffsetsY"])
        self.assertEqual([1.5, 2.5], profile["toolChangeRetractLengths"])
        self.assertEqual([-0.1, 0.2], profile["toolChangeRetractRestartExtras"])
        self.assertEqual(
            "0.20 mm Standard @Example 0.4 nozzle",
            profile["defaultPrintProfile"],
        )
        self.assertEqual(
            ["Example PLA @Example", "Example Support @Example"],
            profile["defaultFilamentProfiles"],
        )
        self.assertEqual(
            [0.0, 0.0, 18.0, 0.0, 18.0, 28.0, 0.0, 28.0],
            profile["bedExcludeArea"],
        )

    def test_normalizes_legacy_semicolon_delimited_printer_defaults(self) -> None:
        profile = build_printer(
            "Example",
            {
                "name": "Legacy defaults printer",
                "printable_area": ["0x0", "200x0", "200x200", "0x200"],
                "printable_height": "220",
                "nozzle_diameter": "0.4",
                "gcode_flavor": "marlin",
                "default_print_profile": " Fine ; Draft ",
                "default_filament_profile": " PLA ; PETG ",
            },
        )

        self.assertEqual("Fine", profile["defaultPrintProfile"])
        self.assertEqual(["PLA", "PETG"], profile["defaultFilamentProfiles"])

    def test_resolves_orca_layer_height_sentinels_and_rejects_inverted_limits(self) -> None:
        base = {
            "name": "Layer limit printer",
            "printable_area": ["0x0", "200x0", "200x200", "0x200"],
            "printable_height": "220",
            "nozzle_diameter": "0.4",
            "gcode_flavor": "marlin",
            "min_layer_height": ["0"],
            "max_layer_height": ["0"],
        }

        profile = build_printer("Example", base)

        self.assertEqual(0.07, profile["minLayerHeight"])
        self.assertAlmostEqual(0.3, profile["maxLayerHeight"])
        with self.assertRaises(ValueError):
            build_printer(
                "Example",
                base | {"min_layer_height": ["0.35"], "max_layer_height": ["0.2"]},
            )

    def test_classifies_single_extruder_multi_material_printers(self) -> None:
        profile = build_printer(
            "Example",
            {
                "name": "SEMM printer",
                "printable_area": ["0x0", "200x0", "200x200", "0x200"],
                "printable_height": "220",
                "nozzle_diameter": ["0.4"],
                "single_extruder_multi_material": "1",
                "gcode_flavor": "marlin",
            },
        )

        self.assertTrue(profile["singleExtruderMultiMaterial"])
        self.assertEqual(16, profile["extruderCount"])
        self.assertFalse(profile["auxiliaryFan"])

    def test_preserves_explicit_dual_tool_capacity_with_inherited_semm_flag(self) -> None:
        profile = build_printer(
            "Example",
            {
                "name": "Explicit dual tool printer",
                "printable_area": ["0x0", "200x0", "200x200", "0x200"],
                "printable_height": "220",
                "nozzle_diameter": ["0.4", "0.4"],
                "single_extruder_multi_material": "1",
                "gcode_flavor": "marlin",
            },
        )

        self.assertTrue(profile["singleExtruderMultiMaterial"])
        self.assertEqual(2, profile["extruderCount"])

    def test_normalizes_legacy_toolchange_gcode_without_overriding_modern_template(self) -> None:
        base = {
            "name": "Legacy tool-change printer",
            "printable_area": ["0x0", "200x0", "200x200", "0x200"],
            "printable_height": "220",
            "nozzle_diameter": ["0.4", "0.4"],
            "gcode_flavor": "marlin",
            "toolchange_gcode": "; LEGACY_TOOL_CHANGE",
        }

        inherited = build_printer(
            "Example",
            base | {"change_filament_gcode": ""},
        )
        modern = build_printer(
            "Example",
            base | {"change_filament_gcode": "; MODERN_TOOL_CHANGE"},
        )

        self.assertEqual("; LEGACY_TOOL_CHANGE", inherited["changeFilamentGcode"])
        self.assertEqual("; MODERN_TOOL_CHANGE", modern["changeFilamentGcode"])

    def test_preserves_all_orca_build_plate_temperatures(self) -> None:
        profile = build_filament(
            "Example",
            {
                "name": "Plate matrix PLA",
                "filament_type": ["PLA"],
                "nozzle_temperature": ["220"],
                "hot_plate_temp": ["71"],
                "hot_plate_temp_initial_layer": ["72"],
                "textured_plate_temp": ["53"],
                "textured_plate_temp_initial_layer": ["54"],
                "eng_plate_temp": ["61"],
                "eng_plate_temp_initial_layer": ["62"],
                "cool_plate_temp": ["31"],
                "cool_plate_temp_initial_layer": ["32"],
                "textured_cool_plate_temp": ["33"],
                "textured_cool_plate_temp_initial_layer": ["34"],
                "supertack_plate_temp": ["35"],
                "supertack_plate_temp_initial_layer": ["36"],
                "graphic_effect_plate_temp": ["55"],
                "graphic_effect_plate_temp_initial_layer": ["56"],
            },
        )

        self.assertEqual(71, profile["bedTemp"])
        self.assertEqual(72, profile["firstLayerBedTemp"])
        self.assertEqual(53, profile["texturedPlateTemp"])
        self.assertEqual(54, profile["firstLayerTexturedPlateTemp"])
        self.assertEqual(61, profile["engineeringPlateTemp"])
        self.assertEqual(62, profile["firstLayerEngineeringPlateTemp"])
        self.assertEqual(31, profile["coolPlateTemp"])
        self.assertEqual(32, profile["firstLayerCoolPlateTemp"])
        self.assertEqual(33, profile["texturedCoolPlateTemp"])
        self.assertEqual(34, profile["firstLayerTexturedCoolPlateTemp"])
        self.assertEqual(35, profile["superTackPlateTemp"])
        self.assertEqual(36, profile["firstLayerSuperTackPlateTemp"])
        self.assertEqual(55, profile["graphicEffectPlateTemp"])
        self.assertEqual(56, profile["firstLayerGraphicEffectPlateTemp"])

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
                "retract_lift_above": ["0.35"],
                "retract_lift_below": ["180"],
                "retract_lift_enforce": ["Top and Bottom"],
                "travel_slope": ["7"],
                "z_hop_when_prime": ["0"],
                "use_firmware_retraction": ["1"],
                "enable_long_retraction_when_cut": ["2"],
                "long_retractions_when_cut": ["0"],
                "retraction_distances_when_cut": ["17"],
                "before_layer_change_gcode": "; DUCKY_BEFORE_LAYER",
                "layer_change_gcode": "; DUCKY_AFTER_LAYER",
                "change_filament_gcode": "T[next_extruder] ; DUCKY_CHANGE_FILAMENT",
                "printing_by_object_gcode": "; DUCKY_BETWEEN_OBJECTS",
                "use_relative_e_distances": "0",
                "emit_machine_limits_to_gcode": "0",
                "manual_filament_change": "1",
                "disable_m73": "1",
                "machine_load_filament_time": "12.5",
                "machine_unload_filament_time": "23.5",
                "machine_tool_change_time": "4.5",
                "tool_change_temprature_wait": "0",
                "cooling_tube_retraction": "73.5",
                "cooling_tube_length": "11",
                "parking_pos_retraction": "80",
                "extra_loading_move": "-3.5",
                "enable_filament_ramming": "0",
                "purge_in_prime_tower": "0",
                "high_current_on_filament_swap": "1",
                "support_chamber_temp_control": "1",
                "support_air_filtration": ["1"],
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
                "filament_long_retractions_when_cut": ["nil"],
                "filament_retraction_distances_when_cut": ["nil"],
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
                "filament_diameter": ["2.85"],
                "filament_density": ["1.07"],
                "filament_cost": ["42.5"],
                "filament_shrink": ["99.2%"],
                "filament_shrinkage_compensation_z": ["99.18%"],
                "filament_soluble": ["1"],
                "filament_is_support": ["1"],
                "filament_minimal_purge_on_wipe_tower": ["35"],
                "filament_tower_interface_pre_extrusion_dist": ["21"],
                "filament_tower_interface_pre_extrusion_length": ["22"],
                "filament_tower_ironing_area": ["23"],
                "filament_tower_interface_purge_volume": ["24"],
                "filament_tower_interface_print_temp": ["241"],
                "additional_cooling_fan_speed": ["75%"],
                "filament_loading_speed": ["27"],
                "filament_loading_speed_start": ["4"],
                "filament_unloading_speed": ["88"],
                "filament_unloading_speed_start": ["98"],
                "filament_toolchange_delay": ["0.7"],
                "filament_cooling_moves": ["6"],
                "filament_stamping_loading_speed": ["29"],
                "filament_stamping_distance": ["45"],
                "filament_cooling_initial_speed": ["2.8"],
                "filament_cooling_final_speed": ["4.8"],
                "filament_ramming_parameters": ['"125 95 7 8 9| 0.1 7 0.5 8"'],
                "filament_multitool_ramming": ["1"],
                "filament_multitool_ramming_volume": ["8"],
                "filament_multitool_ramming_flow": ["18"],
                "temperature_vitrification": ["62"],
                "nozzle_temperature_range_low": ["195"],
                "nozzle_temperature_range_high": ["245"],
                "activate_chamber_temp_control": ["1"],
                "chamber_temperature": ["55"],
                "activate_air_filtration": ["1"],
                "during_print_exhaust_fan_speed": ["70%"],
                "complete_print_exhaust_fan_speed": ["40%"],
                "fan_cooling_layer_time": ["42"],
                "slow_down_for_layer_cooling": ["0"],
                "reduce_fan_stop_start_freq": ["1"],
                "dont_slow_down_outer_wall": ["1"],
                "enable_overhang_bridge_fan": ["1"],
                "overhang_fan_threshold": ["25%"],
                "internal_bridge_fan_speed": ["45"],
                "support_material_interface_fan_speed": ["85"],
                "filament_z_hop_types": ["Normal Lift"],
                "filament_retract_lift_above": ["0.8"],
                "filament_retract_lift_below": ["150"],
                "filament_retract_lift_enforce": ["Top Only"],
                "filament_long_retractions_when_cut": ["1"],
                "filament_retraction_distances_when_cut": ["16.5"],
                "idle_temperature": ["135"],
                "filament_wipe": ["0"],
            },
        )

        self.assertEqual(1.3, printer["retractLength"])
        self.assertEqual(37.0, printer["deretractSpeed"])
        self.assertTrue(printer["retractWhenChangingLayer"])
        self.assertEqual(65.0, printer["retractBeforeWipe"])
        self.assertEqual("spiral", printer["zHopType"])
        self.assertEqual(0.35, printer["retractLiftAbove"])
        self.assertEqual(180.0, printer["retractLiftBelow"])
        self.assertEqual("top_bottom", printer["retractLiftEnforce"])
        self.assertEqual(7.0, printer["travelSlope"])
        self.assertFalse(printer["zHopWhenPrime"])
        self.assertTrue(printer["useFirmwareRetraction"])
        self.assertEqual(2, printer["longRetractionWhenCutLevel"])
        self.assertFalse(printer["longRetractionWhenCut"])
        self.assertEqual(17.0, printer["retractionDistanceWhenCut"])
        self.assertEqual("; DUCKY_BEFORE_LAYER", printer["beforeLayerChangeGcode"])
        self.assertEqual("; DUCKY_AFTER_LAYER", printer["layerChangeGcode"])
        self.assertEqual(
            "T[next_extruder] ; DUCKY_CHANGE_FILAMENT",
            printer["changeFilamentGcode"],
        )
        self.assertEqual("; DUCKY_BETWEEN_OBJECTS", printer["printingByObjectGcode"])
        self.assertFalse(printer["useRelativeEDistances"])
        self.assertFalse(printer["emitMachineLimitsToGcode"])
        self.assertTrue(printer["manualFilamentChange"])
        self.assertTrue(printer["disableM73"])
        self.assertEqual(12.5, printer["machineLoadFilamentTime"])
        self.assertEqual(23.5, printer["machineUnloadFilamentTime"])
        self.assertEqual(4.5, printer["machineToolChangeTime"])
        self.assertFalse(printer["toolChangeTemperatureWait"])
        self.assertEqual(73.5, printer["coolingTubeRetraction"])
        self.assertEqual(11.0, printer["coolingTubeLength"])
        self.assertEqual(80.0, printer["parkingPosRetraction"])
        self.assertEqual(-3.5, printer["extraLoadingMove"])
        self.assertFalse(printer["enableFilamentRamming"])
        self.assertFalse(printer["purgeInPrimeTower"])
        self.assertTrue(printer["highCurrentOnFilamentSwap"])
        self.assertTrue(printer["supportsChamberTemperatureControl"])
        self.assertTrue(printer["supportsAirFiltration"])
        self.assertIsNone(inherited["retractLength"])
        self.assertIsNone(inherited["zHopType"])
        self.assertIsNone(inherited["retractLiftAbove"])
        self.assertIsNone(inherited["retractLiftBelow"])
        self.assertIsNone(inherited["retractLiftEnforce"])
        self.assertIsNone(inherited["longRetractionWhenCut"])
        self.assertIsNone(inherited["retractionDistanceWhenCut"])
        self.assertEqual(0.55, overridden["retractLength"])
        self.assertEqual(2.85, overridden["diameter"])
        self.assertEqual(1.07, overridden["density"])
        self.assertEqual(42.5, overridden["costPerKilogram"])
        self.assertEqual(99.2, overridden["shrinkageXyPercent"])
        self.assertEqual(99.18, overridden["shrinkageZPercent"])
        self.assertTrue(overridden["soluble"])
        self.assertTrue(overridden["supportMaterial"])
        self.assertEqual(35.0, overridden["minimalPurgeOnWipeTower"])
        self.assertEqual(21.0, overridden["towerInterfacePreExtrusionDistance"])
        self.assertEqual(22.0, overridden["towerInterfacePreExtrusionLength"])
        self.assertEqual(23.0, overridden["towerIroningArea"])
        self.assertEqual(24.0, overridden["towerInterfacePurgeLength"])
        self.assertEqual(241, overridden["towerInterfacePrintTemperature"])
        self.assertEqual(75, overridden["additionalCoolingFanSpeed"])
        self.assertEqual(27.0, overridden["loadingSpeed"])
        self.assertEqual(4.0, overridden["loadingSpeedStart"])
        self.assertEqual(88.0, overridden["unloadingSpeed"])
        self.assertEqual(98.0, overridden["unloadingSpeedStart"])
        self.assertEqual(0.7, overridden["toolchangeDelay"])
        self.assertEqual(6, overridden["coolingMoves"])
        self.assertEqual(29.0, overridden["stampingLoadingSpeed"])
        self.assertEqual(45.0, overridden["stampingDistance"])
        self.assertEqual(2.8, overridden["coolingInitialSpeed"])
        self.assertEqual(4.8, overridden["coolingFinalSpeed"])
        self.assertEqual("125 95 7 8 9| 0.1 7 0.5 8", overridden["rammingParameters"])
        self.assertTrue(overridden["multitoolRamming"])
        self.assertEqual(8.0, overridden["multitoolRammingVolume"])
        self.assertEqual(18.0, overridden["multitoolRammingFlow"])
        self.assertEqual(62, overridden["softeningTemperature"])
        self.assertEqual(195, overridden["nozzleTemperatureRangeLow"])
        self.assertEqual(245, overridden["nozzleTemperatureRangeHigh"])
        self.assertTrue(overridden["chamberTemperatureControl"])
        self.assertEqual(55, overridden["chamberTemperature"])
        self.assertTrue(overridden["airFiltration"])
        self.assertEqual(70, overridden["duringPrintExhaustFanSpeed"])
        self.assertEqual(40, overridden["completePrintExhaustFanSpeed"])
        self.assertEqual(42.0, overridden["fanCoolingLayerTime"])
        self.assertFalse(overridden["slowDownForLayerCooling"])
        self.assertTrue(overridden["keepFanAlwaysOn"])
        self.assertTrue(overridden["dontSlowDownOuterWall"])
        self.assertTrue(overridden["enableOverhangBridgeFan"])
        self.assertEqual("25%", overridden["overhangFanThreshold"])
        self.assertEqual(45, overridden["internalBridgeFanSpeed"])
        self.assertEqual(85, overridden["supportInterfaceFanSpeed"])
        self.assertEqual("normal", overridden["zHopType"])
        self.assertEqual(0.8, overridden["retractLiftAbove"])
        self.assertEqual(150.0, overridden["retractLiftBelow"])
        self.assertEqual("top", overridden["retractLiftEnforce"])
        self.assertTrue(overridden["longRetractionWhenCut"])
        self.assertEqual(16.5, overridden["retractionDistanceWhenCut"])
        self.assertEqual(135, overridden["idleTemperature"])
        self.assertFalse(overridden["wipeWhileRetracting"])

    def test_rejects_unsafe_filament_diameter(self) -> None:
        with self.assertRaises(ValueError):
            build_filament(
                "Example",
                {
                    "name": "Unsafe diameter",
                    "filament_type": ["PLA"],
                    "nozzle_temperature": ["220"],
                    "hot_plate_temp": ["60"],
                    "filament_diameter": ["4.01"],
                },
            )

    def test_rejects_unsafe_material_statistics(self) -> None:
        for key, value in (
            ("filament_density", "10.01"),
            ("filament_cost", "1000000.01"),
            ("filament_shrink", "9.99"),
            ("filament_shrinkage_compensation_z", "200.01"),
        ):
            with self.subTest(key=key), self.assertRaises(ValueError):
                build_filament(
                    "Example",
                    {
                        "name": "Unsafe statistics",
                        "filament_type": ["PLA"],
                        "nozzle_temperature": ["220"],
                        "hot_plate_temp": ["60"],
                        key: [value],
                    },
                )

    def test_rejects_unsafe_purge_floor_and_auxiliary_fan_speed(self) -> None:
        for key, value in (
            ("filament_minimal_purge_on_wipe_tower", "1000.01"),
            ("filament_tower_interface_pre_extrusion_dist", "1000.01"),
            ("filament_tower_interface_pre_extrusion_length", "1000.01"),
            ("filament_tower_ironing_area", "10000.01"),
            ("filament_tower_interface_purge_volume", "1000.01"),
            ("filament_tower_interface_print_temp", "501"),
            ("additional_cooling_fan_speed", "101"),
        ):
            with self.subTest(key=key), self.assertRaises(ValueError):
                build_filament(
                    "Example",
                    {
                        "name": "Unsafe material control",
                        "filament_type": ["PLA"],
                        "nozzle_temperature": ["220"],
                        "hot_plate_temp": ["60"],
                        key: [value],
                    },
                )

    def test_rejects_unsafe_prime_tower_infill_gap(self) -> None:
        for value in ("99.9", "1000.1"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                build_process(
                    "Example",
                    {
                        "name": "Unsafe prime tower",
                        "layer_height": "0.2",
                        "initial_layer_print_height": "0.2",
                        "prime_tower_infill_gap": value,
                    },
                    {},
                )

    def test_preserves_first_per_filament_gcode_template(self) -> None:
        profile = build_filament(
            "Example",
            {
                "name": "Template PLA",
                "filament_type": ["PLA"],
                "nozzle_temperature": ["220"],
                "hot_plate_temp": ["60"],
                "filament_start_gcode": ["M117 SLOT_START", "M117 UNUSED_START"],
                "filament_end_gcode": ["M117 SLOT_END", "M117 UNUSED_END"],
            },
        )

        self.assertEqual("M117 SLOT_START", profile["filamentStartGcode"])
        self.assertEqual("M117 SLOT_END", profile["filamentEndGcode"])

    def test_rejects_oversized_utf8_filament_gcode_template(self) -> None:
        with self.assertRaises(ValueError):
            build_filament(
                "Example",
                {
                    "name": "Unsafe template",
                    "filament_type": ["PLA"],
                    "nozzle_temperature": ["220"],
                    "hot_plate_temp": ["60"],
                    "filament_start_gcode": ["한" * 87_382],
                },
            )

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
                "enforce_support_layers": "7",
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
        self.assertEqual(7, profile["enforceSupportLayers"])
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

    def test_preserves_gradual_initial_layer_speed(self) -> None:
        profile = build_process(
            "Example",
            {
                "name": "Gradual speed",
                "layer_height": "0.2",
                "initial_layer_print_height": "0.2",
                "slow_down_layers": "4",
            },
            {},
        )

        self.assertEqual(4, profile["slowDownLayers"])

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

    def test_preserves_automatic_brim_ear_geometry(self) -> None:
        profile = build_process(
            "Example",
            {
                "name": "Automatic brim ears",
                "layer_height": "0.2",
                "initial_layer_print_height": "0.2",
                "brim_type": "brim_ears",
                "brim_width": "6",
                "brim_ears_max_angle": "137",
                "brim_ears_detection_length": "1.8",
            },
            {},
        )

        self.assertEqual("brim_ears", profile["brimType"])
        self.assertEqual(137, profile["brimEarsMaxAngle"])
        self.assertEqual(1.8, profile["brimEarsDetectionLength"])

        for key, value in (
            ("brim_ears_max_angle", "181"),
            ("brim_ears_detection_length", "-0.1"),
        ):
            with self.assertRaises(ValueError):
                build_process(
                    "Example",
                    {
                        "name": "Unsafe automatic brim ears",
                        "layer_height": "0.2",
                        "initial_layer_print_height": "0.2",
                        key: value,
                    },
                    {},
                )

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
