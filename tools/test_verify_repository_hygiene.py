from __future__ import annotations

import unittest

from tools.verify_repository_hygiene import (
    REQUIRED_IGNORE_PATTERNS,
    VerificationError,
    verify_repository_hygiene,
)


VALID_GITIGNORE = "\n".join(REQUIRED_IGNORE_PATTERNS) + "\n"


class VerifyRepositoryHygieneTest(unittest.TestCase):
    def test_accepts_product_and_distribution_files(self) -> None:
        verify_repository_hygiene(
            [
                "README.md",
                "android/app/src/main/AndroidManifest.xml",
                "distribution/google-play/assets.json",
                "third_party/android-slicer-runtime",
            ],
            VALID_GITIGNORE,
        )

    def test_rejects_tracked_claude_command(self) -> None:
        with self.assertRaisesRegex(VerificationError, "local AI instructions"):
            verify_repository_hygiene(
                [".claude/commands/commit-push-pr.md"],
                VALID_GITIGNORE,
            )

    def test_rejects_upstream_ai_issue_automation(self) -> None:
        with self.assertRaisesRegex(VerificationError, "local AI instructions"):
            verify_repository_hygiene(
                ["scripts/auto-close-duplicates.ts"],
                VALID_GITIGNORE,
            )

    def test_rejects_nested_agent_instruction(self) -> None:
        with self.assertRaisesRegex(VerificationError, "AGENTS.md"):
            verify_repository_hygiene(
                ["android/AGENTS.md"],
                VALID_GITIGNORE,
            )

    def test_rejects_nested_claude_instruction(self) -> None:
        with self.assertRaisesRegex(VerificationError, "CLAUDE.md"):
            verify_repository_hygiene(
                ["native/runtime/CLAUDE.md"],
                VALID_GITIGNORE,
            )

    def test_rejects_private_ai_design_note(self) -> None:
        with self.assertRaisesRegex(VerificationError, "preview.ai-design.md"):
            verify_repository_hygiene(
                ["docs/preview.ai-design.md"],
                VALID_GITIGNORE,
            )

    def test_rejects_private_prompt_directory(self) -> None:
        with self.assertRaisesRegex(VerificationError, "prompts/private"):
            verify_repository_hygiene(
                ["prompts/private/release.md"],
                VALID_GITIGNORE,
            )

    def test_rejects_weakened_ignore_policy(self) -> None:
        source = VALID_GITIGNORE.replace(".claude/\n", "")
        with self.assertRaisesRegex(VerificationError, "no longer protects"):
            verify_repository_hygiene([], source)


if __name__ == "__main__":
    unittest.main()
