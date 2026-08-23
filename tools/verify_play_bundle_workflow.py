#!/usr/bin/env python3
"""Enforce local Play builds, isolated signing, and guarded internal publishing."""

from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
JOB = re.compile(r"^  ([A-Za-z0-9_-]+):\s*$", re.MULTILINE)


class VerificationError(ValueError):
    pass


def _job_sections(workflow: str) -> dict[str, str]:
    _, separator, jobs_source = workflow.partition("\njobs:\n")
    if not separator:
        return {}
    matches = list(JOB.finditer(jobs_source))
    return {
        match.group(1): jobs_source[
            match.start() : matches[index + 1].start()
            if index + 1 < len(matches)
            else None
        ]
        for index, match in enumerate(matches)
    }


def _require(section: str, markers: tuple[str, ...], label: str) -> None:
    for marker in markers:
        if marker not in section:
            raise VerificationError(f"{label} is missing: {marker}")


def _reject_repository_execution(section: str, label: str) -> None:
    for marker in (
        "actions/checkout@",
        "./gradlew",
        "gradlew ",
        "cargo ",
        "python3 tools/",
        "prepare_local_play_bundle.py",
    ):
        if marker in section:
            raise VerificationError(f"{label} must not build or execute repository code")


def verify_play_bundle_workflow(sources: dict[str, str]) -> None:
    required_files = {
        "play-bundle.yml",
        "sign-local-release.yml",
        "prepare_local_play_bundle.py",
        "build.gradle.kts",
        "Cargo.toml",
        "build.sh",
        "RELEASING.md",
        "SECURITY.md",
        "CONTRIBUTING.md",
    }
    missing_files = sorted(required_files - sources.keys())
    if missing_files:
        raise VerificationError(f"Play workflow sources are missing: {missing_files}")

    workflow = sources["play-bundle.yml"]
    header, separator, _ = workflow.partition("\njobs:\n")
    if not separator or "workflow_dispatch:" not in header:
        raise VerificationError("Play signing workflow must be manually dispatched")
    for automatic_trigger in ("pull_request:", "push:", "schedule:"):
        if automatic_trigger in header:
            raise VerificationError(
                f"Play signing workflow must not use automatic trigger: {automatic_trigger}"
            )
    _require(
        header,
        (
            "version_name:",
            "version_code:",
            "source_commit:",
            "transport_tag:",
            "unsigned_asset:",
            "unsigned_sha256:",
            "release_notes:",
            "publish_internal:",
        ),
        "Play dispatch identity",
    )

    jobs = _job_sections(workflow)
    if set(jobs) != {"validate", "sign", "publish", "cleanup"}:
        raise VerificationError(
            "Play workflow must contain only validate, sign, publish, and cleanup jobs: "
            f"{sorted(jobs)}"
        )
    validate = jobs["validate"]
    signer = jobs["sign"]
    publisher = jobs["publish"]
    cleanup = jobs["cleanup"]

    _reject_repository_execution(validate, "Play validator")
    if "${{ secrets." in validate or "environment: play" in validate:
        raise VerificationError("Play validator must not receive signing material")
    if "permissions:\n      contents: write" not in validate:
        raise VerificationError("Play validator needs draft-release visibility")
    for mutation in (
        "gh release create",
        "gh release edit",
        "gh release upload",
        "gh release delete",
        "--method POST",
        "--method PATCH",
        "--method DELETE",
    ):
        if mutation in validate:
            raise VerificationError("Play validator must remain release-read-only")
    _require(
        validate,
        (
            '"refs/heads/main"',
            "2100000000",
            'expected_tag="play-v$PLAY_VERSION_NAME-$PLAY_VERSION_CODE"',
            'expected_asset="DuckySlicer-$PLAY_VERSION_NAME-play-unsigned.aab"',
            'commits/$TRANSPORT_TAG" --jq .sha',
            'releases/tags/$TRANSPORT_TAG"',
            'jq -r .draft <<<"$release_json"',
            'if [ "$total_assets" -ne 1 ] || [ "$asset_count" -ne 1 ]',
            'if [ "$actual_sha256" != "$normalized_sha" ]',
            "Local Play input must remain unsigned",
            "BundleConfig.pb",
            "base/manifest/AndroidManifest.xml",
            "base/lib/arm64-v8a/libduckyslicer.so",
            "base/lib/arm64-v8a/libprusaslicer-jni.so",
            "diff -u expected-native-entries.txt actual-native-entries.txt",
            "name: duckyslicer-play-unsigned-${{ github.run_id }}",
            'if [ "$PUBLISH_INTERNAL" != "true" ]',
            'if [ -z "$PLAY_RELEASE_NOTES" ]',
        ),
        "Play local-artifact validation",
    )

    _reject_repository_execution(signer, "Play signer")
    signer_rules = {
        "depends on validation": "needs: validate" in signer,
        "uses protected play environment": "environment: play" in signer,
        "has artifact-read permission only": (
            "permissions:\n      actions: read" in signer
            and "contents:" not in signer
            and "id-token:" not in signer
            and "attestations:" not in signer
        ),
        "receives exactly four upload-key secrets": signer.count("${{ secrets.") == 4,
        "pins the upload certificate": (
            "DUCKYSLICER_PLAY_CERT_SHA256" in signer
            and "actual_fingerprint" in signer
            and "expected_fingerprint" in signer
        ),
        "rechecks the exact local digest": (
            'if [ "$actual_sha256" != "$normalized_sha" ]' in signer
            and "Signer input differs from the locally verified SHA-256" in signer
        ),
        "removes the temporary upload key": (
            "trap 'rm -f \"$key_file\" \"$cert_file\"' EXIT" in signer
        ),
        "requires an RSA 2048-bit upload key": (
            "Public Key Algorithm: rsaEncryption" in signer
            and '"$key_bits" -lt 2048' in signer
        ),
        "signs and verifies the bundle": (
            "jarsigner" in signer
            and "jarsigner -verify -strict -verbose -certs" in signer
            and "jar verified" in signer
            and "jar is unsigned" in signer
            and "signature_block_count" in signer
            and "bundle_fingerprint" in signer
        ),
        "retains only signed output and checksum": (
            "name: duckyslicer-play-signed" in signer
            and "sha256sum --check" in signer
            and "play.aab.sha256" in signer
        ),
    }
    for description, valid in signer_rules.items():
        if not valid:
            raise VerificationError(f"Play signer isolation failed: {description}")

    _reject_repository_execution(publisher, "Play publisher")
    publisher_rules = {
        "depends on validated signing": "needs: [validate, sign]" in publisher,
        "requires explicit internal publication": (
            "if: ${{ inputs.publish_internal }}" in publisher
        ),
        "uses protected play environment": "environment: play" in publisher,
        "has artifact and OIDC permissions only": (
            "permissions:\n      actions: read\n      id-token: write" in publisher
            and "contents:" not in publisher
        ),
        "receives no long-lived Google or signing secret": "${{ secrets." not in publisher,
        "uses the pinned official keyless auth action": (
            "google-github-actions/auth@7c6bc770dae815cd3e89ee6cdf493a5fab2cc093"
            in publisher
            and "token_format: access_token" in publisher
            and "https://www.googleapis.com/auth/androidpublisher" in publisher
            and "access_token_lifetime: 900s" in publisher
            and "create_credentials_file: false" in publisher
            and "export_environment_variables: false" in publisher
        ),
        "binds OIDC to explicit public configuration": (
            "DUCKYSLICER_GOOGLE_WORKLOAD_IDENTITY_PROVIDER" in publisher
            and "DUCKYSLICER_GOOGLE_PLAY_SERVICE_ACCOUNT" in publisher
            and "workloadIdentityPools" in publisher
            and "iam\\.gserviceaccount\\.com" in publisher
        ),
        "downloads and rechecks the exact signed bundle": (
            "name: duckyslicer-play-signed" in publisher
            and 'signed_aab="signed/DuckySlicer-$PLAY_VERSION_NAME-play.aab"'
            in publisher
            and "sha256sum --check" in publisher
        ),
        "pins the application and internal track": (
            "PLAY_PACKAGE_NAME: com.ashcastle.duckyslicer" in publisher
            and '"$api_root/edits/$edit_id/tracks/internal"' in publisher
            and "status: \"completed\"" in publisher
        ),
        "rejects stale or reused version codes": (
            '"$api_root/edits/$edit_id/bundles"' in publisher
            and "highest_version_code" in publisher
            and '"$PLAY_VERSION_CODE" -le "$highest_version_code"' in publisher
            and '"$uploaded_version_code" != "$PLAY_VERSION_CODE"' in publisher
        ),
        "uses the official edit and resumable-upload protocol": (
            '"$api_root/edits"' in publisher
            and '"$upload_root/edits/$edit_id/bundles?uploadType=resumable"'
            in publisher
            and "X-Upload-Content-Type: application/octet-stream" in publisher
            and "X-Upload-Content-Length: $bundle_size" in publisher
            and "--request PUT" in publisher
            and "androidpublisher\\.googleapis\\.com/upload/" in publisher
            and '"$api_root/edits/$edit_id:validate"' in publisher
            and '"$api_root/edits/$edit_id:commit?changesInReviewBehavior=ERROR_IF_IN_REVIEW"'
            in publisher
        ),
        "removes an uncommitted edit after failure": (
            "cleanup_edit()" in publisher
            and "trap cleanup_edit EXIT" in publisher
            and "--request DELETE" in publisher
            and 'edit_committed="true"' in publisher
        ),
        "records a non-secret publication summary": (
            "Google Play internal release" in publisher
            and "Previous maximum versionCode" in publisher
            and "GITHUB_STEP_SUMMARY" in publisher
        ),
    }
    for description, valid in publisher_rules.items():
        if not valid:
            raise VerificationError(f"Play publisher isolation failed: {description}")
    for forbidden in (
        "tracks/production",
        "tracks/beta",
        "tracks/alpha",
        "credentials_json:",
        "GOOGLE_APPLICATION_CREDENTIALS",
        "upload-google-play",
        "gradle-play-publisher",
    ):
        if forbidden.lower() in publisher.lower():
            raise VerificationError(
                f"Play publisher must remain keyless and internal-only: {forbidden}"
            )

    _reject_repository_execution(cleanup, "Play cleanup")
    if "${{ secrets." in cleanup or "environment: play" in cleanup:
        raise VerificationError("Play cleanup must not receive signing material")
    _require(
        cleanup,
        (
            "needs: [validate, sign, publish]",
            "always() && needs.validate.result == 'success'",
            "permissions:\n      contents: write",
            'commits/$TRANSPORT_TAG" --jq .sha',
            'jq -r .draft <<<"$release_json"',
            'if [ "$asset_sha" != "$normalized_sha" ]',
            'gh release delete "$TRANSPORT_TAG" --yes',
        ),
        "Play private-draft cleanup",
    )
    if "--cleanup-tag" in cleanup:
        raise VerificationError("Play cleanup must retain the durable source tag")

    local_preparer = sources["prepare_local_play_bundle.py"]
    _require(
        local_preparer,
        (
            "run_local_gate.py",
            "--require-api-36",
            "--no-build-cache",
            ":app:clean",
            ":app:bundleRelease",
            ":app:packageReleaseUniversalApk",
            "verify_reproducible(candidate_bundle, RELEASE_AAB)",
            "verify_reproducible(candidate_delivery, DELIVERY_APK)",
            "verify_unsigned_apk(delivery_apk",
            "SIGNING_ENVIRONMENT",
            "play_transport_tag",
            "R8_MAPPING_ENTRY",
            "REQUIRED_DEBUG_SYMBOL_ENTRIES",
            "missing production diagnostics",
        ),
        "Local Play preparer",
    )
    _require(
        sources["build.gradle.kts"],
        ('ndk.debugSymbolLevel = "FULL"',),
        "Release Gradle configuration",
    )
    _require(
        sources["Cargo.toml"],
        ("[profile.release]", 'debug = 1'),
        "Rust release profile",
    )
    if "strip =" in sources["Cargo.toml"]:
        raise VerificationError("Rust release profile must preserve native debug symbols")
    _require(
        sources["build.sh"],
        (
            'cp "$runtime_so" "$output_so"',
            "runtime is missing its native symbol table",
            "runtime is missing full debug information",
        ),
        "Inherited slicer runtime build",
    )
    if "--strip-unneeded" in sources["build.sh"]:
        raise VerificationError("Inherited slicer runtime must reach Gradle with symbols")

    release_publish = _job_sections(sources["sign-local-release.yml"]).get(
        "publish", ""
    )
    if ".aab" in release_publish.lower():
        raise VerificationError("GitHub Release publish job must remain free of AAB files")

    documentation = " ".join(
        (
            sources["RELEASING.md"],
            sources["SECURITY.md"],
            sources["CONTRIBUTING.md"],
        )
    ).lower()
    for marker in (
        "prepare_local_play_bundle.py",
        "built twice",
        "never builds the play aab",
        "separate play upload key",
        "workload identity federation",
        "internal track",
        "error_if_in_review",
        "duckyslicer-play-signed",
        "api 36",
        "native debug symbols",
        "r8 mapping",
    ):
        if marker not in documentation:
            raise VerificationError(f"Play handoff documentation is missing: {marker}")


