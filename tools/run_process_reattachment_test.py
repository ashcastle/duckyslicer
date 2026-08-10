#!/usr/bin/env python3
"""Kill only DuckySlicer's UI process and prove the foreground slice reattaches."""

from __future__ import annotations

import argparse
import subprocess
import time
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "com.ashcastle.duckyslicer"
SERVICE_PROCESS = f"{PACKAGE}:slicer"
SESSION_PATH = "files/foreground-slice.session"
READY_PATH = "files/process-recovery.ready"
DEBUG_APK = ROOT / "android/app/build/outputs/apk/debug/app-debug.apk"


class VerificationError(RuntimeError):
    pass


def command(
    arguments: list[str],
    *,
    check: bool = True,
    timeout: float = 30,
) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        arguments,
        check=False,
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    if check and result.returncode != 0:
        detail = (result.stdout + result.stderr).strip()
        raise VerificationError(f"Command failed ({result.returncode}): {detail}")
    return result


def adb(serial: str, *arguments: str, check: bool = True, timeout: float = 30):
    return command(["adb", "-s", serial, *arguments], check=check, timeout=timeout)


def run_as(serial: str, *arguments: str, check: bool = True):
    return adb(serial, "shell", "run-as", PACKAGE, *arguments, check=check)


def wait_for(description: str, probe, timeout: float = 30):
    deadline = time.monotonic() + timeout
    last_value = None
    while time.monotonic() < deadline:
        last_value = probe()
        if last_value:
            return last_value
        time.sleep(0.1)
    raise VerificationError(f"Timed out waiting for {description}; last={last_value!r}")


def private_file(serial: str, path: str) -> str | None:
    result = run_as(serial, "cat", path, check=False)
    return result.stdout if result.returncode == 0 else None


def private_file_absent(serial: str, path: str) -> bool:
    result = run_as(serial, "test", "!", "-e", path, check=False)
    return result.returncode == 0


def process_id(serial: str, process_name: str) -> int | None:
    result = adb(serial, "shell", "pidof", process_name, check=False)
    values = result.stdout.strip().split()
    return int(values[0]) if len(values) == 1 and values[0].isdigit() else None


def slice_files(serial: str) -> set[str]:
    result = run_as(serial, "ls", "files/slices", check=False)
    if result.returncode != 0:
        return set()
    return {line.strip() for line in result.stdout.splitlines() if line.strip().endswith(".gcode")}


def slicer_service_stopped(serial: str) -> bool:
    result = adb(
        serial,
        "shell",
        "dumpsys",
        "activity",
        "services",
        PACKAGE,
        check=False,
    )
    return result.returncode == 0 and "SlicerProcessService" not in result.stdout


def verify_device(serial: str) -> None:
    api = adb(serial, "shell", "getprop", "ro.build.version.sdk").stdout.strip()
    abi = adb(serial, "shell", "getprop", "ro.product.cpu.abi").stdout.strip()
    page_size = adb(serial, "shell", "getconf", "PAGE_SIZE").stdout.strip()
    if int(api) < 35 or abi != "arm64-v8a" or page_size != "16384":
        raise VerificationError(
            f"Expected API 35+ ARM64 16 KB device, got api={api}, abi={abi}, page={page_size}"
        )


