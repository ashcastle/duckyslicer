#!/usr/bin/env python3
"""Verify DuckySlicer's release-critical APK structure without host ELF tools."""

from __future__ import annotations

import argparse
import re
import struct
import sys
import zipfile
from pathlib import Path, PurePosixPath


ANDROID_ARM64_MACHINE = 183
ELF64_HEADER_SIZE = 64
ELF64_PROGRAM_HEADER_SIZE = 56
ELF64_SECTION_HEADER_SIZE = 64
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
    "assets/legal/PRIVACY.md": (
        b"DuckySlicer Privacy Policy",
        b"does not collect, sell, or share",
        b"Optional OctoPrint and Klipper connections",
        b"Retention and deletion",
        "DuckySlicer 개인정보처리방침".encode("utf-8"),
    ),
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
REQUIRED_RUNTIME_PROFILES = {
    "assets/dexopt/baseline.prof": (b"pro\x00010\x00", 1_024),
    "assets/dexopt/baseline.profm": (b"prm\x00002\x00", 100),
}


class VerificationError(ValueError):
    """The APK violates a release invariant."""


def elf_section_names(data: bytes, name: str) -> frozenset[str]:
    section_offset = struct.unpack_from("<Q", data, 40)[0]
    section_entry_size = struct.unpack_from("<H", data, 58)[0]
    section_count = struct.unpack_from("<H", data, 60)[0]
    string_table_index = struct.unpack_from("<H", data, 62)[0]
    if section_offset == 0 and section_count == 0:
        return frozenset()
    if section_entry_size < ELF64_SECTION_HEADER_SIZE or section_count == 0:
        raise VerificationError(f"{name}: invalid ELF section-header table")
    table_end = section_offset + section_entry_size * section_count
    if section_offset < ELF64_HEADER_SIZE or table_end > len(data):
        raise VerificationError(f"{name}: truncated ELF section-header table")
    if string_table_index >= section_count:
        raise VerificationError(f"{name}: invalid ELF section-name table index")

    string_header = section_offset + string_table_index * section_entry_size
    strings_offset = struct.unpack_from("<Q", data, string_header + 24)[0]
    strings_size = struct.unpack_from("<Q", data, string_header + 32)[0]
    strings_end = strings_offset + strings_size
    if strings_end > len(data):
        raise VerificationError(f"{name}: truncated ELF section-name table")
    strings = data[strings_offset:strings_end]

    names: set[str] = set()
    for index in range(section_count):
        header = section_offset + index * section_entry_size
        name_offset = struct.unpack_from("<I", data, header)[0]
        if name_offset >= len(strings):
            raise VerificationError(f"{name}: invalid ELF section-name offset")
        terminator = strings.find(b"\0", name_offset)
        if terminator < 0:
            raise VerificationError(f"{name}: unterminated ELF section name")
        try:
            names.add(strings[name_offset:terminator].decode("ascii"))
        except UnicodeDecodeError as error:
            raise VerificationError(f"{name}: non-ASCII ELF section name") from error
    return frozenset(names)


def inspect_legal_assets(entries: dict[str, bytes]) -> None:
    for name, markers in REQUIRED_LEGAL_ASSETS.items():
        data = entries.get(name)
        if data is None:
            raise VerificationError(f"APK is missing required legal asset: {name}")
        if not all(marker in data for marker in markers):
            raise VerificationError(f"APK legal asset is incomplete or stale: {name}")


def inspect_runtime_profiles(entries: dict[str, bytes]) -> None:
    for name, (magic, minimum_size) in REQUIRED_RUNTIME_PROFILES.items():
        data = entries.get(name)
        if data is None:
            raise VerificationError(f"APK is missing compiled app startup profile: {name}")
        if len(data) < minimum_size or not data.startswith(magic):
            raise VerificationError(f"APK startup profile is invalid or empty: {name}")


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
    forbidden_sections = sorted(
        section
        for section in elf_section_names(data, name)
        if section == ".symtab"
        or section.startswith(".debug_")
        or section.startswith(".zdebug_")
    )
    if forbidden_sections:
        raise VerificationError(
            f"{name}: packaged library contains unstripped debug sections: "
            + ", ".join(forbidden_sections)
        )
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


def verify_apk(apk: Path, *, require_runtime_profiles: bool = False) -> tuple[int, int]:
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
        if require_runtime_profiles:
            inspect_runtime_profiles(
                {
                    name: archive.read(name)
                    for name in REQUIRED_RUNTIME_PROFILES
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
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--require-runtime-profiles", action="store_true")
    arguments = parser.parse_args()
    apk = arguments.apk.resolve()
    try:
        library_count, load_count = verify_apk(
            apk,
            require_runtime_profiles=arguments.require_runtime_profiles,
        )
    except (OSError, zipfile.BadZipFile, VerificationError) as error:
        raise SystemExit(f"APK verification failed: {error}") from error
    profile_summary = (
        "compiled startup profiles, " if arguments.require_runtime_profiles else ""
    )
    print(
        f"Verified {apk}: {library_count} allowlisted ARM64 libraries, "
        f"{load_count} 16 KB-compatible LOAD segments, one binary profile catalog, "
        f"{profile_summary}and offline privacy and legal materials"
    )


if __name__ == "__main__":
    main()