def read_sources() -> dict[str, str]:
    return {
        "play-bundle.yml": (ROOT / ".github/workflows/play-bundle.yml").read_text(
            encoding="utf-8"
        ),
        "sign-local-release.yml": (
            ROOT / ".github/workflows/sign-local-release.yml"
        ).read_text(encoding="utf-8"),
        "prepare_local_play_bundle.py": (
            ROOT / "tools/prepare_local_play_bundle.py"
        ).read_text(encoding="utf-8"),
        "build.gradle.kts": (ROOT / "android/app/build.gradle.kts").read_text(
            encoding="utf-8"
        ),
        "Cargo.toml": (ROOT / "rust/duckyslicer-jni/Cargo.toml").read_text(
            encoding="utf-8"
        ),
        "build.sh": (ROOT / "native/slicer-runtime/build.sh").read_text(
            encoding="utf-8"
        ),
        "RELEASING.md": (ROOT / "docs/RELEASING.md").read_text(encoding="utf-8"),
        "SECURITY.md": (ROOT / "SECURITY.md").read_text(encoding="utf-8"),
        "CONTRIBUTING.md": (ROOT / "CONTRIBUTING.md").read_text(encoding="utf-8"),
    }


def main() -> None:
    try:
        verify_play_bundle_workflow(read_sources())
    except (OSError, VerificationError) as error:
        raise SystemExit(f"Play workflow verification failed: {error}") from error
    print("Verified local Play AAB preparation, isolated signing, and guarded keyless internal publishing")


if __name__ == "__main__":
    main()
