from __future__ import annotations

import unittest

from tools.verify_workflows import (
    android_concurrency_errors,
    ccache_lock_errors,
    literal_run_blocks,
    manual_dispatch_errors,
    shell_syntax_errors,
)


class VerifyWorkflowsTest(unittest.TestCase):
    def test_extracts_and_validates_literal_run_blocks(self) -> None:
        source = """jobs:
  verify:
    steps:
      - name: Valid
        run: |
          value=$(printf '%s' ok)
          test "$value" = ok
      - uses: example/action@0123456789012345678901234567890123456789
"""
        self.assertEqual(1, len(literal_run_blocks(source)))
        self.assertEqual([], shell_syntax_errors("valid.yml", source))

    def test_rejects_invalid_workflow_shell_syntax(self) -> None:
        source = """jobs:
  verify:
    steps:
      - run: |
          value=$(printf '%s' broken
"""
        errors = shell_syntax_errors("broken.yml", source)
        self.assertEqual(1, len(errors))
        self.assertIn("invalid Bash syntax", errors[0])

    def test_accepts_exact_checksum_locked_ccache_asset(self) -> None:
        source = """CCACHE_TOOL_VERSION=4.13.6
CCACHE_TOOL_ARCHIVE=ccache-4.13.6-linux-x86_64-glibc.tar.xz
CCACHE_TOOL_URL=https://github.com/ccache/ccache/releases/download/v4.13.6/ccache-4.13.6-linux-x86_64-glibc.tar.xz
CCACHE_TOOL_SHA256=508b2a1217dc6e04a23e967c7b95a0fb45d8a7e16fde9e180919698f2e2be060
"""
        self.assertEqual([], ccache_lock_errors(source))

    def test_rejects_unlocked_ccache_asset(self) -> None:
        source = """CCACHE_TOOL_VERSION=latest
CCACHE_TOOL_ARCHIVE=ccache.tar.xz
CCACHE_TOOL_URL=https://example.com/ccache.tar.xz
CCACHE_TOOL_SHA256=unverified
"""
        errors = ccache_lock_errors(source)
        self.assertGreaterEqual(len(errors), 4)

    def test_accepts_manual_dispatch_recovery_path(self) -> None:
        source = """on:
  workflow_dispatch:
jobs:
  verify:
"""
        self.assertEqual([], manual_dispatch_errors("android.yml", source))

    def test_rejects_workflow_without_manual_dispatch_recovery_path(self) -> None:
        source = """on:
  push:
jobs:
  verify:
"""
        errors = manual_dispatch_errors("android.yml", source)
        self.assertEqual(
            ["android.yml: manual dispatch recovery path is required"],
            errors,
        )

    def test_accepts_commit_scoped_android_concurrency(self) -> None:
        source = """concurrency:
  group: android-${{ github.sha }}
  cancel-in-progress: true
"""
        self.assertEqual([], android_concurrency_errors(source))

    def test_rejects_branch_scoped_android_concurrency(self) -> None:
        source = """concurrency:
  group: android-${{ github.ref }}
  cancel-in-progress: true
"""
        self.assertEqual(
            ["android.yml: concurrency must be scoped to the exact commit"],
            android_concurrency_errors(source),
        )


if __name__ == "__main__":
    unittest.main()
