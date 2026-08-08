#!/usr/bin/env python3
"""Enforce immutable GitHub Action references and release gate ordering."""

from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
WORKFLOW_ROOT = ROOT / ".github/workflows"
PINNED_REF = re.compile(r"[0-9a-f]{40}")
USES = re.compile(r"^\s*-?\s*uses:\s*([^\s#]+)", re.MULTILINE)


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
    required_android_gates = {
        "Gradle uses strict dependency verification": (
            "./gradlew --dependency-verification=strict"
        ),
        "Gradle trust data is structurally verified": (
            "python3 tools/verify_gradle_supply_chain.py"
        ),
    }
    for description, marker in required_android_gates.items():
        if marker not in android_source:
            errors.append(f"android.yml: missing gate: {description}")

    required_release_gates = {
        "device-tests depends on build": "  device-tests:\n    needs: build\n",
        "publish depends on device tests": "  publish:\n    needs: [build, device-tests]\n",
        "publish has attestation permission": "      attestations: write\n",
        "publish has release permission": "      contents: write\n",
        "release candidate runs instrumentation": "Run release-candidate device tests",
        "signed minified APK cold-launches": "Smoke test signed minified release APK",
        "signed APK activity launch is authoritative": (
            "adb shell am start -W \\\n"
            "            -n com.ashcastle.duckyslicer/.MainActivity"
        ),
        "release APK runs structural verifier": "python3 tools/verify_apk.py \"$release_apk\"",
        "Gradle uses strict dependency verification": (
            "./gradlew --dependency-verification=strict"
        ),
        "Gradle trust data is structurally verified": (
            "python3 tools/verify_gradle_supply_chain.py"
        ),
    }
    for description, marker in required_release_gates.items():
        if marker not in release_source:
            errors.append(f"release.yml: missing gate: {description}")

    if errors:
        raise SystemExit("Workflow verification failed:\n- " + "\n- ".join(errors))
    print(
        f"Verified {len(workflows)} workflows and {action_count} immutable Action references; "
        "release publication depends on ARM64 device tests"
    )


if __name__ == "__main__":
    main()
