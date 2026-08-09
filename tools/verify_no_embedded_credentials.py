#!/usr/bin/env python3
"""Reject credential-bearing signed URLs from tracked repository text."""

from __future__ import annotations

import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
SIGNED_URL_MARKERS = (
    ("AWS credential", "X-" + "Amz-Credential="),
    ("AWS session token", "X-" + "Amz-Security-Token="),
    ("AWS signature", "X-" + "Amz-Signature="),
    ("Google Cloud credential", "X-" + "Goog-Credential="),
    ("Google Cloud signature", "X-" + "Goog-Signature="),
)


class VerificationError(RuntimeError):
    """Tracked source contains a credential-bearing signed URL."""


def _find_marker(root: Path, marker: str) -> list[str]:
    try:
        result = subprocess.run(
            [
                "git",
                "-C",
                str(root),
                "grep",
                "-I",
                "-n",
                "-o",
                "-F",
                "-e",
                marker,
                "--",
                ".",
            ],
            check=False,
            capture_output=True,
            text=True,
        )
    except OSError as error:
        raise VerificationError(f"git grep could not run: {error}") from error
    if result.returncode == 1:
        return []
    if result.returncode != 0:
        detail = result.stderr.strip() or f"exit {result.returncode}"
        raise VerificationError(f"tracked-source scan failed: {detail}")
    return [line for line in result.stdout.splitlines() if line]


def verify_repository(root: Path = ROOT) -> int:
    findings: list[str] = []
    for description, marker in SIGNED_URL_MARKERS:
        findings.extend(
            f"{description} at {occurrence.rsplit(':', 1)[0]}"
            for occurrence in _find_marker(root, marker)
        )
    if findings:
        raise VerificationError(
            "credential-bearing signed URLs are tracked:\n- " + "\n- ".join(findings)
        )
    return len(SIGNED_URL_MARKERS)


def main() -> None:
    marker_count = verify_repository()
    print(
        f"Verified {marker_count} signed-URL credential markers are absent "
        "from tracked text"
    )


if __name__ == "__main__":
    main()
