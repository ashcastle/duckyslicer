#!/usr/bin/env python3
"""Verify DuckySlicer's release-critical APK structure without host ELF tools."""

from __future__ import annotations

import re
import struct
import sys
import zipfile
from pathlib import Path, PurePosixPath


ANDROID_ARM64_MACHINE = 183
ELF64_HEADER_SIZE = 64
ELF64_PROGRAM_HEADER_SIZE = 56
PAGE_ALIGNMENT = 0x4000
PT_LOAD = 1
ZIP_LOCAL_HEADER_SIZE = 30
ZIP_LOCAL_HEADER_SIGNATURE = 0x04034B50

ALLOWED_NATIVE_LIBRARIES = frozenset(
    {
        "lib/arm64-v8a/libandroidx.graphics.path.so",
        "lib/arm64-v8a/libc++_shared.so",
        "lib/arm64-v8a/libduckyslicer.so",
        "lib/arm64-v8a/libprusaslicer-jni.so",
    }
)
REQUIRED_LEGAL_ASSETS = {
    "assets/legal/AGPL-3.0.txt": (
        b"GNU AFFERO GENERAL PUBLIC LICENSE",
        b"Version 3, 19 November 2007",
        b"WITHOUT ANY WARRANTY",
    ),
    "assets/legal/THIRD_PARTY_NOTICES.md": (
        b"Third-party notices",
        b"6f64367361c4bd56bacc97a991874ce1f4b837b4",
        b"2c8a5385bc53cbc16211b4dd36ef9963ee185f4a",
    ),
    "assets/legal/THIRD_PARTY_LICENSES.txt": (
        b"DuckySlicer third-party licenses",
        b"Resolved component index",
        b"native:orca-vendored-admesh@",
        b"pkg:cargo/jni@",
        b"pkg:maven/androidx.activity/activity-compose@",
        b"Apache License",
        b"GNU LESSER GENERAL PUBLIC LICENSE",
        b"Boost Software License",
    ),
}


class VerificationError(ValueError):
    """The APK violates a release invariant."""


def inspect_legal_assets(entries: dict[str, bytes]) -> None:
    for name, markers in REQUIRED_LEGAL_ASSETS.items():
        data = entries.get(name)
        if data is None:
            raise VerificationError(f"APK is missing required legal asset: {name}")
        if not all(marker in data for marker in markers):
            raise VerificationError(f"APK legal asset is incomplete or stale: {name}")


def inspect_elf(data: bytes, name: str) -> int:
    if len(data) < ELF64_HEADER_SIZE or data[:4] != b"\x7fELF":
        raise VerificationError(f"{name}: not an ELF file")
    if data[4] != 2 or data[5] != 1:
        raise VerificationError(f"{name}: expected a little-endian ELF64 library")
    machine = struct.unpack_from("<H", data, 18)[0]
    if machine != ANDROID_ARM64_MACHINE:
        raise VerificationError(f"{name}: expected AArch64 machine {ANDROID_ARM64_MACHINE}, got {machine}")

    program_offset = struct.unpack_from("<Q", data, 32)[0]
    program_entry_size = struct.unpack_from("<H", data, 54)[0]
    program_count = struct.unpack_from("<H", data, 56)[0]
    if program_entry_size < ELF64_PROGRAM_HEADER_SIZE:
        raise VerificationError(f"{name}: invalid ELF program-header size")
    table_end = program_offset + program_entry_size * program_count
    if program_offset < ELF64_HEADER_SIZE or table_end > len(data):
        raise VerificationError(f"{name}: truncated ELF program-header table")

    load_count = 0
    for index in range(program_count):
        offset = program_offset + index * program_entry_size
        program_type = struct.unpack_from("<I", data, offset)[0]
        if program_type != PT_LOAD:
            continue
        file_offset = struct.unpack_from("<Q", data, offset + 8)[0]
        virtual_address = struct.unpack_from("<Q", data, offset + 16)[0]
        file_size = struct.unpack_from("<Q", data, offset + 32)[0]
        alignment = struct.unpack_from("<Q", data, offset + 48)[0]
        if alignment < PAGE_ALIGNMENT or alignment & (alignment - 1):
            raise VerificationError(
                f"{name}: LOAD segment {index} alignment is {alignment:#x}, expected at least {PAGE_ALIGNMENT:#x}"
            )
        if file_offset % alignment != virtual_address % alignment:
            raise VerificationError(f"{name}: LOAD segment {index} has invalid file/virtual alignment")
        if file_offset + file_size > len(data):
            raise VerificationError(f"{name}: LOAD segment {index} exceeds the library size")
        load_count += 1
    if load_count == 0:
        raise VerificationError(f"{name}: contains no LOAD segments")
    return load_count


