from __future__ import annotations

import unittest

from tools.verify_release_contract import VerificationError, verify_release_contract


def valid_sources() -> dict[str, str]:
    return {
        "sign-local-release.yml": """
on:
  workflow_dispatch:
jobs:
  validate:
    permissions:
      contents: write
    env:
      UNSIGNED_SHA256: ${{ inputs.unsigned_sha256 }}
      RELEASE_VERSION_CODE: ${{ inputs.version_code }}
      SOURCE_COMMIT: ${{ inputs.source_commit }}
    steps:
      - run: |
          if [ "$GITHUB_REF" != "refs/heads/main" ]; then exit 1; fi
          gh api "repos/$GITHUB_REPOSITORY/commits/$RELEASE_TAG" --jq .sha
          if [ "$actual_sha256" != "$UNSIGNED_SHA256" ]; then exit 1; fi
          if [ "$package_name" != "com.ashcastle.duckyslicer" ]; then exit 1; fi
          if [ "$actual_version_code" != "$RELEASE_VERSION_CODE" ]; then exit 1; fi
          if [ "$actual_version_name" != "$version" ]; then exit 1; fi
          echo "Release notes must describe the user-visible changes before signing"
          test '<!-- duckyslicer-release-integrity -->'
          echo "--jq '.body | @base64'"
          release_notes_sha256=$(printf '%s' "$release_body_base64" | sha256sum)
          echo "release_notes_sha256=$release_notes_sha256"
          "$build_tools/zipalign" -c -P 16 -v 4 "$unsigned_apk"
          "$build_tools/apksigner" verify "$unsigned_apk"
      - uses: actions/upload-artifact@0123456789012345678901234567890123456789
        with:
          name: duckyslicer-local-unsigned-${{ github.run_id }}
  sign:
    needs: validate
    environment: release
    permissions:
      actions: read
    steps:
      - run: |
          echo "Validated unsigned artifact changed before signing"
          expected_fingerprint="$DUCKYSLICER_SIGNING_CERT_SHA256"
          actual_fingerprint=fixed
          trap 'rm -f "$key_file"' EXIT
        env:
          A: ${{ secrets.A }}
          B: ${{ secrets.B }}
          C: ${{ secrets.C }}
          D: ${{ secrets.D }}
  publish:
    needs: [validate, sign]
    permissions:
      actions: read
      contents: write
    steps:
      - run: |
          echo "Release tag changed after local artifact validation"
          echo "Release was published before the isolated signer completed"
          echo "Release notes changed after local artifact validation"
          gh release upload "$RELEASE_TAG" "$signed_apk"
          gh release delete-asset "$RELEASE_TAG" "$UNSIGNED_ASSET"
          echo "Refusing to publish a release without exactly one signed APK"
          echo 'RELEASE_NOTES_SHA256: ${{ needs.validate.outputs.release_notes_sha256 }}'
          echo "Release notes changed while replacing the draft artifact"
          signed_sha256=$(sha256sum "$signed_apk")
          echo "Signer #1 certificate SHA-256 digest"
          echo "$DUCKYSLICER_SIGNING_CERT_SHA256"
          echo '<!-- duckyslicer-release-integrity -->'
          printf 'APK SHA-256: `%s`' "$signed_sha256"
          printf 'Signing certificate SHA-256: `%s`' "$actual_fingerprint"
          gh release edit "$RELEASE_TAG" \
            --notes-file "$release_notes" \
            --draft=false
""",
        "prepare_local_release.py": " ".join(
            (
                'run((sys.executable, str(ROOT / "tools/run_local_gate.py")))',
                '"--no-build-cache",',
                '":app:clean",',
                'command.append(":app:assembleRelease")',
                "verify_reproducible(candidate_output, RELEASE_APK)",
                'ROOT / "tools/verify_apk.py"',
                'ROOT / "tools/verify_artifact_manifest.py"',
                'ROOT / "tools/verify_artifact_localization.py"',
                'branch != "main"',
                '"+refs/heads/main:refs/remotes/origin/main"',
                'captured(("git", "rev-parse", "origin/main"))',
                '("git", "submodule", "status", "--recursive"),',
                "mismatched_submodules(submodules)",
                '"DUCKYSLICER_KEYSTORE_BASE64"',
                '"sourceCommit": self.source_commit',
                '"unsignedSha256": self.unsigned_sha256',
            )
        ),
        "RELEASING.md": (
            "Run python3 tools/prepare_local_release.py. GitHub Actions never builds the "
            "GitHub Release APK. A GitHub Release contains exactly one public asset: the "
            "signed ARM64 APK. Release notes must describe user-visible changes. The publisher "
            "appends the signed APK SHA-256, signing-certificate fingerprint, and source tag. "
            "Use DuckySlicer_16KB_API35."
        ),
        "SECURITY.md": (
            "The Release APK is built twice on the maintainer's local machine. "
            "SHA-256, package name, versionCode, versionName, and tag commit are pinned. "
            "The GitHub Release exposes exactly one downloadable asset: the signed ARM64 APK. "
            "Published Release notes contain the signed APK SHA-256, signing-certificate "
            "SHA-256, and source tag."
        ),
        "CONTRIBUTING.md": (
            "Run python3 tools/run_local_gate.py and python3 tools/prepare_local_release.py. "
            "The GitHub Release APK must be built locally. The GitHub Release must contain "
            "only the signed ARM64 APK. It publishes the APK SHA-256 and signing-certificate "
            "fingerprint in the Release notes."
        ),
    }


