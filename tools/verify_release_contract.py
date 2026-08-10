#!/usr/bin/env python3
"""Keep local release preparation, isolated signing, and public guidance consistent."""

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
            match.start() : matches[index + 1].start() if index + 1 < len(matches) else None
        ]
        for index, match in enumerate(matches)
    }


def _normalized(source: str) -> str:
    return " ".join(source.lower().split())


def _require_markers(name: str, source: str, markers: tuple[str, ...]) -> None:
    missing = [marker for marker in markers if marker not in source]
    if missing:
        raise VerificationError(f"{name} is missing local-release safeguards: {missing}")


def verify_release_contract(sources: dict[str, str]) -> None:
    required_files = {
        "sign-local-release.yml",
        "prepare_local_release.py",
        "RELEASING.md",
        "SECURITY.md",
        "CONTRIBUTING.md",
    }
    missing_files = sorted(required_files - sources.keys())
    if missing_files:
        raise VerificationError(f"release-contract sources are missing: {missing_files}")

    workflow = sources["sign-local-release.yml"]
    header, separator, _ = workflow.partition("\njobs:\n")
    if not separator or "workflow_dispatch:" not in header:
        raise VerificationError("local release signing must be manually dispatched")
    for automatic_trigger in ("pull_request:", "push:", "schedule:"):
        if automatic_trigger in header:
            raise VerificationError(
                f"local release signing must not use automatic trigger: {automatic_trigger}"
            )

    jobs = _job_sections(workflow)
    if set(jobs) != {"validate", "sign", "publish"}:
        raise VerificationError(
            "local release workflow must contain validate, sign, and publish jobs: "
            f"found {sorted(jobs)}"
        )
    validate = jobs["validate"]
    signer = jobs["sign"]
    publish = jobs["publish"]

    _require_markers(
        "local release validator",
        validate,
        (
            "permissions:\n      contents: write",
            "UNSIGNED_SHA256: ${{ inputs.unsigned_sha256 }}",
            "RELEASE_VERSION_CODE: ${{ inputs.version_code }}",
            "SOURCE_COMMIT: ${{ inputs.source_commit }}",
            'if [ "$GITHUB_REF" != "refs/heads/main" ]',
            'gh api "repos/$GITHUB_REPOSITORY/commits/$RELEASE_TAG" --jq .sha',
            'if [ "$actual_sha256" != "$UNSIGNED_SHA256" ]',
            'package_name" != "com.ashcastle.duckyslicer"',
            'actual_version_code" != "$RELEASE_VERSION_CODE"',
            'actual_version_name" != "$version"',
            "Release notes must describe the user-visible changes before signing",
            "<!-- duckyslicer-release-integrity -->",
            "--jq '.body | @base64'",
            "release_notes_sha256=$(printf '%s' \"$release_body_base64\" | sha256sum",
            "release_notes_sha256=$release_notes_sha256",
            'zipalign" -c -P 16 -v 4 "$unsigned_apk"',
            'apksigner" verify "$unsigned_apk"',
            "name: duckyslicer-local-unsigned-${{ github.run_id }}",
        ),
    )
    if any(
        marker in validate
        for marker in ("${{ secrets.", "environment: release", "./gradlew", "actions/checkout@")
    ):
        raise VerificationError("validation must not receive signing keys or execute builds")
    if any(
        marker in validate
        for marker in (
            "gh release edit",
            "gh release upload",
            "gh release delete",
            "gh api --method",
            "git push",
            "curl -X",
        )
    ):
        raise VerificationError("validation may inspect but must not mutate the draft release")

    signer_rules = {
        "depends on validation": "needs: validate" in signer,
        "uses protected release environment": "environment: release" in signer,
        "has artifact-read permission only": (
            "permissions:\n      actions: read" in signer
            and "contents:" not in signer
            and "id-token:" not in signer
            and "attestations:" not in signer
        ),
        "receives exactly four private key inputs": signer.count("${{ secrets.") == 4,
        "pins the signing certificate": (
            "DUCKYSLICER_SIGNING_CERT_SHA256" in signer
            and "actual_fingerprint" in signer
            and "expected_fingerprint" in signer
        ),
        "rechecks validated bytes": "Validated unsigned artifact changed before signing" in signer,
        "does not execute repository code": (
            "actions/checkout@" not in signer
            and "./gradlew" not in signer
            and "python3 tools/" not in signer
        ),
        "removes temporary key": "trap 'rm -f \"$key_file\"' EXIT" in signer,
    }
    for description, valid in signer_rules.items():
        if not valid:
            raise VerificationError(f"release signer isolation failed: {description}")

    _require_markers(
        "local release publisher",
        publish,
        (
            "needs: [validate, sign]",
            "contents: write",
            "Release tag changed after local artifact validation",
            "Release was published before the isolated signer completed",
            'gh release upload "$RELEASE_TAG" "$signed_apk"',
            'gh release delete-asset "$RELEASE_TAG" "$UNSIGNED_ASSET"',
            "Refusing to publish a release without exactly one signed APK",
            "RELEASE_NOTES_SHA256: ${{ needs.validate.outputs.release_notes_sha256 }}",
            "Release notes changed after local artifact validation",
            "Release notes changed while replacing the draft artifact",
            'signed_sha256=$(sha256sum "$signed_apk"',
            "Signer #1 certificate SHA-256 digest",
            "DUCKYSLICER_SIGNING_CERT_SHA256",
            "<!-- duckyslicer-release-integrity -->",
            'APK SHA-256: `%s`',
            'Signing certificate SHA-256: `%s`',
            '--notes-file "$release_notes"',
            'gh release edit "$RELEASE_TAG"',
            "--draft=false",
        ),
    )
    if "${{ secrets." in publish or "./gradlew" in publish or "actions/checkout@" in publish:
        raise VerificationError("publisher must not receive signing keys or build repository code")
    ordered_publication = (
        "Release notes changed after local artifact validation",
        'gh release upload "$RELEASE_TAG" "$signed_apk"',
        "Release notes changed while replacing the draft artifact",
        "<!-- duckyslicer-release-integrity -->",
        'gh release edit "$RELEASE_TAG"',
    )
    positions = [publish.index(marker) for marker in ordered_publication]
    if positions != sorted(positions) or len(set(positions)) != len(positions):
        raise VerificationError(
            "publisher must pin notes before artifact mutation and recheck them before publication"
        )
    if (
        workflow.count("contents: write") != 2
        or "contents: write" not in validate
        or "contents: write" not in publish
        or "contents: write" in signer
    ):
        raise VerificationError(
            "only draft validation and publication may receive Release-capable tokens"
        )
    if any(marker in workflow for marker in ("assembleRelease", "app-release.aab", "tags:")):
        raise VerificationError("GitHub release automation must not build or publish non-APK artifacts")

    preparation = sources["prepare_local_release.py"]
    _require_markers(
        "prepare_local_release.py",
        preparation,
        (
            'str(ROOT / "tools/run_local_gate.py")',
            '"--require-api-36"',
            '"--no-build-cache",',
            '":app:clean",',
            'command.append(":app:assembleRelease")',
            "verify_reproducible(candidate_output, RELEASE_APK)",
            "verify_reproducible(candidate_mapping, RELEASE_MAPPING)",
            "verify_reproducible(candidate_symbols, RELEASE_NATIVE_SYMBOLS)",
            "verify_release_diagnostics(RELEASE_MAPPING, RELEASE_NATIVE_SYMBOLS)",
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
            '"localR8MappingSha256": self.local_r8_mapping_sha256',
            '"localNativeSymbolsSha256": self.local_native_symbols_sha256',
        ),
    )

    documentation_markers = {
        "RELEASING.md": (
            "python3 tools/prepare_local_release.py",
            "github actions never builds the github release apk",
            "github release contains exactly one public asset: the signed arm64 apk",
            "release notes must describe user-visible changes",
            "appends the signed apk sha-256, signing-certificate fingerprint, and source tag",
            "duckyslicer_16kb_api36",
            "android 16/api 36",
            "local-native-symbols",
            "local-r8-mapping",
            "must not be uploaded to the public github release",
        ),
        "SECURITY.md": (
            "release apk is built twice on the maintainer's local machine",
            "sha-256, package name, versioncode, versionname, and tag commit",
            "release exposes exactly one downloadable asset: the signed arm64 apk",
            "published release notes contain the signed apk sha-256, signing-certificate sha-256",
        ),
        "CONTRIBUTING.md": (
            "python3 tools/run_local_gate.py",
            "python3 tools/prepare_local_release.py",
            "github release apk must be built locally",
            "github release must contain only the signed arm64 apk",
            "publishes the apk sha-256 and signing-certificate fingerprint in the release notes",
        ),
    }
    for source_name, markers in documentation_markers.items():
        document = _normalized(sources[source_name])
        for marker in markers:
            if marker not in document:
                raise VerificationError(
                    f"{source_name} does not state the current release contract: {marker}"
                )

    stale_claims = {
        "RELEASING.md": (
            "tagged releases are built from source by github actions",
            "workflow derives versionname from the tag",
        ),
        "SECURITY.md": (
            "tagged pre-release apks are built by the repository release workflow",
            "apk-bound github provenance attestation",
        ),
    }
    for source_name, claims in stale_claims.items():
        document = _normalized(sources[source_name])
        for claim in claims:
            if claim in document:
                raise VerificationError(
                    f"{source_name} still claims a hosted APK build: {claim}"
                )


def read_sources() -> dict[str, str]:
    return {
        "sign-local-release.yml": (
            ROOT / ".github/workflows/sign-local-release.yml"
        ).read_text(encoding="utf-8"),
        "prepare_local_release.py": (ROOT / "tools/prepare_local_release.py").read_text(
            encoding="utf-8"
        ),
        "RELEASING.md": (ROOT / "docs/RELEASING.md").read_text(encoding="utf-8"),
        "SECURITY.md": (ROOT / "SECURITY.md").read_text(encoding="utf-8"),
        "CONTRIBUTING.md": (ROOT / "CONTRIBUTING.md").read_text(encoding="utf-8"),
    }


def main() -> None:
    try:
        verify_release_contract(read_sources())
    except (OSError, VerificationError) as error:
        raise SystemExit(f"Release-contract verification failed: {error}") from error
    print("Verified reproducible local APK preparation and isolated validate/sign/publish release")


if __name__ == "__main__":
    main()
