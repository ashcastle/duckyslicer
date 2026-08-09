from __future__ import annotations

import unittest

from tools.verify_slice_storage import VerificationError, verify_slice_storage


def valid_sources() -> dict[str, str]:
    return {
        "SliceArtifactStore.kt": (
            "MAXIMUM_OUTPUT_BYTES MAXIMUM_RETAINED_BYTES MINIMUM_FREE_BYTES "
            "EMERGENCY_FREE_BYTES MAXIMUM_RETAINED_OUTPUTS StandardCopyOption.ATOMIC_MOVE "
            "output.fd.sync() copyBounded tryLock() SliceArtifactLease activeOutputIsUnsafe"
        ),
        "SlicerProcessService.kt": (
            "artifactStore.prepareForSlice() artifactStore.persist( scheduleStorageGuard "
            "artifactStore.activeOutputIsUnsafe() estimatedTimeSeconds.isFinite() "
            "estimatedFilamentGrams.isFinite() transientRoots = listOf(filesDir, cacheDir)"
        ),
        "MainActivity.kt": " ".join(["SliceArtifactLease.acquire"] * 3),
        "RemoteDevice.kt": "SliceArtifactLease.acquire(gcode)",
        "SliceArtifactStoreTest.kt": (
            "pruningEnforcesCountAndByteBudgetsOldestFirst "
            "activeReaderLeasePreventsDeletionUntilItCloses "
            "oversizedNativeOutputIsRejectedAndRemoved "
            "preparationRecoversStaleWorkAndFreesTheReserve "
            "activeOutputGuardRequiresAFileAndDetectsSizeOrEmergencySpace "
            "privateCacheOutputIsAcceptedAndRecovered"
        ),
        "NativeEngineInstrumentedTest.kt": (
            "sliceArtifactLeaseProtectsConcurrentReadersAcrossProcesses"
        ),
        "README.md": "G-code reader lease",
        "SECURITY.md": "G-code reader lease",
        "CONTRIBUTING.md": "G-code reader lease",
    }


class VerifySliceStorageTest(unittest.TestCase):
    def test_accepts_complete_storage_contract(self) -> None:
        verify_slice_storage(valid_sources())

    def test_rejects_count_only_pruning(self) -> None:
        sources = valid_sources()
        sources["SlicerProcessService.kt"] += " .drop(MAX_RETAINED_OUTPUTS)"
        with self.assertRaisesRegex(VerificationError, "count-only"):
            verify_slice_storage(sources)

    def test_rejects_missing_reader_lease(self) -> None:
        sources = valid_sources()
        sources["RemoteDevice.kt"] = "upload without a lease"
        with self.assertRaisesRegex(VerificationError, "remote upload"):
            verify_slice_storage(sources)


if __name__ == "__main__":
    unittest.main()
