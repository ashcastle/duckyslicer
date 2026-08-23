from __future__ import annotations

import hashlib
import io
import tarfile
import tempfile
import unittest
from pathlib import Path

from tools.native_license_policy import (
    ARCHIVE_COMPONENTS,
    ARCHIVE_NOTICE_PATHS,
    NativeLicenseError,
    NativePolicy,
    _resolve_archive_notices,
)


class NativeLicensePolicyTest(unittest.TestCase):
    def _write_archive(
        self,
        root: Path,
        content: bytes = b"reviewed license\n",
    ) -> tuple[Path, NativePolicy]:
        policy = next(policy for policy, _ in ARCHIVE_COMPONENTS if policy.key == "CGAL")
        archive_path = root / ARCHIVE_NOTICE_PATHS[policy.key]
        archive_path.parent.mkdir(parents=True)
        with tarfile.open(archive_path, mode="w:xz") as archive:
            member = tarfile.TarInfo("CGAL-5.6/LICENSE")
            member.size = len(content)
            archive.addfile(member, io.BytesIO(content))
        return archive_path, policy

    def test_reads_reviewed_notice_from_locked_source_archive(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive_path, policy = self._write_archive(root)
            digest = hashlib.sha256(archive_path.read_bytes()).hexdigest()

            notices = _resolve_archive_notices(root, policy, digest)

            self.assertEqual(["LICENSE"], [path.name for path in notices])
            self.assertEqual("reviewed license\n", notices[0].read_text(encoding="utf-8"))

    def test_rejects_archive_that_does_not_match_the_lock(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _, policy = self._write_archive(root)

            with self.assertRaisesRegex(NativeLicenseError, "checksum mismatch"):
                _resolve_archive_notices(root, policy, "0" * 64)


if __name__ == "__main__":
    unittest.main()
