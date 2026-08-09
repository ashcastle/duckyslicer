#!/usr/bin/env python3
"""Enforce isolated, manual signing for Play App Bundles."""

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


def verify_play_bundle_workflow(sources: dict[str, str]) -> None:
    required_files = {"play-bundle.yml", "release.yml", "RELEASING.md"}
    missing_files = sorted(required_files - sources.keys())
    if missing_files:
        raise VerificationError(f"Play workflow sources are missing: {missing_files}")

    workflow = sources["play-bundle.yml"]
    header, separator, _ = workflow.partition("\njobs:\n")
    if not separator or "workflow_dispatch:" not in header:
        raise VerificationError("Play bundle workflow must be manually dispatched")
    for automatic_trigger in ("pull_request:", "push:", "schedule:"):
        if automatic_trigger in header:
            raise VerificationError(
                f"Play bundle workflow must not use automatic trigger: {automatic_trigger}"
            )

    jobs = _job_sections(workflow)
    if set(jobs) != {"build", "sign"}:
        raise VerificationError(
            f"Play workflow must contain only isolated build and sign jobs: {sorted(jobs)}"
        )
    build = jobs["build"]
    signer = jobs["sign"]

    build_markers = (
        ":app:bundleRelease :app:packageReleaseUniversalApk",
        "app-release.aab",
        "app-release-universal-unsigned.apk",
        'zipalign" -c -P 16 -v 4 "$delivery_apk"',
        'python3 tools/verify_apk.py "$delivery_apk"',
        "duckyslicer-play-unsigned",
        "2100000000",
        "DUCKYSLICER_PLAY_VERSION_NAME",
        "DUCKYSLICER_PLAY_VERSION_CODE",
        '"refs/heads/main"',
        '${#PLAY_VERSION_NAME_INPUT}" -gt 64',
        '${#PLAY_VERSION_CODE_INPUT}" -gt 10',
        "aapt\" dump badging",
        "versionCode='$DUCKYSLICER_PLAY_VERSION_CODE'",
        "versionName='$DUCKYSLICER_PLAY_VERSION_NAME'",
        "unexpectedly produced a signed bundle",
    )
    for marker in build_markers:
        if marker not in build:
            raise VerificationError(f"Play build gate is missing: {marker}")
    if "${{ secrets." in build or "jarsigner" in build:
        raise VerificationError("Play build job must not receive keys or sign artifacts")

    signer_rules = {
        "uses protected play environment": "environment: play" in signer,
        "has artifact-read permission only": (
            "permissions:\n      actions: read" in signer
            and "contents:" not in signer
            and "id-token:" not in signer
            and "attestations:" not in signer
        ),
        "receives four upload-key secrets": signer.count("${{ secrets.") == 4,
        "pins the upload certificate": (
            "DUCKYSLICER_PLAY_CERT_SHA256" in signer
            and "actual_fingerprint" in signer
            and "expected_fingerprint" in signer
        ),
        "does not execute repository code": (
            "actions/checkout@" not in signer
            and "./gradlew" not in signer
            and "python3 tools/" not in signer
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
            and (
                "jarsigner -verify -strict -verbose -certs \\\n"
                '            -keystore "$key_file" \\\n'
                "            -storepass:env DUCKYSLICER_PLAY_STORE_PASSWORD"
            )
            in signer
            and "jar verified" in signer
            and "jar is unsigned" in signer
            and "signature_block_count" in signer
            and "bundle_fingerprint" in signer
            and "duckyslicer-play-signed" in signer
        ),
        "retains a checksum": (
            "sha256sum --check" in signer and "play.aab.sha256" in signer
        ),
    }
    for description, valid in signer_rules.items():
        if not valid:
            raise VerificationError(f"Play signer isolation failed: {description}")

    forbidden_delivery_markers = (
        "action-gh-release",
        "androidpublisher",
        "gradle-play-publisher",
        "service_account",
        "upload-google-play",
        "contents: write",
        "runs-on: macos-14",
        "device-tests",
    )
    for marker in forbidden_delivery_markers:
        if marker in workflow.lower():
            raise VerificationError(
                f"Play workflow must stop at a signed Actions artifact: {marker}"
            )

    release_publish = _job_sections(sources["release.yml"]).get("publish", "")
    if ".aab" in release_publish.lower():
        raise VerificationError("GitHub Release publish job must remain free of AAB files")

    releasing = " ".join(sources["RELEASING.md"].lower().split())
    for marker in (
        "separate play upload key",
        "never uploads to play console",
        "duckyslicer-play-signed",
    ):
        if marker not in releasing:
            raise VerificationError(f"Play handoff documentation is missing: {marker}")


def read_sources() -> dict[str, str]:
    return {
        "play-bundle.yml": (ROOT / ".github/workflows/play-bundle.yml").read_text(
            encoding="utf-8"
        ),
        "release.yml": (ROOT / ".github/workflows/release.yml").read_text(
            encoding="utf-8"
        ),
        "RELEASING.md": (ROOT / "docs/RELEASING.md").read_text(encoding="utf-8"),
    }


def main() -> None:
    try:
        verify_play_bundle_workflow(read_sources())
    except (OSError, VerificationError) as error:
        raise SystemExit(f"Play workflow verification failed: {error}") from error
    print("Verified manual Play AAB build and isolated upload-key signing")


if __name__ == "__main__":
    main()
