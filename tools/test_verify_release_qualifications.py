from __future__ import annotations

import unittest
from unittest.mock import patch

from tools.test_analyze_startup_benchmark import result
from tools.verify_release_qualifications import (
    QualificationEvidenceError,
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


if __name__ == "__main__":
    unittest.main()
