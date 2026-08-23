from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

from tools.verify_play_bundle_workflow import VerificationError
from tools.verify_play_bundle_workflow import verify_play_bundle_workflow


ROOT = Path(__file__).resolve().parent.parent


def named_run_block(source: str, name: str) -> str:
    marker = f"      - name: {name}\n"
    marker_index = source.index(marker)
    run_marker = "        run: |\n"
    start = source.index(run_marker, marker_index) + len(run_marker)
    lines: list[str] = []
    for line in source[start:].splitlines():
        if line.startswith("          "):
            lines.append(line[10:])
        elif not line:
            lines.append("")
        else:
            break
    return "\n".join(lines) + "\n"


FAKE_CURL = r"""#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys

arguments = sys.argv[1:]
method = "GET"
if "--request" in arguments:
    method = arguments[arguments.index("--request") + 1]
url = arguments[-1]
with Path(os.environ["FAKE_PLAY_LOG"]).open("a", encoding="utf-8") as output:
    output.write(f"{method} {url}\n")

version_code = os.environ["PLAY_VERSION_CODE"]
if method == "DELETE":
    response = {}
elif url.endswith("/edits") and method == "POST":
    response = {"id": "123"}
elif url.endswith("/edits/123/bundles") and method == "GET":
    maximum = os.environ.get("FAKE_PLAY_MAX", "0")
    response = {"bundles": [] if maximum == "0" else [{"versionCode": maximum}]}
elif "bundles?uploadType=resumable" in url and method == "POST":
    header_path = Path(arguments[arguments.index("--dump-header") + 1])
    upload_origin = os.environ.get(
        "FAKE_PLAY_UPLOAD_ORIGIN",
        "https://androidpublisher.googleapis.com/upload/session/abc",
    )
    header_path.write_text(
        f"HTTP/1.1 200 OK\r\nLocation: {upload_origin}\r\n\r\n",
        encoding="utf-8",
    )
    raise SystemExit(0)
elif url == "https://androidpublisher.googleapis.com/upload/session/abc" and method == "PUT":
    uploaded = os.environ.get("FAKE_PLAY_UPLOADED", version_code)
    response = {"versionCode": uploaded}
elif url.endswith("/edits/123/tracks/internal") and method == "PUT":
    response = {
        "track": "internal",
        "releases": [{"versionCodes": [version_code], "status": "completed"}],
    }
elif url.endswith("/edits/123:validate") and method == "POST":
    response = {"id": "123"}
elif ":commit?changesInReviewBehavior=ERROR_IF_IN_REVIEW" in url and method == "POST":
    if os.environ.get("FAKE_PLAY_COMMIT_FAILURE") == "1":
        print("existing review", file=sys.stderr)
        raise SystemExit(22)
    response = {"id": "123"}
else:
    print(f"unexpected request: {method} {url}", file=sys.stderr)
    raise SystemExit(22)
print(json.dumps(response))
"""


