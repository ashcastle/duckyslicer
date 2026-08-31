#!/usr/bin/env python3
"""Verify checked-in native failure containment and patch ownership."""

from __future__ import annotations

import re
import shlex
import tomllib
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "rust/duckyslicer-jni/Cargo.toml"
SOURCE = ROOT / "rust/duckyslicer-jni/src/lib.rs"
PATCH_ROOT = ROOT / "native/slicer-runtime"
RUNTIME_SOURCE_PREFIX = "app/src/main/cpp/"
ENGINE_SUBMODULE_PREFIX = "app/src/main/cpp/orcaslicer/"
JNI_EXPORT = re.compile(
    r'#\[unsafe\(no_mangle\)\]\s*pub extern "system" fn\s+'
    r'(Java_[A-Za-z0-9_]+)(?:\s*<[^>\n]+>)?\s*\(',
    re.MULTILINE,
)
FORBIDDEN_PRODUCTION_PANIC = re.compile(
    r"\.(?:unwrap|expect)\s*\(|\b(?:panic|todo|unreachable)\s*!\s*\("
)
EXPECTED_ENTRYPOINTS = {
    "Java_com_ashcastle_duckyslicer_NativeEngine_version",
    "Java_com_ashcastle_duckyslicer_NativeEngine_vulkanCapabilities",
    "Java_com_ashcastle_duckyslicer_NativeEngine_inspectStlPayload",
    "Java_com_ashcastle_duckyslicer_NativeEngine_transformStl",
    "Java_com_ashcastle_duckyslicer_NativeEngine_transformStlGroup",
    "Java_com_ashcastle_duckyslicer_NativeEngine_layOnFace",
    "Java_com_ashcastle_duckyslicer_NativeEngine_previewGcodeRangeInto",
    "Java_com_ashcastle_duckyslicer_NativeEngine_packToolpathGeometry",
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


def verify_mutation_regressions(source: str) -> None:
    markers = {
        "deterministic byte mutator": "fn deterministic_mutation(",
        "ASCII and binary STL seeds": "let seeds = [ascii, binary_triangle_stl()]",
        "STL mutation corpus": "fn mutated_stl_corpus_never_panics_or_returns_unbounded_geometry()",
        "STL mutation count": "const CASES_PER_SEED: usize = 192",
        "STL panic containment": 'panic!("STL parser panicked for seed {seed_index}, mutation {case}")',
        "STL preview bound": "inspection.preview_triangles.len() <= PREVIEW_TRIANGLE_LIMIT",
        "STL coordinate bound": "value.abs() <= MAX_STL_COORDINATE_ABS_MM",
        "G-code mutation corpus": "fn mutated_gcode_corpus_never_panics_or_exceeds_preview_limits()",
        "G-code mutation count": "const CASES: usize = 384",
        "G-code panic containment": 'panic!("G-code parser panicked for mutation {case}")',
        "G-code layer bound": "preview.layer_count <= MAX_PREVIEW_LAYERS",
        "G-code segment bound": "preview.segments.len() <= MAX_PREVIEW_SEGMENTS",
    }
    missing = [description for description, marker in markers.items() if marker not in source]
    if missing:
        raise VerificationError(
            "deterministic malformed-input regressions are incomplete: " + ", ".join(missing)
        )


def verify_fixed_native_text(source: str) -> None:
    start = source.find("fn native_text(value: &[c_char]) -> String {")
    end = source.find("\nfn probe_vulkan()", start)
    if start < 0 or end < 0:
        raise VerificationError("fixed native text decoder is missing")
    body = source[start:end]
    if "CStr::from_ptr" in body or "from_raw_parts" in body:
        raise VerificationError("fixed native text decoder performs an unbounded pointer read")
    markers = {
        "in-buffer terminator search": ".position(|character| *character == 0)",
        "unterminated-buffer bound": ".unwrap_or(value.len())",
        "bounded UTF-8 conversion": "String::from_utf8_lossy(&bytes)",
        "unterminated-buffer regression": (
            "fn native_text_never_reads_past_a_fixed_native_buffer()"
        ),
    }
    missing = [description for description, marker in markers.items() if marker not in source]
    if missing:
        raise VerificationError(
            "fixed native text decoder contract is incomplete: " + ", ".join(missing)
        )


def verify_patch_boundaries(patches: dict[str, str]) -> int:
    """Keep runtime and nested Orca changes in independently reviewable patches."""
    checked = 0
    for name, source in sorted(patches.items()):
        paths = []
        for line in source.splitlines():
            if not line.startswith("diff --git "):
                continue
            fields = shlex.split(line)
            if len(fields) >= 4 and fields[2].startswith("a/"):
                paths.append(fields[2][2:])
        if not paths:
            raise VerificationError(f"native patch has no file changes: {name}")
        if name.startswith("engine-"):
            rooted = [path for path in paths if path.startswith(RUNTIME_SOURCE_PREFIX)]
            if rooted:
                raise VerificationError(
                    f"engine patch must use Orca-root-relative paths: {name}: {rooted[0]}"
                )
        else:
            nested = [path for path in paths if path.startswith(ENGINE_SUBMODULE_PREFIX)]
            if nested:
                raise VerificationError(
                    f"runtime patch crosses the Orca submodule boundary: {name}: {nested[0]}"
                )
        checked += 1
    return checked


def main() -> None:
    try:
        manifest = tomllib.loads(MANIFEST.read_text(encoding="utf-8"))
        source = SOURCE.read_text(encoding="utf-8")
        verify_manifest(manifest)
        entrypoint_count = verify_source(source)
        verify_mutation_regressions(source)
        verify_fixed_native_text(source)
        patch_count = verify_patch_boundaries(
            {
                path.name: path.read_text(encoding="utf-8")
                for path in PATCH_ROOT.glob("*.patch")
            }
        )
    except (OSError, tomllib.TOMLDecodeError, VerificationError) as error:
        raise SystemExit(f"Native safety verification failed: {error}") from error
    print(
        f"Verified native safety: panic=unwind, {entrypoint_count} allowlisted "
        "entrypoints contained, bounded native text, no panic-prone production shortcuts, "
        "deterministic "
        f"STL/G-code mutation regressions present, {patch_count} patch boundaries owned"
    )


if __name__ == "__main__":
    main()
