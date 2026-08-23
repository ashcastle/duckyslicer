#!/usr/bin/env python3
"""Build and verify a reproducible unsigned Play App Bundle locally."""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sys
import zipfile
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from tools.prepare_local_release import (
    ANDROID,
    PACKAGE_NAME,
    ReleasePreparationError,
    android_build_tools,
    run,
    sha256,
    validate_release_inputs,
    verify_checkout,
    verify_unsigned_apk,
)
from tools.verify_release_qualifications import verify_release_qualifications
from tools.verify_reproducible_release import verify_reproducible


RELEASE_AAB = ANDROID / "app/build/outputs/bundle/release/app-release.aab"
DELIVERY_APK = (
    ANDROID
    / "app/build/outputs/apk_from_bundle/release/app-release-universal-unsigned.apk"
)
SIGNATURE_BLOCK = re.compile(r"^META-INF/[^/]+\.(RSA|DSA|EC)$", re.IGNORECASE)
EXPECTED_NATIVE_ENTRIES = {
    "base/lib/arm64-v8a/libandroidx.graphics.path.so",
    "base/lib/arm64-v8a/libc++_shared.so",
    "base/lib/arm64-v8a/libduckyslicer.so",
    "base/lib/arm64-v8a/libprusaslicer-jni.so",
}
R8_MAPPING_ENTRY = "BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map"
REQUIRED_DEBUG_SYMBOL_ENTRIES = frozenset(
    {
        "BUNDLE-METADATA/com.android.tools.build.debugsymbols/arm64-v8a/"
        "libduckyslicer.so.dbg",
        "BUNDLE-METADATA/com.android.tools.build.debugsymbols/arm64-v8a/"
        "libprusaslicer-jni.so.dbg",
    }
)
SIGNING_ENVIRONMENT = (
    "DUCKYSLICER_KEYSTORE_FILE",
    "DUCKYSLICER_KEYSTORE_BASE64",
    "DUCKYSLICER_STORE_PASSWORD",
    "DUCKYSLICER_KEY_ALIAS",
    "DUCKYSLICER_KEY_PASSWORD",
    "DUCKYSLICER_PLAY_KEYSTORE_FILE",
    "DUCKYSLICER_PLAY_KEYSTORE_BASE64",
    "DUCKYSLICER_PLAY_STORE_PASSWORD",
    "DUCKYSLICER_PLAY_KEY_ALIAS",
    "DUCKYSLICER_PLAY_KEY_PASSWORD",
)


@dataclass(frozen=True)
class PlayBundleIdentity:
    version_name: str
    version_code: int
    source_commit: str
    transport_tag: str
    unsigned_asset: str
    unsigned_sha256: str
    delivery_apk: str
    delivery_apk_sha256: str

    def document(self) -> dict[str, object]:
        return {
            "schemaVersion": 1,
            "project": "DuckySlicer",
            "packageName": PACKAGE_NAME,
            "versionName": self.version_name,
            "versionCode": self.version_code,
            "sourceCommit": self.source_commit,
            "transportTag": self.transport_tag,
            "unsignedAsset": self.unsigned_asset,
            "unsignedSha256": self.unsigned_sha256,
            "deliveryApk": self.delivery_apk,
            "deliveryApkSha256": self.delivery_apk_sha256,
        }


def play_transport_tag(version_name: str, version_code: int) -> str:
    return f"play-v{version_name}-{version_code}"


def signing_variables(environment: Mapping[str, str]) -> list[str]:
    return sorted(
        name
        for name in SIGNING_ENVIRONMENT
        if environment.get(name, "").strip()
    )


def gradle_play_command(
    version_name: str,
    version_code: int,
    *,
    include_lint: bool,
) -> tuple[str, ...]:
    command = [
        "./gradlew",
        "--dependency-verification=strict",
        "--no-build-cache",
        ":app:clean",
    ]
    if include_lint:
        command.append(":app:lintRelease")
    command.extend(
        (
            ":app:bundleRelease",
            ":app:packageReleaseUniversalApk",
            f"-Pduckyslicer.versionName={version_name}",
            f"-Pduckyslicer.versionCode={version_code}",
        )
    )
    return tuple(command)


