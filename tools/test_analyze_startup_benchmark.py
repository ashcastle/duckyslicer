from __future__ import annotations

import unittest

from tools.analyze_startup_benchmark import AnalysisError, analyze


def result(*, emulator: bool = False, with_ttid: float = 90.0, with_ttfd: float = 180.0):
    def benchmark(name: str, ttid: float, ttfd: float):
        return {
            "name": name,
            "repeatIterations": 5,
            "metrics": {
                "timeToInitialDisplayMs": {"median": ttid, "runs": [ttid] * 5},
                "timeToFullDisplayMs": {"median": ttfd, "runs": [ttfd] * 5},
            },
        }

    return {
        "context": {
            "build": {
                "model": "sdk_gphone16k_arm64" if emulator else "Production Phone",
                "fingerprint": "generic/emulator" if emulator else "vendor/device/release",
            }
        },
        "benchmarks": [
            benchmark("coldStartupWithoutCompilation", 100.0, 200.0),
            benchmark("coldStartupWithBaselineProfile", with_ttid, with_ttfd),
        ],
    }


class AnalyzeStartupBenchmarkTest(unittest.TestCase):
    def test_accepts_bounded_profile_improvement(self) -> None:
        self.assertEqual(
            {"timeToInitialDisplayMs": 0.9, "timeToFullDisplayMs": 0.9},
            analyze(result()),
        )

    def test_rejects_emulator_as_release_evidence(self) -> None:
        with self.assertRaisesRegex(AnalysisError, "physical device"):
            analyze(result(emulator=True))
        analyze(result(emulator=True), allow_emulator=True)

    def test_rejects_large_regression_or_no_improvement(self) -> None:
        with self.assertRaisesRegex(AnalysisError, "regressed"):
            analyze(result(with_ttid=120.0, with_ttfd=180.0))
        with self.assertRaisesRegex(AnalysisError, "did not improve"):
            analyze(result(with_ttid=100.0, with_ttfd=200.0))


if __name__ == "__main__":
    unittest.main()
