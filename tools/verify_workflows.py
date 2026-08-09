#!/usr/bin/env python3
"""Enforce immutable GitHub Action references and release gate ordering."""

from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
WORKFLOW_ROOT = ROOT / ".github/workflows"
PINNED_REF = re.compile(r"[0-9a-f]{40}")
USES = re.compile(r"^\s*-?\s*uses:\s*([^\s#]+)", re.MULTILINE)
JOB = re.compile(r"^  ([A-Za-z0-9_-]+):\s*$", re.MULTILINE)


def job_sections(workflow: str) -> dict[str, str]:
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


def literal_block(section: str, marker: str) -> list[str]:
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


def main() -> None:
    errors: list[str] = []
    action_count = 0
    workflows = sorted(WORKFLOW_ROOT.glob("*.yml"))
    if not workflows:
        raise SystemExit("No GitHub Actions workflows found")
    for workflow in workflows:
        source = workflow.read_text(encoding="utf-8")
        for reference in USES.findall(source):
            if reference.startswith("./"):
                continue
            action_count += 1
            if "@" not in reference:
                errors.append(f"{workflow.name}: Action has no ref: {reference}")
                continue
            _, revision = reference.rsplit("@", 1)
            if PINNED_REF.fullmatch(revision) is None:
                errors.append(f"{workflow.name}: Action is not pinned to a full commit: {reference}")

    android_source = (WORKFLOW_ROOT / "android.yml").read_text(encoding="utf-8")
    release_source = (WORKFLOW_ROOT / "release.yml").read_text(encoding="utf-8")
    play_source = (WORKFLOW_ROOT / "play-bundle.yml").read_text(encoding="utf-8")
    android_jobs = job_sections(android_source)
    release_jobs = job_sections(release_source)
    expected_release_jobs = {"build", "sign", "publish"}
    if set(release_jobs) != expected_release_jobs:
        errors.append(
            "release.yml: expected build, sign, and publish jobs; "
            f"found {sorted(release_jobs)}"
        )
    required_android_gates = {
        "Gradle uses strict dependency verification": (
            "./gradlew --dependency-verification=strict"
        ),
        "Gradle trust data is structurally verified": (
            "python3 tools/verify_gradle_supply_chain.py"
        ),
        "Rust compiler is pinned": "toolchain: 1.91.1",
        "Rust JNI failure containment is verified": (
            "python3 tools/verify_native_safety.py"
        ),
        "Orca runtime process isolation is verified": (
            "python3 tools/verify_android_isolation.py"
        ),
        "offline open-source distribution is verified": (
            "python3 tools/verify_open_source_distribution.py"
        ),
        "runtime persistence and LAN inputs are bounded": (
            "python3 tools/verify_runtime_resilience.py"
        ),
        "component license inventory is generated": (
            "python3 tools/generate_license_inventory.py"
        ),
        "offline license policy is unit tested": "tools.test_generate_offline_licenses",
        "source bundle policy is unit tested": "tools.test_generate_source_bundle",
        "release reproducibility policy is unit tested": (
            "tools.test_verify_reproducible_release"
        ),
        "runtime resilience policy is unit tested": (
            "tools.test_verify_runtime_resilience"
        ),
        "data-practice explanation is verified": (
            "python3 tools/verify_data_practices.py"
        ),
        "data-practice policy is unit tested": "tools.test_verify_data_practices",
        "release publication contract is verified": (
            "python3 tools/verify_release_contract.py"
        ),
        "release publication contract is unit tested": (
            "tools.test_verify_release_contract"
        ),
        "Play bundle isolation is verified": (
            "python3 tools/verify_play_bundle_workflow.py"
        ),
        "Play bundle isolation is unit tested": (
            "tools.test_verify_play_bundle_workflow"
        ),
        "CodeQL traces the real Kotlin build": (
            "github/codeql-action/init@5595ccaf912efad79be6eef63a5619ff05969be3"
        ),
        "CodeQL uses manual Java and Kotlin extraction": (
            "languages: java-kotlin\n          build-mode: manual"
        ),
        "CodeQL runs the extended security suite": "queries: security-extended",
        "CodeQL uploads the Java and Kotlin analysis": (
            "github/codeql-action/analyze@5595ccaf912efad79be6eef63a5619ff05969be3"
        ),
        "generated G-code storage policy is verified": (
            "python3 tools/verify_slice_storage.py"
        ),
        "generated G-code storage policy is unit tested": (
            "tools.test_verify_slice_storage"
        ),
        "primitive preview boundary is verified": (
            "python3 tools/verify_preview_boundary.py"
        ),
        "primitive preview boundary is unit tested": (
            "tools.test_verify_preview_boundary"
        ),
        "Play App Bundle is assembled": (
            ":app:bundleRelease :app:packageReleaseUniversalApk"
        ),
        "Play delivery APK is structurally verified": (
            'play_apk="android/app/build/outputs/apk_from_bundle/release/'
            'app-release-universal-unsigned.apk"'
        ),
        "Play delivery APK is 16 KB aligned": (
            'zipalign" -c -P 16 -v 4 "$play_apk"'
        ),
        "Play delivery APK runs the full APK verifier": (
            'python3 tools/verify_apk.py "$play_apk"'
        ),
    }
    for description, marker in required_android_gates.items():
        if marker not in android_source:
            errors.append(f"android.yml: missing gate: {description}")

    verify_job = android_jobs.get("verify", "")
    codeql_fork_guard = (
        "github.event_name != 'pull_request' || "
        "github.event.pull_request.head.repo.full_name == github.repository"
    )
    codeql_permissions = "permissions:\n      contents: read\n      security-events: write"
    if codeql_permissions not in verify_job:
        errors.append(
            "android.yml: CodeQL verify job needs only contents read and "
            "security-events write"
        )
    if verify_job.count(codeql_fork_guard) != 2:
        errors.append(
            "android.yml: CodeQL init and analyze must skip untrusted fork pull requests"
        )
    codeql_init_index = verify_job.find("github/codeql-action/init@")
    gradle_index = verify_job.find("./gradlew")
    codeql_analyze_index = verify_job.find("github/codeql-action/analyze@")
    if (
        codeql_init_index >= 0
        and gradle_index >= 0
        and codeql_init_index > gradle_index
    ):
        errors.append("android.yml: CodeQL must initialize before the Kotlin build")
    if (
        codeql_analyze_index >= 0
        and gradle_index >= 0
        and codeql_analyze_index < gradle_index
    ):
        errors.append("android.yml: CodeQL must analyze after the Kotlin build")

    if "device-tests" in android_jobs or "runs-on: macos-14" in android_source:
        errors.append("android.yml: hosted emulator jobs are not allowed")
    if "app-release.aab" in release_jobs.get("publish", ""):
        errors.append("release.yml: GitHub Releases must remain APK-only")

    required_release_gates = {
        "sign depends on build": "  sign:\n    needs: build\n",
        "publish depends on build and signer": (
            "  publish:\n    needs: [build, sign]\n"
        ),
        "publish has attestation permission": "      attestations: write\n",
        "publish has release permission": "      contents: write\n",
        "signed release APK runs structural verifier": (
            'python3 tools/verify_apk.py "${release_apks[0]}"'
        ),
        "build provenance covers only the published APK": (
            'subject-path: "release/DuckySlicer-*-arm64.apk"'
        ),
        "Gradle uses strict dependency verification": (
            "./gradlew --dependency-verification=strict"
        ),
        "Gradle trust data is structurally verified": (
            "python3 tools/verify_gradle_supply_chain.py"
        ),
        "Rust compiler is pinned": "toolchain: 1.91.1",
        "Rust JNI failure containment is verified": (
            "python3 tools/verify_native_safety.py"
        ),
        "Orca runtime process isolation is verified": (
            "python3 tools/verify_android_isolation.py"
        ),
        "offline open-source distribution is verified": (
            "python3 tools/verify_open_source_distribution.py"
        ),
        "runtime persistence and LAN inputs are bounded": (
            "python3 tools/verify_runtime_resilience.py"
        ),
        "component license inventory is generated": (
            "python3 tools/generate_license_inventory.py"
        ),
        "offline license policy is unit tested": "tools.test_generate_offline_licenses",
        "source bundle policy is unit tested": "tools.test_generate_source_bundle",
        "release reproducibility policy is unit tested": (
            "tools.test_verify_reproducible_release"
        ),
        "runtime resilience policy is unit tested": (
            "tools.test_verify_runtime_resilience"
        ),
        "data-practice explanation is verified": (
            "python3 tools/verify_data_practices.py"
        ),
        "data-practice policy is unit tested": "tools.test_verify_data_practices",
        "release publication contract is verified": (
            "python3 tools/verify_release_contract.py"
        ),
        "release publication contract is unit tested": (
            "tools.test_verify_release_contract"
        ),
        "Play bundle isolation is verified": (
            "python3 tools/verify_play_bundle_workflow.py"
        ),
        "Play bundle isolation is unit tested": (
            "tools.test_verify_play_bundle_workflow"
        ),
        "generated G-code storage policy is verified": (
            "python3 tools/verify_slice_storage.py"
        ),
        "generated G-code storage policy is unit tested": (
            "tools.test_verify_slice_storage"
        ),
        "primitive preview boundary is verified": (
            "python3 tools/verify_preview_boundary.py"
        ),
        "primitive preview boundary is unit tested": (
            "tools.test_verify_preview_boundary"
        ),
        "unsigned release is rebuilt without the build cache": (
            "Rebuild and verify reproducible unsigned release"
        ),
        "unsigned release bytes are compared": (
            "python3 tools/verify_reproducible_release.py"
        ),
        "recursive corresponding source is generated": (
            "python3 tools/generate_source_bundle.py"
        ),
    }
    for description, marker in required_release_gates.items():
        if marker not in release_source:
            errors.append(f"release.yml: missing gate: {description}")

    required_play_gates = {
        "manual dispatch only": "  workflow_dispatch:\n",
        "strict Gradle verification": "./gradlew --dependency-verification=strict",
        "Play isolation verifier runs before build": (
            "python3 tools/verify_play_bundle_workflow.py"
        ),
        "signed Play artifact is retained": "name: duckyslicer-play-signed",
    }
    for description, marker in required_play_gates.items():
        if marker not in play_source:
            errors.append(f"play-bundle.yml: missing gate: {description}")

    build = release_jobs.get("build", "")
    signer = release_jobs.get("sign", "")
    publish = release_jobs.get("publish", "")
    release_assets = literal_block(publish, "          files: |")
    if release_assets != ["release/DuckySlicer-*-arm64.apk"]:
        errors.append(
            "release.yml: GitHub Release assets must contain exactly the signed ARM64 APK; "
            f"found {release_assets}"
        )
    isolated_signing_rules = {
        "build produces an unsigned release": (
            "app-release-unsigned.apk" in build
            and "${{ secrets." not in build
            and "apksigner sign" not in build
        ),
        "signer uses the protected release environment": "environment: release" in signer,
        "signer has artifact-read permission only": (
            "permissions:\n      actions: read" in signer
            and "contents:" not in signer
            and "id-token:" not in signer
            and "attestations:" not in signer
        ),
        "signer receives all secrets only in its own section": (
            signer.count("${{ secrets.") == 4 and "${{ secrets." not in publish
        ),
        "signer does not checkout or execute project build code": (
            "actions/checkout@" not in signer
            and "./gradlew" not in signer
            and "python3 tools/" not in signer
        ),
        "signer pins and verifies the signing certificate": (
            "DUCKYSLICER_SIGNING_CERT_SHA256" in signer
            and "actual_fingerprint" in signer
            and "expected_fingerprint" in signer
        ),
        "signer removes its temporary keystore before later actions": (
            "trap 'rm -f \"$key_file\"' EXIT" in signer
        ),
        "hosted emulator jobs are absent": (
            "device-tests" not in release_jobs
            and "runs-on: macos-14" not in release_source
            and "system-images;android-35;google_apis_ps16k" not in release_source
        ),
        "publish regenerates the SBOM from the signed artifact": (
            "tools/generate_sbom.py" in publish
            and "duckyslicer-release-signed" in publish
        ),
        "publish verifies a release checksum manifest": (
            "SHA256SUMS.txt" in publish and "sha256sum --check" in publish
        ),
        "build publishes recursive corresponding source": (
            "duckyslicer-release-source" in build
            and "DuckySlicer-$DUCKYSLICER_RELEASE_VERSION-source.tar.gz" in build
            and "DuckySlicer-$DUCKYSLICER_RELEASE_VERSION-SOURCE-MANIFEST.json" in build
        ),
        "publish verifies corresponding source before release": (
            "duckyslicer-release-source" in publish
            and "tools/generate_source_bundle.py --verify" in publish
            and "source.tar.gz" in publish
            and "SOURCE-MANIFEST.json" in publish
        ),
        "release checksums cover corresponding source": (
            '"DuckySlicer-$DUCKYSLICER_RELEASE_VERSION-source.tar.gz"' in publish
            and '"DuckySlicer-$DUCKYSLICER_RELEASE_VERSION-SOURCE-MANIFEST.json"'
            in publish
        ),
        "only publish can write releases": (
            release_source.count("contents: write") == 1
            and "contents: write" in publish
            and release_source.count("attestations: write") == 1
            and "attestations: write" in publish
        ),
    }
    for description, valid in isolated_signing_rules.items():
        if not valid:
            errors.append(f"release.yml: isolated signing invariant failed: {description}")

    reproducibility_order = (
        build.find("Stage first release build"),
        build.find("Rebuild and verify reproducible unsigned release"),
        build.find("Generate recursive corresponding source"),
    )
    if min(reproducibility_order) < 0 or reproducibility_order != tuple(
        sorted(reproducibility_order)
    ):
        errors.append(
            "release.yml: first build staging, independent rebuild, and source generation "
            "must run in that order"
        )

    if errors:
        raise SystemExit("Workflow verification failed:\n- " + "\n- ".join(errors))
    print(
        f"Verified {len(workflows)} workflows and {action_count} immutable Action references; "
        "CodeQL, isolated signing, and static ARM64 16 KB checks are enforced"
    )


if __name__ == "__main__":
    main()
