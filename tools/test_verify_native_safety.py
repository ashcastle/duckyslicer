from __future__ import annotations

import unittest

from tools.verify_native_safety import (
    EXPECTED_ENTRYPOINTS,
    VerificationError,
    verify_bounded_core_version,
    verify_fixed_native_text,
    verify_manifest,
    verify_mutation_regressions,
    verify_patch_boundaries,
    verify_source,
    verify_vulkan_abi_layout,
)


def native_source(
    *,
    unguarded: str | None = None,
    production_panic: str = "",
    generic_lifetime: bool = False,
) -> str:
    functions = []
    for name in sorted(EXPECTED_ENTRYPOINTS):
        boundary = "return_null();" if name == unguarded else "guarded_json();"
        generics = "<'local>" if generic_lifetime else ""
        functions.append(
            f'#[unsafe(no_mangle)]\npub extern "system" fn {name}{generics}() {{ {boundary} }}'
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

    def test_accepts_named_lifetimes_on_ffi_safe_jni_exports(self) -> None:
        self.assertEqual(
            len(EXPECTED_ENTRYPOINTS),
            verify_source(native_source(generic_lifetime=True)),
        )

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

    def test_accepts_bounded_fixed_native_text_decoding(self) -> None:
        verify_fixed_native_text(
            """
fn native_text(value: &[c_char]) -> String {
    let end = value.iter().position(|character| *character == 0).unwrap_or(value.len());
    let bytes = value[..end].iter().map(|character| *character as u8).collect::<Vec<_>>();
    String::from_utf8_lossy(&bytes).into_owned()
}
fn probe_vulkan() {}
fn native_text_never_reads_past_a_fixed_native_buffer() {}
"""
        )

    def test_rejects_unbounded_fixed_native_text_pointer_reads(self) -> None:
        with self.assertRaisesRegex(VerificationError, "unbounded pointer read"):
            verify_fixed_native_text(
                """
fn native_text(value: &[c_char]) -> String {
    unsafe { CStr::from_ptr(value.as_ptr()) }.to_string_lossy().into_owned()
}
fn probe_vulkan() {}
fn native_text_never_reads_past_a_fixed_native_buffer() {}
"""
            )

    def test_accepts_bounded_core_version_abi(self) -> None:
        verify_bounded_core_version(
            """
unsafe extern "C" {
    fn duckyslicer_core_version(output: *mut c_char, capacity: usize) -> usize;
}
fn core_version_text() {
    let mut buffer = [0 as c_char; CORE_VERSION_BUFFER_BYTES];
    let required = unsafe { duckyslicer_core_version(buffer.as_mut_ptr(), buffer.len()) };
    if required >= buffer.len() { return; }
}
#[cfg(test)]
fn core_version_uses_a_bounded_c_abi_buffer() {}
""",
            "size_t duckyslicer_core_version(char* output, size_t capacity);",
            """
size_t duckyslicer_core_version(char* output, size_t capacity) {
    if (output != nullptr && capacity > 0) {
        const size_t copied = std::min(length, capacity - 1);
        output[copied] = '\\0';
    }
}
""",
        )

    def test_rejects_borrowed_core_version_pointer(self) -> None:
        with self.assertRaisesRegex(VerificationError, "unbounded C string pointer"):
            verify_bounded_core_version(
                "fn core() { unsafe { CStr::from_ptr(pointer) }; }\n#[cfg(test)]",
                "const char* duckyslicer_core_version(void);",
                'const char* duckyslicer_core_version(void) { return "version"; }',
            )

    def test_accepts_matching_vulkan_abi_layout_guards(self) -> None:
        verify_vulkan_abi_layout(
            """
#[repr(C)]
struct VulkanCapabilitiesNative {}
fn vulkan_capabilities_c_abi_layout_is_stable() {
    assert_eq!(std::mem::size_of::<VulkanCapabilitiesNative>(), 424);
    assert_eq!(std::mem::offset_of!(VulkanCapabilitiesNative, reason), 294);
}
""",
            """
static_assert(sizeof(duckyslicer_vulkan_capabilities) == 424);
static_assert(offsetof(duckyslicer_vulkan_capabilities, compute_queue_family) == 28);
static_assert(offsetof(duckyslicer_vulkan_capabilities, device_name) == 38);
static_assert(offsetof(duckyslicer_vulkan_capabilities, reason) == 294);
""",
        )

    def test_rejects_vulkan_abi_without_cpp_size_guard(self) -> None:
        with self.assertRaisesRegex(VerificationError, "C\\+\\+ size assertion"):
            verify_vulkan_abi_layout(
                """
#[repr(C)]
struct VulkanCapabilitiesNative {}
fn vulkan_capabilities_c_abi_layout_is_stable() {
    assert_eq!(std::mem::size_of::<VulkanCapabilitiesNative>(), 424);
    assert_eq!(std::mem::offset_of!(VulkanCapabilitiesNative, reason), 294);
}
""",
                """
static_assert(offsetof(duckyslicer_vulkan_capabilities, compute_queue_family) == 28);
static_assert(offsetof(duckyslicer_vulkan_capabilities, device_name) == 38);
static_assert(offsetof(duckyslicer_vulkan_capabilities, reason) == 294);
""",
            )

    def test_accepts_repository_relative_patch_ownership(self) -> None:
        self.assertEqual(
            2,
            verify_patch_boundaries(
                {
                    "runtime.patch": "diff --git a/app/src/main/cpp/src/a.cpp b/app/src/main/cpp/src/a.cpp\n",
                    "engine-feature.patch": "diff --git a/src/libslic3r/a.cpp b/src/libslic3r/a.cpp\n",
                }
            ),
        )

    def test_rejects_runtime_patch_crossing_engine_submodule(self) -> None:
        with self.assertRaisesRegex(VerificationError, "crosses the Orca submodule"):
            verify_patch_boundaries(
                {
                    "mixed.patch": (
                        "diff --git a/app/src/main/cpp/orcaslicer/src/a.cpp "
                        "b/app/src/main/cpp/orcaslicer/src/a.cpp\n"
                    )
                }
            )

    def test_rejects_engine_patch_with_runtime_rooted_paths(self) -> None:
        with self.assertRaisesRegex(VerificationError, "Orca-root-relative"):
            verify_patch_boundaries(
                {
                    "engine-mixed.patch": (
                        "diff --git a/app/src/main/cpp/orcaslicer/src/a.cpp "
                        "b/app/src/main/cpp/orcaslicer/src/a.cpp\n"
                    )
                }
            )


if __name__ == "__main__":
    unittest.main()
