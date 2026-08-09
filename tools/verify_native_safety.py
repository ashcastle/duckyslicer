#!/usr/bin/env python3
"""Verify the checked-in Rust/JNI failure-containment contract."""

from __future__ import annotations

import re
import tomllib
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "rust/duckyslicer-jni/Cargo.toml"
SOURCE = ROOT / "rust/duckyslicer-jni/src/lib.rs"
JNI_EXPORT = re.compile(
    r'#\[unsafe\(no_mangle\)\]\s*pub extern "system" fn\s+(Java_[A-Za-z0-9_]+)\s*\(',
    re.MULTILINE,
)
FORBIDDEN_PRODUCTION_PANIC = re.compile(
    r"\.(?:unwrap|expect)\s*\(|\b(?:panic|todo|unreachable)\s*!\s*\("
)
EXPECTED_ENTRYPOINTS = {
    "Java_com_ashcastle_duckyslicer_NativeEngine_version",
    "Java_com_ashcastle_duckyslicer_NativeEngine_inspectStl",
    "Java_com_ashcastle_duckyslicer_NativeEngine_transformStl",
    "Java_com_ashcastle_duckyslicer_NativeEngine_previewGcodeRange",
}


class VerificationError(ValueError):
    """The Rust/JNI safety contract is incomplete or was weakened."""


def verify_manifest(document: dict[str, object]) -> None:
    profile = document.get("profile")
    if not isinstance(profile, dict):
        raise VerificationError("Cargo manifest has no profile table")
    release = profile.get("release")
    if not isinstance(release, dict) or release.get("panic") != "unwind":
        raise VerificationError("Rust release profile must keep panic = \"unwind\"")


def verify_source(source: str) -> int:
    production, separator, _ = source.partition("#[cfg(test)]")
    if not separator:
        raise VerificationError("Rust source has no explicit production/test boundary")
    forbidden = FORBIDDEN_PRODUCTION_PANIC.search(production)
    if forbidden is not None:
        line = production.count("\n", 0, forbidden.start()) + 1
        raise VerificationError(f"panic-prone production construct at lib.rs:{line}")
    if "catch_unwind" not in production or "AssertUnwindSafe" not in production:
        raise VerificationError("Rust production source has no unwind containment helper")

    exports = list(JNI_EXPORT.finditer(production))
    names = {match.group(1) for match in exports}
    if names != EXPECTED_ENTRYPOINTS or len(exports) != len(EXPECTED_ENTRYPOINTS):
        raise VerificationError(
            "JNI export allowlist mismatch; "
            f"missing={sorted(EXPECTED_ENTRYPOINTS - names)}, "
            f"unexpected={sorted(names - EXPECTED_ENTRYPOINTS)}"
        )
    for index, export in enumerate(exports):
        end = exports[index + 1].start() if index + 1 < len(exports) else len(production)
        body = production[export.end() : end]
        if "guarded_json(" not in body and "catch_unwind(" not in body:
            raise VerificationError(f"JNI export lacks an unwind boundary: {export.group(1)}")
    return len(exports)


def main() -> None:
    try:
        manifest = tomllib.loads(MANIFEST.read_text(encoding="utf-8"))
        source = SOURCE.read_text(encoding="utf-8")
        verify_manifest(manifest)
        entrypoint_count = verify_source(source)
    except (OSError, tomllib.TOMLDecodeError, VerificationError) as error:
        raise SystemExit(f"Native safety verification failed: {error}") from error
    print(
        f"Verified Rust/JNI safety: panic=unwind, {entrypoint_count} allowlisted "
        "entrypoints contained, no panic-prone production shortcuts"
    )


if __name__ == "__main__":
    main()
