#!/usr/bin/env python3
"""Enforce immutable GitHub Action references and release gate ordering."""

from __future__ import annotations

import re
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
WORKFLOW_ROOT = ROOT / ".github/workflows"
PINNED_REF = re.compile(r"[0-9a-f]{40}")
USES = re.compile(r"^\s*-?\s*uses:\s*([^\s#]+)", re.MULTILINE)
JOB = re.compile(r"^  ([A-Za-z0-9_-]+):\s*$", re.MULTILINE)
RUN = re.compile(r"^(?P<indent>\s*)(?:-\s*)?run:\s*\|\s*$")


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


def literal_run_blocks(workflow: str) -> tuple[str, ...]:
    """Return YAML literal `run: |` blocks without requiring a YAML dependency."""
    lines = workflow.splitlines()
    blocks: list[str] = []
    for index, line in enumerate(lines):
        match = RUN.match(line)
        if match is None:
            continue
        parent_indent = len(match.group("indent"))
        raw: list[str] = []
        for candidate in lines[index + 1 :]:
            indentation = len(candidate) - len(candidate.lstrip())
            if candidate.strip() and indentation <= parent_indent:
                break
            raw.append(candidate)
        content_indents = [
            len(candidate) - len(candidate.lstrip())
            for candidate in raw
            if candidate.strip()
        ]
        if not content_indents:
            blocks.append("")
            continue
        content_indent = min(content_indents)
        blocks.append(
            "\n".join(
                candidate[content_indent:] if candidate.strip() else ""
                for candidate in raw
            ).rstrip()
            + "\n"
        )
    return tuple(blocks)


def shell_syntax_errors(name: str, workflow: str) -> list[str]:
    errors: list[str] = []
    for index, script in enumerate(literal_run_blocks(workflow), start=1):
        result = subprocess.run(
            ("bash", "-n"),
            input=script,
            text=True,
            capture_output=True,
            check=False,
        )
        if result.returncode != 0:
            detail = result.stderr.strip() or "unknown Bash parser error"
            errors.append(f"{name}: run block {index} has invalid Bash syntax: {detail}")
    return errors


