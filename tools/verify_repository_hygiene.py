#!/usr/bin/env python3
"""Reject tracked local AI instructions and private design workspaces."""

from __future__ import annotations

import subprocess
from pathlib import Path, PurePosixPath


ROOT = Path(__file__).resolve().parent.parent
REQUIRED_IGNORE_PATTERNS = (
    ".codex/",
    ".ai/",
    ".ai-notes/",
    ".agents/",
    "**/AGENT.md",
    "**/AGENTS.md",
    "**/AGENTS.override.md",
    "**/CLAUDE.md",
    "**/GEMINI.md",
    ".claude/",
    "*.prompt.local.md",
    "*.ai.md",
    "*.ai-plan.md",
    "*.ai-design.md",
    "prompts/private/",
    "docs/drafts/",
    "docs/private/",
)
FORBIDDEN_DIRECTORY_PARTS = {
    ".agents",
    ".ai",
    ".ai-notes",
    ".claude",
    ".claude.local",
    ".codex",
}
FORBIDDEN_DIRECTORY_PREFIXES = (
    ("prompts", "private"),
    ("docs", "drafts"),
    ("docs", "private"),
)
FORBIDDEN_BASENAMES = {
    "AGENT.md",
    "AGENTS.md",
    "AGENTS.override.md",
    "CLAUDE.md",
    "CLAUDE.local.md",
    "GEMINI.md",
    "GEMINI.local.md",
    "PROMPT.local.md",
}
FORBIDDEN_SUFFIXES = (
    ".prompt.local.md",
    ".ai.md",
    ".ai-plan.md",
    ".ai-design.md",
)


class VerificationError(ValueError):
    """The repository contains local-only AI or private design state."""


def _is_forbidden_tracked_path(value: str) -> bool:
    path = PurePosixPath(value)
    parts = path.parts
    if not value or path.is_absolute() or ".." in parts:
        raise VerificationError(f"invalid tracked path: {value!r}")
    if FORBIDDEN_DIRECTORY_PARTS.intersection(parts):
        return True
    if any(parts[: len(prefix)] == prefix for prefix in FORBIDDEN_DIRECTORY_PREFIXES):
        return True
    if path.name in FORBIDDEN_BASENAMES:
        return True
    return path.name.endswith(FORBIDDEN_SUFFIXES)


def verify_repository_hygiene(tracked_paths: list[str], gitignore_source: str) -> None:
    ignored = {
        line.strip()
        for line in gitignore_source.splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    }
    missing_patterns = sorted(set(REQUIRED_IGNORE_PATTERNS) - ignored)
    if missing_patterns:
        raise VerificationError(
            f".gitignore no longer protects local AI files: {missing_patterns}"
        )

    forbidden = sorted(
        path for path in tracked_paths if _is_forbidden_tracked_path(path)
    )
    if forbidden:
        raise VerificationError(
            "local AI instructions or private design files are tracked: "
            f"{forbidden}"
        )


def tracked_repository_paths(repository_root: Path = ROOT) -> list[str]:
    result = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=repository_root,
        check=False,
        capture_output=True,
    )
    if result.returncode != 0:
        message = result.stderr.decode("utf-8", errors="replace").strip()
        raise VerificationError(f"git ls-files failed: {message}")
    try:
        source = result.stdout.decode("utf-8")
    except UnicodeDecodeError as error:
        raise VerificationError("tracked paths are not valid UTF-8") from error
    return [path for path in source.split("\0") if path]


def main() -> None:
    try:
        gitignore = (ROOT / ".gitignore").read_text(encoding="utf-8")
        tracked = tracked_repository_paths()
        verify_repository_hygiene(tracked, gitignore)
    except (OSError, VerificationError) as error:
        raise SystemExit(f"Repository hygiene verification failed: {error}") from error
    print(
        "Verified repository hygiene: local AI instructions and private design "
        "workspaces are ignored and untracked"
    )


if __name__ == "__main__":
    main()
