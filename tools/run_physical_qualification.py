#!/usr/bin/env python3
"""Run the pinned qualification corpus on one explicitly selected physical ARM64 device."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
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
        captured_stdout_bytes,
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
        captured_stdout_bytes,
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


QUALIFICATION_APPLICATION_ID = f"{APPLICATION_ID}.qualification"
QUALIFICATION_TEST_APPLICATION_ID = f"{QUALIFICATION_APPLICATION_ID}.test"
QUALIFICATION_APPLICATION_ID_SUFFIX = ".qualification"
QUALIFICATION_VERSION_CODE = 5
QUALIFICATION_VERSION_NAME = "0.2.0-rc.1"
DEBUG_OUTPUT_METADATA = DEBUG_APK.parent / "output-metadata.json"
TEST_OUTPUT_METADATA = TEST_APK.parent / "output-metadata.json"
MAX_AUTOMATIC_FIRST_FRAME_MS = 2_000.0
MAX_AUTOMATIC_SETTLED_P95_MS = 50.0
MAX_AUTOMATIC_INTERACTION_P95_MS = 50.0
MAX_PREVIEW_GEOMETRY_UPLOADS = 4
MAX_PEAK_TOTAL_PSS_KB = 1_572_864
MAX_PEAK_TOTAL_PSS_FRACTION = 0.35
MAX_START_THERMAL_STATUS = 1
MAX_END_THERMAL_STATUS = 2
PHYSICAL_DENSE_SOAK_CYCLES = 3
MAX_SOAK_UI_PSS_GROWTH_KB = 65_536
MAX_SOAK_UI_PSS_GROWTH_FRACTION = 0.15
MAX_SOAK_TIMING_REGRESSION_RATIO = 1.5
MAX_SOAK_FRAME_REGRESSION_ALLOWANCE_MS = 8.0
MAX_SOAK_SLICE_REGRESSION_ALLOWANCE_MS = 1_000.0


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


def output_application_id(path: Path) -> str:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise RunnerError(f"Could not read Android build metadata {path}: {error}") from error
    application_id = document.get("applicationId") if isinstance(document, dict) else None
    if not isinstance(application_id, str) or not application_id:
        raise RunnerError(f"Android build metadata does not identify its application: {path}")
    return application_id


def validate_qualification_artifacts() -> None:
    actual = {
        output_application_id(DEBUG_OUTPUT_METADATA),
        output_application_id(TEST_OUTPUT_METADATA),
    }
    expected = {QUALIFICATION_APPLICATION_ID, QUALIFICATION_TEST_APPLICATION_ID}
    if actual != expected:
        raise RunnerError(
            "Physical qualification APKs are not isolated from the installed release: "
            f"expected={sorted(expected)} actual={sorted(actual)}",
        )


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
    if identity.memory_total_kb is None or identity.memory_total_kb <= 0:
        return f"{identity.serial} did not report a positive physical-memory total"
    return None


def foreground_rejection(power_output: str, window_output: str) -> str | None:
    wakefulness = re.search(r"(?m)^\s*mWakefulness=(\w+)\s*$", power_output)
    if wakefulness is None:
        return "could not verify the device wakefulness"
    if wakefulness.group(1) != "Awake":
        return f"device wakefulness is {wakefulness.group(1)!r}"
    if re.search(r"(?m)^\s*mInputRestricted=true\s*$", window_output):
        return "device input is restricted by the lock screen"
    if re.search(r"mDreamingLockscreen=true", window_output):
        return "device lock screen is active"
    return None


def validate_preview_render(render: object) -> None:
    if not isinstance(render, dict):
        raise RunnerError("Dense Preview did not return the required GPU frame measurement")
    if render.get("measurementSurface") != "foreground-glsurfaceview":
        raise RunnerError("Dense Preview was not measured on the visible foreground surface")
    measurement_shape = (
        render.get("framebufferWidth"),
        render.get("framebufferHeight"),
        render.get("frameCountPerPhase"),
    )
    if measurement_shape != (720, 1280, 30):
        raise RunnerError(
            "Dense Preview used a reduced measurement shape instead of 720x1280 with 30 frames per phase",
        )
    automatic_detail = render.get("automaticDetail")
    tiers = render.get("tiers")
    if automatic_detail not in {"PERFORMANCE", "BALANCED", "DETAIL"} or not isinstance(tiers, dict):
        raise RunnerError("Dense Preview did not report its automatic rendering tier")
    if set(tiers) != {"PERFORMANCE", "BALANCED", "DETAIL"}:
        raise RunnerError("Dense Preview did not measure every rendering tier")
    required_metrics = {
        "firstFrameMs",
        "settledFrameP50Ms",
        "settledFrameP95Ms",
        "interactionFrameP50Ms",
        "interactionFrameP95Ms",
        "geometryUploads",
    }
    for detail, metrics in tiers.items():
        if not isinstance(metrics, dict) or not required_metrics.issubset(metrics):
            raise RunnerError(f"Dense Preview returned incomplete {detail} rendering metrics")
        for metric in required_metrics - {"geometryUploads"}:
            value = metrics.get(metric)
            if not isinstance(value, (int, float)) or isinstance(value, bool) or not math.isfinite(value):
                raise RunnerError(f"Dense Preview returned invalid {detail} {metric}: {value!r}")
            if value < 0:
                raise RunnerError(f"Dense Preview returned negative {detail} {metric}: {value!r}")
        uploads = metrics.get("geometryUploads")
        if (
            not isinstance(uploads, int)
            or isinstance(uploads, bool)
            or uploads < 1
            or uploads > MAX_PREVIEW_GEOMETRY_UPLOADS
        ):
            raise RunnerError(
                f"Dense Preview {detail} geometry uploads exceed the bounded cache policy: {uploads!r}",
            )
    selected = tiers[automatic_detail]
    if render.get("detail") != automatic_detail:
        raise RunnerError("Dense Preview reported a different detail tier than Automatic selected")
    budgets = {
        "firstFrameMs": MAX_AUTOMATIC_FIRST_FRAME_MS,
        "settledFrameP95Ms": MAX_AUTOMATIC_SETTLED_P95_MS,
        "interactionFrameP95Ms": MAX_AUTOMATIC_INTERACTION_P95_MS,
    }
    for metric, maximum in budgets.items():
        value = selected[metric]
        if value > maximum:
            raise RunnerError(
                f"Dense Preview Automatic {automatic_detail} exceeds {metric} budget: "
                f"actual={value:.3f}ms maximum={maximum:.3f}ms",
            )


def validate_dense_render(
    case_payload: dict[str, object],
    *,
    required_soak_cycles: int = 1,
) -> None:
    validate_preview_render(case_payload.get("previewRender"))
    if required_soak_cycles <= 1:
        return
    soak_cycles = case_payload.get("soakCycles")
    if not isinstance(soak_cycles, list) or len(soak_cycles) != required_soak_cycles:
        raise RunnerError(
            f"Dense Preview did not return exactly {required_soak_cycles} same-session soak cycles",
        )
    worker_pids: list[int] = []
    ui_pss: list[int] = []
    validated: list[dict[str, object]] = []
    for index, cycle in enumerate(soak_cycles, start=1):
        if not isinstance(cycle, dict) or cycle.get("cycle") != index:
            raise RunnerError(f"Dense Preview returned an invalid soak cycle {index}")
        worker_pid = cycle.get("workerPid")
        cycle_pss = cycle.get("uiPssKb")
        if not isinstance(worker_pid, int) or isinstance(worker_pid, bool) or worker_pid <= 0:
            raise RunnerError(f"Dense Preview soak cycle {index} has no stable worker PID")
        if not isinstance(cycle_pss, int) or isinstance(cycle_pss, bool) or cycle_pss <= 0:
            raise RunnerError(f"Dense Preview soak cycle {index} has invalid UI PSS")
        validate_preview_render(cycle.get("previewRender"))
        worker_pids.append(worker_pid)
        ui_pss.append(cycle_pss)
        validated.append(cycle)
    if len(set(worker_pids)) != 1:
        raise RunnerError("Dense Preview soak restarted the isolated slicer worker between cycles")

    reference_pss = ui_pss[-2]
    allowed_growth = max(
        MAX_SOAK_UI_PSS_GROWTH_KB,
        int(reference_pss * MAX_SOAK_UI_PSS_GROWTH_FRACTION),
    )
    if ui_pss[-1] - reference_pss > allowed_growth:
        raise RunnerError(
            "Dense Preview soak UI PSS continued growing after warm-up: "
            f"reference={reference_pss}kB final={ui_pss[-1]}kB maximumGrowth={allowed_growth}kB",
        )

    reference = validated[-2]
    final = validated[-1]
    for metric, allowance in (
        ("sliceElapsedMs", MAX_SOAK_SLICE_REGRESSION_ALLOWANCE_MS),
        ("previewParseElapsedMs", MAX_SOAK_FRAME_REGRESSION_ALLOWANCE_MS),
    ):
        validate_soak_timing(reference, final, metric, allowance)
    reference_render = selected_automatic_metrics(reference)
    final_render = selected_automatic_metrics(final)
    for metric in ("settledFrameP95Ms", "interactionFrameP95Ms"):
        validate_soak_timing(
            reference_render,
            final_render,
            metric,
            MAX_SOAK_FRAME_REGRESSION_ALLOWANCE_MS,
        )


def selected_automatic_metrics(cycle: dict[str, object]) -> dict[str, object]:
    render = cycle.get("previewRender")
    if not isinstance(render, dict):
        raise RunnerError("Dense Preview soak cycle has no rendering metrics")
    detail = render.get("automaticDetail")
    tiers = render.get("tiers")
    selected = tiers.get(detail) if isinstance(tiers, dict) else None
    if not isinstance(selected, dict):
        raise RunnerError("Dense Preview soak cycle has no selected Automatic metrics")
    return selected


def validate_soak_timing(
    reference: dict[str, object],
    final: dict[str, object],
    metric: str,
    allowance_ms: float,
) -> None:
    reference_value = reference.get(metric)
    final_value = final.get(metric)
    if (
        not isinstance(reference_value, (int, float))
        or isinstance(reference_value, bool)
        or not math.isfinite(reference_value)
        or reference_value < 0
        or not isinstance(final_value, (int, float))
        or isinstance(final_value, bool)
        or not math.isfinite(final_value)
        or final_value < 0
    ):
        raise RunnerError(f"Dense Preview soak returned invalid {metric} timing")
    maximum = reference_value * MAX_SOAK_TIMING_REGRESSION_RATIO + allowance_ms
    if final_value > maximum:
        raise RunnerError(
            f"Dense Preview soak regressed {metric}: "
            f"reference={reference_value:.3f} final={final_value:.3f} maximum={maximum:.3f}",
        )


def validate_resource_budget(
    host_metrics: dict[str, object],
    identity: DeviceIdentity,
    identifier: str,
) -> None:
    peak_pss = host_metrics.get("peakTotalPssKb")
    if not isinstance(peak_pss, int) or isinstance(peak_pss, bool) or peak_pss <= 0:
        raise RunnerError(f"Physical qualification case {identifier} returned invalid peak PSS")
    memory_total = identity.memory_total_kb
    if memory_total is None or memory_total <= 0:
        raise RunnerError("Physical qualification target has no usable physical-memory total")
    pss_limit = min(
        MAX_PEAK_TOTAL_PSS_KB,
        int(memory_total * MAX_PEAK_TOTAL_PSS_FRACTION),
    )
    if peak_pss > pss_limit:
        raise RunnerError(
            f"Physical qualification case {identifier} exceeds peak PSS budget: "
            f"actual={peak_pss}kB maximum={pss_limit}kB",
        )

    for label, maximum in (
        ("thermalBefore", MAX_START_THERMAL_STATUS),
        ("thermalAfter", MAX_END_THERMAL_STATUS),
    ):
        snapshot = host_metrics.get(label)
        if not isinstance(snapshot, dict):
            raise RunnerError(f"Physical qualification case {identifier} has no {label} snapshot")
        status = snapshot.get("status")
        if not isinstance(status, int) or isinstance(status, bool) or status < 0:
            raise RunnerError(f"Physical qualification case {identifier} has invalid {label} status")
        if status > maximum:
            raise RunnerError(
                f"Physical qualification case {identifier} exceeds {label} severity: "
                f"actual={status} maximum={maximum}",
            )
        readings = snapshot.get("readings")
        if not isinstance(readings, list):
            raise RunnerError(f"Physical qualification case {identifier} has invalid {label} readings")
        for reading in readings:
            if not isinstance(reading, dict):
                raise RunnerError(
                    f"Physical qualification case {identifier} has an invalid {label} sensor",
                )
            sensor_status = reading.get("status")
            if (
                not isinstance(sensor_status, int)
                or isinstance(sensor_status, bool)
                or sensor_status < 0
            ):
                raise RunnerError(
                    f"Physical qualification case {identifier} has an invalid {label} sensor status",
                )
        severe_sensors = [
            reading.get("name", "unknown")
            for reading in readings
            if reading["status"] > maximum
        ]
        if severe_sensors:
            raise RunnerError(
                f"Physical qualification case {identifier} has severe {label} sensors: "
                + ", ".join(str(name) for name in severe_sensors),
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


def sample_pss_kb(serial: str, package: str = QUALIFICATION_APPLICATION_ID) -> int | None:
    output = best_effort(
        adb(serial, "shell", "dumpsys", "meminfo", package),
        timeout=15,
    )
    return parse_total_pss_kb(output)


def qualification_source_commit(expected: str | None = None) -> str:
    status = captured(("git", "status", "--porcelain", "--untracked-files=normal"), timeout=20)
    if status.strip():
        raise RunnerError("Release qualification requires a clean Git checkout")
    branch = captured(("git", "branch", "--show-current"), timeout=20).strip()
    if branch != "main":
        raise RunnerError(f"Release qualification must run on main, found: {branch or 'detached'}")
    commit = captured(("git", "rev-parse", "HEAD"), timeout=20).strip()
    if expected is not None and commit != expected:
        raise RunnerError("Source commit changed during release qualification")
    return commit


def run_instrumented_case(
    serial: str,
    identifier: str,
    *,
    qualification_cycles: int = 1,
    retain_gcode: bool = False,
    timeout_seconds: int = 1_800,
) -> dict[str, object]:
    best_effort(adb(serial, "logcat", "-c"))
    exit_history_before = best_effort(
        adb(serial, "shell", "dumpsys", "activity", "exit-info", QUALIFICATION_APPLICATION_ID),
        timeout=30,
    )
    thermal_before = thermal_snapshot(serial)
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
        "-e",
        "measurePhysical",
        "true",
        "-e",
        "qualificationCycles",
        str(qualification_cycles),
    ]
    if retain_gcode:
        arguments.extend(("-e", "retainCorpusGcode", "true"))
    arguments.append(f"{QUALIFICATION_TEST_APPLICATION_ID}/{RUNNER}")
    command = adb(serial, *arguments)
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
            best_effort(adb(serial, "shell", "am", "force-stop", QUALIFICATION_APPLICATION_ID))
            break
        time.sleep(0.5)
    stdout, stderr = process.communicate()
    elapsed_ms = (time.monotonic() - started) * 1_000.0
    logcat = best_effort(adb(serial, "logcat", "-d", "-v", "threadtime"), timeout=60)
    failures = crash_anr_evidence(logcat, QUALIFICATION_APPLICATION_ID)
    exit_history_after = best_effort(
        adb(serial, "shell", "dumpsys", "activity", "exit-info", QUALIFICATION_APPLICATION_ID),
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
    retain_gcode: Path | None = None,
) -> dict[str, object]:
    source_commit = qualification_source_commit()
    manifest = load_manifest()
    validate(manifest)
    identity = query_identity(serial)
    rejection = physical_rejection(identity)
    if rejection:
        raise RunnerError(
            "Physical qualification refuses emulators and non-representative targets: " + rejection,
        )
    if case_ids is None or "dense-preview" in case_ids:
        foreground_issue = foreground_rejection(
            best_effort(adb(serial, "shell", "dumpsys", "power"), timeout=30),
            best_effort(adb(serial, "shell", "dumpsys", "window"), timeout=30),
        )
        if foreground_issue:
            raise RunnerError(
                "Physical qualification requires an awake, unlocked device for visible Preview "
                f"measurement: {foreground_issue}",
            )
    if not skip_build:
        print(
            f"[physical] building isolated {QUALIFICATION_VERSION_NAME} application and test APK locally",
        )
        build(
            application_id_suffix=QUALIFICATION_APPLICATION_ID_SUFFIX,
            version_code=QUALIFICATION_VERSION_CODE,
            version_name=QUALIFICATION_VERSION_NAME,
        )
    for artifact in (DEBUG_APK, TEST_APK):
        if not artifact.is_file():
            raise RunnerError(f"Expected Android artifact is missing: {artifact}")
    validate_qualification_artifacts()
    print(
        f"[physical] target {serial}: {identity.manufacturer} {identity.model}, "
        f"API {identity.api}, {identity.abi}, {identity.page_size_bytes}-byte pages",
    )
    best_effort(adb(serial, "uninstall", QUALIFICATION_TEST_APPLICATION_ID), timeout=60)
    best_effort(adb(serial, "uninstall", QUALIFICATION_APPLICATION_ID), timeout=60)
    try:
        captured(adb(serial, "install", "-r", "-t", str(DEBUG_APK)), timeout=180)
        captured(adb(serial, "install", "-r", "-t", str(TEST_APK)), timeout=180)
        selected_cases = [
            case for case in manifest["cases"]
            if case_ids is None or case["id"] in case_ids
        ]
        selected_ids = {case["id"] for case in selected_cases}
        if case_ids is not None and selected_ids != case_ids:
            raise RunnerError("Unknown qualification case: " + ", ".join(sorted(case_ids - selected_ids)))
        if retain_gcode is not None:
            retain_gcode.mkdir(parents=True, exist_ok=True)
        case_results: list[dict[str, object]] = []
        first_report: dict[str, object] | None = None
        for case in selected_cases:
            identifier = case["id"]
            print(f"[physical] running {identifier}")
            qualification_cycles = PHYSICAL_DENSE_SOAK_CYCLES if identifier == "dense-preview" else 1
            host_metrics = run_instrumented_case(
                serial,
                identifier,
                qualification_cycles=qualification_cycles,
                retain_gcode=retain_gcode is not None,
            )
            payload = captured(
                adb(
                    serial,
                    "exec-out",
                    "run-as",
                    QUALIFICATION_APPLICATION_ID,
                    "cat",
                    REPORT_PATH,
                ),
                timeout=30,
            )
            partial = validate_report(payload, manifest, {identifier})
            if partial.get("physicalMeasurementRequested") is not True:
                raise RunnerError(f"Physical qualification case {identifier} did not enable measurements")
            case_payload = partial["cases"][0]
            if not isinstance(case_payload, dict):
                raise RunnerError(f"Physical qualification case {identifier} returned invalid metrics")
            if identifier == "dense-preview":
                validate_dense_render(
                    case_payload,
                    required_soak_cycles=qualification_cycles,
                )
            validate_resource_budget(host_metrics, identity, identifier)
            if retain_gcode is not None:
                gcode = captured_stdout_bytes(
                    adb(
                        serial,
                        "exec-out",
                        "run-as",
                        QUALIFICATION_APPLICATION_ID,
                        "cat",
                        f"files/qualification/gcode/{identifier}.gcode",
                    ),
                    timeout=120,
                )
                actual_digest = hashlib.sha256(gcode).hexdigest()
                expected_digest = case_payload.get("gcodeSha256")
                if actual_digest != expected_digest:
                    raise RunnerError(
                        f"Physical qualification G-code digest differs for {identifier}: "
                        f"report={expected_digest!r} retained={actual_digest}",
                    )
                (retain_gcode / f"{identifier}.gcode").write_bytes(gcode)
            case_payload["host"] = host_metrics
            case_results.append(case_payload)
            if first_report is None:
                first_report = dict(partial)
        if first_report is None:
            raise RunnerError("Physical qualification selected no corpus cases")
        report = first_report
        report["source"] = "physical-android"
        qualification_source_commit(source_commit)
        report["sourceCommit"] = source_commit
        report["generatedAtUtc"] = datetime.now(timezone.utc).isoformat()
        report["device"] = asdict(identity)
        report["qualificationPackage"] = {
            "applicationId": QUALIFICATION_APPLICATION_ID,
            "versionCode": QUALIFICATION_VERSION_CODE,
            "versionName": QUALIFICATION_VERSION_NAME,
        }
        report["cases"] = case_results
        report = validate_report(json.dumps(report), manifest, selected_ids)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"[physical] passed {len(case_results)} cases; report: {output}")
        return report
    finally:
        best_effort(adb(serial, "uninstall", QUALIFICATION_TEST_APPLICATION_ID), timeout=60)
        best_effort(adb(serial, "uninstall", QUALIFICATION_APPLICATION_ID), timeout=60)


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
        "--retain-gcode",
        type=Path,
        metavar="DIR",
        help="retain digest-verified corpus G-code for the desktop-engine comparison",
    )
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
            retain_gcode=args.retain_gcode,
        )
    except (CorpusError, RunnerError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
