#!/usr/bin/env python3
"""Verify DuckySlicer's checked-in Gradle dependency trust boundary."""

from __future__ import annotations

import hashlib
import re
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
ANDROID_ROOT = ROOT / "android"
BUILD_SCRIPT = ANDROID_ROOT / "build.gradle.kts"
APP_BUILD_SCRIPT = ANDROID_ROOT / "app/build.gradle.kts"
LOCKFILE = ANDROID_ROOT / "app/gradle.lockfile"
METADATA = ANDROID_ROOT / "gradle/verification-metadata.xml"
WRAPPER = ANDROID_ROOT / "gradle/wrapper/gradle-wrapper.properties"
SHA256 = re.compile(r"[0-9a-f]{64}")
MAVEN_COORDINATE = re.compile(
    r'"([A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:([^"$]+))"'
)
PLUGIN_VERSION = re.compile(r"\bid\([^\n]+\)\s+version\s+\"([^\"]+)\"")


class VerificationError(ValueError):
    """The checked-in Gradle trust data violates a build invariant."""


def require_file(path: Path) -> str:
    if not path.is_file():
        raise VerificationError(f"required file is missing: {path.relative_to(ROOT)}")
    return path.read_text(encoding="utf-8")


def is_dynamic(version: str) -> bool:
    lowered = version.lower()
    return (
        "+" in version
        or any(character in version for character in "[]()")
        or lowered.startswith(("latest.", "release"))
        or lowered.endswith("-snapshot")
    )


def verify_wrapper() -> None:
    properties = {}
    for line in require_file(WRAPPER).splitlines():
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            properties[key] = value
    distribution_url = properties.get("distributionUrl", "").replace("\\:", ":")
    if re.fullmatch(
        r"https://services\.gradle\.org/distributions/gradle-[0-9.]+-bin\.zip",
        distribution_url,
    ) is None:
        raise VerificationError(f"unexpected Gradle distribution URL: {distribution_url}")
    if SHA256.fullmatch(properties.get("distributionSha256Sum", "")) is None:
        raise VerificationError("Gradle distributionSha256Sum must be a lowercase SHA-256")
    if properties.get("validateDistributionUrl") != "true":
        raise VerificationError("Gradle distribution URL validation must remain enabled")


def declared_versions() -> set[str]:
    root_script = require_file(BUILD_SCRIPT)
    app_script = require_file(APP_BUILD_SCRIPT)
    if "lockAllConfigurations()" not in root_script:
        raise VerificationError("all Gradle configurations must participate in dependency locking")

    coordinates = {match[0] for match in MAVEN_COORDINATE.findall(app_script)}
    versions = [match[1] for match in MAVEN_COORDINATE.findall(app_script)]
    versions.extend(PLUGIN_VERSION.findall(root_script))
    dynamic = sorted(version for version in versions if is_dynamic(version))
    if dynamic:
        raise VerificationError(f"dynamic or mutable Gradle versions are forbidden: {dynamic}")
    return coordinates


def verify_lockfile(required_coordinates: set[str]) -> int:
    locked_coordinates: set[str] = set()
    for line in require_file(LOCKFILE).splitlines():
        if not line or line.startswith("#") or line.startswith("empty="):
            continue
        coordinate, separator, configurations = line.partition("=")
        if not separator or coordinate.count(":") != 2 or not configurations:
            raise VerificationError(f"invalid dependency lock entry: {line}")
        version = coordinate.rsplit(":", 1)[1]
        if is_dynamic(version):
            raise VerificationError(f"dynamic version in dependency lock: {coordinate}")
        locked_coordinates.add(coordinate)
    if not locked_coordinates:
        raise VerificationError("dependency lock is empty")
    missing = sorted(required_coordinates - locked_coordinates)
    if missing:
        raise VerificationError(f"direct dependencies missing from lock: {missing}")
    return len(locked_coordinates)


def verify_metadata() -> tuple[int, int]:
    require_file(METADATA)
    try:
        root = ET.parse(METADATA).getroot()
    except ET.ParseError as error:
        raise VerificationError(f"invalid verification metadata XML: {error}") from error
    namespace = root.tag.removesuffix("verification-metadata").strip("{}")
    prefix = f"{{{namespace}}}"
    configuration = root.find(f"{prefix}configuration")
    if configuration is None or configuration.findtext(f"{prefix}verify-metadata") != "true":
        raise VerificationError("Gradle module metadata verification must remain enabled")
    forbidden = ("trusted-artifacts", "ignored-keys", "trusted-keys")
    if any(root.find(f".//{prefix}{name}") is not None for name in forbidden):
        raise VerificationError("verification metadata must not bypass artifacts or signing keys")
    if any(root.find(f".//{prefix}{name}") is not None for name in ("md5", "sha1")):
        raise VerificationError("weak Gradle dependency checksums are forbidden")

    components = root.findall(f"{prefix}components/{prefix}component")
    aapt2_components = [
        component
        for component in components
        if component.get("group") == "com.android.tools.build"
        and component.get("name") == "aapt2"
    ]
    if len(aapt2_components) != 1:
        raise VerificationError("expected one checksum-pinned Android AAPT2 component")
    aapt2 = aapt2_components[0]
    aapt2_version = aapt2.get("version", "")
    aapt2_artifacts = {
        artifact.get("name", "") for artifact in aapt2.findall(f"{prefix}artifact")
    }
    required_aapt2_artifacts = {
        f"aapt2-{aapt2_version}-{host}.jar" for host in ("linux", "osx", "windows")
    }
    missing_aapt2 = sorted(required_aapt2_artifacts - aapt2_artifacts)
    if missing_aapt2:
        raise VerificationError(f"host AAPT2 checksums are missing: {missing_aapt2}")

    artifact_count = 0
    for component in components:
        identity = ":".join(
            component.get(attribute, "") for attribute in ("group", "name", "version")
        )
        if not all(component.get(attribute) for attribute in ("group", "name", "version")):
            raise VerificationError(f"verification component has incomplete identity: {identity}")
        if is_dynamic(component.get("version", "")):
            raise VerificationError(f"dynamic version in verification metadata: {identity}")
        for artifact in component.findall(f"{prefix}artifact"):
            checksums = artifact.findall(f"{prefix}sha256")
            if len(checksums) != 1 or any(
                SHA256.fullmatch(checksum.get("value", "")) is None for checksum in checksums
            ):
                raise VerificationError(
                    f"{identity}:{artifact.get('name', '<unnamed>')} must have one SHA-256"
                )
            artifact_count += 1
    if not components or not artifact_count:
        raise VerificationError("Gradle verification metadata contains no checked artifacts")
    return len(components), artifact_count


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    verify_wrapper()
    lock_count = verify_lockfile(declared_versions())
    component_count, artifact_count = verify_metadata()
    print(
        f"Verified Gradle supply chain: {lock_count} locked modules, "
        f"{component_count} checksum-pinned components, {artifact_count} artifacts; "
        f"lock={digest(LOCKFILE)[:12]}, metadata={digest(METADATA)[:12]}"
    )


if __name__ == "__main__":
    try:
        main()
    except (OSError, VerificationError) as error:
        raise SystemExit(f"Gradle supply-chain verification failed: {error}") from error
