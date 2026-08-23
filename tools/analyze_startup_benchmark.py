#!/usr/bin/env python3
"""Validate DuckySlicer startup Macrobenchmark evidence."""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path


WITHOUT_PROFILE = "coldStartupWithoutCompilation"
WITH_PROFILE = "coldStartupWithBaselineProfile"
REQUIRED_METRICS = ("timeToInitialDisplayMs", "timeToFullDisplayMs")


class AnalysisError(ValueError):
    """The benchmark result cannot qualify a startup profile."""


def is_emulator(context: dict[str, object]) -> bool:
    build = context.get("build")
    if not isinstance(build, dict):
        return False
    identity = " ".join(
        str(build.get(key, "")).lower()
        for key in ("brand", "device", "fingerprint", "model", "type")
    )
    return any(marker in identity for marker in ("emulator", "emu64", "sdk_gphone", "generic"))


def benchmark_medians(
    benchmark: dict[str, object],
    *,
    minimum_runs: int = 4,
) -> dict[str, float]:
    repeat_iterations = benchmark.get("repeatIterations")
    if not isinstance(repeat_iterations, int) or repeat_iterations < 5:
        raise AnalysisError("startup benchmark must declare at least five repeat iterations")
    metrics = benchmark.get("metrics")
    if not isinstance(metrics, dict):
        raise AnalysisError("startup benchmark has no metrics")
    medians: dict[str, float] = {}
    for name in REQUIRED_METRICS:
        measurement = metrics.get(name)
        if not isinstance(measurement, dict):
            raise AnalysisError(f"startup benchmark is missing {name}")
        median = measurement.get("median")
        runs = measurement.get("runs")
        if (
            not isinstance(median, (int, float))
            or isinstance(median, bool)
            or not math.isfinite(median)
            or median <= 0
            or not isinstance(runs, list)
            or len(runs) < minimum_runs
            or any(
                not isinstance(value, (int, float))
                or isinstance(value, bool)
                or not math.isfinite(value)
                or value <= 0
                for value in runs
            )
        ):
            raise AnalysisError(f"startup benchmark returned invalid {name} samples")
        medians[name] = float(median)
    return medians


def analyze(
    document: dict[str, object],
    *,
    allow_emulator: bool = False,
    maximum_regression: float = 0.15,
) -> dict[str, float]:
    context = document.get("context")
    if not isinstance(context, dict):
        raise AnalysisError("benchmark context is missing")
    if is_emulator(context) and not allow_emulator:
        raise AnalysisError("release startup qualification requires a physical device")
    benchmarks = document.get("benchmarks")
    if not isinstance(benchmarks, list):
        raise AnalysisError("benchmark result list is missing")
    by_name = {
        benchmark.get("name"): benchmark
        for benchmark in benchmarks
        if isinstance(benchmark, dict) and isinstance(benchmark.get("name"), str)
    }
    if WITHOUT_PROFILE not in by_name or WITH_PROFILE not in by_name:
        raise AnalysisError("both compiled and uncompiled startup benchmarks are required")
    without = benchmark_medians(by_name[WITHOUT_PROFILE])
    with_profile = benchmark_medians(by_name[WITH_PROFILE])
    ratios = {
        name: with_profile[name] / without[name]
        for name in REQUIRED_METRICS
    }
    if any(ratio > 1.0 + maximum_regression for ratio in ratios.values()):
        raise AnalysisError(
            "baseline profile regressed startup beyond the allowed ratio: "
            + ", ".join(f"{name}={ratio:.3f}" for name, ratio in ratios.items())
        )
    if not any(ratio < 1.0 for ratio in ratios.values()):
        raise AnalysisError("baseline profile did not improve either startup timing metric")
    return ratios


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("result", type=Path)
    parser.add_argument("--allow-emulator", action="store_true")
    parser.add_argument("--maximum-regression", type=float, default=0.15)
    args = parser.parse_args()
    try:
        document = json.loads(args.result.read_text(encoding="utf-8"))
        ratios = analyze(
            document,
            allow_emulator=args.allow_emulator,
            maximum_regression=args.maximum_regression,
        )
    except (OSError, json.JSONDecodeError, AnalysisError) as error:
        raise SystemExit(f"Startup benchmark analysis failed: {error}") from error
    print(
        "Qualified startup benchmark: "
        + ", ".join(
            f"{name}={(1.0 - ratio) * 100.0:+.1f}% improvement"
            for name, ratio in ratios.items()
        )
    )


if __name__ == "__main__":
    main()
