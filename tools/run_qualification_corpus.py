#!/usr/bin/env python3
"""Build and run the pinned Orca qualification corpus on one Android device."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shlex
import subprocess
import sys
from collections.abc import Sequence
from pathlib import Path

try:
    from tools.qualification_corpus import CORPUS_ROOT, MANIFEST, CorpusError, load_manifest, validate
except ModuleNotFoundError:  # Direct `python tools/run_qualification_corpus.py` execution.
    from qualification_corpus import CORPUS_ROOT, MANIFEST, CorpusError, load_manifest, validate


ROOT = Path(__file__).resolve().parent.parent
ANDROID = ROOT / "android"
DEBUG_APK = ANDROID / "app/build/outputs/apk/debug/app-debug.apk"
TEST_APK = ANDROID / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
APPLICATION_ID = "com.ashcastle.duckyslicer"
TEST_APPLICATION_ID = f"{APPLICATION_ID}.test"
TEST_CLASS = f"{APPLICATION_ID}.OrcaQualificationCorpusInstrumentedTest"
RUNNER = "androidx.test.runner.AndroidJUnitRunner"
REPORT_PATH = "files/qualification/corpus-report.json"


class RunnerError(RuntimeError):
    """The local qualification runner could not produce a trustworthy report."""


def captured(command: Sequence[str], *, cwd: Path = ROOT, timeout: int = 1_800) -> str:
    try:
        result = subprocess.run(
            list(command),
            cwd=cwd,
            check=False,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise RunnerError(f"Could not run {shlex.join(command)}: {error}") from error
    output = (result.stdout + result.stderr).strip()
    if result.returncode != 0:
        raise RunnerError(f"Command failed ({result.returncode}): {shlex.join(command)}\n{output}")
    return output


def captured_stdout_bytes(
    command: Sequence[str],
    *,
    cwd: Path = ROOT,
    timeout: int = 1_800,
) -> bytes:
    try:
        result = subprocess.run(
            list(command),
            cwd=cwd,
            check=False,
            capture_output=True,
            timeout=timeout,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise RunnerError(f"Could not run {shlex.join(command)}: {error}") from error
    if result.returncode != 0:
        output = (result.stdout + result.stderr).decode("utf-8", errors="replace").strip()
        raise RunnerError(f"Command failed ({result.returncode}): {shlex.join(command)}\n{output}")
    return result.stdout


def online_devices(output: str) -> list[str]:
    return [
        columns[0]
        for line in output.splitlines()
        if len(columns := line.split()) >= 2 and columns[1] == "device"
    ]


def choose_serial(requested: str | None, environment_serial: str | None, devices: Sequence[str]) -> str:
    selected = requested or environment_serial
    if selected:
        if selected not in devices:
            raise RunnerError(f"Requested Android device is not online: {selected}")
        return selected
    if len(devices) == 1:
        return devices[0]
    if not devices:
        raise RunnerError("No online Android device is available")
    raise RunnerError("Multiple Android devices are online; pass --serial: " + ", ".join(sorted(devices)))


def adb(serial: str, *arguments: str) -> tuple[str, ...]:
    return ("adb", "-s", serial, *arguments)


def target_metadata(abi: str, api: str, page_size: str) -> dict[str, object]:
    if abi != "arm64-v8a":
        raise RunnerError(f"Qualification requires an ARM64 Android target; target reports {abi}")
    try:
        api_level = int(api)
        page_size_bytes = int(page_size)
    except ValueError as error:
        raise RunnerError("Android target returned invalid API or page-size metadata") from error
    if api_level < 26:
        raise RunnerError(f"Qualification requires Android API 26 or newer; target reports {api_level}")
    if page_size_bytes < 4_096 or page_size_bytes & (page_size_bytes - 1):
        raise RunnerError(f"Android target returned an invalid page size: {page_size_bytes}")
    return {
        "apiLevel": api_level,
        "abi": abi,
        "pageSizeBytes": page_size_bytes,
    }


def validate_report(
    payload: str,
    manifest: dict[str, object],
    expected_ids: set[str] | None = None,
) -> dict[str, object]:
    try:
        report = json.loads(payload)
    except json.JSONDecodeError as error:
        raise RunnerError(f"Device returned an invalid qualification report: {error}") from error
    expected_manifest_sha = hashlib.sha256(MANIFEST.read_bytes()).hexdigest()
    engine = manifest["engine"]
    cases = manifest["cases"]
    if not isinstance(report, dict) or report.get("schemaVersion") != 1:
        raise RunnerError("Device qualification report schema is invalid")
    if not isinstance(engine, dict) or report.get("engineRevision") != engine.get("revision"):
        raise RunnerError("Device qualification report used a different Orca engine revision")
    if report.get("manifestSha256") != expected_manifest_sha:
        raise RunnerError("Device qualification report used a stale corpus manifest")
    required_ids = expected_ids or {case["id"] for case in cases if isinstance(case, dict)}
    actual_cases = report.get("cases")
    actual_case_entries = (
        [case for case in actual_cases if isinstance(case, dict)]
        if isinstance(actual_cases, list)
        else []
    )
    actual_ids = {case.get("id") for case in actual_case_entries}
    if actual_ids != required_ids or len(actual_case_entries) != len(actual_ids):
        raise RunnerError("Device qualification report does not contain every corpus case")
    return report


def build() -> None:
    gradlew = "gradlew.bat" if os.name == "nt" else "./gradlew"
    captured(
        (
            gradlew,
            "--dependency-verification=strict",
            ":app:assembleDebug",
            ":app:assembleDebugAndroidTest",
        ),
        cwd=ANDROID,
    )
    for artifact in (DEBUG_APK, TEST_APK):
        if not artifact.is_file():
            raise RunnerError(f"Expected Android artifact is missing: {artifact}")


def run(
    serial: str,
    output: Path,
    *,
    skip_build: bool = False,
    case_ids: set[str] | None = None,
    retain_gcode: Path | None = None,
) -> dict[str, object]:
    manifest = load_manifest()
    validate(manifest)
    if not skip_build:
        print("[qualification] building debug application and test APK")
        build()
    for artifact in (DEBUG_APK, TEST_APK):
        if not artifact.is_file():
            raise RunnerError(f"Expected Android artifact is missing: {artifact}")

    abi = captured(adb(serial, "shell", "getprop", "ro.product.cpu.abi"), timeout=20)
    api = captured(adb(serial, "shell", "getprop", "ro.build.version.sdk"), timeout=20)
    page_size = captured(adb(serial, "shell", "getconf", "PAGE_SIZE"), timeout=20)
    target = target_metadata(abi, api, page_size)
    print(
        f"[qualification] target {serial}: API {target['apiLevel']}, {target['abi']}, "
        f"{target['pageSizeBytes']}-byte pages"
    )
    captured(adb(serial, "install", "-r", "-t", str(DEBUG_APK)), timeout=180)
    captured(adb(serial, "install", "-r", "-t", str(TEST_APK)), timeout=180)
    selected_cases = [
        case for case in manifest["cases"]
        if case_ids is None or case["id"] in case_ids
    ]
    selected_ids = {case["id"] for case in selected_cases}
    if case_ids is not None and selected_ids != case_ids:
        raise RunnerError("Unknown qualification case: " + ", ".join(sorted(case_ids - selected_ids)))
    case_reports: list[dict[str, object]] = []
    if retain_gcode is not None:
        retain_gcode.mkdir(parents=True, exist_ok=True)
    for case in selected_cases:
        identifier = case["id"]
        print(f"[qualification] running {identifier}")
        arguments = [
            "shell",
            "am",
            "instrument",
            "-w",
            "-r",
            "-e",
            "class",
            TEST_CLASS,
            "-e",
            "corpusCase",
            identifier,
        ]
        if retain_gcode is not None:
            arguments.extend(("-e", "retainCorpusGcode", "true"))
        arguments.append(f"{TEST_APPLICATION_ID}/{RUNNER}")
        instrumented = captured(adb(serial, *arguments))
        if "FAILURES!!!" in instrumented or "INSTRUMENTATION_FAILED" in instrumented or "OK (" not in instrumented:
            raise RunnerError(f"Qualification case {identifier} did not pass:\n" + instrumented)
        payload = captured(
            adb(serial, "exec-out", "run-as", APPLICATION_ID, "cat", REPORT_PATH),
            timeout=30,
        )
        case_reports.append(validate_report(payload, manifest, {identifier}))
        if retain_gcode is not None:
            gcode = captured_stdout_bytes(
                adb(
                    serial,
                    "exec-out",
                    "run-as",
                    APPLICATION_ID,
                    "cat",
                    f"files/qualification/gcode/{identifier}.gcode",
                ),
                timeout=120,
            )
            destination = retain_gcode / f"{identifier}.gcode"
            destination.write_bytes(gcode)
    report = dict(case_reports[0])
    report["cases"] = [case for partial in case_reports for case in partial["cases"]]
    report["target"] = target
    report = validate_report(json.dumps(report), manifest, selected_ids)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"[qualification] passed {len(report['cases'])} cases; report: {output}")
    return report


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="ADB serial; required when more than one device is online")
    parser.add_argument("--case", action="append", dest="cases", help="run one named case; repeatable")
    parser.add_argument("--skip-build", action="store_true", help="reuse existing debug APKs")
    parser.add_argument(
        "--retain-gcode",
        type=Path,
        metavar="DIR",
        help="copy each selected case's G-code into a local directory for desktop comparison",
    )
    parser.add_argument("--validate-only", action="store_true", help="validate checked-in corpus only")
    parser.add_argument(
        "--output",
        type=Path,
        default=ROOT / "build/qualification/android-report.json",
        help="local JSON report path",
    )
    args = parser.parse_args(argv)
    try:
        manifest = load_manifest()
        validate(manifest)
        if args.validate_only:
            print(f"Qualification corpus is valid: {CORPUS_ROOT}")
            return 0
        devices = online_devices(captured(("adb", "devices", "-l"), timeout=20))
        serial = choose_serial(args.serial, os.environ.get("ANDROID_SERIAL"), devices)
        run(
            serial,
            args.output,
            skip_build=args.skip_build,
            case_ids=set(args.cases) if args.cases else None,
            retain_gcode=args.retain_gcode,
        )
    except (CorpusError, RunnerError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
