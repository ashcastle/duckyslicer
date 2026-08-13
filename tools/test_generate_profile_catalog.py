from __future__ import annotations

import math
import unittest

from tools.generate_profile_catalog import build_process, printable_geometry


class GenerateProfileCatalogTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
