#!/usr/bin/env python3
"""Build and verify a reproducible unsigned DuckySlicer release locally."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from tools.verify_reproducible_release import verify_reproducible


ANDROID = ROOT / "android"
RELEASE_APK = ANDROID / "app/build/outputs/apk/release/app-release-unsigned.apk"
PACKAGE_NAME = "com.ashcastle.duckyslicer"
MAX_VERSION_CODE = 2_100_000_000
SEMVER = re.compile(
    r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?$"
)
BADGING = re.compile(
    r"^package: name='(?P<package>[^']+)' versionCode='(?P<code>[^']+)' "
    r"versionName='(?P<version>[^']+)'"
)
SIGNING_ENVIRONMENT = (
    "DUCKYSLICER_KEYSTORE_FILE",
    "DUCKYSLICER_KEYSTORE_BASE64",
    "DUCKYSLICER_STORE_PASSWORD",
    "DUCKYSLICER_KEY_ALIAS",
    "DUCKYSLICER_KEY_PASSWORD",
)


class ReleasePreparationError(RuntimeError):
    """The local checkout or release artifact does not meet the release contract."""


@dataclass(frozen=True)
class ReleaseIdentity:
    version_name: str
    version_code: int
    source_commit: str
    unsigned_asset: str
    unsigned_sha256: str

    def document(self) -> dict[str, object]:
        return {
            "schemaVersion": 1,
            "project": "DuckySlicer",
            "packageName": PACKAGE_NAME,
            "versionName": self.version_name,
            "versionCode": self.version_code,
            "sourceCommit": self.source_commit,
            "unsignedAsset": self.unsigned_asset,
            "unsignedSha256": self.unsigned_sha256,
        }


def validate_release_inputs(version_name: str, version_code: int) -> None:
    if SEMVER.fullmatch(version_name) is None:
        raise ReleasePreparationError(
            f"Release version must be SemVer without a leading v: {version_name}"
        )
    if not 1 <= version_code <= MAX_VERSION_CODE:
        raise ReleasePreparationError(
            f"Release version code must be between 1 and {MAX_VERSION_CODE}"
        )


def signing_variables(environment: Mapping[str, str]) -> list[str]:
    return sorted(name for name in SIGNING_ENVIRONMENT if environment.get(name, "").strip())


def gradle_release_command(
    version_name: str,
    version_code: int,
    *,
    rebuild: bool,
) -> tuple[str, ...]:
    command = ["./gradlew", "--dependency-verification=strict"]
    if rebuild:
        command.append("--no-build-cache")
    command.append(":app:clean")
    if rebuild:
        command.append(":app:assembleRelease")
    else:
        command.extend(
            (
                ":app:lintRelease",
                ":app:assembleRelease",
                ":app:writeReleaseDependencyInventory",
            )
        )
    command.extend(
        (
            f"-Pduckyslicer.versionName={version_name}",
            f"-Pduckyslicer.versionCode={version_code}",
        )
    )
    return tuple(command)


def parse_badging(source: str) -> tuple[str, int, str]:
    first_line = source.splitlines()[0] if source.splitlines() else ""
    match = BADGING.match(first_line)
    if match is None:
        raise ReleasePreparationError("Could not read the release APK package identity")
    try:
        version_code = int(match.group("code"))
    except ValueError as error:
        raise ReleasePreparationError("Release APK versionCode is not an integer") from error
    return match.group("package"), version_code, match.group("version")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run(
    command: Sequence[str],
    *,
    cwd: Path = ROOT,
    capture: bool = False,
    environment: Mapping[str, str] | None = None,
) -> subprocess.CompletedProcess[str]:
    merged_environment = os.environ.copy()
    if environment:
        merged_environment.update(environment)
    try:
        result = subprocess.run(
            list(command),
            cwd=cwd,
            env=merged_environment,
            check=False,
            capture_output=capture,
            text=True,
        )
    except OSError as error:
        raise ReleasePreparationError(f"Could not run {' '.join(command)}: {error}") from error
    if result.returncode != 0:
        detail = ((result.stdout or "") + (result.stderr or "")).strip()
        suffix = f"\n{detail}" if detail else ""
        raise ReleasePreparationError(
            f"Command failed ({result.returncode}): {' '.join(command)}{suffix}"
        )
    return result


def captured(command: Sequence[str], *, cwd: Path = ROOT) -> str:
    return run(command, cwd=cwd, capture=True).stdout.strip()


def mismatched_submodules(source: str) -> list[str]:
    return [
        line
        for line in source.splitlines()
        if line and not line.startswith(" ")
    ]


def verify_checkout() -> str:
    status = captured(("git", "status", "--porcelain", "--untracked-files=normal"))
    if status:
        raise ReleasePreparationError("Release preparation requires a clean Git checkout")
    branch = captured(("git", "branch", "--show-current"))
    if branch != "main":
        raise ReleasePreparationError(f"Release preparation must run on main, found: {branch}")
    run(
        (
            "git",
            "fetch",
            "--quiet",
            "--prune",
            "--no-tags",
            "origin",
            "+refs/heads/main:refs/remotes/origin/main",
        )
    )
    commit = captured(("git", "rev-parse", "HEAD"))
    origin_main = captured(("git", "rev-parse", "origin/main"))
    if commit != origin_main:
        raise ReleasePreparationError("Local main must exactly match origin/main")
    submodules = run(
        ("git", "submodule", "status", "--recursive"),
        capture=True,
    ).stdout
    invalid = mismatched_submodules(submodules)
    if invalid:
        raise ReleasePreparationError(
            "Recursive submodules do not match their recorded commits: " + "; ".join(invalid)
        )
    return commit


def android_build_tools(environment: Mapping[str, str]) -> Path:
    sdk_text = environment.get("ANDROID_SDK_ROOT") or environment.get("ANDROID_HOME")
    if not sdk_text:
        raise ReleasePreparationError("ANDROID_HOME or ANDROID_SDK_ROOT is required")
    tools = Path(sdk_text) / "build-tools/36.0.0"
    required = ("aapt", "apksigner", "zipalign")
    missing = [name for name in required if not (tools / name).is_file()]
    if missing:
        raise ReleasePreparationError(
            f"Android build-tools 36.0.0 are incomplete: {', '.join(missing)}"
        )
    return tools


def verify_unsigned_apk(
    apk: Path,
    version_name: str,
    version_code: int,
    build_tools: Path,
) -> None:
    if not apk.is_file() or apk.stat().st_size <= 0:
        raise ReleasePreparationError(f"Unsigned release APK is missing: {apk}")
    run((sys.executable, str(ROOT / "tools/verify_apk.py"), str(apk)))
    run((str(build_tools / "zipalign"), "-c", "-P", "16", "-v", "4", str(apk)))
    badging = captured((str(build_tools / "aapt"), "dump", "badging", str(apk)))
    package, actual_code, actual_version = parse_badging(badging)
    if (package, actual_code, actual_version) != (
        PACKAGE_NAME,
        version_code,
        version_name,
    ):
        raise ReleasePreparationError(
            "Release APK identity mismatch: "
            f"found {package} {actual_code} {actual_version}"
        )
    signature = subprocess.run(
        [str(build_tools / "apksigner"), "verify", str(apk)],
        check=False,
        capture_output=True,
        text=True,
    )
    if signature.returncode == 0:
        raise ReleasePreparationError("Local release candidate must remain unsigned")


def write_metadata(path: Path, identity: ReleaseIdentity) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(identity.document(), indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    os.replace(temporary, path)


def prepare_release(version_name: str, version_code: int, output_root: Path) -> ReleaseIdentity:
    validate_release_inputs(version_name, version_code)
    exposed_signing = signing_variables(os.environ)
    if exposed_signing:
        raise ReleasePreparationError(
            "Unsigned release preparation refuses signing variables: "
            + ", ".join(exposed_signing)
        )
    source_commit = verify_checkout()
    build_tools = android_build_tools(os.environ)
    output = output_root.resolve()
    output.mkdir(parents=True, exist_ok=True)
    unsigned_name = f"DuckySlicer-{version_name}-arm64-unsigned.apk"
    unsigned_output = output / unsigned_name
    candidate_output = output / f".{unsigned_name}.candidate"
    metadata_output = output / f"DuckySlicer-{version_name}-LOCAL-RELEASE.json"
    collisions = [
        path
        for path in (unsigned_output, candidate_output, metadata_output)
        if path.exists()
    ]
    if collisions:
        raise ReleasePreparationError(
            "Refusing to overwrite release output: " + ", ".join(map(str, collisions))
        )

    run((sys.executable, str(ROOT / "tools/run_local_gate.py")))
    run(gradle_release_command(version_name, version_code, rebuild=False), cwd=ANDROID)
    verify_unsigned_apk(RELEASE_APK, version_name, version_code, build_tools)
    shutil.copyfile(RELEASE_APK, candidate_output)

    try:
        run(gradle_release_command(version_name, version_code, rebuild=True), cwd=ANDROID)
        verify_unsigned_apk(RELEASE_APK, version_name, version_code, build_tools)
        verify_reproducible(candidate_output, RELEASE_APK)

        identity = ReleaseIdentity(
            version_name=version_name,
            version_code=version_code,
            source_commit=source_commit,
            unsigned_asset=unsigned_name,
            unsigned_sha256=sha256(candidate_output),
        )
        os.replace(candidate_output, unsigned_output)
        write_metadata(metadata_output, identity)
        return identity
    finally:
        candidate_output.unlink(missing_ok=True)


def main(arguments: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True, help="SemVer without a leading v")
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument(
        "--output",
        type=Path,
        help="Output directory (default: build/local-release/<version>)",
    )
    options = parser.parse_args(arguments)
    output = options.output or ROOT / "build/local-release" / options.version
    try:
        identity = prepare_release(options.version, options.version_code, output)
    except (OSError, ReleasePreparationError, ValueError) as error:
        print(f"Local release preparation failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(identity.document(), indent=2, sort_keys=True))
    print(f"Local release candidate prepared in {output.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
