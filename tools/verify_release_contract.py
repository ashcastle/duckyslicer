#!/usr/bin/env python3
"""Keep release automation, public assets, and contributor guidance consistent."""

from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
JOB = re.compile(r"^  ([A-Za-z0-9_-]+):\s*$", re.MULTILINE)
EXPECTED_RELEASE_ASSETS = ["release/DuckySlicer-*-arm64.apk"]


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


def _literal_block(section: str, marker: str) -> list[str]:
    _, separator, remainder = section.partition(marker + "\n")
    if not separator:
        return []
    indentation = len(marker) - len(marker.lstrip()) + 2
    values: list[str] = []
    for line in remainder.splitlines():
        if not line.strip():
            continue
        if len(line) - len(line.lstrip()) < indentation:
            break
        values.append(line.strip())
    return values


def _normalized(source: str) -> str:
    return " ".join(source.lower().split())


def verify_release_contract(sources: dict[str, str]) -> None:
    required_files = {
        "release.yml",
        "RELEASING.md",
        "SECURITY.md",
        "CONTRIBUTING.md",
    }
    missing_files = sorted(required_files - sources.keys())
    if missing_files:
        raise VerificationError(f"release-contract sources are missing: {missing_files}")

    workflow = sources["release.yml"]
    publish = _job_sections(workflow).get("publish", "")
    if not publish:
        raise VerificationError("release workflow has no publish job")

    release_assets = _literal_block(publish, "          files: |")
    if release_assets != EXPECTED_RELEASE_ASSETS:
        raise VerificationError(
            "GitHub Release must expose exactly one signed ARM64 APK: "
            f"found {release_assets}"
        )
    if 'subject-path: "release/DuckySlicer-*-arm64.apk"' not in publish:
        raise VerificationError("build provenance must cover only the published APK")
    if ".aab" in publish.lower():
        raise VerificationError("Play bundles must not be published on GitHub Releases")
    for evidence_marker in (
        "tools/generate_source_bundle.py --verify",
        "tools/generate_sbom.py",
        "SHA256SUMS.txt",
        "duckyslicer-release-audit",
    ):
        if evidence_marker not in publish:
            raise VerificationError(
                f"release audit evidence is no longer verified before publication: {evidence_marker}"
            )

    documentation_markers = {
        "RELEASING.md": (
            "github release contains exactly one public asset: the signed arm64 apk",
            "github actions does not run an android emulator",
            "duckyslicer_16kb_api35",
        ),
        "SECURITY.md": (
            "release exposes exactly one downloadable asset: the signed arm64 apk",
            "github-hosted emulators are not part of the release pipeline",
            "local arm64 16 kb avd",
        ),
        "CONTRIBUTING.md": (
            "python3 tools/run_local_gate.py",
            "local arm64 16 kb avd is the authoritative functional gate",
            "hosted emulator jobs must remain absent",
            "github release must contain only the signed arm64 apk",
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
            "publish job cannot run until that exact signed apk passes",
            "release contains the arm64 apk, cyclonedx",
        ),
        "SECURITY.md": ("before the same artifact is tested and published",),
        "CONTRIBUTING.md": ("arm64 device test → publish",),
    }
    for source_name, claims in stale_claims.items():
        document = _normalized(sources[source_name])
        for claim in claims:
            if claim in document:
                raise VerificationError(
                    f"{source_name} still claims a removed hosted-device gate: {claim}"
                )


def read_sources() -> dict[str, str]:
    return {
        "release.yml": (ROOT / ".github/workflows/release.yml").read_text(
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
    print("Verified APK-only publication and local ARM64 16 KB functional-gate guidance")


if __name__ == "__main__":
    main()