class VerifyReleaseContractTest(unittest.TestCase):
    def test_accepts_reproducible_local_build_and_isolated_signing(self) -> None:
        verify_release_contract(valid_sources())

    def test_rejects_automatic_release_trigger(self) -> None:
        sources = valid_sources()
        sources["sign-local-release.yml"] = sources["sign-local-release.yml"].replace(
            "  workflow_dispatch:", "  push:\n    tags: ['v*']"
        )
        with self.assertRaisesRegex(VerificationError, "manually dispatched"):
            verify_release_contract(sources)

    def test_rejects_missing_local_digest_validation(self) -> None:
        sources = valid_sources()
        sources["sign-local-release.yml"] = sources["sign-local-release.yml"].replace(
            'if [ "$actual_sha256" != "$UNSIGNED_SHA256" ]; then exit 1; fi', ""
        )
        with self.assertRaisesRegex(VerificationError, "actual_sha256"):
            verify_release_contract(sources)

    def test_rejects_missing_main_ref_validation(self) -> None:
        sources = valid_sources()
        sources["sign-local-release.yml"] = sources["sign-local-release.yml"].replace(
            'if [ "$GITHUB_REF" != "refs/heads/main" ]; then exit 1; fi', ""
        )
        with self.assertRaisesRegex(VerificationError, "GITHUB_REF"):
            verify_release_contract(sources)

    def test_rejects_release_write_access_in_signer(self) -> None:
        sources = valid_sources()
        sources["sign-local-release.yml"] = sources["sign-local-release.yml"].replace(
            "      actions: read\n    steps:",
            "      actions: read\n      contents: write\n    steps:",
            1,
        )
        with self.assertRaisesRegex(VerificationError, "artifact-read"):
            verify_release_contract(sources)

    def test_rejects_hosted_release_build(self) -> None:
        sources = valid_sources()
        sources["sign-local-release.yml"] += "\n# ./gradlew :app:assembleRelease\n"
        with self.assertRaisesRegex(VerificationError, "build"):
            verify_release_contract(sources)

    def test_rejects_missing_local_release_documentation(self) -> None:
        sources = valid_sources()
        sources["RELEASING.md"] = ""
        with self.assertRaisesRegex(VerificationError, "RELEASING.md"):
            verify_release_contract(sources)

    def test_rejects_missing_release_note_integrity_publication(self) -> None:
        sources = valid_sources()
        sources["sign-local-release.yml"] = sources["sign-local-release.yml"].replace(
            'APK SHA-256: `%s`', "APK digest omitted"
        )
        with self.assertRaisesRegex(VerificationError, "APK SHA-256"):
            verify_release_contract(sources)

    def test_rejects_unpinned_release_notes(self) -> None:
        sources = valid_sources()
        sources["sign-local-release.yml"] = sources["sign-local-release.yml"].replace(
            "Release notes changed after local artifact validation", "notes not pinned"
        )
        with self.assertRaisesRegex(VerificationError, "Release notes changed"):
            verify_release_contract(sources)


if __name__ == "__main__":
    unittest.main()
