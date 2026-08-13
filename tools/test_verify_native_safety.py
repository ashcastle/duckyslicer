from __future__ import annotations

import unittest

from tools.verify_native_safety import (
    EXPECTED_ENTRYPOINTS,
    VerificationError,
    verify_manifest,
    verify_mutation_regressions,
    verify_source,
)


def native_source(*, unguarded: str | None = None, production_panic: str = "") -> str:
    functions = []
    for name in sorted(EXPECTED_ENTRYPOINTS):
        boundary = "return_null();" if name == unguarded else "guarded_json();"
        functions.append(
            f'#[unsafe(no_mangle)]\npub extern "system" fn {name}() {{ {boundary} }}'
        )
    return (
        "use std::panic::{catch_unwind, AssertUnwindSafe};\n"
        f"{production_panic}\n"
        + "\n".join(functions)
        + "\n#[cfg(test)]\nmod tests { fn panic_is_allowed_here() { panic!(\"test\"); } }\n"
    )


def mutation_regressions() -> str:
    return " ".join(
        (
            "fn deterministic_mutation(",
            "let seeds = [ascii, binary_triangle_stl()]",
            "fn mutated_stl_corpus_never_panics_or_returns_unbounded_geometry()",
            "const CASES_PER_SEED: usize = 192",
            'panic!("STL parser panicked for seed {seed_index}, mutation {case}")',
            "inspection.preview_triangles.len() <= PREVIEW_TRIANGLE_LIMIT",
            "value.abs() <= MAX_STL_COORDINATE_ABS_MM",
            "fn mutated_gcode_corpus_never_panics_or_exceeds_preview_limits()",
            "const CASES: usize = 384",
            'panic!("G-code parser panicked for mutation {case}")',
            "preview.layer_count <= MAX_PREVIEW_LAYERS",
            "preview.segments.len() <= MAX_PREVIEW_SEGMENTS",
        )
    )


class VerifyNativeSafetyTest(unittest.TestCase):
    def test_capability_probe_is_part_of_the_export_contract(self) -> None:
        self.assertIn(
            "Java_com_ashcastle_duckyslicer_NativeEngine_vulkanCapabilities",
            EXPECTED_ENTRYPOINTS,
        )

    def test_accepts_unwind_and_guarded_allowlisted_exports(self) -> None:
        verify_manifest({"profile": {"release": {"panic": "unwind"}}})
        self.assertEqual(len(EXPECTED_ENTRYPOINTS), verify_source(native_source()))

    def test_rejects_abort_release_profile(self) -> None:
        with self.assertRaisesRegex(VerificationError, "panic ="):
            verify_manifest({"profile": {"release": {"panic": "abort"}}})

    def test_rejects_an_unguarded_jni_export(self) -> None:
        target = sorted(EXPECTED_ENTRYPOINTS)[0]
        with self.assertRaisesRegex(VerificationError, "lacks an unwind boundary"):
            verify_source(native_source(unguarded=target))

    def test_rejects_panic_prone_production_shortcuts(self) -> None:
        with self.assertRaisesRegex(VerificationError, "panic-prone"):
            verify_source(native_source(production_panic="fn bad() { panic!(\"bad\"); }"))

    def test_accepts_deterministic_stl_and_gcode_mutation_regressions(self) -> None:
        verify_mutation_regressions(mutation_regressions())

    def test_rejects_missing_gcode_segment_bound(self) -> None:
        source = mutation_regressions().replace(
            "preview.segments.len() <= MAX_PREVIEW_SEGMENTS", ""
        )
        with self.assertRaisesRegex(VerificationError, "G-code segment bound"):
            verify_mutation_regressions(source)

    def test_rejects_stl_mutations_without_a_binary_seed(self) -> None:
        source = mutation_regressions().replace(
            "let seeds = [ascii, binary_triangle_stl()]", "let seeds = [ascii]"
        )
        with self.assertRaisesRegex(VerificationError, "ASCII and binary STL seeds"):
            verify_mutation_regressions(source)


if __name__ == "__main__":
    unittest.main()
