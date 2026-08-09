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
        "SliceConfig.kt": "maximumGcodeBytes: Int = 1_073_741_824",
        "ProjectStore.kt": "modelStorageRoot(filesRoot: File)",
        "SlicerProcessService.kt": (
            "artifactStore.prepareForSlice() artifactStore.persist( scheduleStorageGuard "
            "artifactStore.activeOutputIsUnsafe() estimatedTimeSeconds.isFinite() "
            "estimatedFilamentGrams.isFinite() ProjectStore.modelStorageRoot(filesDir) "
            "sliceWithOutputLimitForTest KEY_MAXIMUM_GCODE_BYTES_FOR_TEST "
            "PRODUCTION_MAXIMUM_GCODE_BYTES this.maximumGcodeBytes = maximumGcodeBytes"
        ),
        "runtime.patch": (
            "+maximum_gcode_bytes\n+RLIMIT_FSIZE\n+getrlimit\n+setrlimit\n"
            "+MAXIMUM_GCODE_BYTES\n+LEGACY_GCODE_PREVIEW_BYTES\n+gcode_file.read"
        ),
        "MainActivity.kt": (
            "GCODE_DOCUMENT_MIME_TYPE = \"application/octet-stream\" "
            "CreateDocument(GCODE_DOCUMENT_MIME_TYPE) "
            + " ".join(["SliceArtifactLease.acquire"] * 3)
        ),
        "RemoteDevice.kt": "SliceArtifactLease.acquire(gcode)",
        "SliceArtifactStoreTest.kt": (
            "pruningEnforcesCountAndByteBudgetsOldestFirst "
            "activeReaderLeasePreventsDeletionUntilItCloses "
            "oversizedNativeOutputIsRejectedAndRemoved "
            "preparationRecoversStaleWorkAndFreesTheReserve "
            "activeOutputGuardRequiresAFileAndDetectsSizeOrEmergencySpace "
            "privateCacheOutputIsAcceptedAndRecovered "
            "persistentProjectModelOutputIsAcceptedGuardedAndRecovered"
        ),
        "NativeEngineInstrumentedTest.kt": (
            "sliceArtifactLeaseProtectsConcurrentReadersAcrossProcesses "
            "nativeGcodeWriterHardLimitContainsDiskGrowthAndRecovers "
            "persistentProjectModelSlicesIntoRetainedArtifact"
        ),
        "README.md": "G-code reader lease RLIMIT_FSIZE",
        "SECURITY.md": "G-code reader lease RLIMIT_FSIZE",
        "CONTRIBUTING.md": "G-code reader lease RLIMIT_FSIZE",
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

    def test_rejects_text_plain_gcode_export(self) -> None:
        sources = valid_sources()
        sources["MainActivity.kt"] = sources["MainActivity.kt"].replace(
            "CreateDocument(GCODE_DOCUMENT_MIME_TYPE)", 'CreateDocument("text/plain")'
        )
        with self.assertRaisesRegex(VerificationError, "document contract|txt-producing"):
            verify_slice_storage(sources)

    def test_rejects_missing_native_file_size_limit(self) -> None:
        sources = valid_sources()
        sources["runtime.patch"] = sources["runtime.patch"].replace("+RLIMIT_FSIZE", "+limit")
        with self.assertRaisesRegex(VerificationError, "RLIMIT_FSIZE"):
            verify_slice_storage(sources)

    def test_rejects_unbounded_native_preview_read(self) -> None:
        sources = valid_sources()
        sources["runtime.patch"] += "\n+gcode_file.rdbuf()"
        with self.assertRaisesRegex(VerificationError, "complete G-code"):
            verify_slice_storage(sources)

    def test_rejects_missing_persistent_project_output_root(self) -> None:
        sources = valid_sources()
        sources["SlicerProcessService.kt"] = sources["SlicerProcessService.kt"].replace(
            "ProjectStore.modelStorageRoot(filesDir)", "cacheDir"
        )
        with self.assertRaisesRegex(VerificationError, "modelStorageRoot"):
            verify_slice_storage(sources)


if __name__ == "__main__":
    unittest.main()
