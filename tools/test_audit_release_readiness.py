from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from subprocess import CompletedProcess
from unittest.mock import patch

from tools.audit_release_readiness import (
    CommandResult,
    PLAY_VARIABLES,
    audit,
    evidence_check,
    github_checks,
    physical_device_check,
    repository_checks,
    run_command,
)
from tools.run_physical_qualification import DeviceIdentity


COMMIT = "a" * 40
ORIGIN_URL = "https://github.com/ashcastle/duckyslicer.git"
REPOSITORY = "ashcastle/duckyslicer"


def identity(serial: str, *, emulator: bool = False) -> DeviceIdentity:
    return DeviceIdentity(
        serial=serial,
        manufacturer="Google" if emulator else "Samsung",
        model="Emulator" if emulator else "Test Phone",
        api=36,
        abi="arm64-v8a",
        page_size_bytes=16_384,
        hardware="ranchu" if emulator else "test-hardware",
        kernel_qemu="1" if emulator else "0",
        boot_qemu="1" if emulator else "0",
        build_fingerprint="test/fingerprint",
        memory_total_kb=8_000_000,
    )


class FakeRunner:
    def __init__(self, results: dict[tuple[str, ...], CommandResult]) -> None:
        self.results = results

    def __call__(self, command: tuple[str, ...] | list[str]) -> CommandResult:
        return self.results.get(tuple(command), CommandResult(127))


def ready_runner() -> FakeRunner:
    return FakeRunner(
        {
            ("git", "rev-parse", "HEAD"): CommandResult(0, COMMIT),
            ("git", "branch", "--show-current"): CommandResult(0, "main"),
            ("git", "status", "--porcelain", "--untracked-files=normal"): CommandResult(0),
            (
                "git",
                "ls-remote",
                "--exit-code",
                "origin",
                "refs/heads/main",
            ): CommandResult(0, f"{COMMIT}\trefs/heads/main"),
            ("git", "submodule", "status", "--recursive"): CommandResult(
                0,
                " 0123456789abcdef third_party/runtime",
            ),
            ("gh", "auth", "status", "-h", "github.com"): CommandResult(0),
            ("git", "remote", "get-url", "origin"): CommandResult(0, ORIGIN_URL),
            (
                "gh",
                "repo",
                "view",
                ORIGIN_URL,
                "--json",
                "nameWithOwner",
                "--jq",
                ".nameWithOwner",
            ): CommandResult(0, REPOSITORY),
            (
                "gh",
                "variable",
                "list",
                "--repo",
                REPOSITORY,
                "--env",
                "play",
                "--json",
                "name",
                "--jq",
                ".[].name",
            ): CommandResult(0, "\n".join(sorted(PLAY_VARIABLES))),
            ("adb", "devices", "-l"): CommandResult(
                0,
                "List of devices attached\nphone-1 device product:test model:Phone",
            ),
        }
    )


class AuditReleaseReadinessTest(unittest.TestCase):
    def test_command_runner_preserves_significant_submodule_prefix(self) -> None:
        completed = CompletedProcess(
            args=["git", "submodule"],
            returncode=0,
            stdout=" 0123456789abcdef third_party/runtime\n",
            stderr="",
        )
        with patch("tools.audit_release_readiness.subprocess.run", return_value=completed):
            self.assertTrue(run_command(("git", "submodule")).stdout.startswith(" "))

    def test_repository_requires_clean_synchronized_main(self) -> None:
        source_commit, checks = repository_checks(ready_runner())
        self.assertEqual(COMMIT, source_commit)
        self.assertTrue(all(check.passed for check in checks))

        runner = ready_runner()
        runner.results[
            ("git", "ls-remote", "--exit-code", "origin", "refs/heads/main")
        ] = CommandResult(0, f"{'b' * 40}\trefs/heads/main")
        _, checks = repository_checks(runner)
        self.assertFalse(next(check for check in checks if check.name == "origin-sync").passed)

    def test_github_check_never_requires_secret_values(self) -> None:
        checks = github_checks(ready_runner())
        self.assertTrue(all(check.passed for check in checks))
        self.assertEqual({"github-auth", "play-wif"}, {check.name for check in checks})
        self.assertIn(REPOSITORY, checks[-1].detail)

        runner = ready_runner()
        runner.results[("gh", "auth", "status", "-h", "github.com")] = CommandResult(1)
        self.assertEqual("github-auth", github_checks(runner)[0].name)
        self.assertFalse(github_checks(runner)[0].passed)

    def test_physical_check_rejects_emulator_and_accepts_phone(self) -> None:
        runner = FakeRunner(
            {
                ("adb", "devices", "-l"): CommandResult(
                    0,
                    "List of devices attached\nemulator-1 device\nphone-1 device",
                )
            }
        )
        check = physical_device_check(
            runner,
            lambda serial: identity(serial, emulator=serial.startswith("emulator")),
        )
        self.assertTrue(check.passed)
        self.assertIn("phone-1", check.detail)
        self.assertNotIn("emulator-1", check.detail)

    def test_evidence_check_lists_missing_reports_without_calling_verifier(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            called = False

            def verifier(*_args: object) -> None:
                nonlocal called
                called = True

            check = evidence_check(
                COMMIT,
                root / "physical.json",
                root / "startup.json",
                root / "orca.json",
                verifier,
            )
            self.assertFalse(check.passed)
            self.assertIn("physical.json", check.detail)
            self.assertFalse(called)

    def test_complete_audit_is_ready_only_when_every_gate_passes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            reports = tuple(root / name for name in ("physical.json", "startup.json", "orca.json"))
            for report in reports:
                report.write_text("{}", encoding="utf-8")
            verified: list[str] = []

            def verifier(_physical: Path, _startup: Path, _orca: Path, commit: str) -> None:
                verified.append(commit)

            report = audit(
                physical_report=reports[0],
                startup_report=reports[1],
                orca_report=reports[2],
                runner=ready_runner(),
                identity_query=lambda serial: identity(serial),
                verifier=verifier,
            )
            self.assertTrue(report.ready)
            self.assertEqual([COMMIT], verified)
            self.assertTrue(all(check.passed for check in report.checks))


if __name__ == "__main__":
    unittest.main()
