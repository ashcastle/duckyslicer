from __future__ import annotations

import json
import unittest

from tools import verify_dependency_audit as audit


VERSIONS = """\
ALPHA_REPOSITORY=https://github.com/example/alpha.git
ALPHA_COMMIT=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
BETA_REPOSITORY=https://gitlab.com/example/beta.git
BETA_COMMIT=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
ARCHIVE_URL=https://example.com/archive.tar.xz
ARCHIVE_SHA256=cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
"""

WORKFLOW = f"""\
name: Dependency vulnerability audit

on:
  push:
    branches: [main]
  schedule:
    - cron: "30 12 * * 1"
  workflow_dispatch:

permissions:
  actions: read
  contents: read
  security-events: write

jobs:
  full-audit:
    uses: {audit.ACTION_REFERENCE}
    with:
      checkout-submodules: true
      fail-on-vuln: true
      upload-sarif: true
      scan-args: |-
        --lockfile=android/app/gradle.lockfile
        --lockfile=rust/duckyslicer-jni/Cargo.lock
        --lockfile=osv-scanner:osv-scanner-custom.json
"""


def inventory(source: str = VERSIONS) -> str:
    return json.dumps(
        audit.expected_inventory(audit.native_packages(source)),
        indent=2,
    )


class VerifyDependencyAuditTest(unittest.TestCase):
    def test_accepts_exact_native_inventory_and_fail_closed_workflow(self) -> None:
        self.assertEqual(
            2,
            audit.verify_sources(
                VERSIONS,
                inventory(),
                WORKFLOW,
                config_exists=False,
            ),
        )

    def test_rejects_repository_without_matching_commit(self) -> None:
        with self.assertRaisesRegex(audit.VerificationError, "pairs are incomplete"):
            audit.native_packages(VERSIONS.replace("BETA_COMMIT=" + "b" * 40 + "\n", ""))

    def test_rejects_abbreviated_or_uppercase_commit(self) -> None:
        for invalid in ("abc123", "A" * 40):
            with self.subTest(invalid=invalid):
                changed = VERSIONS.replace("a" * 40, invalid)
                with self.assertRaisesRegex(audit.VerificationError, "lowercase full Git SHA"):
                    audit.native_packages(changed)

    def test_rejects_credential_bearing_or_mutable_repository_url(self) -> None:
        for invalid in (
            "https://token@github.com/example/alpha.git",
            "http://github.com/example/alpha.git",
            "https://github.com/example/alpha",
        ):
            with self.subTest(invalid=invalid):
                changed = VERSIONS.replace(
                    "https://github.com/example/alpha.git",
                    invalid,
                )
                with self.assertRaisesRegex(audit.VerificationError, "credential-free HTTPS"):
                    audit.native_packages(changed)

    def test_rejects_inventory_drift_or_extra_package(self) -> None:
        actual = json.loads(inventory())
        actual["results"][0]["packages"].append(
            {"package": {"name": "github.com/example/extra", "commit": "d" * 40}}
        )
        with self.assertRaisesRegex(audit.VerificationError, "exactly match"):
            audit.verify_inventory(json.dumps(actual), audit.native_packages(VERSIONS))

    def test_rejects_unpinned_action_or_non_blocking_findings(self) -> None:
        for changed in (
            WORKFLOW.replace(audit.ACTION_REVISION, "v2.5.0"),
            WORKFLOW.replace("fail-on-vuln: true", "fail-on-vuln: false"),
            WORKFLOW + "    continue-on-error: true\n",
        ):
            with self.subTest(changed=changed[-80:]):
                with self.assertRaises(audit.VerificationError):
                    audit.verify_workflow(changed)

    def test_rejects_missing_schedule_or_incomplete_scan(self) -> None:
        for changed in (
            WORKFLOW.replace('  schedule:\n    - cron: "30 12 * * 1"\n', ""),
            WORKFLOW.replace("        --lockfile=rust/duckyslicer-jni/Cargo.lock\n", ""),
            WORKFLOW.replace("checkout-submodules: true", "checkout-submodules: false"),
        ):
            with self.subTest(changed=changed[-80:]):
                with self.assertRaises(audit.VerificationError):
                    audit.verify_workflow(changed)

    def test_rejects_unreviewed_osv_exception_configuration(self) -> None:
        with self.assertRaisesRegex(audit.VerificationError, "exceptions require"):
            audit.verify_sources(
                VERSIONS,
                inventory(),
                WORKFLOW,
                config_exists=True,
            )


if __name__ == "__main__":
    unittest.main()
