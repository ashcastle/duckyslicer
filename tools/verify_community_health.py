#!/usr/bin/env python3
"""Verify DuckySlicer's safe contribution intake and maintenance policy."""

from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
FORM_ITEM = re.compile(r"(?m)^  - type: (?P<type>[a-z-]+)\s*$")
FORM_ID = re.compile(r"(?m)^    id: (?P<id>[a-z][a-z0-9_]*)\s*$")
DEPENDABOT_ENTRY = re.compile(
    r'(?m)^  - package-ecosystem: "(?P<ecosystem>[a-z-]+)"\s*$'
)
PUBLIC_SAFETY_TERMS = (
    "private models",
    "g-code",
    "printer addresses",
    "access keys",
    "personal data",
)


class CommunityHealthError(ValueError):
    """A public contribution path is missing or unsafe."""


def _item_blocks(source: str) -> list[tuple[str, str]]:
    matches = list(FORM_ITEM.finditer(source))
    return [
        (
            match.group("type"),
            source[match.start() : matches[index + 1].start() if index + 1 < len(matches) else None],
        )
        for index, match in enumerate(matches)
    ]


def _form_fields(source: str) -> dict[str, tuple[str, str]]:
    fields: dict[str, tuple[str, str]] = {}
    for field_type, block in _item_blocks(source):
        identifier = FORM_ID.search(block)
        if identifier is None:
            continue
        field_id = identifier.group("id")
        if field_id in fields:
            raise CommunityHealthError(f"issue form repeats id: {field_id}")
        fields[field_id] = (field_type, block)
    return fields


def _verify_issue_form(
    name: str,
    source: str,
    *,
    label: str,
    required_fields: set[str],
) -> None:
    if not source.startswith("name: ") or "\ndescription: " not in source or "\nbody:\n" not in source:
        raise CommunityHealthError(f"{name} is not a complete GitHub issue form")
    if f'labels: ["{label}"]' not in source:
        raise CommunityHealthError(f"{name} must use the existing {label} label")
    normalized = source.lower()
    missing_safety = [term for term in PUBLIC_SAFETY_TERMS if term not in normalized]
    if missing_safety:
        raise CommunityHealthError(
            f"{name} omits public-report safety terms: {missing_safety}"
        )

    fields = _form_fields(source)
    missing_fields = sorted(required_fields - fields.keys())
    if missing_fields:
        raise CommunityHealthError(f"{name} omits required fields: {missing_fields}")
    for field_id in required_fields - {"privacy"}:
        if "validations:\n      required: true" not in fields[field_id][1]:
            raise CommunityHealthError(f"{name} field is optional: {field_id}")
    privacy_type, privacy = fields["privacy"]
    if privacy_type != "checkboxes" or privacy.count("required: true") < 2:
        raise CommunityHealthError(f"{name} privacy confirmations are not mandatory")


def _dependabot_blocks(source: str) -> dict[str, str]:
    matches = list(DEPENDABOT_ENTRY.finditer(source))
    blocks: dict[str, str] = {}
    for index, match in enumerate(matches):
        ecosystem = match.group("ecosystem")
        if ecosystem in blocks:
            raise CommunityHealthError(f"Dependabot repeats ecosystem: {ecosystem}")
        blocks[ecosystem] = source[
            match.start() : matches[index + 1].start() if index + 1 < len(matches) else None
        ]
    return blocks


