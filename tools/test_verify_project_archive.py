from __future__ import annotations

import unittest

from tools.verify_project_archive import REQUIRED_STRINGS, VerificationError, verify_project_archive


def string_resources() -> str:
    values = "".join(f'<string name="{name}">{name}</string>' for name in REQUIRED_STRINGS)
    return f"<resources>{values}</resources>"


def valid_sources() -> dict[str, str]:
    return {
        "ProjectArchive.kt": " ".join(
            (
                'PROJECT_ARCHIVE_MIME_TYPE = "application/vnd.duckyslicer.project+zip"',
                'PROJECT_ARCHIVE_FILE_EXTENSION = ".duckyproject"',
                "MAX_PROJECT_ARCHIVE_MANIFEST_BYTES = 1_048_576",
                "MAX_PROJECT_ARCHIVE_CONTENT_BYTES = 1_073_741_824L",
                "MAX_PROJECT_ARCHIVE_FILE_BYTES = 1_082_130_432L",
                "MAX_PROJECT_ARCHIVE_ENTRIES = ProjectStore.MAX_PROJECT_VOLUMES + 1",
                'PROJECT_ARCHIVE_FORMAT = "com.ashcastle.duckyslicer.project"',
                "MIN_PROJECT_ARCHIVE_SCHEMA_VERSION = 1",
                "PROJECT_ARCHIVE_SCHEMA_VERSION = 28",
                'ArchivedProjectPlate ArchivedProjectVolume put("role", volume.role.name) '
                'put("config", volume.config.toJson()) ProjectVolumeRole.valueOf '
                "ProjectVolumeConfig.fromJson",
                'getJSONArray("plates") getJSONArray("volumes") legacyProjectVolumeId',
                "selectedPlateId plateOptions: Map<String, SliceOptions>",
                'Regex("models/[0-9]{3}\\\\.stl")',
                "require(!entry.isDirectory require(entries.add(entry.name))",
                "entry.method == ZipEntry.DEFLATED || entry.method == ZipEntry.STORED",
                "readArchiveBytes(",
                "MAX_MODEL_IMPORT_BYTES checkedArchiveTotal",
                "require(referencedEntries == models.keys)",
                "output.fd.sync() parseBoundedJsonObject require(info.triangles > 0)",
                "supportPaint.facets.keys.all",
                "requireAxisScales = schemaVersion >= 6",
                "seamPaint.facets.keys.all",
                "multiColorPaint.facets.keys.all",
                'getJSONArray("multiColorPaint").toArchiveMultiColorPaint()',
                'getJSONArray("variableLayerHeights").toArchiveVariableLayerHeights()',
                'getJSONArray("brimPoints").toArchiveBrimPoints()',
                'getJSONObject("processOverrides").toObjectProcessOverrides()',
                "checkCancellation: () -> Unit = {}",
                "copyArchiveBytes(input, archive, model.length(), checkCancellation)",
                "val copied = copyArchiveBytes(",
                "val info = inspectModel(file)",
                "catch (failure: DocumentTransferCancelledException)",
            )
        ),
        "ProjectVolumeSemantics.kt": (
            "enum class ProjectVolumeRole NEGATIVE_VOLUME(1) PARAMETER_MODIFIER(2) "
            "SUPPORT_BLOCKER(3) SUPPORT_ENFORCER(4) MAX_ENTRIES = 128 "
            "MAX_VALUE_BYTES = 4 * 1_024 MAX_SIDECAR_BYTES = 64 * 1_024 "
            "fun readSidecar fun fromJson"
        ),
        "ProjectStore.kt": " ".join(
            (
                'File(projectRoot, ".archive-${UUID.randomUUID()}")',
                "ProjectArchiveCodec.read(",
                "moveArchiveModel(stagedModel.file, destination)",
                "val plateOptions = decoded.plates.associate save(snapshot, plateOptions)",
                "pruneUnreferencedModels(snapshot)",
                "installed.forEach(File::delete) staging.deleteRecursively()",
                "modelFile.parentFile == modelRoot && modelFile.isFile",
                "StandardCopyOption.ATOMIC_MOVE",
                "recoverAbandonedArchiveStaging recoverGeneratedStaging(projectRoot, \".archive-\")",
                "private fun recoverGeneratedStaging removePrefix(prefix)",
                "UUID.fromString(identifier) !Files.isSymbolicLink(candidate.toPath())",
                "checkCancellation: () -> Unit = {}",
                "ProjectArchiveCodec.write(snapshot, plateOptions, output, checkCancellation)",
                "beginCommit: () -> Unit = {} beginCommit()",
                'SCHEMA_VERSION = 30 put("role", role.name) put("config", config.toJson())',
            )
        ),
        "ProjectOpenRequest.kt": " ".join(
            (
                "intent.action != Intent.ACTION_VIEW",
                "ContentResolver.SCHEME_CONTENT",
                "PROJECT_ARCHIVE_MIME_TYPE PROJECT_ARCHIVE_FILE_EXTENSION",
                "PROJECT_ARCHIVE_COMPATIBLE_MIME_TYPES",
                '"application/zip" "application/x-zip-compressed" "application/octet-stream"',
                "SavedStateHandle StateFlow<ExternalProjectRequest?>",
            )
        ),
        "ProjectTransfer.kt": " ".join(
            (
                "AndroidViewModel(application)",
                "viewModelScope.launch(Dispatchers.IO)",
                "ProjectStore.recoverAbandonedArchiveStaging",
                "ProjectTransferState(busy = true)",
                "val history: ProjectHistoryState val sliceOptions: SliceOptions "
                "val plateOptions: Map<String, SliceOptions> "
                "val restored: Boolean val sessionRevision: Long "
                "val persistedRevision: Long "
                "val activeTransferId: Long? "
                "val activeTransferDirection: ProjectTransferDirection? "
                "val transferCancellationRequested: Boolean",
                "fun ProjectTransferState.withStartedTransfer( "
                "fun ProjectTransferState.withTransferCancellationRequested( "
                "fun ProjectTransferState.withCompletedTransfer(",
                "fun updateHistory( fun updateSession(",
                "projectStore.loadProject()",
                "projectStore.save(document.history.current, document.plateOptions)",
                "PROJECT_SAVE_DEBOUNCE_MILLIS = 400L",
                "pendingPersistence?.join() fun flushPersistence() "
                "override fun onCleared() hasPersistableChanges",
                "mutableState.value.completion != null",
                "projectStore.importArchive",
                "uri.scheme != ContentResolver.SCHEME_CONTENT "
                "DocumentTransferCancellation() openAssetFileDescriptor( \"wt\", "
                "acquireContentProviderClient(uri) "
                "provider.openAssetFile(uri, \"r\", cancellation.providerSignal) "
                "cancellation.providerSignal cancellation.attachInput(input) "
                "cancellation::complete projectStore.exportArchive "
                "cancellation::throwIfRequested DocumentTransferCancelledException "
                "ProjectTransferCompletion.Canceled fun cancelProjectExport(): Boolean "
                "fun cancelProjectImport(): Boolean "
                "activeProjectDocumentTransfer?.operation == operation "
                "FinalProjectOwnerCleanup( activeProjectDocumentTransfer, pending, "
                "cleanup.transfer?.cancellation?.cancel() "
                "completionWasClaimed() hasUnpersistedSession() "
                "hasPersistableChanges(allowActiveTransfer = true) "
                "deleteFailedCreatedDocument(application, uri) "
                "SupportEvent.PROJECT_ARCHIVE_EXPORT_FAILED",
                "catch (failure: CancellationException) consumeCompletion",
            )
        ),
        "CreatedDocument.kt": (
            "fun deleteFailedCreatedDocument(context: Context, uri: Uri) "
            "ContentResolver.SCHEME_CONTENT DocumentsContract.deleteDocument "
            "resolver.delete(uri, null, null) class DocumentTransferCancelledException "
            "class DocumentTransferCancellation CancellationSignal() providerSignal.cancel() "
            "completionClaimed = true fun completionWasClaimed(): Boolean"
        ),
        "MainActivity.kt": " ".join(
            (
                "projectOpenPicker = rememberLauncherForActivityResult",
                "ActivityResultContracts.OpenDocument()",
                "projectSavePicker = rememberLauncherForActivityResult",
                "ActivityResultContracts.CreateDocument(PROJECT_ARCHIVE_MIME_TYPE)",
                "SupportEvent.PROJECT_ARCHIVE_IMPORT_FAILED",
                "override fun onNewIntent(intent: Intent)",
                "externalProjectModel.enqueue(intent)",
                "override fun onStop() projectTransferModel.flushPersistence()",
                "ProjectTransferViewModel projectTransferState.completion ProjectReplacementDialog(",
                "projectHistory = projectTransferState.history "
                "sliceOptions = projectTransferState.sliceOptions "
                "projectPlates = projectHistory.current.plates "
                "selectedPlateId = projectHistory.current.selectedPlateId "
                "projectRestored = projectTransferState.restored "
                "projectTransferModel.updateHistory( projectTransferModel::cancelProjectImport "
                "projectTransferModel::cancelProjectExport "
                "ProjectTransferCompletion.Canceled",
            )
        ),
        "WorkspaceScreen.kt": (
            "ProjectSheet( onOpenProject onSaveProject onPlateSelected onAddPlate "
            "onRemovePlate PlateSwitcher( confirmReplacement "
            "R.string.replace_project_title R.string.replace_project_body "
            "projectImporting: Boolean projectTransferCancellationRequested: Boolean "
            "onCancelProjectImport: () -> Unit onCancelProjectExport: () -> Unit "
            "R.string.cancel_project_import R.string.canceling_project_import "
            "R.string.cancel_project_export R.string.canceling_project_export "
            "if (exporting) onCancelProjectExport() else onSaveProject()"
        ),
        "ProjectArchiveTest.kt": (
            "projectArchiveRoundTripsModelsTransformsPaintAndResolvedProfilesDeterministically "
            "multiplePlatesAndTheirSettingsRoundTripThroughThePortableArchive "
            "invalidArchiveCannotEscapeStagingOrReplaceTheCurrentProject "
            "oversizedManifestIsRejectedBeforeProjectStateChanges "
            "startupRecoveryRemovesOnlyExactAbandonedArchiveDirectories "
            "canceledArchiveCopyRemovesStagingAndPreservesTheCurrentProject "
            "cancellationWinningTheCommitGateRemovesInstalledModelsAndPreservesCurrentProject"
        ),
        "ProjectVolumeSemanticsTest.kt": (
            "nativeRoleValuesAreStableAndComplete "
            "volumeConfigSidecarAndJsonRoundTripExactly "
            "auxiliaryVolumesRejectPrintableOnlyState "
            "projectAndArchiveObjectsRequirePrintableModelParts"
        ),
        "ProjectTransferStateTest.kt": (
            "retainedSessionMutationKeepsHistoryAndOptionsTogether "
            "staleOrBusySessionMutationIsRejected withUpdatedSession "
            "projectExportCancellationIsBoundToTheExactActiveTransfer "
            "projectImportCancellationIsBoundToTheExactActiveTransfer"
            " switchingPlatesRestoresEachPlatesIndependentSliceOptions"
        ),
        "ProjectArchiveIntentInstrumentedTest.kt": (
            "customProjectIntentSurvivesRecreationRestoresAndSlices "
            "unsavedProjectEditAndUndoSurviveImmediateActivityRecreation "
            "clearingRetainedOwnerFlushesProjectBeforeDebounce "
            "compatibleZipIntentConfirmsBeforeReplacingTheCurrentProject "
            "projectViewIntentRejectsNetworkAndUnrelatedBinaryUris "
            "Intent.ACTION_VIEW Intent.FLAG_GRANT_READ_URI_PERMISSION "
            "scenario.recreate() OnDeviceSlicer.slice("
        ),
        "CreatedDocumentLifecycleInstrumentedTest.kt": (
            "failedProjectArchiveExportDeletesTheNewDocument "
            "BlockingExportProvider.METHOD_PREPARE_FAILURE model.exportProject( "
            "BlockingExportProvider.KEY_DELETED "
            "projectExportCancellationSurvivesRecreationAndDeletesThePartialDocument "
            "projectExportCancellationInterruptsProviderOpen "
            "finalProjectOwnerClearStopsItsExportAndDeletesThePartialDocument "
            "BlockingExportProvider.METHOD_PREPARE "
            "BlockingExportProvider.METHOD_PREPARE_OPEN_BLOCK scenario.recreate() "
            "retained.cancelProjectExport() store.clear()"
        ),
        "ProjectImportLifecycleInstrumentedTest.kt": (
            "projectImportCancellationSurvivesRecreationAndPreservesTheCurrentProject "
            "projectImportCancellationInterruptsProviderOpen "
            "finalProjectOwnerClearStopsItsImportAndPreservesTheCurrentProject "
            "BlockingImportProvider.METHOD_PREPARE "
            "BlockingImportProvider.METHOD_PREPARE_OPEN_BLOCK "
            "retained.cancelProjectImport() store.clear() model.updateSession( "
            "unsavedOptions waitForStagingCleanup()"
        ),
        "BlockingExportProvider.java": (
            "openAssetFile( CancellationSignal signal "
            "signal.setOnCancelListener(target.release::countDown) signal.throwIfCanceled()"
        ),
        "BlockingImportProvider.java": (
            "openAssetFile( CancellationSignal signal "
            "signal.setOnCancelListener(target.release::countDown) signal.throwIfCanceled() "
            "ParcelFileDescriptor.createPipe() Blocking import provider is read-only"
        ),
        "AccessibilityInstrumentedTest.kt": (
            "cancelProjectImportActionIsReachable cancelProjectExportActionIsReachable "
            "plateSwitcherExposesSelectionAddAndConfirmedRemovalActions"
        ),
        "NativeEngineInstrumentedTest.kt": (
            "projectArchiveRoundTripReinspectsAndSlicesOnArm64 "
            "inspectModel( OnDeviceSlicer.slice("
        ),
        "strings.xml": string_resources(),
        "strings-ko.xml": string_resources(),
        "AndroidManifest.xml": (
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
            '<application><activity android:name=".MainActivity" '
            'android:launchMode="singleTop" '
            'android:intentMatchingFlags="enforceIntentFilter">'
            '<intent-filter><action android:name="android.intent.action.VIEW" />'
            '<category android:name="android.intent.category.DEFAULT" />'
            '<data android:mimeType="application/vnd.duckyslicer.project+zip" />'
            '<data android:scheme="content" /></intent-filter>'
            '<intent-filter><action android:name="android.intent.action.VIEW" />'
            '<category android:name="android.intent.category.DEFAULT" />'
            '<data android:mimeType="application/zip" />'
            '<data android:mimeType="application/x-zip-compressed" />'
            '<data android:mimeType="application/octet-stream" />'
            '<data android:host="*" />'
            '<data android:pathPattern=".*\\.duckyproject" />'
            '<data android:scheme="content" /></intent-filter>'
            '<intent-filter><action android:name="android.intent.action.VIEW" />'
            '<category android:name="android.intent.category.DEFAULT" />'
            '<data android:mimeType="application/vnd.duckyslicer.profiles+json" />'
            '<data android:scheme="content" /></intent-filter>'
            '<intent-filter><action android:name="android.intent.action.VIEW" />'
            '<category android:name="android.intent.category.DEFAULT" />'
            '<data android:mimeType="application/json" />'
            '<data android:mimeType="application/octet-stream" />'
            '<data android:host="*" />'
            '<data android:pathPattern=".*\\.duckyprofiles" />'
            '<data android:scheme="content" /></intent-filter>'
            "</activity></application></manifest>"
        ),
        "AndroidTestManifest.xml": (
            '<manifest><application><provider android:authorities="'
            'com.ashcastle.duckyslicer.test.blocking-import" /></application></manifest>'
        ),
        "PRIVACY.md": (
            "Exported DuckySlicer project files contain plate organization, model geometry\n"
            "support, seam, and multi-color painting, manual Brim-ear points, variable layer-height ranges,\n"
            "They do not contain G-code, saved printer addresses, or printer\n"
            "형상, 오브젝트 배치, 서포트·심·다중 색상 채색, 수동 Brim 이어 점, 가변 레이어\n"
            "프린터 접속 키는 포함되지"
        ),
        "SUPPORT.md": "`.duckyproject` model geometry include saved printer addresses, access keys, or G-code",
        "PROJECT_FORMAT.md": (
            "manifest.json models/000.stl schema version `28` "
            "Schema 1 through 28 projects remain readable up to 16 plates "
            "parameter modifier support blocker support enforcer "
            "plate-local objects and settings stable, bounded `volumes` list "
            "up to 64 volumes per object independent X, Y, and Z scale "
            "multi-color painting manual Brim-ear points variable layer-height ranges "
            "rejects duplicate, directory, traversal, and unknown entries "
            "A failed import leaves the current project unchanged and removes staged data "
            "it in Files. External opening accepts only a granted `content://` URI "
            "requires confirmation before the current project is replaced "
            "the transfer. If Android terminates the process exact generated UUID form "
            "1 GiB total uncompressed content"
        ),
        "CONTRIBUTING.md": (
            "Project history, active slicing options, restoration, and debounced persistence "
            "same Activity-retained owner process-death recovery "
            "Flush the latest dirty revision app enters the background owner is finally cleared "
            "archive import commits cancel and join any older metadata write "
            "Every `CreateDocument` writer delete it after cancellation or failure "
            "Project archive export exact provider open and ZIP write "
            "Project archive import atomic commit"
        ),
    }


