from __future__ import annotations

import unittest
import zipfile
from pathlib import Path
from tempfile import TemporaryDirectory

from tools.verify_reproducible_release import verify_reproducible


class VerifyReproducibleReleaseTest(unittest.TestCase):
    @staticmethod
    def write_apk(path: Path, value: bytes, timestamp: tuple[int, ...] = (2026, 1, 1, 0, 0, 0)) -> None:
        info = zipfile.ZipInfo("classes.dex", timestamp)
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr(info, value)

    def test_accepts_byte_identical_apks(self) -> None:
        with TemporaryDirectory() as temporary:
            first = Path(temporary) / "first.apk"
            second = Path(temporary) / "second.apk"
            self.write_apk(first, b"same")
            second.write_bytes(first.read_bytes())
            self.assertEqual(64, len(verify_reproducible(first, second)))

    def test_reports_changed_entry_content(self) -> None:
        with TemporaryDirectory() as temporary:
            first = Path(temporary) / "first.apk"
            second = Path(temporary) / "second.apk"
            self.write_apk(first, b"first")
            self.write_apk(second, b"second")
            with self.assertRaisesRegex(ValueError, "content changed: classes.dex"):
                verify_reproducible(first, second)

    def test_reports_changed_zip_metadata(self) -> None:
        with TemporaryDirectory() as temporary:
            first = Path(temporary) / "first.apk"
            second = Path(temporary) / "second.apk"
            self.write_apk(first, b"same")
            self.write_apk(second, b"same", (2026, 1, 1, 0, 0, 2))
            with self.assertRaisesRegex(ValueError, "ZIP metadata changed: classes.dex"):
                verify_reproducible(first, second)


if __name__ == "__main__":
    unittest.main()