def verify_community_health(sources: dict[str, str]) -> None:
    expected_sources = {
        "bug_report.yml",
        "feature_request.yml",
        "support_question.yml",
        "config.yml",
        "pull_request_template.md",
        "dependabot.yml",
        "SUPPORT.md",
        "SECURITY.md",
        "PRIVACY.md",
    }
    missing = sorted(expected_sources - sources.keys())
    if missing:
        raise CommunityHealthError(f"community-health sources are missing: {missing}")

    _verify_issue_form(
        "bug report",
        sources["bug_report.yml"],
        label="bug",
        required_fields={
            "version",
            "device",
            "android_version",
            "area",
            "steps",
            "expected",
            "actual",
            "privacy",
        },
    )
    _verify_issue_form(
        "feature request",
        sources["feature_request.yml"],
        label="enhancement",
        required_fields={"problem", "proposal", "area", "offline", "privacy"},
    )
    _verify_issue_form(
        "support question",
        sources["support_question.yml"],
        label="question",
        required_fields={"question", "privacy"},
    )

    chooser = sources["config.yml"]
    chooser_markers = (
        "blank_issues_enabled: false",
        "https://github.com/ashcastle/duckyslicer/blob/main/SECURITY.md",
        "https://github.com/ashcastle/duckyslicer/blob/main/SUPPORT.md",
    )
    missing_chooser = [marker for marker in chooser_markers if marker not in chooser]
    if missing_chooser:
        raise CommunityHealthError(f"issue chooser is missing safe routes: {missing_chooser}")

    pull_request = sources["pull_request_template.md"]
    pull_request_markers = (
        "## Outcome",
        "## Implementation",
        "## Validation",
        "## User-visible evidence",
        "## Risk and recovery",
        "private models",
        "local AI instructions",
        "license and attribution",
        "appropriate local gate",
        "without an account, cloud service, or network connection",
        "ARM64 16 KB compatibility",
        "store disclosures were updated",
    )
    missing_pull_request = [
        marker for marker in pull_request_markers if marker not in pull_request
    ]
    if missing_pull_request:
        raise CommunityHealthError(
            f"pull request template is missing review evidence: {missing_pull_request}"
        )

    dependabot = sources["dependabot.yml"]
    if not dependabot.startswith("version: 2\nupdates:\n") or "registries:" in dependabot:
        raise CommunityHealthError("Dependabot must use a public, registry-free version 2 policy")
    blocks = _dependabot_blocks(dependabot)
    if set(blocks) != {"github-actions", "cargo", "gradle"}:
        raise CommunityHealthError(
            f"Dependabot ecosystems changed without review: {sorted(blocks)}"
        )
    expected_directories = {
        "github-actions": "/",
        "cargo": "/rust/duckyslicer-jni",
        "gradle": "/android",
    }
    for ecosystem, directory in expected_directories.items():
        block = blocks[ecosystem]
        common_markers = (
            f'directory: "{directory}"',
        )
        review_markers = (
            ('interval: "monthly"', "open-pull-requests-limit: 2")
            if ecosystem == "gradle"
            else (
                'interval: "weekly"',
                "open-pull-requests-limit: 3",
                "patterns:\n          - \"*\"",
            )
        )
        missing_markers = [
            marker for marker in (*common_markers, *review_markers) if marker not in block
        ]
        if missing_markers:
            raise CommunityHealthError(
                f"Dependabot {ecosystem} review policy changed: {missing_markers}"
            )

    documentation_markers = {
        "SUPPORT.md": "issues/new?template=bug_report.yml",
        "SECURITY.md": "security/advisories",
        "PRIVACY.md": "issues/new?template=support_question.yml",
    }
    for document, marker in documentation_markers.items():
        if marker not in sources[document]:
            raise CommunityHealthError(f"{document} does not route readers safely")


def read_sources() -> dict[str, str]:
    issue_root = ROOT / ".github/ISSUE_TEMPLATE"
    return {
        "bug_report.yml": (issue_root / "bug_report.yml").read_text(encoding="utf-8"),
        "feature_request.yml": (issue_root / "feature_request.yml").read_text(
            encoding="utf-8"
        ),
        "support_question.yml": (issue_root / "support_question.yml").read_text(
            encoding="utf-8"
        ),
        "config.yml": (issue_root / "config.yml").read_text(encoding="utf-8"),
        "pull_request_template.md": (
            ROOT / ".github/pull_request_template.md"
        ).read_text(encoding="utf-8"),
        "dependabot.yml": (ROOT / ".github/dependabot.yml").read_text(encoding="utf-8"),
        "SUPPORT.md": (ROOT / "SUPPORT.md").read_text(encoding="utf-8"),
        "SECURITY.md": (ROOT / "SECURITY.md").read_text(encoding="utf-8"),
        "PRIVACY.md": (ROOT / "PRIVACY.md").read_text(encoding="utf-8"),
    }


def main() -> None:
    try:
        verify_community_health(read_sources())
    except (OSError, CommunityHealthError) as error:
        raise SystemExit(f"Community-health verification failed: {error}") from error
    print(
        "Verified safe security guidance, bounded public contribution forms, "
        "and scheduled pinned dependency review"
    )


if __name__ == "__main__":
    main()
