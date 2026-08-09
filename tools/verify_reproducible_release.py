#!/usr/bin/env python3
"""Require two independently assembled unsigned APKs to be byte-for-byte equal."""

from __future__ import annotations

import hashlib
import sys
import zipfile
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def zip_differences(first: Path, second: Path) -> list[str]:
    try:
        with zipfile.ZipFile(first) as first_zip, zipfile.ZipFile(second) as second_zip:
            first_entries = {entry.filename: entry for entry in first_zip.infolist()}
            second_entries = {entry.filename: entry for entry in second_zip.infolist()}
            differences: list[str] = []
            for name in sorted(first_entries.keys() - second_entries.keys()):
                differences.append(f"only in first APK: {name}")
            for name in sorted(second_entries.keys() - first_entries.keys()):
                differences.append(f"only in second APK: {name}")
            for name in sorted(first_entries.keys() & second_entries.keys()):
                left = first_entries[name]
                right = second_entries[name]
                metadata = (
                    left.date_time,
                    left.compress_type,
                    left.external_attr,
                    left.extra,
                    left.comment,
                )
                other_metadata = (
                    right.date_time,
                    right.compress_type,
                    right.external_attr,
                    right.extra,
                    right.comment,
                )
                if metadata != other_metadata:
                    differences.append(f"ZIP metadata changed: {name}")
                if left.CRC != right.CRC or left.file_size != right.file_size:
                    differences.append(f"content changed: {name}")
                elif first_zip.read(left) != second_zip.read(right):
                    differences.append(f"content changed despite matching CRC: {name}")
            if first_zip.comment != second_zip.comment:
                differences.append("ZIP archive comment changed")
            return differences
    except zipfile.BadZipFile as error:
        return [f"could not inspect APK ZIP entries: {error}"]


def verify_reproducible(first: Path, second: Path) -> str:
    if not first.is_file() or not second.is_file():
        missing = [str(path) for path in (first, second) if not path.is_file()]
        raise ValueError(f"missing APK input: {', '.join(missing)}")
    first_hash = sha256(first)
    second_hash = sha256(second)
    if first_hash != second_hash:
        details = zip_differences(first, second)
        detail_text = "\n- ".join(details[:25]) or "container bytes changed"
        raise ValueError(
            "unsigned release is not reproducible: "
            f"{first.name}={first_hash}, {second.name}={second_hash}\n- {detail_text}"
        )
    return first_hash


def main(argv: list[str]) -> None:
    if len(argv) != 3:
        raise SystemExit(f"usage: {argv[0]} FIRST_UNSIGNED.apk SECOND_UNSIGNED.apk")
    try:
        digest = verify_reproducible(Path(argv[1]), Path(argv[2]))
    except ValueError as error:
        raise SystemExit(str(error)) from error
    print(f"Reproducible unsigned APK: sha256={digest}")


if __name__ == "__main__":
    main(sys.argv)
