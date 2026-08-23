#!/usr/bin/env python3
"""Collect release startup evidence on one explicitly selected physical Android device."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
from dataclasses import asdict
from datetime import datetime, timezone
from pathlib import Path

try:
    from tools.analyze_startup_benchmark import AnalysisError, analyze
    from tools.run_physical_qualification import (
        ROOT,
        RunnerError,
        best_effort,
        foreground_rejection,
        physical_rejection,
        qualification_source_commit,
        query_identity,
    )
    from tools.run_qualification_corpus import captured, online_devices
except ModuleNotFoundError:  # Direct `python tools/run_startup_qualification.py` execution.
    from analyze_startup_benchmark import AnalysisError, analyze
    from run_physical_qualification import (
        ROOT,
        RunnerError,
        best_effort,
        foreground_rejection,
        physical_rejection,
        qualification_source_commit,
        query_identity,
    )
    from run_qualification_corpus import captured, online_devices


ANDROID = ROOT / "android"
BENCHMARK_OUTPUT = (
    ANDROID
    / "baselineprofile/build/outputs/connected_android_test_additional_output/benchmarkRelease"
)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def benchmark_files() -> dict[Path, int]:
    if not BENCHMARK_OUTPUT.is_dir():
        return {}
    return {
        path: path.stat().st_mtime_ns
        for path in BENCHMARK_OUTPUT.rglob("*benchmarkData.json")
        if path.is_file()
    }


def changed_benchmark(before: dict[Path, int]) -> Path:
    changed = [
        path
        for path, modified in benchmark_files().items()
        if before.get(path) != modified
    ]
    if len(changed) != 1:
        raise RunnerError(
            "Startup qualification expected one newly written benchmark result, "
            f"found {len(changed)}"
        )
    return changed[0]


def qualify(serial: str, output: Path) -> dict[str, object]:
    source_commit = qualification_source_commit()
    identity = query_identity(serial)
    rejection = physical_rejection(identity)
    if rejection:
        raise RunnerError(
            "Startup qualification refuses emulators and non-representative targets: "
            + rejection
        )
    foreground_issue = foreground_rejection(
        best_effort(("adb", "-s", serial, "shell", "dumpsys", "power"), timeout=30),
        best_effort(("adb", "-s", serial, "shell", "dumpsys", "window"), timeout=30),
    )
    if foreground_issue:
        raise RunnerError(
            "Startup qualification requires an awake, unlocked device: " + foreground_issue
        )

    before = benchmark_files()
    environment = os.environ.copy()
    environment["ANDROID_SERIAL"] = serial
    command = (
        str(ANDROID / "gradlew"),
        "--dependency-verification=strict",
        ":baselineprofile:connectedBenchmarkReleaseAndroidTest",
    )
    completed = subprocess.run(command, cwd=ANDROID, env=environment, check=False)
    if completed.returncode != 0:
        raise RunnerError(f"Startup Macrobenchmark failed with exit code {completed.returncode}")

    benchmark_path = changed_benchmark(before)
    try:
        benchmark = json.loads(benchmark_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise RunnerError(f"Could not read startup benchmark result: {error}") from error
    try:
        ratios = analyze(benchmark)
    except AnalysisError as error:
        raise RunnerError(str(error)) from error
    qualification_source_commit(source_commit)
    evidence: dict[str, object] = {
        "schemaVersion": 1,
        "sourceCommit": source_commit,
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "device": asdict(identity),
        "benchmarkSha256": digest(benchmark_path),
        "profileRatios": ratios,
        "benchmark": benchmark,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"[startup] physical startup qualification passed; report: {output}")
    return evidence


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument(
        "--output",
        type=Path,
        default=ROOT / "build/qualification/startup-report.json",
    )
    args = parser.parse_args()
    try:
        devices = online_devices(captured(("adb", "devices", "-l"), timeout=20))
        if args.serial not in devices:
            raise RunnerError(f"Requested physical Android device is not online: {args.serial}")
        qualify(args.serial, args.output)
    except RunnerError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