class VerifyProjectArchiveTest(unittest.TestCase):
    def test_accepts_bounded_atomic_local_archive(self) -> None:
        verify_project_archive(valid_sources())

    def test_rejects_removed_uncompressed_limit(self) -> None:
        sources = valid_sources()
        sources["ProjectArchive.kt"] = sources["ProjectArchive.kt"].replace(
            "MAX_PROJECT_ARCHIVE_CONTENT_BYTES = 1_073_741_824L", ""
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_missing_duplicate_entry_check(self) -> None:
        sources = valid_sources()
        sources["ProjectArchive.kt"] = sources["ProjectArchive.kt"].replace(
            "require(entries.add(entry.name))", "entries.add(entry.name)"
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_direct_non_staged_import(self) -> None:
        sources = valid_sources()
        sources["ProjectStore.kt"] = sources["ProjectStore.kt"].replace(
            'File(projectRoot, ".archive-${UUID.randomUUID()}")', "File(projectRoot, PROJECT_FILE)"
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_broad_archive_staging_recovery(self) -> None:
        sources = valid_sources()
        sources["ProjectStore.kt"] = sources["ProjectStore.kt"].replace(
            'recoverGeneratedStaging(projectRoot, ".archive-")',
            'recoverGeneratedStaging(projectRoot, "")',
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_automatic_or_shared_export(self) -> None:
        sources = valid_sources()
        sources["MainActivity.kt"] += " ACTION_SEND"
        with self.assertRaisesRegex(VerificationError, "user-chosen"):
            verify_project_archive(sources)

    def test_rejects_missing_privacy_disclosure(self) -> None:
        sources = valid_sources()
        sources["PRIVACY.md"] = ""
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_broad_network_view_filter(self) -> None:
        sources = valid_sources()
        sources["AndroidManifest.xml"] = sources["AndroidManifest.xml"].replace(
            "</activity>",
            '<intent-filter><action android:name="android.intent.action.VIEW" />'
            '<category android:name="android.intent.category.DEFAULT" />'
            '<data android:scheme="https" /></intent-filter></activity>',
        )
        with self.assertRaisesRegex(VerificationError, "content project"):
            verify_project_archive(sources)

    def test_rejects_missing_single_top_delivery(self) -> None:
        sources = valid_sources()
        sources["AndroidManifest.xml"] = sources["AndroidManifest.xml"].replace(
            ' android:launchMode="singleTop"', ""
        )
        with self.assertRaisesRegex(VerificationError, "onNewIntent"):
            verify_project_archive(sources)

    def test_rejects_relaxed_external_intent_matching(self) -> None:
        sources = valid_sources()
        sources["AndroidManifest.xml"] = sources["AndroidManifest.xml"].replace(
            ' android:intentMatchingFlags="enforceIntentFilter"', ""
        )
        with self.assertRaisesRegex(VerificationError, "external intent allowlist"):
            verify_project_archive(sources)

    def test_rejects_activity_scoped_transfer(self) -> None:
        sources = valid_sources()
        sources["ProjectTransfer.kt"] = sources["ProjectTransfer.kt"].replace(
            "viewModelScope.launch(Dispatchers.IO)", "scope.launch(Dispatchers.IO)"
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_activity_owned_project_session(self) -> None:
        sources = valid_sources()
        sources["MainActivity.kt"] += " mutableStateOf(ProjectHistoryState())"
        with self.assertRaisesRegex(VerificationError, "session persistence"):
            verify_project_archive(sources)

    def test_rejects_partial_project_export_cleanup(self) -> None:
        sources = valid_sources()
        sources["ProjectTransfer.kt"] = sources["ProjectTransfer.kt"].replace(
            "deleteFailedCreatedDocument(application, uri)", "leave partial archive"
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_non_interruptible_project_archive_copy(self) -> None:
        sources = valid_sources()
        sources["ProjectArchive.kt"] = sources["ProjectArchive.kt"].replace(
            "copyArchiveBytes(input, archive, model.length(), checkCancellation)",
            "copyArchiveBytes(input, archive, model.length())",
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_project_export_without_provider_cancellation(self) -> None:
        sources = valid_sources()
        sources["ProjectTransfer.kt"] = sources["ProjectTransfer.kt"].replace(
            "DocumentTransferCancellation()", "non_interruptible_writer"
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_missing_project_export_cancel_action(self) -> None:
        sources = valid_sources()
        sources["WorkspaceScreen.kt"] = sources["WorkspaceScreen.kt"].replace(
            "onCancelProjectExport: () -> Unit", "no_cancel_action"
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_missing_project_export_cancellation_lifecycle_regression(self) -> None:
        sources = valid_sources()
        sources["CreatedDocumentLifecycleInstrumentedTest.kt"] = sources[
            "CreatedDocumentLifecycleInstrumentedTest.kt"
        ].replace(
            "projectExportCancellationSurvivesRecreationAndDeletesThePartialDocument",
            "missing_rotation_regression",
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_missing_provider_open_cancellation_regression(self) -> None:
        sources = valid_sources()
        sources["BlockingExportProvider.java"] = sources["BlockingExportProvider.java"].replace(
            "signal.throwIfCanceled()", "ignore_provider_cancellation"
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_missing_accessible_project_export_cancel_regression(self) -> None:
        sources = valid_sources()
        sources["AccessibilityInstrumentedTest.kt"] = "missing accessibility regression"
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_project_import_without_bound_input_cancellation(self) -> None:
        sources = valid_sources()
        sources["ProjectTransfer.kt"] = sources["ProjectTransfer.kt"].replace(
            "cancellation.attachInput(input)", "unbound_import_input"
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_project_import_through_the_read_typed_asset_shortcut(self) -> None:
        sources = valid_sources()
        sources["ProjectTransfer.kt"] = sources["ProjectTransfer.kt"].replace(
            "provider.openAssetFile(uri, \"r\", cancellation.providerSignal)",
            "resolver.openAssetFileDescriptor(uri, \"r\", cancellation.providerSignal)",
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_project_import_without_atomic_commit_gate(self) -> None:
        sources = valid_sources()
        sources["ProjectStore.kt"] = sources["ProjectStore.kt"].replace(
            "beginCommit()", "commit_without_cancellation_gate"
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_missing_project_import_cancel_action(self) -> None:
        sources = valid_sources()
        sources["WorkspaceScreen.kt"] = sources["WorkspaceScreen.kt"].replace(
            "onCancelProjectImport: () -> Unit", "no_import_cancel_action"
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_missing_project_import_lifecycle_regression(self) -> None:
        sources = valid_sources()
        sources["ProjectImportLifecycleInstrumentedTest.kt"] = sources[
            "ProjectImportLifecycleInstrumentedTest.kt"
        ].replace(
            "projectImportCancellationSurvivesRecreationAndPreservesTheCurrentProject",
            "missing_import_rotation_regression",
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_final_owner_import_cleanup_that_can_overwrite_a_committed_import(self) -> None:
        sources = valid_sources()
        sources["ProjectTransfer.kt"] = sources["ProjectTransfer.kt"].replace(
            "completionWasClaimed()", "always_restore_the_old_project"
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_missing_accessible_project_import_cancel_regression(self) -> None:
        sources = valid_sources()
        sources["AccessibilityInstrumentedTest.kt"] = sources[
            "AccessibilityInstrumentedTest.kt"
        ].replace("cancelProjectImportActionIsReachable", "missing_import_accessibility")
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_import_without_joining_older_persistence(self) -> None:
        sources = valid_sources()
        sources["ProjectTransfer.kt"] = sources["ProjectTransfer.kt"].replace(
            "pendingPersistence?.join()", "start archive import immediately"
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_background_transition_without_project_flush(self) -> None:
        sources = valid_sources()
        sources["MainActivity.kt"] = sources["MainActivity.kt"].replace(
            "projectTransferModel.flushPersistence()", "leave debounce pending"
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)


if __name__ == "__main__":
    unittest.main()
