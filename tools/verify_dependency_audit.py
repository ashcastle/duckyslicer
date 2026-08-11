#!/usr/bin/env python3
"""Verify the complete, fail-closed dependency vulnerability audit contract."""

from __future__ import annotations

import json
import re
from pathlib import Path
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parent.parent
VERSIONS = ROOT / "native/slicer-runtime/versions.env"
INVENTORY = ROOT / "osv-scanner-custom.json"
WORKFLOW = ROOT / ".github/workflows/dependency-audit.yml"
CONFIG = ROOT / "osv-scanner.toml"
ACTION_REVISION = "8deb546fdb875b9996d27d4950be7312dac076a1"
ACTION_REFERENCE = (
    "google/osv-scanner-action/.github/workflows/"
    f"osv-scanner-reusable.yml@{ACTION_REVISION}"
)
COMMIT = re.compile(r"[0-9a-f]{40}")
KEY = re.compile(r"[A-Z][A-Z0-9_]*")


class VerificationError(ValueError):
    """The dependency audit inputs violate a repository invariant."""


def parse_versions(source: str) -> dict[str, str]:
    values: dict[str, str] = {}
    for line_number, raw_line in enumerate(source.splitlines(), start=1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if (
            not separator
            or KEY.fullmatch(key) is None
            or not value
            or any(character.isspace() for character in value)
        ):
            raise VerificationError(f"invalid versions.env entry on line {line_number}")
        if key in values:
            raise VerificationError(f"duplicate versions.env key: {key}")
        values[key] = value
    return values


def normalized_repository(value: str) -> str:
    parsed = urlsplit(value)
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or not parsed.path.endswith(".git")
    ):
        raise VerificationError(
            "native repositories must be credential-free HTTPS .git URLs: " + value
        )
    path = parsed.path.removesuffix(".git").strip("/")
    if not path or "//" in path or any(part in (".", "..") for part in path.split("/")):
        raise VerificationError(f"invalid native repository path: {value}")
    return f"{parsed.hostname.lower()}/{path}"


def native_packages(source: str) -> tuple[dict[str, str], ...]:
    values = parse_versions(source)
    repositories = {
        key.removesuffix("_REPOSITORY"): value
        for key, value in values.items()
        if key.endswith("_REPOSITORY")
    }
    commits = {
        key.removesuffix("_COMMIT"): value
        for key, value in values.items()
        if key.endswith("_COMMIT")
    }
    if not repositories:
        raise VerificationError("versions.env contains no commit-pinned repositories")
    if repositories.keys() != commits.keys():
        missing_commits = sorted(repositories.keys() - commits.keys())
        missing_repositories = sorted(commits.keys() - repositories.keys())
        raise VerificationError(
            "native repository/commit pairs are incomplete: "
            f"missing commits={missing_commits}, missing repositories={missing_repositories}"
        )

    packages: list[dict[str, str]] = []
    seen_repositories: set[str] = set()
    for prefix in sorted(repositories):
        commit = commits[prefix]
        if COMMIT.fullmatch(commit) is None:
            raise VerificationError(f"{prefix}_COMMIT must be a lowercase full Git SHA")
        repository = normalized_repository(repositories[prefix])
        if repository in seen_repositories:
            raise VerificationError(f"duplicate native repository: {repository}")
        seen_repositories.add(repository)
        packages.append({"name": repository, "commit": commit})
    return tuple(sorted(packages, key=lambda package: package["name"].lower()))


def expected_inventory(packages: tuple[dict[str, str], ...]) -> dict[str, object]:
    return {
        "results": [
            {
                "packages": [
                    {"package": package}
                    for package in packages
                ]
            }
        ]
    }


def verify_inventory(source: str, packages: tuple[dict[str, str], ...]) -> None:
    try:
        actual = json.loads(source)
    except json.JSONDecodeError as error:
        raise VerificationError(f"invalid OSV custom inventory JSON: {error}") from error
    if actual != expected_inventory(packages):
        raise VerificationError(
            "osv-scanner-custom.json must exactly match every commit-pinned native repository"
        )


def verify_workflow(source: str) -> None:
    header, separator, jobs = source.partition("\njobs:\n")
    if not separator:
        raise VerificationError("dependency audit workflow has no jobs section")
    for trigger in ("  push:", "  schedule:", "  workflow_dispatch:"):
        if trigger not in header:
            raise VerificationError(f"dependency audit workflow is missing trigger: {trigger.strip()}")
    if "    branches: [main]" not in header:
        raise VerificationError("dependency audit push must target main")
    if 'cron: "30 12 * * 1"' not in header:
        raise VerificationError("dependency audit must retain the weekly full scan")
    for permission in ("actions: read", "contents: read", "security-events: write"):
        if permission not in header:
            raise VerificationError(f"dependency audit is missing permission: {permission}")
    if "continue-on-error:" in source:
        raise VerificationError("dependency vulnerability findings must not be ignored")
    if jobs.count(f"uses: {ACTION_REFERENCE}") != 1:
        raise VerificationError("dependency audit must use the approved full-SHA OSV workflow")
    required_inputs = (
        "checkout-submodules: true",
        "fail-on-vuln: true",
        "upload-sarif: true",
        "scan-args: |-\n"
        "        --lockfile=android/app/gradle.lockfile\n"
        "        --lockfile=rust/duckyslicer-jni/Cargo.lock\n"
        "        --lockfile=osv-scanner:osv-scanner-custom.json",
    )
    for marker in required_inputs:
        if marker not in jobs:
            raise VerificationError(f"dependency audit is missing fail-closed input: {marker}")


def verify_sources(
    versions_source: str,
    inventory_source: str,
    workflow_source: str,
    *,
    config_exists: bool,
) -> int:
    if config_exists:
        raise VerificationError(
            "osv-scanner.toml is forbidden because vulnerability exceptions require a reviewed policy"
        )
    packages = native_packages(versions_source)
    verify_inventory(inventory_source, packages)
    verify_workflow(workflow_source)
    return len(packages)


def main() -> None:
    for path in (VERSIONS, INVENTORY, WORKFLOW):
        if not path.is_file():
            raise VerificationError(f"required dependency audit input is missing: {path.relative_to(ROOT)}")
    count = verify_sources(
        VERSIONS.read_text(encoding="utf-8"),
        INVENTORY.read_text(encoding="utf-8"),
        WORKFLOW.read_text(encoding="utf-8"),
        config_exists=CONFIG.exists(),
    )
    print(
        f"Verified dependency audit: Cargo and Gradle locks plus {count} native Git commits; "
        f"OSV workflow={ACTION_REVISION[:12]}"
    )


if __name__ == "__main__":
    try:
        main()
    except (OSError, VerificationError) as error:
        raise SystemExit(f"Dependency audit verification failed: {error}") from error
