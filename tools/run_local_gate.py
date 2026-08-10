#!/usr/bin/env python3
"""Run DuckySlicer's authoritative local production gate."""

from __future__ import annotations

import argparse
import os
import shlex
import subprocess
import sys
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass, field
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
ANDROID = ROOT / "android"
RUST = ROOT / "rust/duckyslicer-jni"
DEBUG_APK = ANDROID / "app/build/outputs/apk/debug/app-debug.apk"
STATIC_VERIFIERS = (
    "verify_workflows.py",
    "verify_no_embedded_credentials.py",
    "verify_gradle_supply_chain.py",
    "verify_native_safety.py",
    "verify_android_isolation.py",
    "verify_slice_storage.py",
    "verify_preview_boundary.py",
    "verify_profile_editor.py",
    "verify_open_source_distribution.py",
    "verify_runtime_resilience.py",
    "verify_data_practices.py",
    "verify_support_diagnostics.py",
    "verify_project_archive.py",
    "verify_release_contract.py",
    "verify_play_bundle_workflow.py",
    "verify_localization.py",
    "verify_community_health.py",
    "verify_store_listing.py",
)


class GateError(RuntimeError):
    """A local gate precondition or command failed."""


@dataclass(frozen=True)
class DeviceFacts:
    api_level: int
    abi: str
    page_size: int

    @property
    def eligible(self) -> bool:
        return (
            self.api_level >= 35
            and self.abi == "arm64-v8a"
            and self.page_size == 16_384
        )

    def summary(self) -> str:
        return f"API {self.api_level}, {self.abi}, {self.page_size}-byte pages"


@dataclass(frozen=True)
class GateStep:
    name: str
    command: tuple[str, ...]
    cwd: Path
    environment: Mapping[str, str] = field(default_factory=dict)


def parse_online_devices(output: str) -> list[str]:
    devices: list[str] = []
    for line in output.splitlines():
        columns = line.split()
        if len(columns) >= 2 and columns[1] == "device":
            devices.append(columns[0])
    return devices


def choose_device(
    requested: str | None,
    environment_serial: str | None,
    devices: Mapping[str, DeviceFacts],
) -> str:
    selected = requested or environment_serial
    if selected:
        facts = devices.get(selected)
        if facts is None:
            raise GateError(f"Requested Android device is not online: {selected}")
        if not facts.eligible:
            raise GateError(
                f"Requested device {selected} is not the local 16 KB gate: {facts.summary()}"
            )
        return selected

    eligible = sorted(serial for serial, facts in devices.items() if facts.eligible)
    if len(eligible) == 1:
        return eligible[0]
    if not eligible:
        detail = ", ".join(
            f"{serial} ({facts.summary()})" for serial, facts in sorted(devices.items())
        ) or "no online devices"
        raise GateError(
            "No API 35+ ARM64 device with 16,384-byte pages is available: " + detail
        )
    raise GateError(
        "Multiple eligible 16 KB devices are online; pass --serial: " + ", ".join(eligible)
    )


