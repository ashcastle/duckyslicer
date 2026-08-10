from __future__ import annotations

import unittest

from tools.verify_play_bundle_workflow import VerificationError
from tools.verify_play_bundle_workflow import verify_play_bundle_workflow


def valid_sources() -> dict[str, str]:
    return {
        "play-bundle.yml": """
name: Sign Local Play Bundle
on:
  workflow_dispatch:
    inputs:
      version_name:
      version_code:
      source_commit:
      transport_tag:
      unsigned_asset:
      unsigned_sha256:
jobs:
  validate:
    permissions:
      contents: write
    steps:
      - run: |
          test "$GITHUB_REF" = "refs/heads/main"
          test 1 -le 2100000000
          expected_tag="play-v$PLAY_VERSION_NAME-$PLAY_VERSION_CODE"
          expected_asset="DuckySlicer-$PLAY_VERSION_NAME-play-unsigned.aab"
          gh api "repos/$GITHUB_REPOSITORY/commits/$TRANSPORT_TAG" --jq .sha
          gh api "repos/$GITHUB_REPOSITORY/releases/tags/$TRANSPORT_TAG"
          jq -r .draft <<<"$release_json"
          if [ "$total_assets" -ne 1 ] || [ "$asset_count" -ne 1 ]; then exit 1; fi
          if [ "$actual_sha256" != "$normalized_sha" ]; then exit 1; fi
          echo Local Play input must remain unsigned
          test BundleConfig.pb base/manifest/AndroidManifest.xml
          test base/lib/arm64-v8a/libduckyslicer.so
          test base/lib/arm64-v8a/libprusaslicer-jni.so
          diff -u expected-native-entries.txt actual-native-entries.txt
      - uses: actions/upload-artifact@0123456789012345678901234567890123456789
        with:
          name: duckyslicer-play-unsigned-${{ github.run_id }}
  sign:
    needs: validate
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
          if [ "$actual_sha256" != "$normalized_sha" ]; then
            echo Signer input differs from the locally verified SHA-256
          fi
          expected_fingerprint=expected
          actual_fingerprint=actual
          key_file=key
          cert_file=cert
          trap 'rm -f "$key_file" "$cert_file"' EXIT
          echo Public Key Algorithm: rsaEncryption
          test "$key_bits" -lt 2048
          jarsigner input.aab
          jarsigner -verify -strict -verbose -certs output.aab
          echo jar verified jar is unsigned
          signature_block_count=1
          bundle_fingerprint=fingerprint
          sha256sum --check output-play.aab.sha256
      - uses: actions/upload-artifact@0123456789012345678901234567890123456789
        with:
          name: duckyslicer-play-signed
  cleanup:
    needs: [validate, sign]
    if: ${{ always() && needs.validate.result == 'success' }}
    permissions:
      contents: write
    steps:
      - run: |
          gh api "repos/$GITHUB_REPOSITORY/commits/$TRANSPORT_TAG" --jq .sha
          jq -r .draft <<<"$release_json"
          if [ "$asset_sha" != "$normalized_sha" ]; then exit 1; fi
          gh release delete "$TRANSPORT_TAG" --yes
""",
        "sign-local-release.yml": """
jobs:
  publish:
    steps:
      - run: echo release/DuckySlicer-arm64.apk
""",
        "prepare_local_play_bundle.py": """
SIGNING_ENVIRONMENT = ()
run_local_gate.py
--require-api-36
--no-build-cache
:app:clean
:app:bundleRelease
:app:packageReleaseUniversalApk
verify_reproducible(candidate_bundle, RELEASE_AAB)
verify_reproducible(candidate_delivery, DELIVERY_APK)
verify_unsigned_apk(delivery_apk
play_transport_tag
""",
        "RELEASING.md": """
Run prepare_local_play_bundle.py; it is built twice. GitHub never builds the Play AAB.
Use a separate Play upload key. It never uploads to Play Console.
Download duckyslicer-play-signed. The local gate requires API 36.
""",
        "SECURITY.md": "local-only Play signing",
        "CONTRIBUTING.md": "local-only Play signing",
    }


class VerifyPlayBundleWorkflowTest(unittest.TestCase):
    def test_accepts_local_only_manual_play_handoff(self) -> None:
        verify_play_bundle_workflow(valid_sources())

    def test_rejects_automatic_trigger(self) -> None:
        sources = valid_sources()
        sources["play-bundle.yml"] = sources["play-bundle.yml"].replace(
            "  workflow_dispatch:", "  workflow_dispatch:\n  push:"
        )
        with self.assertRaisesRegex(VerificationError, "automatic trigger"):
            verify_play_bundle_workflow(sources)

    def test_rejects_hosted_build(self) -> None:
        sources = valid_sources()
        sources["play-bundle.yml"] = sources["play-bundle.yml"].replace(
            "  sign:\n", "      - run: ./gradlew bundleRelease\n  sign:\n"
        )
        with self.assertRaisesRegex(VerificationError, "must not build"):
            verify_play_bundle_workflow(sources)

    def test_rejects_secret_in_validator(self) -> None:
        sources = valid_sources()
        sources["play-bundle.yml"] = sources["play-bundle.yml"].replace(
            "  sign:\n", "      - run: echo ${{ secrets.LEAK }}\n  sign:\n"
        )
        with self.assertRaisesRegex(VerificationError, "signing material"):
            verify_play_bundle_workflow(sources)

    def test_rejects_validator_release_mutation(self) -> None:
        sources = valid_sources()
        sources["play-bundle.yml"] = sources["play-bundle.yml"].replace(
            "  sign:\n", "      - run: gh release delete draft --yes\n  sign:\n"
        )
        with self.assertRaisesRegex(VerificationError, "release-read-only"):
            verify_play_bundle_workflow(sources)

    def test_rejects_signer_checkout(self) -> None:
        sources = valid_sources()
        sources["play-bundle.yml"] = sources["play-bundle.yml"].replace(
            "    environment: play", "    environment: play\n    # actions/checkout@bad"
        )
        with self.assertRaisesRegex(VerificationError, "must not build"):
            verify_play_bundle_workflow(sources)

    def test_rejects_cleanup_of_source_tag(self) -> None:
        sources = valid_sources()
        sources["play-bundle.yml"] = sources["play-bundle.yml"].replace(
            'gh release delete "$TRANSPORT_TAG" --yes',
            'gh release delete "$TRANSPORT_TAG" --yes --cleanup-tag',
        )
        with self.assertRaisesRegex(VerificationError, "durable source tag"):
            verify_play_bundle_workflow(sources)

    def test_rejects_store_upload(self) -> None:
        sources = valid_sources()
        sources["play-bundle.yml"] += "\n# androidpublisher upload\n"
        with self.assertRaisesRegex(VerificationError, "signed Actions artifact"):
            verify_play_bundle_workflow(sources)

    def test_rejects_play_preparation_without_api36_runtime_gate(self) -> None:
        sources = valid_sources()
        sources["prepare_local_play_bundle.py"] = sources[
            "prepare_local_play_bundle.py"
        ].replace("--require-api-36", "--host-only")
        with self.assertRaisesRegex(VerificationError, "require-api-36"):
            verify_play_bundle_workflow(sources)

    def test_rejects_aab_in_github_release(self) -> None:
        sources = valid_sources()
        sources["sign-local-release.yml"] = sources[
            "sign-local-release.yml"
        ].replace("DuckySlicer-arm64.apk", "DuckySlicer-play.aab")
        with self.assertRaisesRegex(VerificationError, "free of AAB"):
            verify_play_bundle_workflow(sources)


if __name__ == "__main__":
    unittest.main()
