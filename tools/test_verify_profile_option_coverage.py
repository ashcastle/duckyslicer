from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from tools.verify_profile_option_coverage import CoverageError, verify_coverage


class VerifyProfileOptionCoverageTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.profiles = self.root / "profiles"
        self.profiles.mkdir()
        self.engine = self.root / "PrintConfig.cpp"
        self.generator = self.root / "generator.py"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_profile(self, **options: object) -> None:
        (self.profiles / "profile.json").write_text(
            json.dumps(options),
            encoding="utf-8",
        )

    def test_accepts_mapped_and_explicitly_reviewed_options(self) -> None:
        self.write_profile(layer_height="0.2", notes="mobile metadata")
        self.engine.write_text(
            'def = this->add("layer_height", coFloat);\n'
            'def = this->add("notes", coString);\n',
            encoding="utf-8",
        )
        self.generator.write_text('raw.get("layer_height")\n', encoding="utf-8")

        report = verify_coverage(
            self.profiles,
            self.engine,
            self.generator,
            intentionally_unmapped={"notes"},
            default_only=set(),
        )

        self.assertEqual(frozenset({"layer_height"}), report.mapped_options)
        self.assertEqual(frozenset({"notes"}), report.intentionally_unmapped)

    def test_rejects_a_new_engine_backed_profile_option(self) -> None:
        self.write_profile(future_quality_option="1")
        self.engine.write_text(
            'def = this->add("future_quality_option", coBool);\n',
            encoding="utf-8",
        )
        self.generator.write_text('raw.get("layer_height")\n', encoding="utf-8")

        with self.assertRaisesRegex(CoverageError, "future_quality_option"):
            verify_coverage(
                self.profiles,
                self.engine,
                self.generator,
                intentionally_unmapped=set(),
                default_only=set(),
            )

    def test_rejects_material_value_for_neutral_only_option(self) -> None:
        self.write_profile(z_offset="0.15")
        self.engine.write_text(
            'def = this->add("z_offset", coFloat);\n',
            encoding="utf-8",
        )
        self.generator.write_text('raw.get("layer_height")\n', encoding="utf-8")

        with self.assertRaisesRegex(CoverageError, "z_offset"):
            verify_coverage(
                self.profiles,
                self.engine,
                self.generator,
                intentionally_unmapped=set(),
                default_only={"z_offset"},
            )

    def test_ignores_commented_engine_definitions(self) -> None:
        self.write_profile(retired_option="1")
        self.engine.write_text(
            '// def = this->add("retired_option", coBool);\n'
            '/* new_def("also_retired", coBool, "", ""); */\n',
            encoding="utf-8",
        )
        self.generator.write_text('raw.get("layer_height")\n', encoding="utf-8")

        report = verify_coverage(
            self.profiles,
            self.engine,
            self.generator,
            intentionally_unmapped=set(),
            default_only=set(),
        )

        self.assertFalse(report.engine_options)


if __name__ == "__main__":
    unittest.main()
