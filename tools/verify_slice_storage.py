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
        "SliceConfig.kt",
        "ProjectStore.kt",
        "SlicerProcessService.kt",
        "runtime.patch",
        "MainActivity.kt",
        "SliceOperationViewModel.kt",
        "CreatedDocument.kt",
        "GcodeExportViewModel.kt",
        "RemoteDevice.kt",
        "SliceArtifactStoreTest.kt",
        "NativeEngineInstrumentedTest.kt",
        "GcodeExportLifecycleInstrumentedTest.kt",
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
        "ProjectStore.modelStorageRoot(filesDir)",
        "estimatedTimeSeconds.isFinite()",
        "estimatedFilamentGrams.isFinite()",
        "sliceWithOutputLimitForTest",
        "KEY_MAXIMUM_GCODE_BYTES_FOR_TEST",
        "PRODUCTION_MAXIMUM_GCODE_BYTES",
        "this.maximumGcodeBytes = maximumGcodeBytes",
    ):
        if marker not in service:
            raise VerificationError(f"slicer worker storage containment is missing: {marker}")
    if ".drop(MAX_RETAINED_OUTPUTS)" in service:
        raise VerificationError("slicer worker reverted to count-only output pruning")
    if "modelStorageRoot(filesRoot: File)" not in sources["ProjectStore.kt"]:
        raise VerificationError("persistent project model output root is not canonicalized")

    if "maximumGcodeBytes: Int = 1_073_741_824" not in sources["SliceConfig.kt"]:
        raise VerificationError("JNI G-code output ceiling is missing")

    runtime_patch = sources["runtime.patch"]
    added_runtime = "\n".join(
        line[1:]
        for line in runtime_patch.splitlines()
        if line.startswith("+") and not line.startswith("+++")
    )
    for marker in (
        "maximum_gcode_bytes",
        "RLIMIT_FSIZE",
        "getrlimit",
        "setrlimit",
        "MAXIMUM_GCODE_BYTES",
        "LEGACY_GCODE_PREVIEW_BYTES",
        "gcode_file.read",
    ):
        if marker not in added_runtime:
            raise VerificationError(f"native G-code writer containment is missing: {marker}")
    if "gcode_file.rdbuf()" in added_runtime:
        raise VerificationError("native compatibility preview reads the complete G-code")

    main_activity = sources["MainActivity.kt"]
    exporter = sources["GcodeExportViewModel.kt"]
    created_document = sources["CreatedDocument.kt"]
    for marker in (
        "fun deleteFailedCreatedDocument(context: Context, uri: Uri)",
        "ContentResolver.SCHEME_CONTENT",
        "DocumentsContract.deleteDocument",
        "resolver.delete(uri, null, null)",
    ):
        if marker not in created_document:
            raise VerificationError(f"failed created-document cleanup is missing: {marker}")
    for marker in (
        "class GcodeExportViewModel(application: Application) : AndroidViewModel(application)",
        "viewModelScope.launch(Dispatchers.IO)",
        "SliceArtifactLease.acquire(source)",
        'openOutputStream(uri, "wt")',
        "deleteFailedCreatedDocument(application, uri)",
        "SupportEvent.GCODE_EXPORT_FAILED",
    ):
        if marker not in exporter:
            raise VerificationError(f"retained G-code export contract is missing: {marker}")
    for marker in (
        "ViewModelProvider(this)[GcodeExportViewModel::class.java]",
        "gcodeExportModel.export(uri, completed)",
    ):
        if marker not in main_activity:
            raise VerificationError(f"retained G-code export dispatch is missing: {marker}")
    for forbidden in (
        "SliceArtifactLease.acquire(completed.output)",
        "openOutputStream(uri)",
        "rememberCoroutineScope()",
    ):
        if forbidden in main_activity:
            raise VerificationError("G-code export is still owned by the Activity composition")
    preview_operation = sources["SliceOperationViewModel.kt"]
    if "SliceArtifactLease.acquire(outcome.output)" not in preview_operation:
        raise VerificationError("Preview generation does not lease its retained artifact")
    if 'GCODE_DOCUMENT_MIME_TYPE = "application/octet-stream"' not in main_activity:
        raise VerificationError("G-code document MIME type may let providers append .txt")
    if "CreateDocument(GCODE_DOCUMENT_MIME_TYPE)" not in main_activity:
        raise VerificationError("G-code export does not use the binary document contract")
    if 'CreateDocument("text/plain")' in main_activity:
        raise VerificationError("G-code export reverted to a .txt-producing MIME type")
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
        "persistentProjectModelOutputIsAcceptedGuardedAndRecovered",
    ):
        if marker not in tests:
            raise VerificationError(f"slice storage host regression is missing: {marker}")
    device_tests = sources["NativeEngineInstrumentedTest.kt"]
    if "sliceArtifactLeaseProtectsConcurrentReadersAcrossProcesses" not in device_tests:
        raise VerificationError("cross-process ARM64 artifact lease regression is missing")
    if "nativeGcodeWriterHardLimitContainsDiskGrowthAndRecovers" not in device_tests:
        raise VerificationError("ARM64 native G-code hard-limit recovery regression is missing")
    if "persistentProjectModelSlicesIntoRetainedArtifact" not in device_tests:
        raise VerificationError("ARM64 persistent-project slice regression is missing")
    export_tests = sources["GcodeExportLifecycleInstrumentedTest.kt"]
    for marker in (
        "gcodeExportSurvivesActivityRecreationAndCopiesTheExactArtifactOnce",
        "assertSame(",
        "assertFalse(retainedModel.export(",
        "KEY_SHA256",
    ):
        if marker not in export_tests:
            raise VerificationError(f"retained G-code export regression is missing: {marker}")

    for document in ("SECURITY.md", "CONTRIBUTING.md"):
        if "G-code" not in sources[document] or "lease" not in sources[document].lower():
            raise VerificationError(f"slice artifact policy is not documented in {document}")
        if "RLIMIT_FSIZE" not in sources[document]:
            raise VerificationError(f"native G-code hard limit is not documented in {document}")