def run_test(serial: str) -> None:
    if not DEBUG_APK.is_file() or DEBUG_APK.stat().st_size <= 0:
        raise VerificationError(f"Build the required APK first: {DEBUG_APK}")
    verify_device(serial)
    boot_id = adb(
        serial,
        "shell",
        "cat",
        "/proc/sys/kernel/random/boot_id",
    ).stdout.strip()
    adb(serial, "install", "-r", str(DEBUG_APK), timeout=120)
    adb(
        serial,
        "shell",
        "pm",
        "grant",
        PACKAGE,
        "android.permission.POST_NOTIFICATIONS",
        check=False,
    )
    if private_file(serial, SESSION_PATH) is not None:
        raise VerificationError("A foreground slice checkpoint already exists; finish it first")
    run_as(serial, "rm", "-f", READY_PATH, check=False)
    initial_slices = slice_files(serial)
    adb(serial, "shell", "am", "force-stop", PACKAGE)
    adb(serial, "logcat", "-c")
    adb(
        serial,
        "shell",
        "am",
        "start",
        "-W",
        "-n",
        f"{PACKAGE}/.ProcessRecoveryHarnessActivity",
    )
    verification_succeeded = False
    try:
        ready = wait_for(
            "the active foreground slice stage",
            lambda: private_file(serial, READY_PATH),
            timeout=30,
        )
        ready_lines = ready.strip().splitlines()
        if len(ready_lines) != 2 or not ready_lines[1].isdigit():
            raise VerificationError(f"Invalid process-death readiness record: {ready!r}")
        request_id, old_ui_pid_text = ready_lines
        old_ui_pid = int(old_ui_pid_text)
        if process_id(serial, PACKAGE) != old_ui_pid:
            raise VerificationError("The readiness record does not identify the current UI process")
        old_service_pid = process_id(serial, SERVICE_PROCESS)
        if old_service_pid is None or old_service_pid == old_ui_pid:
            raise VerificationError("The slicer is not isolated from the UI process")
        checkpoint = private_file(serial, SESSION_PATH) or ""
        if request_id not in checkpoint or '"phase":"ACTIVE"' not in checkpoint:
            raise VerificationError("The foreground slice was not active before process death")

        print(
            f"Killing only UI pid {old_ui_pid}; preserving slicer pid {old_service_pid}",
            flush=True,
        )
        run_as(serial, "kill", "-9", str(old_ui_pid))
        wait_for(
            "the old UI process to die",
            lambda: process_id(serial, PACKAGE) != old_ui_pid,
            timeout=10,
        )
        surviving_service_pid = wait_for(
            "the isolated foreground service to survive",
            lambda: process_id(serial, SERVICE_PROCESS),
            timeout=10,
        )
        if surviving_service_pid != old_service_pid:
            raise VerificationError(
                "The isolated slicer process restarted instead of surviving UI process death"
            )
        current_boot_id = adb(
            serial,
            "shell",
            "cat",
            "/proc/sys/kernel/random/boot_id",
        ).stdout.strip()
        if current_boot_id != boot_id:
            raise VerificationError("The Android guest rebooted during process-death recovery")

        adb(
            serial,
            "shell",
            "am",
            "start",
            "-W",
            "-a",
            "android.intent.action.MAIN",
            "-c",
            "android.intent.category.LAUNCHER",
            "-n",
            f"{PACKAGE}/.MainActivity",
        )
        new_ui_pid = wait_for(
            "a replacement UI process",
            lambda: process_id(serial, PACKAGE),
            timeout=10,
        )
        if new_ui_pid == old_ui_pid:
            raise VerificationError("MainActivity did not start in a new process")
        new_slices = wait_for(
            "a newly retained G-code artifact",
            lambda: slice_files(serial) - initial_slices,
            timeout=90,
        )
        wait_for(
            "the replacement UI to publish the recovered Preview",
            lambda: "Recovered foreground slice"
            in adb(
                serial,
                "logcat",
                "-d",
                "-s",
                "DuckySliceOperation:I",
                timeout=30,
            ).stdout,
            timeout=90,
        )
        wait_for(
            "the recovered Preview checkpoint to close",
            lambda: private_file_absent(serial, SESSION_PATH),
            timeout=10,
        )
        wait_for(
            "the foreground service to stop after recovery",
            lambda: slicer_service_stopped(serial),
            timeout=10,
        )
        logs = adb(serial, "logcat", "-d", "-v", "brief", timeout=30).stdout
        if "ForegroundServiceDidNotStartInTime" in logs or "FATAL EXCEPTION" in logs:
            raise VerificationError("A fatal Android lifecycle error occurred during recovery")
        print(
            "Verified UI-process death recovery on "
            f"{serial}: ui {old_ui_pid}->{new_ui_pid}, slicer {old_service_pid} survived, "
            f"new G-code={sorted(new_slices)}"
        )
        verification_succeeded = True
    finally:
        run_as(serial, "rm", "-f", READY_PATH, check=False)
        if not verification_succeeded:
            adb(serial, "shell", "am", "force-stop", PACKAGE, check=False)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default="emulator-5556")
    arguments = parser.parse_args()
    run_test(arguments.serial)


if __name__ == "__main__":
    main()
