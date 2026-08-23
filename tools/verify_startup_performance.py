#!/usr/bin/env python3
"""Verify DuckySlicer's app-owned startup optimization contract."""

from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
ANDROID = ROOT / "android"
BASELINE_DIR = ANDROID / "app/src/main/generated/baselineProfiles"
PROFILE_RULE = re.compile(r"^[HSP]*L.+;$|^[HSP]*L.+;->.+$")
FIRST_PARTY_PREFIX = "Lcom/ashcastle/duckyslicer/"


class VerificationError(ValueError):
    """The startup optimization contract is incomplete or stale."""


def display_path(path: Path) -> str:
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def require_markers(path: Path, markers: tuple[str, ...]) -> str:
    if not path.is_file():
        raise VerificationError(f"required file is missing: {display_path(path)}")
    source = path.read_text(encoding="utf-8")
    missing = [marker for marker in markers if marker not in source]
    if missing:
        raise VerificationError(
            f"{display_path(path)} is missing startup contract markers: {missing}"
        )
    return source


def read_profile(path: Path) -> set[str]:
    if not path.is_file():
        raise VerificationError(f"generated profile is missing: {display_path(path)}")
    if path.stat().st_size > 3_500_000:
        raise VerificationError(f"generated profile is unexpectedly large: {path.stat().st_size}")
    lines = path.read_text(encoding="utf-8").splitlines()
    if not lines or any(not PROFILE_RULE.fullmatch(line) for line in lines):
        raise VerificationError(f"generated profile contains an invalid rule: {display_path(path)}")
    if len(lines) != len(set(lines)):
        raise VerificationError(f"generated profile contains duplicate rules: {display_path(path)}")
    if lines != sorted(lines):
        raise VerificationError(f"generated profile is not deterministically sorted: {display_path(path)}")
    if any(line[0] in "HSP" and not line.startswith("HSPL") for line in lines):
        raise VerificationError(f"generated profile contains noncanonical runtime flags: {display_path(path)}")
    first_party = sum(FIRST_PARTY_PREFIX in line for line in lines)
    if first_party < 100:
        raise VerificationError(
            f"generated profile covers only {first_party} first-party rules; expected at least 100"
        )
    return set(lines)


def main() -> None:
    require_markers(
        ANDROID / "settings.gradle.kts",
        ('include(":baselineprofile")',),
    )
    require_markers(
        ANDROID / "build.gradle.kts",
        (
            'id("com.android.test") version "9.3.1" apply false',
            'id("androidx.baselineprofile") version "1.5.0-rc01" apply false',
        ),
    )
    require_markers(
        ANDROID / "app/build.gradle.kts",
        (
            'id("androidx.baselineprofile")',
            "automaticGenerationDuringBuild = false",
            "mergeIntoMain = true",
            "saveInSrc = true",
            'baselineProfile(project(":baselineprofile"))',
            'task.name == "generateBaselineProfile"',
            'rule.replace(Regex("^[HSP]+(?=L)"), "HSP")',
            ".distinct()",
            ".sorted()",
        ),
    )
    require_markers(
        ANDROID / "baselineprofile/build.gradle.kts",
        (
            'id("com.android.test")',
            'id("androidx.baselineprofile")',
            'implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0-rc01")',
            "useConnectedDevices = true",
        ),
    )
    require_markers(
        ANDROID
        / "baselineprofile/src/main/java/com/ashcastle/duckyslicer/baselineprofile/BaselineProfileGenerator.kt",
        (
            "BaselineProfileRule()",
            "includeInStartupProfile = true",
            "strictStability = true",
            "filterPredicate = { rule -> rule.contains(FIRST_PARTY_PROFILE_PREFIX) }",
            'FIRST_PARTY_PROFILE_PREFIX = "Lcom/ashcastle/duckyslicer/"',
            "startActivityAndWait()",
            "FULLY_DRAWN_SETTLE_MILLIS = 2_000L",
        ),
    )
    require_markers(
        ANDROID
        / "baselineprofile/src/main/java/com/ashcastle/duckyslicer/baselineprofile/StartupBenchmark.kt",
        (
            "StartupTimingMetric()",
            "CompilationMode.None()",
            "CompilationMode.Partial(BaselineProfileMode.Require)",
            "StartupMode.COLD",
            "FULLY_DRAWN_SETTLE_MILLIS = 2_000L",
        ),
    )
    require_markers(
        ANDROID / "app/src/main/java/com/ashcastle/duckyslicer/MainActivity.kt",
        (
            "ReportDrawnWhen",
            "projectTransferState.restored",
            "profileLibraryState.catalogLoaded",
            "profileLibraryState.recentsLoaded",
            "initialWorkspaceReady(",
        ),
    )
    require_markers(
        ANDROID
        / "app/src/test/java/com/ashcastle/duckyslicer/StartupReadinessTest.kt",
        ("class StartupReadinessTest", "fullyDrawnWaitsForEveryDurableWorkspaceSource"),
    )

    baseline = read_profile(BASELINE_DIR / "baseline-prof.txt")
    startup = read_profile(BASELINE_DIR / "startup-prof.txt")
    if not startup <= baseline:
        raise VerificationError("startup profile must be a subset of the baseline profile")
    print(
        "Verified startup performance contract: "
        f"{len(baseline)} baseline rules, {len(startup)} startup rules"
    )


if __name__ == "__main__":
    try:
        main()
    except (OSError, VerificationError) as error:
        raise SystemExit(f"Startup performance verification failed: {error}") from error