def read_sources() -> dict[str, str]:
    main = ROOT / "android/app/src/main/java/com/ashcastle/duckyslicer"
    tests = ROOT / "android/app/src/test/java/com/ashcastle/duckyslicer"
    device_tests = ROOT / "android/app/src/androidTest/java/com/ashcastle/duckyslicer"
    return {
        "SliceArtifactStore.kt": (main / "SliceArtifactStore.kt").read_text(encoding="utf-8"),
        "SliceConfig.kt": (
            ROOT / "android/app/src/main/java/com/u1/slicer/data/SliceConfig.kt"
        ).read_text(encoding="utf-8"),
        "ProjectStore.kt": (main / "ProjectStore.kt").read_text(encoding="utf-8"),
        "SlicerProcessService.kt": (main / "SlicerProcessService.kt").read_text(encoding="utf-8"),
        "runtime.patch": (ROOT / "native/slicer-runtime/runtime.patch").read_text(
            encoding="utf-8"
        ),
        "MainActivity.kt": (main / "MainActivity.kt").read_text(encoding="utf-8"),
        "SliceOperationViewModel.kt": (main / "SliceOperationViewModel.kt").read_text(
            encoding="utf-8"
        ),
        "CreatedDocument.kt": (main / "CreatedDocument.kt").read_text(encoding="utf-8"),
        "GcodeExportViewModel.kt": (main / "GcodeExportViewModel.kt").read_text(
            encoding="utf-8"
        ),
        "RemoteDevice.kt": (main / "RemoteDevice.kt").read_text(encoding="utf-8"),
        "SliceArtifactStoreTest.kt": (tests / "SliceArtifactStoreTest.kt").read_text(
            encoding="utf-8"
        ),
        "NativeEngineInstrumentedTest.kt": (
            device_tests / "NativeEngineInstrumentedTest.kt"
        ).read_text(encoding="utf-8"),
        "GcodeExportLifecycleInstrumentedTest.kt": (
            device_tests / "GcodeExportLifecycleInstrumentedTest.kt"
        ).read_text(encoding="utf-8"),
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
