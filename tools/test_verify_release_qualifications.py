from __future__ import annotations

import hashlib
import unittest
from unittest.mock import patch

from tools.qualification_corpus import MANIFEST, REQUIRED_CASES, load_manifest
from tools.test_analyze_startup_benchmark import result
from tools.run_desktop_orca_qualification import compatibility_sha256
from tools.verify_release_qualifications import (
    QualificationEvidenceError,
    validate_orca_conformance,
    validate_physical,
    validate_startup,
)


COMMIT = "a" * 40


def physical_device(*, serial: str = "R3CN123456") -> dict[str, object]:
    return {
        "serial": serial,
        "manufacturer": "Vendor",
        "model": "Production Phone",
        "api": 36,
        "abi": "arm64-v8a",
        "page_size_bytes": 16384,
        "hardware": "real-hardware",
        "kernel_qemu": "0",
        "boot_qemu": "0",
        "build_fingerprint": "vendor/device/release",
        "memory_total_kb": 8_000_000,
    }


def orca_report() -> dict[str, object]:
    manifest = load_manifest()
    return {
        "schemaVersion": 1,
        "source": "desktop-orca",
        "sourceCommit": COMMIT,
        "engineRevision": manifest["engine"]["revision"],
        "manifestSha256": hashlib.sha256(MANIFEST.read_bytes()).hexdigest(),
        "androidReportSha256": "9" * 64,
        "desktopBuildMode": "pinned-source-rebuilt",
        "desktopBuildCacheHit": False,
        "desktopBinarySha256": "d" * 64,
        "desktopCompatibilitySha256": compatibility_sha256(),
        "passed": True,
        "failures": [],
        "cases": [
            {
                "id": identifier,
                "layers": 100,
                "emittedLayers": 100,
                "extrusionMotions": 10_000,
                "profileFingerprint": "c" * 64,
                "differences": [],
            }
            for identifier in sorted(REQUIRED_CASES)
        ],
    }


class VerifyReleaseQualificationsTest(unittest.TestCase):
    def test_accepts_current_full_physical_report_and_rechecks_budgets(self) -> None:
        document = {
            "schemaVersion": 1,
            "source": "physical-android",
            "sourceCommit": COMMIT,
            "device": physical_device(),
            "cases": [{"id": "dense-preview", "host": {}}],
        }
        with (
            patch("tools.verify_release_qualifications.load_manifest", return_value={}),
            patch("tools.verify_release_qualifications.validate"),
            patch(
                "tools.verify_release_qualifications.validate_report",
                return_value=document,
            ),
            patch("tools.verify_release_qualifications.validate_resource_budget") as budget,
            patch("tools.verify_release_qualifications.validate_dense_render") as render,
        ):
            validate_physical(document, COMMIT)
        budget.assert_called_once()
        render.assert_called_once_with(document["cases"][0], required_soak_cycles=3)

    def test_rejects_stale_or_emulated_physical_report(self) -> None:
        stale = {
            "source": "physical-android",
            "sourceCommit": "b" * 40,
            "device": physical_device(),
        }
        with self.assertRaisesRegex(QualificationEvidenceError, "different source commit"):
            validate_physical(stale, COMMIT)
        stale["sourceCommit"] = COMMIT
        stale["device"] = physical_device(serial="emulator-5554")
        with self.assertRaisesRegex(QualificationEvidenceError, "physical device"):
            validate_physical(stale, COMMIT)

    def test_accepts_representative_startup_benchmark_and_exact_ratios(self) -> None:
        benchmark = result()
        document = {
            "schemaVersion": 1,
            "sourceCommit": COMMIT,
            "device": physical_device(),
            "benchmarkSha256": "b" * 64,
            "profileRatios": {
                "timeToInitialDisplayMs": 0.9,
                "timeToFullDisplayMs": 0.9,
            },
            "benchmark": benchmark,
        }
        validate_startup(document, COMMIT)
        document["profileRatios"] = {
            "timeToInitialDisplayMs": 0.8,
            "timeToFullDisplayMs": 0.9,
        }
        with self.assertRaisesRegex(QualificationEvidenceError, "do not match"):
            validate_startup(document, COMMIT)

    def test_accepts_only_complete_clean_orca_conformance_for_the_commit(self) -> None:
        document = orca_report()
        validate_orca_conformance(document, COMMIT, "9" * 64)

        document["cases"][0]["differences"] = ["outer wall differs"]
        with self.assertRaisesRegex(QualificationEvidenceError, "differs"):
            validate_orca_conformance(document, COMMIT, "9" * 64)

        document = orca_report()
        document["cases"].pop()
        with self.assertRaisesRegex(QualificationEvidenceError, "every corpus case"):
            validate_orca_conformance(document, COMMIT, "9" * 64)

        document = orca_report()
        document["sourceCommit"] = "b" * 40
        with self.assertRaisesRegex(QualificationEvidenceError, "different source commit"):
            validate_orca_conformance(document, COMMIT, "9" * 64)

        document = orca_report()
        document["desktopCompatibilitySha256"] = "f" * 64
        with self.assertRaisesRegex(QualificationEvidenceError, "stale compatibility"):
            validate_orca_conformance(document, COMMIT, "9" * 64)

        document = orca_report()
        with self.assertRaisesRegex(QualificationEvidenceError, "different physical report"):
            validate_orca_conformance(document, COMMIT, "8" * 64)


if __name__ == "__main__":
    unittest.main()
