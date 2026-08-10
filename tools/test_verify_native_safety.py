from __future__ import annotations

import unittest

from tools.verify_native_safety import (
    EXPECTED_ENTRYPOINTS,
    VerificationError,
    verify_manifest,
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


if __name__ == "__main__":
    unittest.main()