def run_real_publisher(
    *,
    version_code: str = "15",
    maximum: str = "14",
    uploaded: str | None = None,
    commit_failure: bool = False,
    upload_origin: str | None = None,
) -> tuple[subprocess.CompletedProcess[str], list[str], dict[str, object] | None]:
    workflow = (ROOT / ".github/workflows/play-bundle.yml").read_text(encoding="utf-8")
    script = named_run_block(
        workflow,
        "Publish the exact signed bundle to the internal track",
    )
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        binary = root / "bin"
        binary.mkdir()
        curl = binary / "curl"
        curl.write_text(textwrap.dedent(FAKE_CURL), encoding="utf-8")
        curl.chmod(0o755)
        signed = root / "signed"
        signed.mkdir()
        bundle = signed / "DuckySlicer-0.2.0-rc.1-play.aab"
        bundle.write_bytes(b"signed-play-bundle")
        digest = hashlib.sha256(bundle.read_bytes()).hexdigest()
        (signed / f"{bundle.name}.sha256").write_text(
            f"{digest}  {bundle.name}\n",
            encoding="utf-8",
        )
        log = root / "requests.log"
        environment = dict(os.environ)
        environment.update(
            {
                "PATH": f"{binary}{os.pathsep}{environment['PATH']}",
                "PLAY_ACCESS_TOKEN": "short-lived-test-token",
                "PLAY_PACKAGE_NAME": "com.ashcastle.duckyslicer",
                "PLAY_VERSION_NAME": "0.2.0-rc.1",
                "PLAY_VERSION_CODE": version_code,
                "PLAY_RELEASE_NOTES": "Internal qualification build.",
                "FAKE_PLAY_MAX": maximum,
                "FAKE_PLAY_LOG": str(log),
                "GITHUB_STEP_SUMMARY": str(root / "summary.md"),
                "RUNNER_TEMP": str(root),
                "SOURCE_COMMIT": "a" * 40,
                "TRANSPORT_TAG": "play-v0.2.0-rc.1-15",
                "GITHUB_SERVER_URL": "https://github.com",
                "GITHUB_REPOSITORY": "ashcastle/duckyslicer",
                "GITHUB_RUN_ID": "12345",
            }
        )
        if uploaded is not None:
            environment["FAKE_PLAY_UPLOADED"] = uploaded
        if commit_failure:
            environment["FAKE_PLAY_COMMIT_FAILURE"] = "1"
        if upload_origin is not None:
            environment["FAKE_PLAY_UPLOAD_ORIGIN"] = upload_origin
        result = subprocess.run(
            ["bash", "-c", script],
            cwd=root,
            env=environment,
            text=True,
            capture_output=True,
            check=False,
        )
        requests = log.read_text(encoding="utf-8").splitlines() if log.exists() else []
        receipt_path = signed / "DuckySlicer-0.2.0-rc.1-PLAY-PUBLISH.json"
        receipt = (
            json.loads(receipt_path.read_text(encoding="utf-8"))
            if receipt_path.exists()
            else None
        )
        return result, requests, receipt


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
      release_notes:
      publish_internal:
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
          if [ "$PUBLISH_INTERNAL" != "true" ]; then exit 1; fi
          if [ -z "$PLAY_RELEASE_NOTES" ]; then exit 1; fi
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
  publish:
    needs: [validate, sign]
    if: ${{ inputs.publish_internal }}
    environment: play
    permissions:
      actions: read
      id-token: write
    steps:
      - uses: actions/download-artifact@0123456789012345678901234567890123456789
        with:
          name: duckyslicer-play-signed
      - env:
          PROVIDER: ${{ vars.DUCKYSLICER_GOOGLE_WORKLOAD_IDENTITY_PROVIDER }}
          ACCOUNT: ${{ vars.DUCKYSLICER_GOOGLE_PLAY_SERVICE_ACCOUNT }}
        run: |
          echo workloadIdentityPools
          echo 'iam\\.gserviceaccount\\.com'
      - id: google_auth
        uses: google-github-actions/auth@7c6bc770dae815cd3e89ee6cdf493a5fab2cc093
        with:
          token_format: access_token
          access_token_scopes: https://www.googleapis.com/auth/androidpublisher
          access_token_lifetime: 900s
          create_credentials_file: false
          export_environment_variables: false
      - env:
          PLAY_PACKAGE_NAME: com.ashcastle.duckyslicer
        run: |
          signed_aab="signed/DuckySlicer-$PLAY_VERSION_NAME-play.aab"
          sha256sum --check signed.aab.sha256
          edit_response=$(curl "$api_root/edits")
          bundles_response=$(curl "$api_root/edits/$edit_id/bundles")
          highest_version_code=1
          test "$PLAY_VERSION_CODE" -le "$highest_version_code"
          test "$uploaded_version_code" != "$PLAY_VERSION_CODE"
          curl "$upload_root/edits/$edit_id/bundles?uploadType=resumable"
          echo 'X-Upload-Content-Type: application/octet-stream'
          echo 'X-Upload-Content-Length: $bundle_size'
          echo 'androidpublisher\\.googleapis\\.com/upload/'
          curl --request PUT https://androidpublisher.googleapis.com/upload/session
          echo 'status: "completed"'
          curl "$api_root/edits/$edit_id/tracks/internal"
          curl "$api_root/edits/$edit_id:validate"
          curl "$api_root/edits/$edit_id:commit?changesInReviewBehavior=ERROR_IF_IN_REVIEW"
          cleanup_edit() { curl --request DELETE target; }
          trap cleanup_edit EXIT
          edit_committed="true"
          echo '### Google Play internal release' >> "$GITHUB_STEP_SUMMARY"
          echo 'Previous maximum versionCode' >> "$GITHUB_STEP_SUMMARY"
          echo 'DuckySlicer-$PLAY_VERSION_NAME-PLAY-PUBLISH.json schemaVersion: 1'
          echo 'previousMaximumVersionCode sourceCommit transportTag signedSha256 workflowRun'
      - uses: actions/upload-artifact@0123456789012345678901234567890123456789
        with:
          name: duckyslicer-play-receipt-${{ inputs.version_code }}
          retention-days: 90
  cleanup:
    needs: [validate, sign, publish]
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
R8_MAPPING_ENTRY
REQUIRED_DEBUG_SYMBOL_ENTRIES
missing production diagnostics
""",
        "build.gradle.kts": 'ndk.debugSymbolLevel = "FULL"',
        "Cargo.toml": "[profile.release]\ndebug = 1\n",
        "build.sh": """
cp "$runtime_so" "$output_so"
runtime is missing its native symbol table
runtime is missing full debug information
""",
        "RELEASING.md": """
