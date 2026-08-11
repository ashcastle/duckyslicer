#!/usr/bin/env python3
"""Run the pinned Orca corpus on one explicitly selected physical ARM64 device."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from collections.abc import Sequence
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path

try:
    from tools.qualification_corpus import CorpusError, load_manifest, validate
    from tools.run_qualification_corpus import (
        APPLICATION_ID,
        DEBUG_APK,
        REPORT_PATH,
        ROOT,
        RUNNER,
        TEST_APPLICATION_ID,
        TEST_APK,
        TEST_CLASS,
        RunnerError,
        adb,
        build,
        captured,
        online_devices,
        validate_report,
    )
except ModuleNotFoundError:  # Direct `python tools/run_physical_qualification.py` execution.
    from qualification_corpus import CorpusError, load_manifest, validate
    from run_qualification_corpus import (
        APPLICATION_ID,
        DEBUG_APK,
        REPORT_PATH,
        ROOT,
        RUNNER,
        TEST_APPLICATION_ID,
        TEST_APK,
        TEST_CLASS,
        RunnerError,
        adb,
        build,
        captured,
        online_devices,
        validate_report,
    )


@dataclass(frozen=True)
class DeviceIdentity:
    serial: str
    manufacturer: str
    model: str
    api: int
    abi: str
    page_size_bytes: int
    hardware: str
    kernel_qemu: str
    boot_qemu: str
    build_fingerprint: str
    memory_total_kb: int | None


@dataclass(frozen=True)
class ThermalReading:
    value: float
    sensor_type: int
    name: str
    status: int


def best_effort(command: Sequence[str], *, timeout: int = 20) -> str:
    try:
        result = subprocess.run(
            list(command),
            cwd=ROOT,
            check=False,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except (OSError, subprocess.TimeoutExpired):
        return ""
    return result.stdout if result.returncode == 0 else ""


def parse_memory_total_kb(output: str) -> int | None:
    match = re.search(r"(?m)^MemTotal:\s*([\d,]+)\s+kB\s*$", output)
    return int(match.group(1).replace(",", "")) if match else None


def parse_total_pss_kb(output: str) -> int | None:
    summary = re.search(r"\bTOTAL PSS:\s*([\d,]+)\b", output)
    if summary:
        return int(summary.group(1).replace(",", ""))
    table = re.search(r"(?m)^\s*TOTAL\s+([\d,]+)(?:\s+[\d,]+){2,}", output)
    return int(table.group(1).replace(",", "")) if table else None


def parse_thermal_status(output: str) -> int | None:
    for pattern in (
        r"(?m)^Thermal Status:\s*(\d+)\s*$",
        r"(?m)^\s*mStatus:\s*(\d+)\s*$",
        r"(?m)^\s*Status:\s*(\d+)\s*$",
    ):
        match = re.search(pattern, output)
        if match:
            return int(match.group(1))
    return None


def parse_thermal_readings(output: str) -> list[ThermalReading]:
    readings: list[ThermalReading] = []
    pattern = re.compile(
        r"Temperature\{mValue=([-+\d.]+), mType=(\d+), mName=([^,}]+), mStatus=(\d+)\}",
    )
    for match in pattern.finditer(output):
        readings.append(
            ThermalReading(
                value=float(match.group(1)),
                sensor_type=int(match.group(2)),
                name=match.group(3).strip(),
                status=int(match.group(4)),
            ),
        )
    return readings


def crash_anr_evidence(output: str, package: str = APPLICATION_ID) -> list[str]:
    lines = output.splitlines()
    evidence: list[str] = []
    markers = ("FATAL EXCEPTION", "Fatal signal", "ANR in", "am_anr")
    for index, line in enumerate(lines):
        if not any(marker in line for marker in markers):
            continue
        window = "\n".join(lines[max(0, index - 2) : min(len(lines), index + 7)])
        if package not in window:
            continue
        normalized = line.strip()
        if normalized and normalized not in evidence:
            evidence.append(normalized)
    return evidence


def parse_exit_entries(output: str) -> set[tuple[str, str, int, str]]:
    pattern = re.compile(
        r"ApplicationExitInfo #\d+:\s+"
        r"timestamp=([^\n]+?)\s+pid=.*?\n\s+"
        r"process=([^\s]+)\s+reason=(\d+)\s+\(([^)]+)\)",
    )
    return {
        (match.group(1).strip(), match.group(2), int(match.group(3)), match.group(4))
        for match in pattern.finditer(output)
    }


def exit_failure_evidence(before: str, after: str) -> list[str]:
    previous = parse_exit_entries(before)
    failures = [entry for entry in parse_exit_entries(after) - previous if entry[2] in {4, 5, 6}]
    return [
        f"{timestamp} process={process} reason={reason} ({label})"
        for timestamp, process, reason, label in sorted(failures)
    ]


def physical_rejection(identity: DeviceIdentity) -> str | None:
    emulator_hardware = ("ranchu", "goldfish", "cuttlefish", "cutf_cvm")
    hardware = identity.hardware.lower()
    if identity.serial.startswith("emulator-"):
        return f"{identity.serial} is an emulator serial"
    if identity.kernel_qemu == "1" or identity.boot_qemu == "1":
        return f"{identity.serial} reports a QEMU runtime"
    if any(marker in hardware for marker in emulator_hardware):
        return f"{identity.serial} reports emulator hardware {identity.hardware!r}"
    if identity.abi != "arm64-v8a":
        return f"{identity.serial} is not ARM64 (reported {identity.abi!r})"
    if identity.api < 30:
        return f"{identity.serial} is API {identity.api}; physical qualification requires API 30+ exit diagnostics"
    return None


def validate_dense_render(case_payload: dict[str, object]) -> None:
    render = case_payload.get("previewRender")
    if not isinstance(render, dict):
        raise RunnerError("Dense Preview did not return the required GPU frame measurement")
    measurement_shape = (
        render.get("framebufferWidth"),
        render.get("framebufferHeight"),
        render.get("frameCountPerPhase"),
    )
    if measurement_shape != (720, 1280, 30):
        raise RunnerError(
            "Dense Preview used a reduced measurement shape instead of 720x1280 with 30 frames per phase",
        )


def query_identity(serial: str) -> DeviceIdentity:
    def prop(name: str) -> str:
        return captured(adb(serial, "shell", "getprop", name), timeout=20).strip()

    api = prop("ro.build.version.sdk")
    page_size = captured(adb(serial, "shell", "getconf", "PAGE_SIZE"), timeout=20).strip()
    try:
        api_number = int(api)
        page_size_number = int(page_size)
    except ValueError as error:
        raise RunnerError(
            f"Physical target {serial} returned invalid API/page-size values: {api!r}, {page_size!r}",
        ) from error
    memory = best_effort(adb(serial, "shell", "cat", "/proc/meminfo"))
    return DeviceIdentity(
        serial=serial,
        manufacturer=prop("ro.product.manufacturer"),
        model=prop("ro.product.model"),
        api=api_number,
        abi=prop("ro.product.cpu.abi"),
        page_size_bytes=page_size_number,
        hardware=prop("ro.hardware"),
        kernel_qemu=prop("ro.kernel.qemu"),
        boot_qemu=prop("ro.boot.qemu"),
        build_fingerprint=prop("ro.build.fingerprint"),
        memory_total_kb=parse_memory_total_kb(memory),
    )


def thermal_snapshot(serial: str) -> dict[str, object]:
    output = best_effort(adb(serial, "shell", "dumpsys", "thermalservice"), timeout=30)
    return {
        "status": parse_thermal_status(output),
        "readings": [asdict(reading) for reading in parse_thermal_readings(output)],
    }


def sample_pss_kb(serial: str) -> int | None:
    output = best_effort(
        adb(serial, "shell", "dumpsys", "meminfo", APPLICATION_ID),
        timeout=15,
    )
    return parse_total_pss_kb(output)


def run_instrumented_case(serial: str, identifier: str, *, timeout_seconds: int = 1_800) -> dict[str, object]:
    best_effort(adb(serial, "logcat", "-c"))
    exit_history_before = best_effort(
        adb(serial, "shell", "dumpsys", "activity", "exit-info", APPLICATION_ID),
        timeout=30,
    )
    thermal_before = thermal_snapshot(serial)
    command = adb(
        serial,
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
        "-e",
        "measurePhysical",
        "true",
        f"{TEST_APPLICATION_ID}/{RUNNER}",
    )
    started = time.monotonic()
    try:
        process = subprocess.Popen(
            list(command),
            cwd=ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
    except OSError as error:
        raise RunnerError(f"Could not start physical qualification case {identifier}: {error}") from error
    pss_samples: list[int] = []
    timed_out = False
    while process.poll() is None:
        sample = sample_pss_kb(serial)
        if sample is not None:
            pss_samples.append(sample)
        if time.monotonic() - started > timeout_seconds:
            timed_out = True
            process.kill()
            best_effort(adb(serial, "shell", "am", "force-stop", APPLICATION_ID))
            break
        time.sleep(0.5)
    stdout, stderr = process.communicate()
    elapsed_ms = (time.monotonic() - started) * 1_000.0
    logcat = best_effort(adb(serial, "logcat", "-d", "-v", "threadtime"), timeout=60)
    failures = crash_anr_evidence(logcat)
    exit_history_after = best_effort(
        adb(serial, "shell", "dumpsys", "activity", "exit-info", APPLICATION_ID),
        timeout=30,
    )
    exit_failures = exit_failure_evidence(exit_history_before, exit_history_after)
    thermal_after = thermal_snapshot(serial)
    if timed_out:
        raise RunnerError(f"Physical qualification case {identifier} exceeded {timeout_seconds} seconds")
    instrumented = (stdout + stderr).strip()
    if process.returncode != 0 or "FAILURES!!!" in instrumented or "INSTRUMENTATION_FAILED" in instrumented:
        raise RunnerError(f"Physical qualification case {identifier} failed:\n{instrumented}")
    if "OK (" not in instrumented:
        raise RunnerError(f"Physical qualification case {identifier} returned no passing result:\n{instrumented}")
    if failures or exit_failures:
        raise RunnerError(
            f"Physical qualification case {identifier} emitted crash/ANR evidence:\n"
            + "\n".join(failures + exit_failures),
        )
    if not pss_samples:
        raise RunnerError(f"Physical qualification case {identifier} produced no process-memory samples")
    if thermal_before["status"] is None or thermal_after["status"] is None:
        raise RunnerError(f"Physical qualification case {identifier} produced no thermal-status evidence")
    return {
        "wallElapsedMs": elapsed_ms,
        "peakTotalPssKb": max(pss_samples),
        "memorySampleCount": len(pss_samples),
        "thermalBefore": thermal_before,
        "thermalAfter": thermal_after,
        "crashOrAnrEvidence": failures,
        "exitHistoryFailureEvidence": exit_failures,
    }


def run(
    serial: str,
    output: Path,
    *,
    skip_build: bool = False,
    case_ids: set[str] | None = None,
) -> dict[str, object]:
    manifest = load_manifest()
    validate(manifest)
    identity = query_identity(serial)
    rejection = physical_rejection(identity)
    if rejection:
        raise RunnerError(
            "Physical qualification refuses emulators and non-representative targets: " + rejection,
        )
    if not skip_build:
        print("[physical] building debug application and test APK locally")
        build()
    for artifact in (DEBUG_APK, TEST_APK):
        if not artifact.is_file():
            raise RunnerError(f"Expected Android artifact is missing: {artifact}")
    print(
        f"[physical] target {serial}: {identity.manufacturer} {identity.model}, "
        f"API {identity.api}, {identity.abi}, {identity.page_size_bytes}-byte pages",
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
    case_results: list[dict[str, object]] = []
    first_report: dict[str, object] | None = None
    for case in selected_cases:
        identifier = case["id"]
        print(f"[physical] running {identifier}")
        host_metrics = run_instrumented_case(serial, identifier)
        payload = captured(
            adb(serial, "exec-out", "run-as", APPLICATION_ID, "cat", REPORT_PATH),
            timeout=30,
        )
        partial = validate_report(payload, manifest, {identifier})
        if partial.get("physicalMeasurementRequested") is not True:
            raise RunnerError(f"Physical qualification case {identifier} did not enable measurements")
        case_payload = partial["cases"][0]
        if not isinstance(case_payload, dict):
            raise RunnerError(f"Physical qualification case {identifier} returned invalid metrics")
        if identifier == "dense-preview":
            validate_dense_render(case_payload)
        case_payload["host"] = host_metrics
        case_results.append(case_payload)
        if first_report is None:
            first_report = dict(partial)
    if first_report is None:
        raise RunnerError("Physical qualification selected no corpus cases")
    report = first_report
    report["source"] = "physical-android"
    report["generatedAtUtc"] = datetime.now(timezone.utc).isoformat()
    report["device"] = asdict(identity)
    report["cases"] = case_results
    report = validate_report(json.dumps(report), manifest, selected_ids)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"[physical] passed {len(case_results)} cases; report: {output}")
    return report


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--serial",
        required=True,
        help="explicit ADB serial for one physical ARM64 API 30+ device",
    )
    parser.add_argument("--case", action="append", dest="cases", help="run one named case; repeatable")
    parser.add_argument("--skip-build", action="store_true", help="reuse existing locally built debug APKs")
    parser.add_argument(
        "--output",
        type=Path,
        default=ROOT / "build/qualification/physical-report.json",
        help="ignored local JSON evidence path",
    )
    args = parser.parse_args(argv)
    try:
        devices = online_devices(captured(("adb", "devices", "-l"), timeout=20))
        if args.serial not in devices:
            raise RunnerError(f"Requested physical Android device is not online: {args.serial}")
        run(
            args.serial,
            args.output,
            skip_build=args.skip_build,
            case_ids=set(args.cases) if args.cases else None,
        )
    except (CorpusError, RunnerError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
