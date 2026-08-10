#!/usr/bin/env python3
"""Verify that the built APK contains DuckySlicer's reviewed app languages."""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from collections.abc import Mapping, Sequence
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from tools.generate_android_translations import ORCA_LOCALE_TO_ANDROID


RESOURCE = re.compile(r"^\s*resource\s+\S+\s+(?P<name>\S+)\s*$")
CONFIGURATION = re.compile(r"^\s+\((?P<configuration>[^)]*)\)\s+")
RESOURCE_NAME = "string/settings"
EXPECTED_CONFIGURATIONS = (
    "",
    "ko",
    *(qualifier for _, qualifier in ORCA_LOCALE_TO_ANDROID.values()),
)


class LocalizationArtifactError(ValueError):
    """The APK resource table diverges from the reviewed language set."""


def packaged_configurations(source: str, resource_name: str = RESOURCE_NAME) -> tuple[str, ...]:
    found_resource = False
    configurations: list[str] = []
    active = False
    for line in source.splitlines():
        resource = RESOURCE.match(line)
        if resource is not None:
            name = resource.group("name")
            active = name == resource_name
            if active:
                if found_resource:
                    raise LocalizationArtifactError(
                        f"APK repeats the {resource_name} resource block"
                    )
                found_resource = True
            continue
        if not active:
            continue
        configuration = CONFIGURATION.match(line)
        if configuration is not None:
            configurations.append(configuration.group("configuration"))

    if not found_resource:
        raise LocalizationArtifactError(f"APK has no {resource_name} resource")
    if not configurations:
        raise LocalizationArtifactError(f"APK has no values for {resource_name}")
    if len(configurations) != len(set(configurations)):
        raise LocalizationArtifactError(
            f"APK repeats a configuration for {resource_name}: {configurations}"
        )
    return tuple(configurations)


def verify_aapt2_output(source: str) -> None:
    actual = packaged_configurations(source)
    if set(actual) != set(EXPECTED_CONFIGURATIONS) or len(actual) != len(
        EXPECTED_CONFIGURATIONS
    ):
        raise LocalizationArtifactError(
            "APK language resource set changed: "
            f"expected={list(EXPECTED_CONFIGURATIONS)}, found={list(actual)}"
        )


def resolve_aapt2(environment: Mapping[str, str]) -> Path:
    sdk_text = environment.get("ANDROID_SDK_ROOT") or environment.get("ANDROID_HOME")
    if not sdk_text:
        raise LocalizationArtifactError("ANDROID_HOME or ANDROID_SDK_ROOT is required")
    executable = Path(sdk_text) / "build-tools/36.0.0/aapt2"
    if not executable.is_file():
        raise LocalizationArtifactError(f"Android aapt2 36.0.0 is missing: {executable}")
    return executable


def verify_apk(
    apk: Path,
    *,
    environment: Mapping[str, str] = os.environ,
    runner=subprocess.run,
) -> None:
    if not apk.is_file() or apk.stat().st_size <= 0:
        raise LocalizationArtifactError(f"APK is missing: {apk}")
    command = (str(resolve_aapt2(environment)), "dump", "resources", str(apk))
    try:
        result = runner(
            command,
            check=False,
            capture_output=True,
            text=True,
            timeout=30,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise LocalizationArtifactError(f"Could not inspect APK resources: {error}") from error
    if result.returncode != 0:
        detail = ((result.stdout or "") + (result.stderr or "")).strip()
        raise LocalizationArtifactError(
            f"aapt2 resource inspection failed ({result.returncode}): {detail}"
        )
    verify_aapt2_output(result.stdout)


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> None:
    arguments = parse_args(argv)
    try:
        verify_apk(arguments.apk)
    except LocalizationArtifactError as error:
        raise SystemExit(f"APK localization verification failed: {error}") from error
    print(
        f"Verified {len(EXPECTED_CONFIGURATIONS)} packaged app-language configurations"
    )


if __name__ == "__main__":
    main()
