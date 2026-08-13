#!/usr/bin/env python3
"""Install or verify the local Android 16 ARM64 16 KB release-test AVD."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import subprocess
import sys
from typing import Mapping, Sequence


IMAGE_PACKAGE = "system-images;android-36;google_apis_ps16k;arm64-v8a"
DEFAULT_AVD_NAME = "DuckySlicer_16KB_API36"
DEFAULT_DEVICE = "pixel_6"


class AvdPreparationError(RuntimeError):
    pass


def resolve_sdk_root(environment: Mapping[str, str]) -> Path:
    value = environment.get("ANDROID_SDK_ROOT") or environment.get("ANDROID_HOME")
    if not value:
        raise AvdPreparationError("Set ANDROID_SDK_ROOT or ANDROID_HOME to the Android SDK")
    root = Path(value).expanduser().resolve()
    if not root.is_dir():
        raise AvdPreparationError(f"Android SDK directory does not exist: {root}")
    return root


def require_tool(sdk_root: Path, relative_candidates: Sequence[str]) -> Path:
    for relative in relative_candidates:
        candidate = sdk_root / relative
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return candidate
    raise AvdPreparationError(
        "Android SDK tool is unavailable: " + ", ".join(relative_candidates)
    )


def image_marker(sdk_root: Path) -> Path:
    return (
        sdk_root
        / "system-images"
        / "android-36"
        / "google_apis_ps16k"
        / "arm64-v8a"
        / "package.xml"
    )


def listed_avds(emulator: Path) -> set[str]:
    result = subprocess.run(
        (str(emulator), "-list-avds"),
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise AvdPreparationError(result.stderr.strip() or "Could not list Android AVDs")
    return {line.strip() for line in result.stdout.splitlines() if line.strip()}


def install_image(sdkmanager: Path) -> None:
    result = subprocess.run(
        (str(sdkmanager), IMAGE_PACKAGE),
        input="y\n",
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise AvdPreparationError("Android 16 ARM64 16 KB system-image installation failed")


def create_avd(avdmanager: Path, name: str, device: str) -> None:
    result = subprocess.run(
        (
            str(avdmanager),
            "create",
            "avd",
            "--name",
            name,
            "--package",
            IMAGE_PACKAGE,
            "--device",
            device,
        ),
        input="no\n",
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise AvdPreparationError(f"Could not create AVD {name}")


def launch_command(emulator: Path, name: str, port: int) -> tuple[str, ...]:
    return (
        str(emulator),
        "-avd",
        name,
        "-port",
        str(port),
        "-no-window",
        "-no-snapshot",
        "-no-audio",
        "-no-boot-anim",
        "-gpu",
        "swiftshader_indirect",
    )


def prepare(create: bool, name: str, device: str, port: int) -> tuple[str, ...]:
    sdk_root = resolve_sdk_root(os.environ)
    sdkmanager = require_tool(
        sdk_root,
        ("cmdline-tools/latest/bin/sdkmanager", "cmdline-tools/bin/sdkmanager"),
    )
    avdmanager = require_tool(
        sdk_root,
        ("cmdline-tools/latest/bin/avdmanager", "cmdline-tools/bin/avdmanager"),
    )
    emulator = require_tool(sdk_root, ("emulator/emulator",))

    if not image_marker(sdk_root).is_file():
        if not create:
            raise AvdPreparationError(
                f"Missing {IMAGE_PACKAGE}; rerun with --create to install it"
            )
        install_image(sdkmanager)
        if not image_marker(sdk_root).is_file():
            raise AvdPreparationError("The installed Android 16 system image is incomplete")

    names = listed_avds(emulator)
    if name not in names:
        if not create:
            raise AvdPreparationError(f"Missing AVD {name}; rerun with --create")
        create_avd(avdmanager, name, device)
        if name not in listed_avds(emulator):
            raise AvdPreparationError(f"Created AVD was not listed: {name}")

    return launch_command(emulator, name, port)


def main(arguments: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--create",
        action="store_true",
        help="Install the pinned system image and create the AVD when missing.",
    )
    parser.add_argument("--name", default=DEFAULT_AVD_NAME)
    parser.add_argument("--device", default=DEFAULT_DEVICE)
    parser.add_argument("--port", type=int, default=5558)
    options = parser.parse_args(arguments)
    if options.port < 5554 or options.port > 5682 or options.port % 2:
        parser.error("--port must be an even emulator console port from 5554 through 5682")
    try:
        command = prepare(options.create, options.name, options.device, options.port)
    except (AvdPreparationError, OSError) as error:
        print(f"Release AVD preparation failed: {error}", file=sys.stderr)
        return 1
    print("Release AVD is ready. Start it with:")
    print(" ".join(command))
    print("Then verify it with:")
    print(
        "python3 tools/run_local_gate.py "
        f"--serial emulator-{options.port} --require-api-36"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
