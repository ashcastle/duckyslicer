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
from tools.compare_android_qualification import (
    QualificationComparisonError,
    compare,
)
from tools.run_qualification_corpus import (
    RunnerError,
    choose_serial,
    online_devices,
    target_metadata,
    validate_report,
)
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

        weak_structure = copy.deepcopy(load_manifest())
        del next(
            case for case in weak_structure["cases"] if case["id"] == "simple-part"
        )["expected"]["minRoleLayers"]["innerWall"]
        with self.assertRaisesRegex(CorpusError, "role-layer bounds"):
            validate(weak_structure, check_files=False)

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
        report["cases"].append(dict(report["cases"][0]))
        with self.assertRaisesRegex(RunnerError, "every corpus case"):
            validate_report(json.dumps(report), manifest)
        report["cases"].pop()
        report["manifestSha256"] = "0" * 64
        with self.assertRaisesRegex(RunnerError, "stale corpus manifest"):
            validate_report(json.dumps(report), manifest)

    def test_target_metadata_is_typed_and_rejects_unsupported_targets(self) -> None:
        self.assertEqual(
            {"apiLevel": 35, "abi": "arm64-v8a", "pageSizeBytes": 16_384},
            target_metadata("arm64-v8a", "35", "16384"),
        )
        with self.assertRaisesRegex(RunnerError, "ARM64"):
            target_metadata("x86_64", "35", "4096")
        with self.assertRaisesRegex(RunnerError, "page size"):
            target_metadata("arm64-v8a", "35", "6000")

    def test_page_size_comparison_normalizes_only_run_identity(self) -> None:
        manifest = load_manifest()
        manifest_sha = hashlib.sha256(MANIFEST.read_bytes()).hexdigest()

        def report(page_size: int, nonce: str) -> dict[str, object]:
            return {
                "schemaVersion": 1,
                "source": "android",
                "engineRevision": manifest["engine"]["revision"],
                "runtimeVersion": "test runtime",
                "manifestSha256": manifest_sha,
                "effectiveProfile": {"layerHeightMm": 0.2},
                "physicalMeasurementRequested": False,
                "target": {
                    "apiLevel": 35,
                    "abi": "arm64-v8a",
                    "pageSizeBytes": page_size,
                },
                "cases": [
                    {
                        "id": case["id"],
                        "layers": 10,
                        "previewLayerCount": 10,
                        "gcodeBytes": len(nonce),
                        "gcodeSha256": nonce,
                        "sliceElapsedMs": 1.0,
                        "previewParseElapsedMs": 2.0,
                    }
                    for case in manifest["cases"]
                ],
            }

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            baseline_report = root / "4kb.json"
            candidate_report = root / "16kb.json"
            baseline_report.write_text(json.dumps(report(4_096, "baseline")), encoding="utf-8")
            candidate_report.write_text(json.dumps(report(16_384, "candidate")), encoding="utf-8")
            baseline_gcode = root / "4kb"
            candidate_gcode = root / "16kb"
            baseline_gcode.mkdir()
            candidate_gcode.mkdir()
            for case in manifest["cases"]:
                identifier = case["id"]
                (baseline_gcode / f"{identifier}.gcode").write_text(
                    "; generated on 2026-08-12 at 10:00:00\n"
                    "; printing object slicer-input-0-0-123.stl id:0 copy 0\n"
                    "G1 X1 E0.1\n",
                    encoding="utf-8",
                )
                (candidate_gcode / f"{identifier}.gcode").write_text(
                    "; generated on 2026-08-12 at 11:00:00\n"
                    "; printing object slicer-input-0-0-987654.stl id:0 copy 0\n"
                    "G1 X1 E0.1\n\n\n",
                    encoding="utf-8",
                )
            result = compare(
                baseline_report,
                candidate_report,
                baseline_gcode,
                candidate_gcode,
            )
            self.assertEqual(len(manifest["cases"]), len(result["cases"]))
            first_case = manifest["cases"][0]["id"]
            (candidate_gcode / f"{first_case}.gcode").write_text(
                "G1 X2 E0.1\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(QualificationComparisonError, "normalized G-code"):
                compare(
                    baseline_report,
                    candidate_report,
                    baseline_gcode,
                    candidate_gcode,
                )

    def test_desktop_comparison_parses_config_roles_and_material_differences(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            gcode = Path(directory) / "sample.gcode"
            gcode.write_text(
                """; total layer number: 2
M83
;LAYER_CHANGE
;Z:0.2
;TYPE:Outer wall
G1 X1 E0.1
G1 E-0.2
;LAYER_CHANGE
;Z:0.4
;TYPE:Inner wall
G1 X3 E0.1
G2 I1 J0 E0.1
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
            self.assertEqual(3, metrics["extrusionMotions"])
            self.assertEqual(2.0, metrics["extrusionXSpanMm"])
            self.assertEqual(1, metrics["roleMotions"]["outerWall"])
            self.assertEqual(2, metrics["emittedLayers"])
            self.assertEqual(1, metrics["roleLayers"]["outerWall"])
            self.assertEqual(0, metrics["roleFirstLayers"]["outerWall"])
            self.assertEqual(1, metrics["roleLastLayers"]["innerWall"])
            self.assertAlmostEqual(0.2, metrics["roleExtrusionMm"]["innerWall"])
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
