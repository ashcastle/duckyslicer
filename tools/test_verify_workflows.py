from __future__ import annotations

import unittest

from tools.verify_workflows import literal_run_blocks, shell_syntax_errors


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


if __name__ == "__main__":
    unittest.main()
