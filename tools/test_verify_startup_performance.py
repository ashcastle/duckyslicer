from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from tools.verify_startup_performance import VerificationError, read_profile


class VerifyStartupPerformanceTest(unittest.TestCase):
    def write_profile(self, directory: str, rules: list[str]) -> Path:
        path = Path(directory) / "profile.txt"
        path.write_text("\n".join(rules) + "\n", encoding="utf-8")
        return path

    def valid_rules(self) -> list[str]:
        return sorted([
            f"HSPLcom/ashcastle/duckyslicer/Startup{index};->draw()V"
            for index in range(100)
        ])

    def test_accepts_profile_with_first_party_startup_coverage(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(
                set(self.valid_rules()),
                read_profile(self.write_profile(directory, self.valid_rules())),
            )

    def test_rejects_duplicate_or_invalid_profile_rules(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            duplicate = self.valid_rules() + [self.valid_rules()[0]]
            with self.assertRaisesRegex(VerificationError, "duplicate"):
                read_profile(self.write_profile(directory, duplicate))
            invalid = self.valid_rules() + ["not-a-profile-rule"]
            with self.assertRaisesRegex(VerificationError, "invalid rule"):
                read_profile(self.write_profile(directory, invalid))
            noncanonical = self.valid_rules()
            noncanonical[0] = noncanonical[0].removeprefix("H")
            noncanonical.sort()
            with self.assertRaisesRegex(VerificationError, "noncanonical"):
                read_profile(self.write_profile(directory, noncanonical))


if __name__ == "__main__":
    unittest.main()
