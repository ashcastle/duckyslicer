from __future__ import annotations

import unittest

from tools.verify_play_bundle_workflow import VerificationError
from tools.verify_play_bundle_workflow import verify_play_bundle_workflow


def valid_sources() -> dict[str, str]:
    return {
        "play-bundle.yml": """
name: Play Bundle
on:
  workflow_dispatch:
jobs:
  build:
    steps:
      - run: |
          test 1 -le 2100000000
          test "$GITHUB_REF" = "refs/heads/main"
          test "${#PLAY_VERSION_NAME_INPUT}" -gt 64
          test "${#PLAY_VERSION_CODE_INPUT}" -gt 10
          DUCKYSLICER_PLAY_VERSION_NAME=0.2.0
          DUCKYSLICER_PLAY_VERSION_CODE=2
          ./gradlew :app:bundleRelease :app:packageReleaseUniversalApk
          test app-release.aab app-release-universal-unsigned.apk
          zipalign" -c -P 16 -v 4 "$delivery_apk"
          aapt" dump badging "$delivery_apk"
          grep "versionCode='$DUCKYSLICER_PLAY_VERSION_CODE'"
          grep "versionName='$DUCKYSLICER_PLAY_VERSION_NAME'"
          echo unexpectedly produced a signed bundle
          python3 tools/verify_apk.py "$delivery_apk"
      - uses: actions/upload-artifact@0123456789012345678901234567890123456789
        with:
          name: duckyslicer-play-unsigned
  sign:
    needs: build
    environment: play
    permissions:
      actions: read
    steps:
      - uses: actions/download-artifact@0123456789012345678901234567890123456789
      - env:
          A: ${{ secrets.ONE }}
          B: ${{ secrets.TWO }}
          C: ${{ secrets.THREE }}
          D: ${{ secrets.FOUR }}
          DUCKYSLICER_PLAY_CERT_SHA256: ${{ vars.CERT }}
        run: |
          expected_fingerprint=expected
          actual_fingerprint=actual
          key_file=key
          cert_file=cert
          trap 'rm -f "$key_file" "$cert_file"' EXIT
          Public Key Algorithm: rsaEncryption
          test "$key_bits" -lt 2048
          jarsigner input.aab
          jarsigner -verify -strict -verbose -certs \\
            -keystore "$key_file" \\
            -storepass:env DUCKYSLICER_PLAY_STORE_PASSWORD output.aab
          test jar verified
          test jar is unsigned
          signature_block_count=1
          bundle_fingerprint=fingerprint
          sha256sum --check output-play.aab.sha256
      - uses: actions/upload-artifact@0123456789012345678901234567890123456789
        with:
          name: duckyslicer-play-signed
""",
        "sign-local-release.yml": """
jobs:
  publish:
    steps:
      - run: echo release/DuckySlicer-arm64.apk
""",
        "RELEASING.md": (
            "Use a separate Play upload key. The workflow never uploads to Play Console. "
            "Download duckyslicer-play-signed."
        ),
    }


class VerifyPlayBundleWorkflowTest(unittest.TestCase):
    def test_accepts_isolated_manual_play_handoff(self) -> None:
        verify_play_bundle_workflow(valid_sources())

    def test_rejects_automatic_trigger(self) -> None:
        sources = valid_sources()
        sources["play-bundle.yml"] = sources["play-bundle.yml"].replace(
            "  workflow_dispatch:", "  workflow_dispatch:\n  push:"
        )
        with self.assertRaisesRegex(VerificationError, "automatic trigger"):
            verify_play_bundle_workflow(sources)

    def test_rejects_secret_in_build_job(self) -> None:
        sources = valid_sources()
        sources["play-bundle.yml"] = sources["play-bundle.yml"].replace(
            "  sign:\n", "      - run: echo ${{ secrets.LEAK }}\n  sign:\n"
        )
        with self.assertRaisesRegex(VerificationError, "must not receive"):
            verify_play_bundle_workflow(sources)

    def test_rejects_missing_version_code_input_bound(self) -> None:
        sources = valid_sources()
        sources["play-bundle.yml"] = sources["play-bundle.yml"].replace(
            '          test "${#PLAY_VERSION_CODE_INPUT}" -gt 10\n', ""
        )
        with self.assertRaisesRegex(VerificationError, "Play build gate"):
            verify_play_bundle_workflow(sources)

    def test_rejects_signer_checkout(self) -> None:
        sources = valid_sources()
        sources["play-bundle.yml"] = sources["play-bundle.yml"].replace(
            "    steps:\n      - uses: actions/download-artifact@",
            "    steps:\n      - uses: actions/checkout@"
            "0123456789012345678901234567890123456789\n"
            "      - uses: actions/download-artifact@",
        )
        with self.assertRaisesRegex(VerificationError, "repository code"):
            verify_play_bundle_workflow(sources)

    def test_rejects_signer_verification_without_pinned_keystore(self) -> None:
        sources = valid_sources()
        sources["play-bundle.yml"] = sources["play-bundle.yml"].replace(
            '            -keystore "$key_file" \\\n', ""
        )
        with self.assertRaisesRegex(VerificationError, "signs and verifies"):
            verify_play_bundle_workflow(sources)

    def test_rejects_store_upload(self) -> None:
        sources = valid_sources()
        sources["play-bundle.yml"] += "\n# androidpublisher upload\n"
        with self.assertRaisesRegex(VerificationError, "signed Actions artifact"):
            verify_play_bundle_workflow(sources)

    def test_rejects_aab_in_github_release(self) -> None:
        sources = valid_sources()
        sources["sign-local-release.yml"] = sources["sign-local-release.yml"].replace(
            "DuckySlicer-arm64.apk", "DuckySlicer-arm64.apk DuckySlicer-play.aab"
        )
        with self.assertRaisesRegex(VerificationError, "free of AAB"):
            verify_play_bundle_workflow(sources)


if __name__ == "__main__":
    unittest.main()
