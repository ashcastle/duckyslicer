from __future__ import annotations

import unittest

from tools.verify_release_contract import VerificationError, verify_release_contract


def valid_sources() -> dict[str, str]:
    return {
        "release.yml": """
jobs:
  publish:
    steps:
      - run: |
          python3 tools/generate_source_bundle.py --verify source.tar.gz manifest.json
          python3 tools/generate_sbom.py app.apk sbom.json
          sha256sum --check SHA256SUMS.txt
      - uses: actions/upload-artifact@0123456789012345678901234567890123456789
        with:
          name: duckyslicer-release-audit
      - uses: actions/attest-build-provenance@0123456789012345678901234567890123456789
        with:
          subject-path: "release/DuckySlicer-*-arm64.apk"
      - uses: softprops/action-gh-release@0123456789012345678901234567890123456789
        with:
          files: |
            release/DuckySlicer-*-arm64.apk
""",
        "RELEASING.md": (
            "A GitHub Release contains exactly one public asset: the signed ARM64 APK. "
            "GitHub Actions does not run an Android emulator. DuckySlicer_16KB_API35."
        ),
        "SECURITY.md": (
            "The GitHub Release exposes exactly one downloadable asset: the signed ARM64 APK. "
            "GitHub-hosted emulators are not part of the release pipeline. "
            "Use the local ARM64 16 KB AVD."
        ),
        "CONTRIBUTING.md": (
            "The local ARM64 16 KB AVD is the authoritative functional gate. "
            "Hosted emulator jobs must remain absent. The GitHub Release must contain only "
            "the signed ARM64 APK."
        ),
    }


class VerifyReleaseContractTest(unittest.TestCase):
    def test_accepts_apk_only_local_device_contract(self) -> None:
        verify_release_contract(valid_sources())

    def test_rejects_extra_public_release_asset(self) -> None:
        sources = valid_sources()
        sources["release.yml"] = sources["release.yml"].replace(
            "            release/DuckySlicer-*-arm64.apk\n",
            "            release/DuckySlicer-*-arm64.apk\n"
            "            release/DuckySlicer-*.cdx.json\n",
        )
        with self.assertRaisesRegex(VerificationError, "exactly one"):
            verify_release_contract(sources)

    def test_rejects_broad_provenance_subject(self) -> None:
        sources = valid_sources()
        sources["release.yml"] = sources["release.yml"].replace(
            'subject-path: "release/DuckySlicer-*-arm64.apk"',
            'subject-path: "release/DuckySlicer-*"',
        )
        with self.assertRaisesRegex(VerificationError, "provenance"):
            verify_release_contract(sources)

    def test_rejects_play_bundle_in_publish_job(self) -> None:
        sources = valid_sources()
        sources["release.yml"] = sources["release.yml"].replace(
            "          sha256sum --check SHA256SUMS.txt",
            "          sha256sum --check SHA256SUMS.txt\n          test -s app-release.aab",
        )
        with self.assertRaisesRegex(VerificationError, "Play bundles"):
            verify_release_contract(sources)

    def test_rejects_unretained_audit_evidence(self) -> None:
        sources = valid_sources()
        sources["release.yml"] = sources["release.yml"].replace(
            "duckyslicer-release-audit", "discarded-release-audit"
        )
        with self.assertRaisesRegex(VerificationError, "audit evidence"):
            verify_release_contract(sources)

    def test_rejects_missing_local_device_guidance(self) -> None:
        sources = valid_sources()
        sources["RELEASING.md"] = sources["RELEASING.md"].replace(
            "GitHub Actions does not run an Android emulator.", ""
        )
        with self.assertRaisesRegex(VerificationError, "RELEASING.md"):
            verify_release_contract(sources)


if __name__ == "__main__":
    unittest.main()
