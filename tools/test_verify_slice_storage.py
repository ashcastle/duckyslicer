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
            "ViewModelProvider(this)[GcodeExportViewModel::class.java] "
            "rememberGcodeOutputActions( gcodeOutputActions.clearPending() "
            "onSave = gcodeOutputActions.save onShareGcode = gcodeOutputActions.share "
            "gcodeExportModel::cancelActiveExport"
        ),
        "WorkspaceScreen.kt": (
            "gcodeExportState: GcodeExportState canExportAllGcode: Boolean "
            "onCancelGcodeExport: () -> Unit "
            "R.string.cancel_gcode_export R.string.canceling_gcode_export "
            "R.string.export_all_gcode R.string.exporting_gcode_files "
            "if (exporting) onCancelExport() else onExport() "
            "onShareGcode: () -> Unit R.string.share_gcode onShare = onShareGcode"
        ),
        "SliceOperationViewModel.kt": "SliceArtifactLease.acquire(outcome.output)",
        "CreatedDocument.kt": (
            "fun deleteFailedCreatedDocument(context: Context, uri: Uri) "
            "ContentResolver.SCHEME_CONTENT DocumentsContract.deleteDocument "
            "resolver.delete(uri, null, null) "
            "class DocumentTransferCancelledException "
            "class DocumentTransferCancellation CancellationSignal() "
            "providerSignal.cancel() resources.first.closeQuietly() "
            "resources.second.closeQuietly()"
        ),
        "GcodeExportViewModel.kt": (
            "class GcodeExportViewModel(application: Application) : AndroidViewModel(application) "
            "viewModelScope.launch(Dispatchers.IO) SliceArtifactLease.acquire(source) "
            "DocumentTransferCancellation() DocumentTransferCancelledException "
            "openAssetFileDescriptor( \"wt\", "
            "copyCancellable( fun cancelActiveExport(): Boolean override fun onCleared() "
            "if (activeExport?.id == operationId) activeExport = null "
            "deleteFailedCreatedDocument(application, uri) "
            "SupportEvent.GCODE_EXPORT_FAILED "
            "fun exportAll(treeUri: Uri, batch: GcodeExportBatch): Boolean "
            "DocumentsContract.isTreeUri(treeUri) DocumentsContract.buildDocumentUriUsingTree( "
            "DocumentsContract.createDocument( createdDocuments.asReversed() "
            "batch.entries.any { !it.outcome.isRestorableFrom(application.filesDir) } "
            "withExportProgress(operationId, completedFiles)"
        ),
        "GcodeOutputActions.kt": (
            "CreateDocument(GCODE_DOCUMENT_MIME_TYPE) "
            "var pendingSingle by rememberSaveable pendingSingle = selectedResult "
            "model.export(uri, requested.outcome) ActivityResultContracts.OpenDocumentTree() "
            "var pendingBatch by rememberSaveable pendingBatch = requested "
            "model.exportAll(uri, requested) clearPending = "
            "gcodeShareIntentOrNull(context, outcome) Intent.createChooser(share, chooserTitle)"
        ),
        "GcodeShare.kt": (
            'GCODE_SHARE_MIME_TYPE = "text/x.gcode" '
            "outcome.isRestorableFrom(context.filesDir) "
            '"${context.packageName}.slice-share" safeGcodeFileName(outcome.suggestedName) '
            "FileProvider.getUriForFile( Intent.ACTION_SEND Intent.EXTRA_STREAM "
            "Intent.EXTRA_TITLE ClipData.newUri( Intent.FLAG_GRANT_READ_URI_PERMISSION"
        ),
        "GcodeShareProvider.kt": (
            "class GcodeShareProvider : FileProvider(R.xml.gcode_share_paths)"
        ),
        "AndroidManifest.xml": (
            '<provider android:name=".GcodeShareProvider" '
            'android:authorities="${applicationId}.slice-share" '
            'android:exported="false" android:grantUriPermissions="true" '
            'android:resource="@xml/gcode_share_paths" />'
        ),
        "gcode_share_paths.xml": (
            '<paths><files-path name="retained-gcode" path="slices/" /></paths>'
        ),
        "PlateSliceResults.kt": (
            "fun PlateSliceResults.completeExportBatch( snapshot.plates.withIndex() "
            "resultFor(plate.id) ?: return null displayName = plateGcodeFileName( "
            "indexedPlate.index + 1 plate.name"
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
        "GcodeExportLifecycleInstrumentedTest.kt": (
            "gcodeExportSurvivesActivityRecreationAndCopiesTheExactArtifactOnce "
            "retainedCancellationStopsTheExactCopyAndDeletesThePartialDocument "
            "finalOwnerClearStopsItsCopyAndDeletesThePartialDocument "
            "assertSame( assertFalse(retainedModel.export( retainedModel.cancelActiveExport() "
            "store.clear() KEY_DELETED KEY_SHA256"
            " allPlateExportSurvivesRecreationAndCreatesEveryNamedDocument"
            " laterBatchFailureDeletesEarlierDocumentsAndKeepsPrivateArtifacts"
            " batchCancellationDeletesEveryDocumentCreatedByThatOperation"
            " BatchExportDocumentsProvider.TREE_URI"
            " batchProviderRejectsTraversalDocumentNamesOutsideItsRoot"
            " currentArtifactShareIsNamedReadableAndLimitedToRetainedGcode"
        ),
        "BatchExportDocumentsProvider.java": (
            'extends ContentProvider METHOD_CREATE_DOCUMENT = "android:createDocument" '
            "openAssetFile( MODE_FAIL_SECOND MODE_BLOCK_SECOND createDocument( "
            'deleteDocument( signal.setOnCancelListener documentId.contains("..") '
            "candidate.startsWith(rootPath) rootPath.equals(candidate.getParent())"
        ),
        "AccessibilityInstrumentedTest.kt": (
            "cancelGcodeExportActionIsReachable "
            "allPlateGcodeExportIsExplicitAndReportsFileProgress "
            "gcodeShareIsExplicitInPreviewExportOptions"
        ),
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

    def test_rejects_missing_remote_reader_lease(self) -> None:
        sources = valid_sources()
        sources["RemoteDevice.kt"] = "upload without a lease"
        with self.assertRaisesRegex(VerificationError, "remote upload"):
            verify_slice_storage(sources)

    def test_rejects_missing_preview_reader_lease(self) -> None:
        sources = valid_sources()
        sources["SliceOperationViewModel.kt"] = "preview without a lease"
        with self.assertRaisesRegex(VerificationError, "Preview generation"):
            verify_slice_storage(sources)

    def test_rejects_missing_export_reader_lease(self) -> None:
        sources = valid_sources()
        sources["GcodeExportViewModel.kt"] = sources["GcodeExportViewModel.kt"].replace(
            "SliceArtifactLease.acquire(source)", "export without a lease"
        )
        with self.assertRaisesRegex(VerificationError, "retained G-code export"):
            verify_slice_storage(sources)

    def test_rejects_activity_owned_export_coroutine(self) -> None:
        sources = valid_sources()
        sources["MainActivity.kt"] += " rememberCoroutineScope() openOutputStream(uri)"
        with self.assertRaisesRegex(VerificationError, "Activity composition"):
            verify_slice_storage(sources)

    def test_rejects_root_owned_gcode_output_launchers(self) -> None:
        sources = valid_sources()
        sources["GcodeOutputActions.kt"] = "output actions omitted"
        with self.assertRaisesRegex(VerificationError, "output action ownership"):
            verify_slice_storage(sources)

    def test_rejects_missing_failed_document_cleanup(self) -> None:
        sources = valid_sources()
        sources["GcodeExportViewModel.kt"] = sources["GcodeExportViewModel.kt"].replace(
            "deleteFailedCreatedDocument(application, uri)", "leave partial output"
        )
        with self.assertRaisesRegex(VerificationError, "retained G-code export"):
            verify_slice_storage(sources)

    def test_rejects_non_atomic_batch_cleanup(self) -> None:
        sources = valid_sources()
        sources["GcodeExportViewModel.kt"] = sources["GcodeExportViewModel.kt"].replace(
            "createdDocuments.asReversed()", "leave earlier batch files"
        )
        with self.assertRaisesRegex(VerificationError, "retained G-code export"):
            verify_slice_storage(sources)

    def test_rejects_non_interruptible_gcode_export(self) -> None:
        sources = valid_sources()
        sources["GcodeExportViewModel.kt"] = sources["GcodeExportViewModel.kt"].replace(
            "DocumentTransferCancellation()", "blocking copy without request cancellation"
        )
        with self.assertRaisesRegex(VerificationError, "retained G-code export"):
            verify_slice_storage(sources)

    def test_rejects_missing_gcode_export_cancel_action(self) -> None:
        sources = valid_sources()
        sources["WorkspaceScreen.kt"] = sources["WorkspaceScreen.kt"].replace(
            "onCancelGcodeExport: () -> Unit", "no cancel action"
        )
        with self.assertRaisesRegex(VerificationError, "cancellation UI"):
            verify_slice_storage(sources)

    def test_rejects_missing_accessible_gcode_export_cancel_regression(self) -> None:
        sources = valid_sources()
        sources["AccessibilityInstrumentedTest.kt"] = "missing accessibility regression"
        with self.assertRaisesRegex(VerificationError, "accessible G-code"):
            verify_slice_storage(sources)

    def test_rejects_missing_accessible_all_plate_export_regression(self) -> None:
        sources = valid_sources()
        sources["AccessibilityInstrumentedTest.kt"] = sources[
            "AccessibilityInstrumentedTest.kt"
        ].replace("allPlateGcodeExportIsExplicitAndReportsFileProgress", "missing batch export")
        with self.assertRaisesRegex(VerificationError, "accessible all-plate"):
            verify_slice_storage(sources)

    def test_rejects_broad_gcode_share_path(self) -> None:
        sources = valid_sources()
        sources["gcode_share_paths.xml"] = '<paths><files-path name="all" path="." /></paths>'
        with self.assertRaisesRegex(VerificationError, "share path"):
            verify_slice_storage(sources)

    def test_rejects_writable_or_unbounded_gcode_share_intent(self) -> None:
        sources = valid_sources()
        sources["GcodeShare.kt"] = sources["GcodeShare.kt"].replace(
            "Intent.FLAG_GRANT_READ_URI_PERMISSION",
            "Intent.FLAG_GRANT_WRITE_URI_PERMISSION",
        )
        with self.assertRaisesRegex(VerificationError, "share contract"):
            verify_slice_storage(sources)

    def test_rejects_missing_gcode_share_device_regression(self) -> None:
        sources = valid_sources()
        sources["GcodeExportLifecycleInstrumentedTest.kt"] = sources[
            "GcodeExportLifecycleInstrumentedTest.kt"
        ].replace(
            "currentArtifactShareIsNamedReadableAndLimitedToRetainedGcode",
            "missing outgoing share regression",
        )
        with self.assertRaisesRegex(VerificationError, "export regression"):
            verify_slice_storage(sources)

    def test_rejects_text_plain_gcode_export(self) -> None:
        sources = valid_sources()
        sources["GcodeOutputActions.kt"] = sources["GcodeOutputActions.kt"].replace(
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