def verify_unsigned_bundle(bundle: Path) -> None:
    if not bundle.is_file() or bundle.stat().st_size <= 0:
        raise ReleasePreparationError(f"Unsigned Play bundle is missing: {bundle}")
    try:
        with zipfile.ZipFile(bundle) as archive:
            entries = archive.infolist()
            names = [entry.filename for entry in entries]
            if len(names) != len(set(names)):
                raise ReleasePreparationError("Play bundle contains duplicate ZIP entries")
            for name in names:
                path = PurePosixPath(name)
                if path.is_absolute() or ".." in path.parts or "\\" in name:
                    raise ReleasePreparationError(
                        f"Play bundle contains an unsafe ZIP path: {name}"
                    )
            corrupt = archive.testzip()
            mapping_header = None
            if R8_MAPPING_ENTRY in names:
                with archive.open(R8_MAPPING_ENTRY) as source:
                    mapping_header = source.read(64)
            symbol_headers: dict[str, bytes] = {}
            for name in REQUIRED_DEBUG_SYMBOL_ENTRIES:
                if name in names:
                    with archive.open(name) as source:
                        symbol_headers[name] = source.read(4)
    except (OSError, zipfile.BadZipFile) as error:
        raise ReleasePreparationError(f"Play bundle is not a valid ZIP: {bundle}") from error
    if corrupt is not None:
        raise ReleasePreparationError(f"Play bundle contains a corrupt entry: {corrupt}")
    signatures = sorted(name for name in names if SIGNATURE_BLOCK.fullmatch(name))
    if signatures:
        raise ReleasePreparationError(
            "Local Play candidate must remain unsigned: " + ", ".join(signatures)
        )
    required = {"BundleConfig.pb", "base/manifest/AndroidManifest.xml"}
    missing = sorted(required - set(names))
    if missing:
        raise ReleasePreparationError(
            "Play bundle is missing required entries: " + ", ".join(missing)
        )
    missing_diagnostics = sorted(
        {R8_MAPPING_ENTRY, *REQUIRED_DEBUG_SYMBOL_ENTRIES} - set(names)
    )
    if missing_diagnostics:
        raise ReleasePreparationError(
            "Play bundle is missing production diagnostics: "
            + ", ".join(missing_diagnostics)
        )
    if mapping_header is None or not mapping_header.startswith(b"# compiler: R8\n"):
        raise ReleasePreparationError("Play bundle contains an invalid R8 mapping")
    invalid_symbols = sorted(
        name for name, header in symbol_headers.items() if header != b"\x7fELF"
    )
    if invalid_symbols:
        raise ReleasePreparationError(
            "Play bundle contains invalid native debug symbols: "
            + ", ".join(invalid_symbols)
        )
    native_entries = {name for name in names if name.startswith("base/lib/")}
    if native_entries != EXPECTED_NATIVE_ENTRIES:
        raise ReleasePreparationError(
            "Play bundle native entries differ from the ARM64 allowlist: "
            + ", ".join(sorted(native_entries))
        )


def verify_play_candidate(
    bundle: Path,
    delivery_apk: Path,
    version_name: str,
    version_code: int,
    build_tools: Path,
) -> None:
    verify_unsigned_bundle(bundle)
    verify_unsigned_apk(delivery_apk, version_name, version_code, build_tools)


