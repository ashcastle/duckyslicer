from __future__ import annotations

import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from tools.qualification_corpus import (
    MANIFEST,
    REQUIRED_CASES,
    CorpusError,
    generated_bytes,
    generated_models,
    load_manifest,
    validate,
)
from tools.run_qualification_corpus import RunnerError, choose_serial, online_devices, validate_report
from tools.run_desktop_orca_qualification import (
    analyze_gcode,
    bed_center,
    compare_case,
    parse_config_block,
)


class QualificationCorpusTest(unittest.TestCase):
    def test_checked_in_corpus_is_deterministic_and_complete(self) -> None:
        manifest = load_manifest()
        validate(manifest)
        models = {entry["path"]: entry for entry in manifest["models"]}
        for path, payload in generated_bytes().items():
            self.assertEqual(hashlib.sha256(payload).hexdigest(), models[path]["sha256"])
            self.assertEqual(len(generated_models()[path]), models[path]["triangles"])
        self.assertEqual(REQUIRED_CASES, {case["id"] for case in manifest["cases"]})

    def test_manifest_rejects_a_different_engine_or_incomplete_dense_gate(self) -> None:
        wrong_engine = copy.deepcopy(load_manifest())
        wrong_engine["engine"]["revision"] = "0" * 40
        with self.assertRaisesRegex(CorpusError, "locked Orca engine"):
            validate(wrong_engine, check_files=False)

        weak_dense = copy.deepcopy(load_manifest())
        next(case for case in weak_dense["cases"] if case["id"] == "dense-preview")["expected"][
            "minPreviewLayerCoverage"
        ] = 0.5
        with self.assertRaisesRegex(CorpusError, "broad layer coverage"):
            validate(weak_dense, check_files=False)

    def test_adb_selection_is_explicit_when_ambiguous(self) -> None:
        output = """List of devices attached
phone device product:test
offline offline product:test
emulator-5554 device product:test
"""
        self.assertEqual(["phone", "emulator-5554"], online_devices(output))
        self.assertEqual("phone", choose_serial("phone", None, ["phone", "emulator-5554"]))
        with self.assertRaisesRegex(RunnerError, "Multiple Android devices"):
            choose_serial(None, None, ["phone", "emulator-5554"])

    def test_report_is_bound_to_manifest_engine_and_digest(self) -> None:
        manifest = load_manifest()
        report = {
            "schemaVersion": 1,
            "engineRevision": manifest["engine"]["revision"],
            "manifestSha256": hashlib.sha256(MANIFEST.read_bytes()).hexdigest(),
            "cases": [{"id": case["id"]} for case in manifest["cases"]],
        }
        self.assertEqual(report, validate_report(json.dumps(report), manifest))
        report["manifestSha256"] = "0" * 64
        with self.assertRaisesRegex(RunnerError, "stale corpus manifest"):
            validate_report(json.dumps(report), manifest)

    def test_desktop_comparison_parses_config_roles_and_material_differences(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            gcode = Path(directory) / "sample.gcode"
            gcode.write_text(
                """; total layer number: 2
;TYPE:Outer wall
G1 X1 E0.1
;TYPE:Inner wall
G1 X3 E0.1
; CONFIG_BLOCK_START
; layer_height = 0.2
; wall_loops = 2
; CONFIG_BLOCK_END
""",
                encoding="utf-8",
            )
            self.assertEqual(
                {"layer_height": "0.2", "wall_loops": "2"},
                parse_config_block(gcode),
            )
            metrics = analyze_gcode(gcode, ["layer_height", "wall_loops"])
            self.assertEqual(2, metrics["layers"])
            self.assertEqual(2, metrics["extrusionMotions"])
            self.assertEqual(2.0, metrics["extrusionXSpanMm"])
            self.assertEqual(1, metrics["roleMotions"]["outerWall"])
            android = dict(metrics)
            self.assertEqual([], compare_case(metrics, android, ["outerWall", "innerWall"]))
            android["layers"] = 3
            android["previewLayerCount"] = 2
            self.assertEqual([], compare_case(metrics, android, ["outerWall"]))
            android["previewLayerCount"] = 3
            self.assertRegex(compare_case(metrics, android, ["outerWall"])[0], "layers")
            role_mismatch = dict(metrics)
            role_mismatch["roleMotions"] = dict(metrics["roleMotions"], outerWall=10)
            self.assertTrue(
                any(
                    "outerWall motions" in difference
                    for difference in compare_case(metrics, role_mismatch, ["outerWall"])
                )
            )

    def test_desktop_comparison_uses_effective_bed_center(self) -> None:
        self.assertEqual((135.0, 135.0), bed_center({"bed_shape": "0x0,270x0,270x270,0x270"}))


if __name__ == "__main__":
    unittest.main()
