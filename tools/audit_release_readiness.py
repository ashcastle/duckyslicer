#!/usr/bin/env python3
"""Report every remaining DuckySlicer release precondition without changing state."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from collections.abc import Callable, Sequence
from dataclasses import asdict, dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from tools.qualification_corpus import CorpusError
from tools.run_local_gate import parse_online_devices
from tools.run_physical_qualification import (
    DeviceIdentity,
    RunnerError,
    physical_rejection,
    query_identity,
)
from tools.verify_release_qualifications import (
    QualificationEvidenceError,
    verify_release_qualifications,
)


PHYSICAL_REPORT = ROOT / "build/qualification/physical-report.json"
STARTUP_REPORT = ROOT / "build/qualification/startup-report.json"
ORCA_REPORT = ROOT / "build/qualification/desktop-orca-release/comparison-report.json"
PLAY_VARIABLES = frozenset(
    {
        "DUCKYSLICER_GOOGLE_WORKLOAD_IDENTITY_PROVIDER",
        "DUCKYSLICER_GOOGLE_PLAY_SERVICE_ACCOUNT",
    }
)


@dataclass(frozen=True)
class CommandResult:
    returncode: int
    stdout: str = ""


@dataclass(frozen=True)
class ReadinessCheck:
    name: str
    passed: bool
    detail: str


@dataclass(frozen=True)
class ReadinessReport:
    source_commit: str | None
    ready: bool
    checks: tuple[ReadinessCheck, ...]

    def document(self) -> dict[str, object]:
        return {
            "schemaVersion": 1,
            "sourceCommit": self.source_commit,
            "ready": self.ready,
            "checks": [asdict(check) for check in self.checks],
        }


CommandRunner = Callable[[Sequence[str]], CommandResult]
IdentityQuery = Callable[[str], DeviceIdentity]
EvidenceVerifier = Callable[[Path, Path, Path, str], None]


def run_command(command: Sequence[str]) -> CommandResult:
    try:
        result = subprocess.run(
            list(command),
            cwd=ROOT,
            check=False,
            capture_output=True,
            text=True,
            timeout=30,
        )
    except (OSError, subprocess.TimeoutExpired):
        return CommandResult(127)
    return CommandResult(result.returncode, result.stdout.rstrip())


def passed(name: str, detail: str) -> ReadinessCheck:
    return ReadinessCheck(name=name, passed=True, detail=detail)


def blocked(name: str, detail: str) -> ReadinessCheck:
    return ReadinessCheck(name=name, passed=False, detail=detail)


def git_check(command: Sequence[str], runner: CommandRunner) -> str | None:
    result = runner(command)
    if result.returncode != 0:
        return None
    value = result.stdout.strip()
    return value or None


def repository_checks(runner: CommandRunner) -> tuple[str | None, list[ReadinessCheck]]:
    checks: list[ReadinessCheck] = []
    commit = git_check(("git", "rev-parse", "HEAD"), runner)
    if commit is None:
        return None, [blocked("repository", "Git HEAD를 읽을 수 없음")]

    branch = git_check(("git", "branch", "--show-current"), runner)
    status = runner(("git", "status", "--porcelain", "--untracked-files=normal"))
    if branch == "main" and status.returncode == 0 and not status.stdout.strip():
        checks.append(passed("repository", f"깨끗한 main ({commit[:12]})"))
    else:
        reasons: list[str] = []
        if branch != "main":
            reasons.append(f"현재 브랜치: {branch or 'unknown'}")
        if status.returncode != 0:
            reasons.append("worktree 상태 확인 실패")
        elif status.stdout.strip():
            reasons.append("커밋되지 않은 변경 있음")
        checks.append(blocked("repository", ", ".join(reasons)))

    remote = runner(
        ("git", "ls-remote", "--exit-code", "origin", "refs/heads/main")
    )
    columns = remote.stdout.split()
    origin = columns[0] if remote.returncode == 0 and len(columns) == 2 else None
    if origin == commit:
        checks.append(passed("origin-sync", "HEAD와 원격 main 일치"))
    elif origin is None:
        checks.append(blocked("origin-sync", "원격 main을 읽을 수 없음"))
    else:
        checks.append(
            blocked(
                "origin-sync",
                f"로컬 {commit[:12]} / 원격 {origin[:12]}",
            )
        )

    submodules = runner(("git", "submodule", "status", "--recursive"))
    invalid = [
        line for line in submodules.stdout.splitlines() if line and not line.startswith(" ")
    ]
    if submodules.returncode == 0 and not invalid:
        checks.append(passed("submodules", "재귀 서브모듈 핀 일치"))
    else:
        checks.append(blocked("submodules", "누락되거나 기록 커밋과 다른 서브모듈 있음"))
    return commit, checks


def github_checks(runner: CommandRunner) -> list[ReadinessCheck]:
    auth = runner(("gh", "auth", "status", "-h", "github.com"))
    if auth.returncode != 0:
        return [blocked("github-auth", "GitHub CLI 재인증 필요")]

    checks = [passed("github-auth", "GitHub CLI 인증 유효")]
    origin = runner(("git", "remote", "get-url", "origin"))
    if origin.returncode != 0 or not origin.stdout.strip():
        checks.append(blocked("play-wif", "origin 저장소를 확인할 수 없음"))
        return checks
    repository = runner(
        (
            "gh",
            "repo",
            "view",
            origin.stdout.strip(),
            "--json",
            "nameWithOwner",
            "--jq",
            ".nameWithOwner",
        )
    )
    repository_name = repository.stdout.strip()
    if repository.returncode != 0 or not repository_name:
        checks.append(blocked("play-wif", "origin GitHub 저장소를 확인할 수 없음"))
        return checks
    variables = runner(
        (
            "gh",
            "variable",
            "list",
            "--repo",
            repository_name,
            "--env",
            "play",
            "--json",
            "name",
            "--jq",
            ".[].name",
        )
    )
    if variables.returncode != 0:
        checks.append(
            blocked(
                "play-wif",
                f"{repository_name}의 보호된 play 환경 변수를 읽을 수 없음",
            )
        )
        return checks
    configured = {line.strip() for line in variables.stdout.splitlines() if line.strip()}
    missing = sorted(PLAY_VARIABLES - configured)
    if missing:
        checks.append(
            blocked(
                "play-wif",
                f"{repository_name} 누락 변수: " + ", ".join(missing),
            )
        )
    else:
        checks.append(
            passed("play-wif", f"{repository_name}에 키 없는 Play 인증 변수 구성됨")
        )
    return checks


def physical_device_check(
    runner: CommandRunner,
    identity_query: IdentityQuery,
) -> ReadinessCheck:
    devices = runner(("adb", "devices", "-l"))
    if devices.returncode != 0:
        return blocked("physical-device", "ADB 기기 목록을 읽을 수 없음")
    accepted: list[str] = []
    for serial in parse_online_devices(devices.stdout):
        try:
            identity = identity_query(serial)
        except (OSError, RunnerError):
            continue
        if physical_rejection(identity) is None:
            accepted.append(f"{serial} ({identity.manufacturer} {identity.model})")
    if not accepted:
        return blocked("physical-device", "대표 ARM64 실기기가 연결되지 않음")
    return passed("physical-device", ", ".join(accepted))


def evidence_check(
    source_commit: str | None,
    physical_report: Path,
    startup_report: Path,
    orca_report: Path,
    verifier: EvidenceVerifier,
) -> ReadinessCheck:
    reports = (physical_report, startup_report, orca_report)
    missing = [
        str(path.relative_to(ROOT)) if path.is_relative_to(ROOT) else str(path)
        for path in reports
        if not path.is_file()
    ]
    if missing:
        return blocked("release-evidence", "누락: " + ", ".join(missing))
    if source_commit is None:
        return blocked("release-evidence", "검증할 source commit이 없음")
    try:
        verifier(physical_report, startup_report, orca_report, source_commit)
    except (CorpusError, OSError, QualificationEvidenceError, ValueError) as error:
        return blocked("release-evidence", str(error))
    return passed("release-evidence", "실기기·시작 성능·Orca 비교 증거가 HEAD에 일치")


def audit(
    *,
    physical_report: Path = PHYSICAL_REPORT,
    startup_report: Path = STARTUP_REPORT,
    orca_report: Path = ORCA_REPORT,
    runner: CommandRunner = run_command,
    identity_query: IdentityQuery = query_identity,
    verifier: EvidenceVerifier = verify_release_qualifications,
) -> ReadinessReport:
    source_commit, checks = repository_checks(runner)
    checks.extend(github_checks(runner))
    checks.append(physical_device_check(runner, identity_query))
    checks.append(
        evidence_check(
            source_commit,
            physical_report,
            startup_report,
            orca_report,
            verifier,
        )
    )
    return ReadinessReport(
        source_commit=source_commit,
        ready=all(check.passed for check in checks),
        checks=tuple(checks),
    )


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--physical-report", type=Path, default=PHYSICAL_REPORT)
    parser.add_argument("--startup-report", type=Path, default=STARTUP_REPORT)
    parser.add_argument("--orca-report", type=Path, default=ORCA_REPORT)
    parser.add_argument("--json", action="store_true", dest="json_output")
    args = parser.parse_args(argv)
    report = audit(
        physical_report=args.physical_report,
        startup_report=args.startup_report,
        orca_report=args.orca_report,
    )
    if args.json_output:
        print(json.dumps(report.document(), ensure_ascii=False, indent=2))
    else:
        for check in report.checks:
            marker = "PASS" if check.passed else "BLOCKED"
            print(f"[{marker}] {check.name}: {check.detail}")
        print("READY" if report.ready else "NOT READY")
    return 0 if report.ready else 1


if __name__ == "__main__":
    raise SystemExit(main())