def main() -> None:
    errors: list[str] = []
    action_count = 0
    workflows = sorted(WORKFLOW_ROOT.glob("*.yml"))
    if not workflows:
        raise SystemExit("No GitHub Actions workflows found")
    for workflow in workflows:
        source = workflow.read_text(encoding="utf-8")
        errors.extend(shell_syntax_errors(workflow.name, source))
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
    release_source = (WORKFLOW_ROOT / "sign-local-release.yml").read_text(encoding="utf-8")
    play_source = (WORKFLOW_ROOT / "play-bundle.yml").read_text(encoding="utf-8")
    android_jobs = job_sections(android_source)
    release_jobs = job_sections(release_source)
    expected_release_jobs = {"validate", "sign", "publish"}
    if set(release_jobs) != expected_release_jobs:
        errors.append(
            "sign-local-release.yml: expected validate, sign, and publish jobs; "
            f"found {sorted(release_jobs)}"
        )
    release_header, release_separator, _ = release_source.partition("\njobs:\n")
    if not release_separator or "workflow_dispatch:" not in release_header:
        errors.append("sign-local-release.yml: release signing must be manually dispatched")
    for automatic_trigger in ("pull_request:", "push:", "schedule:"):
        if automatic_trigger in release_header:
            errors.append(
                "sign-local-release.yml: release signing must not use automatic trigger: "
                + automatic_trigger
            )
    for workflow in workflows:
        header = workflow.read_text(encoding="utf-8").partition("\njobs:\n")[0]
        if "tags:" in header:
            errors.append(f"{workflow.name}: tag-triggered hosted releases are not allowed")
    required_android_gates = {
        "Gradle uses strict dependency verification": (
            "./gradlew --dependency-verification=strict"
        ),
        "Gradle trust data is structurally verified": (
            "python3 tools/verify_gradle_supply_chain.py"
        ),
        "locked dependency vulnerabilities are audited": (
            "python3 tools/verify_dependency_audit.py"
        ),
        "dependency audit policy is unit tested": (
            "tools.test_verify_dependency_audit"
        ),
        "credential-bearing signed URLs are rejected": (
            "python3 tools/verify_no_embedded_credentials.py"
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
        "local release preparation is unit tested": (
            "tools.test_prepare_local_release"
        ),
        "local Play preparation is unit tested": (
            "tools.test_prepare_local_play_bundle"
        ),
        "merged artifact manifest policy is unit tested": (
            "tools.test_verify_artifact_manifest"
        ),
        "Orca translation generation is unit tested": (
            "tools.test_generate_android_translations"
        ),
        "localization source policy is verified": (
            "python3 tools/verify_localization.py"
        ),
        "localization source policy is unit tested": (
            "tools.test_verify_localization"
        ),
        "packaged localization is unit tested": (
            "tools.test_verify_artifact_localization"
        ),
        "workflow shell syntax verification is unit tested": (
            "tools.test_verify_workflows"
        ),
        "runtime resilience policy is unit tested": (
            "tools.test_verify_runtime_resilience"
        ),
        "data-practice explanation is verified": (
            "python3 tools/verify_data_practices.py"
        ),
        "data-practice policy is unit tested": "tools.test_verify_data_practices",
        "support details are privacy bounded": (
            "python3 tools/verify_support_diagnostics.py"
        ),
        "support-detail policy is unit tested": (
            "tools.test_verify_support_diagnostics"
        ),
        "portable projects are bounded and atomic": (
            "python3 tools/verify_project_archive.py"
        ),
        "project-archive policy is unit tested": "tools.test_verify_project_archive",
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
        "merged debug manifest is verified": (
            "python3 tools/verify_artifact_manifest.py --variant debug"
        ),
        "debug APK language resources are verified": (
            "python3 tools/verify_artifact_localization.py"
        ),
        "safe community contribution routes are verified": (
            "python3 tools/verify_community_health.py"
        ),
        "community contribution policy is unit tested": (
            "tools.test_verify_community_health"
        ),
        "Google Play listing assets are verified": (
            "python3 tools/verify_store_listing.py"
        ),
        "Google Play listing policy is unit tested": (
            "tools.test_verify_store_listing"
        ),
        "repository hygiene is verified": (
            "python3 tools/verify_repository_hygiene.py"
        ),
        "repository hygiene policy is unit tested": (
            "tools.test_verify_repository_hygiene"
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
    for hosted_play_build in (
        ":app:bundleRelease",
        ":app:packageReleaseUniversalApk",
        "app-release.aab",
        "app-release-universal-unsigned.apk",
    ):
        if hosted_play_build in android_source:
            errors.append(
                "android.yml: hosted CI must not assemble Play artifacts: "
                + hosted_play_build
            )
    if "app-release.aab" in release_jobs.get("publish", ""):
        errors.append("sign-local-release.yml: GitHub Releases must remain APK-only")

    required_release_gates = {
        "validate accepts the local artifact digest": "unsigned_sha256:",
        "validate accepts the Android version code": "version_code:",
        "validate accepts the exact source commit": "source_commit:",
        "validate requires a main-branch dispatch": (
            'if [ "$GITHUB_REF" != "refs/heads/main" ]'
        ),
        "validate checks the tag commit": (
            'gh api "repos/$GITHUB_REPOSITORY/commits/$RELEASE_TAG" --jq .sha'
        ),
        "validate checks the local SHA-256": (
            'if [ "$actual_sha256" != "$UNSIGNED_SHA256" ]'
        ),
        "validate checks package identity": (
            'package_name" != "com.ashcastle.duckyslicer"'
        ),
        "validate checks version code": (
            'actual_version_code" != "$RELEASE_VERSION_CODE"'
        ),
        "validate checks version name": (
            'actual_version_name" != "$version"'
        ),
        "validate checks 16 KB alignment": (
            'zipalign" -c -P 16 -v 4 "$unsigned_apk"'
        ),
        "validate rejects a pre-signed input": (
            'apksigner" verify "$unsigned_apk"'
        ),
        "validate retains the exact unsigned bytes": (
            "name: duckyslicer-local-unsigned-${{ github.run_id }}"
        ),
        "sign depends on validation": "  sign:\n    needs: validate\n",
        "publish depends on validation and signer": (
            "  publish:\n    needs: [validate, sign]\n"
        ),
        "publish has release permission": "      contents: write\n",
        "publish rechecks the tag commit": (
            "Release tag changed after local artifact validation"
        ),
        "publish requires the draft to remain unpublished": (
            "Release was published before the isolated signer completed"
        ),
        "publish checks exactly one signed asset": (
            "Refusing to publish a release without exactly one signed APK"
        ),
    }
    for description, marker in required_release_gates.items():
        if marker not in release_source:
            errors.append(f"sign-local-release.yml: missing gate: {description}")

    required_play_gates = {
        "manual dispatch only": "  workflow_dispatch:\n",
        "local source identity is accepted": "source_commit:",
        "private transport tag is accepted": "transport_tag:",
        "local artifact digest is accepted": "unsigned_sha256:",
        "validator checks the tag commit": (
            'commits/$TRANSPORT_TAG" --jq .sha'
        ),
        "validator checks the local digest": (
            'if [ "$actual_sha256" != "$normalized_sha" ]'
        ),
        "signer rechecks the local digest": (
            "Signer input differs from the locally verified SHA-256"
        ),
        "signed Play artifact is retained": "name: duckyslicer-play-signed",
        "private transport draft is removed": (
            'gh release delete "$TRANSPORT_TAG" --yes'
        ),
    }
    for description, marker in required_play_gates.items():
        if marker not in play_source:
            errors.append(f"play-bundle.yml: missing gate: {description}")
    for forbidden_play_execution in (
        "actions/checkout@",
        "./gradlew",
        "cargo ",
        "python3 tools/",
    ):
        if forbidden_play_execution in play_source:
            errors.append(
                "play-bundle.yml: signing handoff must not build or execute "
                "repository code: " + forbidden_play_execution
            )

    validate = release_jobs.get("validate", "")
    signer = release_jobs.get("sign", "")
    publish = release_jobs.get("publish", "")
    isolated_signing_rules = {
        "validation can inspect drafts without signing material": (
            "permissions:\n      contents: write" in validate
            and "environment: release" not in validate
            and "${{ secrets." not in validate
        ),
        "validation does not mutate the draft release": not any(
            marker in validate
            for marker in (
                "gh release edit",
                "gh release upload",
                "gh release delete",
                "gh api --method",
                "git push",
                "curl -X",
            )
        ),
        "validation does not build or execute repository code": (
            "actions/checkout@" not in validate
            and "./gradlew" not in validate
            and "cargo " not in validate
            and "python3 tools/" not in validate
        ),
        "signer uses the protected release environment": "environment: release" in signer,
        "signer has artifact-read permission only": (
            "permissions:\n      actions: read" in signer
            and "contents:" not in signer
            and "id-token:" not in signer
            and "attestations:" not in signer
        ),
        "signer receives all secrets only in its own section": (
            signer.count("${{ secrets.") == 4
            and release_source.count("${{ secrets.") == 4
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
        "signer rechecks the validated unsigned digest": (
            "Validated unsigned artifact changed before signing" in signer
        ),
        "hosted emulator jobs are absent": (
            "device-tests" not in release_jobs
            and "runs-on: macos-14" not in release_source
            and "system-images;android-35;google_apis_ps16k" not in release_source
        ),
        "publish has no signing secrets or repository code": (
            "${{ secrets." not in publish
            and "actions/checkout@" not in publish
            and "./gradlew" not in publish
            and "python3 tools/" not in publish
        ),
        "only validation and publish receive release-capable tokens": (
            release_source.count("contents: write") == 2
            and "contents: write" in validate
            and "contents: write" in publish
            and "contents: write" not in signer
            and "attestations: write" not in release_source
        ),
        "GitHub never builds the release candidate": (
            "./gradlew" not in release_source
            and "assembleRelease" not in release_source
            and "actions/checkout@" not in release_source
        ),
    }
    for description, valid in isolated_signing_rules.items():
        if not valid:
            errors.append(
                "sign-local-release.yml: isolated signing invariant failed: "
                + description
            )

    if errors:
        raise SystemExit("Workflow verification failed:\n- " + "\n- ".join(errors))
    print(
        f"Verified {len(workflows)} workflows and {action_count} immutable Action references; "
        "CodeQL, local-only release signing, and static ARM64 16 KB checks are enforced"
    )


if __name__ == "__main__":
    main()