def capture(command: Sequence[str], timeout: float = 20) -> str:
    try:
        result = subprocess.run(
            list(command),
            check=False,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise GateError(f"Could not run {shlex.join(command)}: {error}") from error
    if result.returncode != 0:
        detail = (result.stdout + result.stderr).strip()
        raise GateError(
            f"Command failed ({result.returncode}): {shlex.join(command)}\n{detail}"
        )
    return result.stdout.strip()


def discover_device(requested: str | None, environment_serial: str | None) -> str:
    online = parse_online_devices(capture(("adb", "devices", "-l")))
    facts: dict[str, DeviceFacts] = {}
    for serial in online:
        try:
            api_text = capture(("adb", "-s", serial, "shell", "getprop", "ro.build.version.sdk"))
            abi = capture(("adb", "-s", serial, "shell", "getprop", "ro.product.cpu.abi"))
            page_text = capture(("adb", "-s", serial, "shell", "getconf", "PAGE_SIZE"))
            facts[serial] = DeviceFacts(int(api_text), abi, int(page_text))
        except (GateError, ValueError):
            continue
    return choose_device(requested, environment_serial, facts)


def host_steps(python: str = sys.executable, windows: bool = os.name == "nt") -> list[GateStep]:
    gradlew = "gradlew.bat" if windows else "./gradlew"
    steps = [
        GateStep("Rust format", ("cargo", "fmt", "--check"), RUST),
        GateStep("Rust tests", ("cargo", "test", "--locked"), RUST),
        GateStep("Rust release tests", ("cargo", "test", "--release", "--locked"), RUST),
        GateStep(
            "Rust lint",
            ("cargo", "clippy", "--locked", "--all-targets", "--", "-D", "warnings"),
            RUST,
        ),
        GateStep(
            "Android host build and tests",
            (
                gradlew,
                "--dependency-verification=strict",
                ":app:testDebugUnitTest",
                ":app:assembleDebug",
                ":app:assembleDebugAndroidTest",
                ":app:lintDebug",
            ),
            ANDROID,
        ),
        GateStep(
            "Python verifier tests",
            (python, "-m", "unittest", "discover", "-s", "tools", "-p", "test_*.py"),
            ROOT,
        ),
    ]
    steps.extend(
        GateStep(
            f"Static policy: {script.removesuffix('.py').removeprefix('verify_')}",
            (python, str(ROOT / "tools" / script)),
            ROOT,
        )
        for script in STATIC_VERIFIERS
    )
    steps.append(
        GateStep(
            "Debug merged manifest policy",
            (
                python,
                str(ROOT / "tools/verify_artifact_manifest.py"),
                "--variant",
                "debug",
                str(DEBUG_APK),
            ),
            ROOT,
        )
    )
    steps.append(
        GateStep(
            "Debug artifact localization",
            (
                python,
                str(ROOT / "tools/verify_artifact_localization.py"),
                str(DEBUG_APK),
            ),
            ROOT,
        )
    )
    steps.append(
        GateStep(
            "Debug APK policy",
            (python, str(ROOT / "tools/verify_apk.py"), str(DEBUG_APK)),
            ROOT,
        )
    )
    return steps


def device_steps(
    serial: str,
    python: str = sys.executable,
    windows: bool = os.name == "nt",
) -> list[GateStep]:
    gradlew = "gradlew.bat" if windows else "./gradlew"
    environment = {"ANDROID_SERIAL": serial}
    return [
        GateStep(
            "Android 16 KB device suite",
            (gradlew, "--dependency-verification=strict", ":app:connectedDebugAndroidTest"),
            ANDROID,
            environment,
        ),
        GateStep(
            "UI-process death recovery",
            (python, str(ROOT / "tools/run_process_reattachment_test.py"), "--serial", serial),
            ROOT,
            environment,
        ),
    ]


def run_steps(
    steps: Sequence[GateStep],
    runner: Callable[..., subprocess.CompletedProcess[object]] = subprocess.run,
) -> None:
    total = len(steps)
    for index, step in enumerate(steps, start=1):
        print(f"[{index}/{total}] {step.name}", flush=True)
        print(f"  {shlex.join(step.command)}", flush=True)
        environment = os.environ.copy()
        environment.update(step.environment)
        try:
            result = runner(
                list(step.command),
                cwd=step.cwd,
                env=environment,
                check=False,
            )
        except OSError as error:
            raise GateError(f"Could not run {step.name}: {error}") from error
        if result.returncode != 0:
            raise GateError(f"{step.name} failed with exit code {result.returncode}")


def main(arguments: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--host-only",
        action="store_true",
        help="Run the host/build/static portion without the authoritative device gate.",
    )
    parser.add_argument(
        "--serial",
        help="Use this API 35+ ARM64 16 KB Android device instead of automatic selection.",
    )
    options = parser.parse_args(arguments)
    if options.host_only and options.serial:
        parser.error("--serial cannot be combined with --host-only")

    try:
        serial = (
            None
            if options.host_only
            else discover_device(options.serial, os.environ.get("ANDROID_SERIAL"))
        )
        steps = host_steps()
        if serial:
            facts = DeviceFacts(
                int(capture(("adb", "-s", serial, "shell", "getprop", "ro.build.version.sdk"))),
                capture(("adb", "-s", serial, "shell", "getprop", "ro.product.cpu.abi")),
                int(capture(("adb", "-s", serial, "shell", "getconf", "PAGE_SIZE"))),
            )
            print(f"Using {serial}: {facts.summary()}", flush=True)
            steps.extend(device_steps(serial))
        run_steps(steps)
    except (GateError, ValueError) as error:
        print(f"Local production gate failed: {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("Local production gate interrupted", file=sys.stderr)
        return 130

    scope = "host and local ARM64 16 KB device" if serial else "host only"
    print(f"Local production gate passed: {scope}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
