from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path

from tools.verify_no_embedded_credentials import VerificationError, verify_repository


class VerifyNoEmbeddedCredentialsTest(unittest.TestCase):
    def repository(self) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        subprocess.run(["git", "init", "-q", str(root)], check=True)
        return temporary, root

    def test_accepts_permanent_citation_without_query_credentials(self) -> None:
        temporary, root = self.repository()
        with temporary:
            (root / "citation.txt").write_text(
                "https://doi.org/10.1016/S0925-7721(01)00012-8\n",
                encoding="utf-8",
            )
            subprocess.run(["git", "-C", str(root), "add", "citation.txt"], check=True)
            self.assertEqual(5, verify_repository(root))

    def test_rejects_tracked_signed_url_without_disclosing_value(self) -> None:
        temporary, root = self.repository()
        with temporary:
            marker = "X-" + "Amz-Credential="
            secret_value = "EXAMPLE-DO-NOT-DISCLOSE"
            (root / "vendor.cpp").write_text(
                f"// https://example.invalid/file?{marker}{secret_value}\n",
                encoding="utf-8",
            )
            subprocess.run(["git", "-C", str(root), "add", "vendor.cpp"], check=True)
            with self.assertRaises(VerificationError) as caught:
                verify_repository(root)
            message = str(caught.exception)
            self.assertIn("vendor.cpp", message)
            self.assertNotIn(secret_value, message)

    def test_ignores_untracked_local_files(self) -> None:
        temporary, root = self.repository()
        with temporary:
            marker = "X-" + "Goog-Signature="
            (root / "local-only.txt").write_text(marker + "example\n", encoding="utf-8")
            self.assertEqual(5, verify_repository(root))


if __name__ == "__main__":
    unittest.main()