def write_metadata(path: Path, identity: PlayBundleIdentity) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(identity.document(), indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    os.replace(temporary, path)


def prepare_play_bundle(
    version_name: str,
    version_code: int,
    output_root: Path,
    physical_report: Path,
    startup_report: Path,
    orca_report: Path,
) -> PlayBundleIdentity:
    validate_release_inputs(version_name, version_code)
    exposed_signing = signing_variables(os.environ)
    if exposed_signing:
        raise ReleasePreparationError(
            "Unsigned Play preparation refuses signing variables: "
            + ", ".join(exposed_signing)
        )
    source_commit = verify_checkout()
    verify_release_qualifications(
        physical_report,
        startup_report,
        orca_report,
        source_commit,
    )
    build_tools = android_build_tools(os.environ)
    output = output_root.resolve()
    output.mkdir(parents=True, exist_ok=True)

    unsigned_name = f"DuckySlicer-{version_name}-play-unsigned.aab"
    delivery_name = f"DuckySlicer-{version_name}-play-universal-unsigned.apk"
    metadata_name = f"DuckySlicer-{version_name}-LOCAL-PLAY.json"
    unsigned_output = output / unsigned_name
    delivery_output = output / delivery_name
    metadata_output = output / metadata_name
    candidate_bundle = output / f".{unsigned_name}.candidate"
    candidate_delivery = output / f".{delivery_name}.candidate"
    collisions = [
        path
        for path in (
            unsigned_output,
            delivery_output,
            metadata_output,
            candidate_bundle,
            candidate_delivery,
        )
        if path.exists()
    ]
    if collisions:
        raise ReleasePreparationError(
            "Refusing to overwrite Play output: " + ", ".join(map(str, collisions))
        )

    completed = False
    try:
        run(
            (
                sys.executable,
                str(ROOT / "tools/run_local_gate.py"),
                "--require-api-36",
            )
        )
        run(
            gradle_play_command(version_name, version_code, include_lint=True),
            cwd=ANDROID,
        )
        verify_play_candidate(
            RELEASE_AAB,
            DELIVERY_APK,
            version_name,
            version_code,
            build_tools,
        )
        shutil.copyfile(RELEASE_AAB, candidate_bundle)
        shutil.copyfile(DELIVERY_APK, candidate_delivery)

        run(
            gradle_play_command(version_name, version_code, include_lint=False),
            cwd=ANDROID,
        )
        verify_play_candidate(
            RELEASE_AAB,
            DELIVERY_APK,
            version_name,
            version_code,
            build_tools,
        )
        verify_reproducible(candidate_bundle, RELEASE_AAB)
        verify_reproducible(candidate_delivery, DELIVERY_APK)

        identity = PlayBundleIdentity(
            version_name=version_name,
            version_code=version_code,
            source_commit=source_commit,
            transport_tag=play_transport_tag(version_name, version_code),
            unsigned_asset=unsigned_name,
            unsigned_sha256=sha256(candidate_bundle),
            delivery_apk=delivery_name,
            delivery_apk_sha256=sha256(candidate_delivery),
        )
        os.replace(candidate_bundle, unsigned_output)
        os.replace(candidate_delivery, delivery_output)
        write_metadata(metadata_output, identity)
        completed = True
        return identity
    finally:
        candidate_bundle.unlink(missing_ok=True)
        candidate_delivery.unlink(missing_ok=True)
        if not completed:
            unsigned_output.unlink(missing_ok=True)
            delivery_output.unlink(missing_ok=True)
            metadata_output.unlink(missing_ok=True)
            metadata_output.with_suffix(metadata_output.suffix + ".tmp").unlink(
                missing_ok=True
            )


def main(arguments: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True, help="SemVer without a leading v")
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--physical-report", required=True, type=Path)
    parser.add_argument("--startup-report", required=True, type=Path)
    parser.add_argument("--orca-report", required=True, type=Path)
    parser.add_argument(
        "--output",
        type=Path,
        help="Output directory (default: build/local-play/<version>-<versionCode>)",
    )
    options = parser.parse_args(arguments)
    output = (
        options.output
        or ROOT / "build/local-play" / f"{options.version}-{options.version_code}"
    )
    try:
        identity = prepare_play_bundle(
            options.version,
            options.version_code,
            output,
            options.physical_report,
            options.startup_report,
            options.orca_report,
        )
    except (OSError, ReleasePreparationError, ValueError, zipfile.BadZipFile) as error:
        print(f"Local Play preparation failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(identity.document(), indent=2, sort_keys=True))
    print(f"Local Play candidate prepared in {output.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
