from __future__ import annotations

import unittest
import zipfile
from pathlib import Path
from tempfile import TemporaryDirectory

from tools.generate_sbom import (
    apply_license_inventory,
    parse_apk_license_index,
    verify_apk_license_bundle,
)


class GenerateSbomTest(unittest.TestCase):
    def test_applies_exact_license_inventory_and_preserves_native_license(self) -> None:
        components = [
            {"bom-ref": "native:runtime@1", "licenses": [{"expression": "AGPL-3.0-only"}]},
            {"bom-ref": "pkg:cargo/example@1"},
        ]
        apply_license_inventory(
            components,
            {"schemaVersion": 1, "components": {"pkg:cargo/example@1": "MIT"}},
        )
        self.assertEqual([{"expression": "MIT"}], components[1]["licenses"])

    def test_rejects_missing_or_unexpected_license_entries(self) -> None:
        for inventory in (
            {"schemaVersion": 1, "components": {}},
            {"schemaVersion": 1, "components": {"pkg:cargo/example@1": "MIT", "extra": "MIT"}},
        ):
            with self.assertRaisesRegex(ValueError, "mismatch"):
                apply_license_inventory([{"bom-ref": "pkg:cargo/example@1"}], inventory)

    def test_rejects_empty_license_expression(self) -> None:
        with self.assertRaisesRegex(ValueError, "empty"):
            apply_license_inventory(
                [{"bom-ref": "pkg:cargo/example@1"}],
                {"schemaVersion": 1, "components": {"pkg:cargo/example@1": ""}},
            )

    def test_apk_license_index_is_tied_exactly_to_sbom_components(self) -> None:
        components = [
            {"bom-ref": "native:runtime@1", "licenses": [{"expression": "AGPL-3.0-only"}]},
            {"bom-ref": "pkg:cargo/example@1", "licenses": [{"expression": "MIT"}]},
        ]
        bundle = """DuckySlicer third-party licenses
Resolved component index
- native:runtime@1
  License: AGPL-3.0-only
  Documents: a
- pkg:cargo/example@1
  License: MIT
  Documents: b
License and attribution documents
"""
        with TemporaryDirectory() as temporary:
            apk = Path(temporary) / "app.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("assets/legal/THIRD_PARTY_LICENSES.txt", bundle)
            verify_apk_license_bundle(apk, components)

    def test_rejects_changed_apk_license_expression(self) -> None:
        parsed = parse_apk_license_index(
            "Resolved component index\n- pkg:cargo/example@1\n  License: Apache-2.0\n"
            "  Documents: a\nLicense and attribution documents\n"
        )
        self.assertEqual({"pkg:cargo/example@1": "Apache-2.0"}, parsed)
        with TemporaryDirectory() as temporary:
            apk = Path(temporary) / "app.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr(
                    "assets/legal/THIRD_PARTY_LICENSES.txt",
                    "Resolved component index\n- pkg:cargo/example@1\n  License: Apache-2.0\n"
                    "  Documents: a\nLicense and attribution documents\n",
                )
            with self.assertRaisesRegex(ValueError, "changed"):
                verify_apk_license_bundle(
                    apk,
                    [{"bom-ref": "pkg:cargo/example@1", "licenses": [{"expression": "MIT"}]}],
                )


if __name__ == "__main__":
    unittest.main()
