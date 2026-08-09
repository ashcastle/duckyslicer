#!/usr/bin/env python3
"""Enforce bounded, durable, reader-safe generated G-code storage."""

from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent


class VerificationError(ValueError):
    pass


def verify_slice_storage(sources: dict[str, str]) -> None:
    required = {
        "SliceArtifactStore.kt",
        "SlicerProcessService.kt",
        "MainActivity.kt",
        "RemoteDevice.kt",
        "SliceArtifactStoreTest.kt",
        "NativeEngineInstrumentedTest.kt",
        "README.md",
        "SECURITY.md",
        "CONTRIBUTING.md",
    }
    missing = sorted(required - sources.keys())
    if missing:
        raise VerificationError(f"slice storage sources are missing: {missing}")

    artifacts = sources["SliceArtifactStore.kt"]
    for marker in (
        "MAXIMUM_OUTPUT_BYTES",
        "MAXIMUM_RETAINED_BYTES",
        "MINIMUM_FREE_BYTES",
        "EMERGENCY_FREE_BYTES",
        "MAXIMUM_RETAINED_OUTPUTS",
        "StandardCopyOption.ATOMIC_MOVE",
        "output.fd.sync()",
        "copyBounded",
        "tryLock()",
        "SliceArtifactLease",
        "activeOutputIsUnsafe",
    ):
        if marker not in artifacts:
            raise VerificationError(f"slice artifact contract is missing: {marker}")

    service = sources["SlicerProcessService.kt"]
    for marker in (
        "artifactStore.prepareForSlice()",
        "artifactStore.persist(",
        "scheduleStorageGuard",
        "artifactStore.activeOutputIsUnsafe()",
        "transientRoots = listOf(filesDir, cacheDir)",
        "estimatedTimeSeconds.isFinite()",
        "estimatedFilamentGrams.isFinite()",
    ):
        if marker not in service:
            raise VerificationError(f"slicer worker storage containment is missing: {marker}")
    if ".drop(MAX_RETAINED_OUTPUTS)" in service:
        raise VerificationError("slicer worker reverted to count-only output pruning")

    if sources["MainActivity.kt"].count("SliceArtifactLease.acquire") < 3:
        raise VerificationError("preview and export readers are not all leased")
    if "SliceArtifactLease.acquire(gcode)" not in sources["RemoteDevice.kt"]:
        raise VerificationError("remote upload does not lease its G-code")

    tests = sources["SliceArtifactStoreTest.kt"]
    for marker in (
        "pruningEnforcesCountAndByteBudgetsOldestFirst",
        "activeReaderLeasePreventsDeletionUntilItCloses",
        "oversizedNativeOutputIsRejectedAndRemoved",
        "preparationRecoversStaleWorkAndFreesTheReserve",
        "activeOutputGuardRequiresAFileAndDetectsSizeOrEmergencySpace",
        "privateCacheOutputIsAcceptedAndRecovered",
    ):
        if marker not in tests:
            raise VerificationError(f"slice storage host regression is missing: {marker}")
    if "sliceArtifactLeaseProtectsConcurrentReadersAcrossProcesses" not in sources[
        "NativeEngineInstrumentedTest.kt"
    ]:
        raise VerificationError("cross-process ARM64 artifact lease regression is missing")

    for document in ("README.md", "SECURITY.md", "CONTRIBUTING.md"):
        if "G-code" not in sources[document] or "lease" not in sources[document].lower():
            raise VerificationError(f"slice artifact policy is not documented in {document}")


def read_sources() -> dict[str, str]:
    main = ROOT / "android/app/src/main/java/com/ashcastle/duckyslicer"
    tests = ROOT / "android/app/src/test/java/com/ashcastle/duckyslicer"
    device_tests = ROOT / "android/app/src/androidTest/java/com/ashcastle/duckyslicer"
    return {
        "SliceArtifactStore.kt": (main / "SliceArtifactStore.kt").read_text(encoding="utf-8"),
        "SlicerProcessService.kt": (main / "SlicerProcessService.kt").read_text(encoding="utf-8"),
        "MainActivity.kt": (main / "MainActivity.kt").read_text(encoding="utf-8"),
        "RemoteDevice.kt": (main / "RemoteDevice.kt").read_text(encoding="utf-8"),
        "SliceArtifactStoreTest.kt": (tests / "SliceArtifactStoreTest.kt").read_text(
            encoding="utf-8"
        ),
        "NativeEngineInstrumentedTest.kt": (
            device_tests / "NativeEngineInstrumentedTest.kt"
        ).read_text(encoding="utf-8"),
        "README.md": (ROOT / "README.md").read_text(encoding="utf-8"),
        "SECURITY.md": (ROOT / "SECURITY.md").read_text(encoding="utf-8"),
        "CONTRIBUTING.md": (ROOT / "CONTRIBUTING.md").read_text(encoding="utf-8"),
    }


def main() -> None:
    try:
        verify_slice_storage(read_sources())
    except (OSError, VerificationError) as error:
        raise SystemExit(f"Slice storage verification failed: {error}") from error
    print("Verified bounded generated G-code storage and cross-process reader leases")


if __name__ == "__main__":
    main()