def zip_data_offset(apk: Path, entry: zipfile.ZipInfo) -> int:
    with apk.open("rb") as source:
        source.seek(entry.header_offset)
        header = source.read(ZIP_LOCAL_HEADER_SIZE)
    if len(header) != ZIP_LOCAL_HEADER_SIZE:
        raise VerificationError(f"{entry.filename}: truncated ZIP local header")
    signature = struct.unpack_from("<I", header)[0]
    if signature != ZIP_LOCAL_HEADER_SIGNATURE:
        raise VerificationError(f"{entry.filename}: invalid ZIP local header")
    name_length, extra_length = struct.unpack_from("<HH", header, 26)
    return entry.header_offset + ZIP_LOCAL_HEADER_SIZE + name_length + extra_length


def verify_apk(apk: Path) -> tuple[int, int]:
    if not apk.is_file():
        raise VerificationError(f"APK is unavailable: {apk}")
    with zipfile.ZipFile(apk) as archive:
        entries = archive.infolist()
        names = [entry.filename for entry in entries]
        if len(names) != len(set(names)):
            raise VerificationError("APK contains duplicate ZIP entry names")
        for name in names:
            path = PurePosixPath(name)
            if path.is_absolute() or ".." in path.parts or "\\" in name:
                raise VerificationError(f"APK contains an unsafe ZIP path: {name}")

        native_entries = {entry.filename: entry for entry in entries if entry.filename.endswith(".so")}
        native_names = frozenset(native_entries)
        if native_names != ALLOWED_NATIVE_LIBRARIES:
            missing = sorted(ALLOWED_NATIVE_LIBRARIES - native_names)
            unexpected = sorted(native_names - ALLOWED_NATIVE_LIBRARIES)
            raise VerificationError(
                f"native library allowlist mismatch; missing={missing}, unexpected={unexpected}"
            )

        catalog_entries = [
            name
            for name in names
            if name.startswith("assets/profile_catalog_v")
        ]
        if len(catalog_entries) != 1 or re.fullmatch(
            r"assets/profile_catalog_v[0-9]+\.bin", catalog_entries[0]
        ) is None:
            raise VerificationError(
                f"expected one binary profile catalog, found {sorted(catalog_entries)}"
            )

        inspect_legal_assets(
            {
                name: archive.read(name)
                for name in REQUIRED_LEGAL_ASSETS
                if name in names
            }
        )

        load_segments = 0
        for name, entry in sorted(native_entries.items()):
            if entry.compress_type != zipfile.ZIP_STORED:
                raise VerificationError(f"{name}: native library must be stored uncompressed")
            data_offset = zip_data_offset(apk, entry)
            if data_offset % PAGE_ALIGNMENT:
                raise VerificationError(
                    f"{name}: APK data offset {data_offset:#x} is not {PAGE_ALIGNMENT:#x}-aligned"
                )
            load_segments += inspect_elf(archive.read(entry), name)
    return len(native_entries), load_segments


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: verify_apk.py APK")
    apk = Path(sys.argv[1]).resolve()
    try:
        library_count, load_count = verify_apk(apk)
    except (OSError, zipfile.BadZipFile, VerificationError) as error:
        raise SystemExit(f"APK verification failed: {error}") from error
    print(
        f"Verified {apk}: {library_count} allowlisted ARM64 libraries, "
        f"{load_count} 16 KB-compatible LOAD segments, one binary profile catalog, "
        "and offline legal notices"
    )


if __name__ == "__main__":
    main()
