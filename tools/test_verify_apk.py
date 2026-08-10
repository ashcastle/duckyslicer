from __future__ import annotations

import struct
import unittest

from tools.verify_apk import (
    PAGE_ALIGNMENT,
    REQUIRED_LEGAL_ASSETS,
    VerificationError,
    inspect_elf,
    inspect_legal_assets,
)


def synthetic_elf(*, machine: int = 183, alignment: int = PAGE_ALIGNMENT) -> bytes:
    data = bytearray(64 + 56)
    data[:16] = b"\x7fELF\x02\x01\x01" + bytes(9)
    struct.pack_into("<H", data, 16, 3)
    struct.pack_into("<H", data, 18, machine)
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<H", data, 52, 64)
    struct.pack_into("<H", data, 54, 56)
    struct.pack_into("<H", data, 56, 1)
    struct.pack_into("<I", data, 64, 1)
    struct.pack_into("<Q", data, 64 + 8, 0)
    struct.pack_into("<Q", data, 64 + 16, 0)
    struct.pack_into("<Q", data, 64 + 32, len(data))
    struct.pack_into("<Q", data, 64 + 40, len(data))
    struct.pack_into("<Q", data, 64 + 48, alignment)
    return bytes(data)


def synthetic_elf_with_debug_section() -> bytes:
    data = bytearray(synthetic_elf())
    section_offset = len(data)
    section_count = 3
    data.extend(bytes(64 * section_count))
    strings = b"\0.shstrtab\0.debug_info\0"
    strings_offset = len(data)
    data.extend(strings)
    struct.pack_into("<Q", data, 40, section_offset)
    struct.pack_into("<H", data, 58, 64)
    struct.pack_into("<H", data, 60, section_count)
    struct.pack_into("<H", data, 62, 1)

    string_header = section_offset + 64
    struct.pack_into("<I", data, string_header, 1)
    struct.pack_into("<I", data, string_header + 4, 3)
    struct.pack_into("<Q", data, string_header + 24, strings_offset)
    struct.pack_into("<Q", data, string_header + 32, len(strings))

    debug_header = section_offset + 128
    struct.pack_into("<I", data, debug_header, 11)
    struct.pack_into("<I", data, debug_header + 4, 1)
    return bytes(data)


class VerifyApkTest(unittest.TestCase):
    def test_accepts_16_kb_aarch64_load_segment(self) -> None:
        self.assertEqual(1, inspect_elf(synthetic_elf(), "valid.so"))

    def test_rejects_4_kb_load_segment(self) -> None:
        with self.assertRaisesRegex(VerificationError, "alignment is 0x1000"):
            inspect_elf(synthetic_elf(alignment=0x1000), "legacy.so")

    def test_rejects_non_aarch64_library(self) -> None:
        with self.assertRaisesRegex(VerificationError, "expected AArch64"):
            inspect_elf(synthetic_elf(machine=62), "x86_64.so")

    def test_rejects_truncated_program_header_table(self) -> None:
        with self.assertRaisesRegex(VerificationError, "truncated ELF"):
            inspect_elf(synthetic_elf()[:-1], "truncated.so")

    def test_rejects_unstripped_debug_sections_in_packaged_library(self) -> None:
        with self.assertRaisesRegex(VerificationError, "unstripped debug sections"):
            inspect_elf(synthetic_elf_with_debug_section(), "debug.so")

    def test_accepts_complete_offline_legal_assets(self) -> None:
        inspect_legal_assets(
            {
                name: b"\n".join(markers)
                for name, markers in REQUIRED_LEGAL_ASSETS.items()
            }
        )

    def test_rejects_missing_or_stale_offline_legal_assets(self) -> None:
        complete = {
            name: b"\n".join(markers)
            for name, markers in REQUIRED_LEGAL_ASSETS.items()
        }
        for invalid in (
            {name: data for name, data in complete.items() if not name.endswith("AGPL-3.0.txt")},
            {**complete, "assets/legal/PRIVACY.md": b"stale"},
            {**complete, "assets/legal/THIRD_PARTY_NOTICES.md": b"stale"},
        ):
            with self.assertRaisesRegex(VerificationError, "legal asset"):
                inspect_legal_assets(invalid)


if __name__ == "__main__":
    unittest.main()
