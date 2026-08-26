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
        "WorkspaceScreen.kt",
        "SliceOperationViewModel.kt",
        "CreatedDocument.kt",
        "GcodeExportViewModel.kt",
        "GcodeOutputActions.kt",
        "GcodeShare.kt",
        "GcodeShareProvider.kt",
        "AndroidManifest.xml",
        "gcode_share_paths.xml",
        "PlateSliceResults.kt",
        "RemoteDevice.kt",
        "SliceArtifactStoreTest.kt",
        "NativeEngineInstrumentedTest.kt",
        "GcodeExportLifecycleInstrumentedTest.kt",
        "BatchExportDocumentsProvider.java",
        "AccessibilityInstrumentedTest.kt",
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
    workspace = sources["WorkspaceScreen.kt"]
    exporter = sources["GcodeExportViewModel.kt"]
    output_actions = sources["GcodeOutputActions.kt"]
    created_document = sources["CreatedDocument.kt"]
    for marker in (
        "fun deleteFailedCreatedDocument(context: Context, uri: Uri)",
        "ContentResolver.SCHEME_CONTENT",
        "DocumentsContract.deleteDocument",
        "resolver.delete(uri, null, null)",
        "class DocumentTransferCancelledException",
        "class DocumentTransferCancellation",
        "CancellationSignal()",
        "providerSignal.cancel()",
        "resources.first.closeQuietly()",
        "resources.second.closeQuietly()",
    ):
        if marker not in created_document:
            raise VerificationError(f"failed created-document cleanup is missing: {marker}")
    for marker in (
        "class GcodeExportViewModel(application: Application) : AndroidViewModel(application)",
        "viewModelScope.launch(Dispatchers.IO)",
        "SliceArtifactLease.acquire(source)",
        "DocumentTransferCancellation()",
        "DocumentTransferCancelledException",
        "openAssetFileDescriptor(",
        '"wt",',
        "copyCancellable(",
        "fun cancelActiveExport(): Boolean",
        "if (activeExport?.id == operationId) activeExport = null",
        "override fun onCleared()",
        "deleteFailedCreatedDocument(application, uri)",
        "SupportEvent.GCODE_EXPORT_FAILED",
        "fun exportAll(treeUri: Uri, batch: GcodeExportBatch): Boolean",
        "DocumentsContract.isTreeUri(treeUri)",
        "DocumentsContract.buildDocumentUriUsingTree(",
        "DocumentsContract.createDocument(",
        "createdDocuments.asReversed()",
        "batch.entries.any { !it.outcome.isRestorableFrom(application.filesDir) }",
        "withExportProgress(operationId, completedFiles)",
    ):
        if marker not in exporter:
            raise VerificationError(f"retained G-code export contract is missing: {marker}")
    for marker in (
        "ViewModelProvider(this)[GcodeExportViewModel::class.java]",
        "rememberGcodeOutputActions(",
        "gcodeOutputActions.clearPending()",
        "onSave = gcodeOutputActions.save",
        "onShareGcode = gcodeOutputActions.share",
        "gcodeExportModel::cancelActiveExport",
    ):
        if marker not in main_activity:
            raise VerificationError(f"retained G-code export dispatch is missing: {marker}")
    for marker in (
        "var pendingSingle by rememberSaveable",
        "pendingSingle = selectedResult",
        "model.export(uri, requested.outcome)",
        "ActivityResultContracts.OpenDocumentTree()",
        "var pendingBatch by rememberSaveable",
        "pendingBatch = requested",
        "model.exportAll(uri, requested)",
        "clearPending =",
    ):
        if marker not in output_actions:
            raise VerificationError(f"G-code output action ownership is missing: {marker}")
    for forbidden in (
        "SliceArtifactLease.acquire(completed.output)",
        "openOutputStream(uri)",
        "rememberCoroutineScope()",
    ):
        if forbidden in main_activity:
            raise VerificationError("G-code export is still owned by the Activity composition")
    for marker in (
        "gcodeExportState: GcodeExportState",
        "canExportAllGcode: Boolean",
        "onCancelGcodeExport: () -> Unit",
        "R.string.cancel_gcode_export",
        "R.string.canceling_gcode_export",
        "R.string.export_all_gcode",
        "R.string.exporting_gcode_files",
        "if (exporting) onCancelExport() else onExport()",
    ):
        if marker not in workspace:
            raise VerificationError(f"G-code export cancellation UI is missing: {marker}")
    preview_operation = sources["SliceOperationViewModel.kt"]
    if "SliceArtifactLease.acquire(outcome.output)" not in preview_operation:
        raise VerificationError("Preview generation does not lease its retained artifact")
    if 'GCODE_DOCUMENT_MIME_TYPE = "application/octet-stream"' not in main_activity:
        raise VerificationError("G-code document MIME type may let providers append .txt")
    if "CreateDocument(GCODE_DOCUMENT_MIME_TYPE)" not in output_actions:
        raise VerificationError("G-code export does not use the binary document contract")
    if 'CreateDocument("text/plain")' in output_actions:
        raise VerificationError("G-code export reverted to a .txt-producing MIME type")
    share = sources["GcodeShare.kt"]
    for marker in (
        'GCODE_SHARE_MIME_TYPE = "text/x.gcode"',
        "outcome.isRestorableFrom(context.filesDir)",
        '"${context.packageName}.slice-share"',
        "safeGcodeFileName(outcome.suggestedName)",
        "FileProvider.getUriForFile(",
        "Intent.ACTION_SEND",
        "Intent.EXTRA_STREAM",
        "Intent.EXTRA_TITLE",
        "ClipData.newUri(",
        "Intent.FLAG_GRANT_READ_URI_PERMISSION",
    ):
        if marker not in share:
            raise VerificationError(f"outgoing G-code share contract is missing: {marker}")
    provider_source = sources["GcodeShareProvider.kt"]
    if "class GcodeShareProvider : FileProvider(R.xml.gcode_share_paths)" not in provider_source:
        raise VerificationError("G-code share provider is not bound to its narrow path policy")
    share_manifest = sources["AndroidManifest.xml"]
    for marker in (
        'android:name=".GcodeShareProvider"',
        'android:authorities="${applicationId}.slice-share"',
        'android:exported="false"',
        'android:grantUriPermissions="true"',
        'android:resource="@xml/gcode_share_paths"',
    ):
        if marker not in share_manifest:
            raise VerificationError(f"G-code share manifest boundary is missing: {marker}")
    share_paths = sources["gcode_share_paths.xml"]
    if (
        share_paths.count("<files-path") != 1
        or 'name="retained-gcode"' not in share_paths
        or 'path="slices/"' not in share_paths
        or any(marker in share_paths for marker in ("<root-path", "<cache-path", 'path="."'))
    ):
        raise VerificationError("G-code share path must expose only retained slices")
    for marker in (
        "gcodeShareIntentOrNull(context, outcome)",
        "Intent.createChooser(share, chooserTitle)",
    ):
        if marker not in output_actions:
            raise VerificationError(f"outgoing G-code share dispatch is missing: {marker}")
    for marker in (
        "onShareGcode: () -> Unit",
        "R.string.share_gcode",
        "onShare = onShareGcode",
    ):
        if marker not in workspace:
            raise VerificationError(f"outgoing G-code share UI is missing: {marker}")
    plate_results = sources["PlateSliceResults.kt"]
    for marker in (
        "fun PlateSliceResults.completeExportBatch(",
        "snapshot.plates.withIndex()",
        "resultFor(plate.id) ?: return null",
        "displayName = plateGcodeFileName(",
        "indexedPlate.index + 1",
        "plate.name",
    ):
        if marker not in plate_results:
            raise VerificationError(f"complete plate export selection is missing: {marker}")
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
        "retainedCancellationStopsTheExactCopyAndDeletesThePartialDocument",
        "finalOwnerClearStopsItsCopyAndDeletesThePartialDocument",
        "assertSame(",
        "assertFalse(retainedModel.export(",
        "retainedModel.cancelActiveExport()",
        "store.clear()",
        "KEY_DELETED",
        "KEY_SHA256",
        "allPlateExportSurvivesRecreationAndCreatesEveryNamedDocument",
        "laterBatchFailureDeletesEarlierDocumentsAndKeepsPrivateArtifacts",
        "batchCancellationDeletesEveryDocumentCreatedByThatOperation",
        "BatchExportDocumentsProvider.TREE_URI",
        "batchProviderRejectsTraversalDocumentNamesOutsideItsRoot",
        "currentArtifactShareIsNamedReadableAndLimitedToRetainedGcode",
    ):
        if marker not in export_tests:
            raise VerificationError(f"retained G-code export regression is missing: {marker}")
    provider = sources["BatchExportDocumentsProvider.java"]
    for marker in (
        "extends ContentProvider",
        'METHOD_CREATE_DOCUMENT = "android:createDocument"',
        "openAssetFile(",
        "MODE_FAIL_SECOND",
        "MODE_BLOCK_SECOND",
        "createDocument(",
        "deleteDocument(",
        "signal.setOnCancelListener",
        'documentId.contains("..")',
        "candidate.startsWith(rootPath)",
        "rootPath.equals(candidate.getParent())",
    ):
        if marker not in provider:
            raise VerificationError(f"batch export test provider is incomplete: {marker}")
    if "cancelGcodeExportActionIsReachable" not in sources["AccessibilityInstrumentedTest.kt"]:
        raise VerificationError("accessible G-code export cancellation regression is missing")
    if "allPlateGcodeExportIsExplicitAndReportsFileProgress" not in sources[
        "AccessibilityInstrumentedTest.kt"
    ]:
        raise VerificationError("accessible all-plate G-code export regression is missing")
    if "gcodeShareIsExplicitInPreviewExportOptions" not in sources[
        "AccessibilityInstrumentedTest.kt"
    ]:
        raise VerificationError("accessible outgoing G-code share regression is missing")

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
        "WorkspaceScreen.kt": (main / "WorkspaceScreen.kt").read_text(encoding="utf-8"),
        "SliceOperationViewModel.kt": (main / "SliceOperationViewModel.kt").read_text(
            encoding="utf-8"
        ),
        "CreatedDocument.kt": (main / "CreatedDocument.kt").read_text(encoding="utf-8"),
        "GcodeExportViewModel.kt": (main / "GcodeExportViewModel.kt").read_text(
            encoding="utf-8"
        ),
        "GcodeOutputActions.kt": (main / "GcodeOutputActions.kt").read_text(
            encoding="utf-8"
        ),
        "GcodeShare.kt": (main / "GcodeShare.kt").read_text(encoding="utf-8"),
        "GcodeShareProvider.kt": (main / "GcodeShareProvider.kt").read_text(
            encoding="utf-8"
        ),
        "AndroidManifest.xml": (ROOT / "android/app/src/main/AndroidManifest.xml").read_text(
            encoding="utf-8"
        ),
        "gcode_share_paths.xml": (
            ROOT / "android/app/src/main/res/xml/gcode_share_paths.xml"
        ).read_text(encoding="utf-8"),
        "PlateSliceResults.kt": (main / "PlateSliceResults.kt").read_text(encoding="utf-8"),
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
        "BatchExportDocumentsProvider.java": (
            device_tests / "BatchExportDocumentsProvider.java"
        ).read_text(encoding="utf-8"),
        "AccessibilityInstrumentedTest.kt": (
            device_tests / "AccessibilityInstrumentedTest.kt"
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
