from __future__ import annotations

import unittest

from tools.generate_license_inventory import InventoryError, build_inventory


class GenerateLicenseInventoryTest(unittest.TestCase):
    def test_generates_reviewed_gradle_and_normalized_cargo_licenses(self) -> None:
        inventory = build_inventory(
            "androidx.core:core:1.0\norg.jspecify:jspecify:1.0\n",
            {
                "packages": [
                    {"name": "duckyslicer-jni", "version": "0.1.0", "license": "AGPL-3.0"},
                    {"name": "jni", "version": "1.0.0", "license": "MIT/Apache-2.0"},
                    {"name": "memchr", "version": "2.0.0", "license": "Unlicense OR MIT"},
                ]
            },
        )

        self.assertEqual(1, inventory["schemaVersion"])
        self.assertEqual(
            "MIT OR Apache-2.0",
            inventory["components"]["pkg:cargo/jni@1.0.0"],
        )
        self.assertEqual(
            "Apache-2.0",
            inventory["components"]["pkg:maven/androidx.core/core@1.0"],
        )

    def test_rejects_unknown_gradle_group(self) -> None:
        with self.assertRaisesRegex(InventoryError, "no unambiguous license policy"):
            build_inventory("example:unknown:1.0\n", {"packages": []})

    def test_rejects_missing_or_unreviewed_cargo_license(self) -> None:
        for license_text in (None, "BUSL-1.1"):
            package = {"name": "crate", "version": "1.0", "license": license_text}
            with self.assertRaises(InventoryError):
                build_inventory("", {"packages": [package]})

    def test_rejects_duplicate_gradle_component(self) -> None:
        with self.assertRaisesRegex(InventoryError, "duplicate"):
            build_inventory(
                "androidx.core:core:1.0\nandroidx.core:core:1.0\n",
                {"packages": []},
            )


if __name__ == "__main__":
    unittest.main()