Run prepare_local_play_bundle.py; it is built twice. GitHub never builds the Play AAB.
Use a separate Play upload key and Workload Identity Federation for the internal track.
Use ERROR_IF_IN_REVIEW. Download duckyslicer-play-signed. The local gate requires API 36.
The AAB contains native debug symbols and an R8 mapping.
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

    def test_rejects_non_internal_store_upload(self) -> None:
        sources = valid_sources()
        sources["play-bundle.yml"] = sources["play-bundle.yml"].replace(
            "tracks/internal", "tracks/production"
        )
        with self.assertRaisesRegex(VerificationError, "internal track"):
            verify_play_bundle_workflow(sources)

    def test_rejects_long_lived_publisher_secret(self) -> None:
        sources = valid_sources()
        sources["play-bundle.yml"] = sources["play-bundle.yml"].replace(
            "  cleanup:\n", "      - run: echo ${{ secrets.GOOGLE_JSON }}\n  cleanup:\n"
        )
        with self.assertRaisesRegex(VerificationError, "long-lived"):
            verify_play_bundle_workflow(sources)

    def test_rejects_publisher_without_edit_rollback(self) -> None:
        sources = valid_sources()
        sources["play-bundle.yml"] = sources["play-bundle.yml"].replace(
            "          trap cleanup_edit EXIT\n", ""
        )
        with self.assertRaisesRegex(VerificationError, "uncommitted edit"):
            verify_play_bundle_workflow(sources)

    def test_real_publisher_block_commits_one_new_internal_bundle(self) -> None:
        result, requests, receipt = run_real_publisher()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(any("bundles?uploadType=resumable" in request for request in requests))
        self.assertTrue(any(request.startswith("PUT https://androidpublisher.googleapis.com/upload/") for request in requests))
        self.assertTrue(any("tracks/internal" in request for request in requests))
        self.assertTrue(any("ERROR_IF_IN_REVIEW" in request for request in requests))
        self.assertFalse(any(request.startswith("DELETE ") for request in requests))
        self.assertIsNotNone(receipt)
        self.assertEqual(15, receipt["versionCode"] if receipt else None)
        self.assertEqual("internal", receipt["track"] if receipt else None)
        self.assertEqual("a" * 40, receipt["sourceCommit"] if receipt else None)
        self.assertRegex(str(receipt["signedSha256"] if receipt else ""), r"^[0-9a-f]{64}$")

    def test_real_publisher_block_rejects_reused_version_before_upload(self) -> None:
        result, requests, receipt = run_real_publisher(version_code="14", maximum="14")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("not greater than Play's current maximum", result.stderr)
        self.assertFalse(any("uploadType=resumable" in request for request in requests))
        self.assertTrue(any(request.startswith("DELETE ") for request in requests))
        self.assertIsNone(receipt)

    def test_real_publisher_block_rolls_back_a_review_conflict(self) -> None:
        result, requests, receipt = run_real_publisher(commit_failure=True)
        self.assertNotEqual(0, result.returncode)
        self.assertTrue(any("ERROR_IF_IN_REVIEW" in request for request in requests))
        self.assertTrue(any(request.startswith("DELETE ") for request in requests))
        self.assertIsNone(receipt)

    def test_real_publisher_block_rejects_an_unexpected_uploaded_code(self) -> None:
        result, requests, receipt = run_real_publisher(uploaded="16")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("instead of 15", result.stderr)
        self.assertTrue(any(request.startswith("DELETE ") for request in requests))
        self.assertIsNone(receipt)

    def test_real_publisher_block_rejects_a_foreign_upload_session(self) -> None:
        result, requests, receipt = run_real_publisher(
            upload_origin="https://example.invalid/steal-upload"
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("invalid resumable upload origin", result.stderr)
        self.assertFalse(any(request.startswith("PUT ") for request in requests))
        self.assertTrue(any(request.startswith("DELETE ") for request in requests))
        self.assertIsNone(receipt)

    def test_rejects_play_preparation_without_api36_runtime_gate(self) -> None:
        sources = valid_sources()
        sources["prepare_local_play_bundle.py"] = sources[
            "prepare_local_play_bundle.py"
        ].replace("--require-api-36", "--host-only")
        with self.assertRaisesRegex(VerificationError, "require-api-36"):
            verify_play_bundle_workflow(sources)

    def test_rejects_prestripped_owned_native_libraries(self) -> None:
        for source_name, marker in (
            ("Cargo.toml", 'strip = "symbols"'),
            ("build.sh", '"$strip_tool" --strip-unneeded "$output_so"'),
        ):
            with self.subTest(source_name=source_name):
                sources = valid_sources()
                sources[source_name] += "\n" + marker
                with self.assertRaisesRegex(VerificationError, "symbols"):
                    verify_play_bundle_workflow(sources)

    def test_rejects_non_full_gradle_native_metadata(self) -> None:
        sources = valid_sources()
        sources["build.gradle.kts"] = sources["build.gradle.kts"].replace(
            '"FULL"', '"SYMBOL_TABLE"'
        )
        with self.assertRaisesRegex(VerificationError, "FULL"):
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
