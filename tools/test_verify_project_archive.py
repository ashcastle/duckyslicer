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
                "MAX_PROJECT_ARCHIVE_ENTRIES = ProjectStore.MAX_PROJECT_OBJECTS + 1",
                'PROJECT_ARCHIVE_FORMAT = "com.ashcastle.duckyslicer.project"',
                "MIN_PROJECT_ARCHIVE_SCHEMA_VERSION = 1",
                "PROJECT_ARCHIVE_SCHEMA_VERSION = 6",
                'Regex("models/[0-9]{3}\\\\.stl")',
                "require(!entry.isDirectory require(entries.add(entry.name))",
                "entry.method == ZipEntry.DEFLATED || entry.method == ZipEntry.STORED",
                "readArchiveBytes(archive, MAX_PROJECT_ARCHIVE_MANIFEST_BYTES)",
                "MAX_MODEL_IMPORT_BYTES checkedArchiveTotal",
                "require(referencedEntries == models.keys)",
                "output.fd.sync() parseBoundedJsonObject require(info.triangles > 0)",
                "supportPaint.facets.keys.all",
                "requireAxisScales = schemaVersion >= 6",
                "seamPaint.facets.keys.all",
                "multiColorPaint.facets.keys.all",
                'getJSONArray("multiColorPaint").toArchiveMultiColorPaint()',
                'getJSONArray("variableLayerHeights").toArchiveVariableLayerHeights()',
                'getJSONObject("processOverrides").toObjectProcessOverrides()',
            )
        ),
        "ProjectStore.kt": " ".join(
            (
                'File(projectRoot, ".archive-${UUID.randomUUID()}")',
                "ProjectArchiveCodec.read(input, staging, inspectModel)",
                "moveArchiveModel(stagedModel.file, destination)",
                "save(snapshot, decoded.sliceOptions)",
                "pruneUnreferencedModels(snapshot)",
                "installed.forEach(File::delete) staging.deleteRecursively()",
                "modelFile.parentFile == modelRoot && modelFile.isFile",
                "StandardCopyOption.ATOMIC_MOVE",
                "recoverAbandonedArchiveStaging recoverGeneratedStaging(projectRoot, \".archive-\")",
                "private fun recoverGeneratedStaging removePrefix(prefix)",
                "UUID.fromString(identifier) !Files.isSymbolicLink(candidate.toPath())",
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
                "val restored: Boolean val sessionRevision: Long",
                "fun updateHistory( fun updateSession(",
                "projectStore.loadProject()",
                "projectStore.save(document.history.current, document.sliceOptions)",
                "PROJECT_SAVE_DEBOUNCE_MILLIS = 400L",
                "mutableState.value.completion != null",
                "openInputStream(uri) projectStore.importArchive",
                "uri.scheme != ContentResolver.SCHEME_CONTENT "
                'openOutputStream(uri, "wt") projectStore.exportArchive '
                "deleteFailedCreatedDocument(application, uri) "
                "SupportEvent.PROJECT_ARCHIVE_EXPORT_FAILED",
                "catch (failure: CancellationException) consumeCompletion",
            )
        ),
        "CreatedDocument.kt": (
            "fun deleteFailedCreatedDocument(context: Context, uri: Uri) "
            "ContentResolver.SCHEME_CONTENT DocumentsContract.deleteDocument "
            "resolver.delete(uri, null, null)"
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
                "ProjectTransferViewModel projectTransferState.completion ProjectReplacementDialog(",
                "projectHistory = projectTransferState.history "
                "sliceOptions = projectTransferState.sliceOptions "
                "projectRestored = projectTransferState.restored "
                "projectTransferModel.updateHistory(",
            )
        ),
        "WorkspaceScreen.kt": (
            "ProjectSheet( onOpenProject onSaveProject confirmReplacement "
            "R.string.replace_project_title R.string.replace_project_body"
        ),
        "ProjectArchiveTest.kt": (
            "projectArchiveRoundTripsModelsTransformsPaintAndResolvedProfilesDeterministically "
            "invalidArchiveCannotEscapeStagingOrReplaceTheCurrentProject "
            "oversizedManifestIsRejectedBeforeProjectStateChanges "
            "startupRecoveryRemovesOnlyExactAbandonedArchiveDirectories"
        ),
        "ProjectTransferStateTest.kt": (
            "retainedSessionMutationKeepsHistoryAndOptionsTogether "
            "staleOrBusySessionMutationIsRejected withUpdatedSession"
        ),
        "ProjectArchiveIntentInstrumentedTest.kt": (
            "customProjectIntentSurvivesRecreationRestoresAndSlices "
            "unsavedProjectEditAndUndoSurviveImmediateActivityRecreation "
            "compatibleZipIntentConfirmsBeforeReplacingTheCurrentProject "
            "projectViewIntentRejectsNetworkAndUnrelatedBinaryUris "
            "Intent.ACTION_VIEW Intent.FLAG_GRANT_READ_URI_PERMISSION "
            "scenario.recreate() OnDeviceSlicer.slice("
        ),
        "CreatedDocumentLifecycleInstrumentedTest.kt": (
            "failedProjectArchiveExportDeletesTheNewDocument "
            "BlockingExportProvider.METHOD_PREPARE_FAILURE model.exportProject( "
            "BlockingExportProvider.KEY_DELETED"
        ),
        "NativeEngineInstrumentedTest.kt": (
            "projectArchiveRoundTripReinspectsAndSlicesOnArm64 "
            "NativeEngine.inspectStl OnDeviceSlicer.slice("
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
            "</activity></application></manifest>"
        ),
        "PRIVACY.md": (
            "Exported DuckySlicer project files contain the model geometry\n"
            "support, seam, and multi-color painting, variable layer-height ranges, and active printer, filament,\n"
            "They do not contain G-code, saved printer addresses, or printer\n"
            "형상, 오브젝트 배치, 서포트·심·다중 색상 채색, 가변 레이어 높이 구간\n"
            "프린터 접속 키는 포함되지"
        ),
        "SUPPORT.md": "`.duckyproject` model geometry include saved printer addresses, access keys, or G-code",
        "PROJECT_FORMAT.md": (
            "manifest.json models/000.stl schema version `6` "
            "Schema 1 through 5 projects remain readable independent X, Y, and Z scale "
            "multi-color painting variable layer-height ranges "
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
            "Every `CreateDocument` writer delete it after cancellation or failure"
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


if __name__ == "__main__":
    unittest.main()
