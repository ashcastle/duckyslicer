from __future__ import annotations

import copy
import hashlib
import json
import unittest

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


if __name__ == "__main__":
    unittest.main()
