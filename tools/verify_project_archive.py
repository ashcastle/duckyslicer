#!/usr/bin/env python3
"""Keep portable projects bounded, atomic, offline, and free of printer secrets."""

from __future__ import annotations

import xml.etree.ElementTree as ElementTree
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
ANDROID_NAMESPACE = "{http://schemas.android.com/apk/res/android}"
COMPATIBLE_MIME_TYPES = {
    "application/zip",
    "application/x-zip-compressed",
    "application/octet-stream",
}
PROFILE_COMPATIBLE_MIME_TYPES = {
    "application/json",
    "application/octet-stream",
}
REQUIRED_STRINGS = {
    "cancel_project_import",
    "canceling_project_import",
    "cancel_project_export",
    "canceling_project_export",
    "open_project",
    "save_project",
    "replace_project_title",
    "replace_project_body",
    "project_opened",
    "project_saved",
    "project_open_error",
    "project_import_canceled",
    "project_export_error",
    "project_export_canceled",
    "plate_number",
    "add_plate",
    "remove_plate",
    "remove_plate_title",
    "remove_plate_message",
}


class VerificationError(ValueError):
    pass


def _require_markers(name: str, source: str, markers: tuple[str, ...]) -> None:
    missing = [marker for marker in markers if marker not in source]
    if missing:
        raise VerificationError(f"{name} is missing project-archive safeguards: {missing}")


def _strings(name: str, source: str) -> dict[str, str]:
    try:
        root = ElementTree.fromstring(source)
    except ElementTree.ParseError as error:
        raise VerificationError(f"{name} is not valid XML: {error}") from error
    return {
        element.attrib["name"]: "".join(element.itertext()).strip()
        for element in root.findall("string")
        if "name" in element.attrib
    }


def verify_project_archive(sources: dict[str, str]) -> None:
    required_files = {
        "ProjectArchive.kt",
        "ProjectStore.kt",
        "ProjectOpenRequest.kt",
        "ProjectTransfer.kt",
        "CreatedDocument.kt",
        "MainActivity.kt",
        "WorkspaceScreen.kt",
        "AndroidManifest.xml",
        "AndroidTestManifest.xml",
        "ProjectArchiveTest.kt",
        "ProjectTransferStateTest.kt",
        "ProjectArchiveIntentInstrumentedTest.kt",
        "CreatedDocumentLifecycleInstrumentedTest.kt",
        "ProjectImportLifecycleInstrumentedTest.kt",
        "BlockingExportProvider.java",
        "BlockingImportProvider.java",
        "AccessibilityInstrumentedTest.kt",
        "NativeEngineInstrumentedTest.kt",
        "strings.xml",
        "strings-ko.xml",
        "PRIVACY.md",
        "SUPPORT.md",
        "PROJECT_FORMAT.md",
        "CONTRIBUTING.md",
    }
    missing_files = sorted(required_files - sources.keys())
    if missing_files:
        raise VerificationError(f"project-archive sources are missing: {missing_files}")

    archive = sources["ProjectArchive.kt"]
    _require_markers(
        "ProjectArchive.kt",
        archive,
        (
            'PROJECT_ARCHIVE_MIME_TYPE = "application/vnd.duckyslicer.project+zip"',
            'PROJECT_ARCHIVE_FILE_EXTENSION = ".duckyproject"',
            "MAX_PROJECT_ARCHIVE_MANIFEST_BYTES = 1_048_576",
            "MAX_PROJECT_ARCHIVE_CONTENT_BYTES = 1_073_741_824L",
            "MAX_PROJECT_ARCHIVE_FILE_BYTES = 1_082_130_432L",
            "MAX_PROJECT_ARCHIVE_ENTRIES = ProjectStore.MAX_PROJECT_VOLUMES + 1",
            'PROJECT_ARCHIVE_FORMAT = "com.ashcastle.duckyslicer.project"',
            "MIN_PROJECT_ARCHIVE_SCHEMA_VERSION = 1",
            "PROJECT_ARCHIVE_SCHEMA_VERSION = 13",
            "ArchivedProjectPlate",
            "ArchivedProjectVolume",
            'getJSONArray("plates")',
            "selectedPlateId",
            "plateOptions: Map<String, SliceOptions>",
            'getJSONArray("volumes")',
            "legacyProjectVolumeId",
            'Regex("models/[0-9]{3}\\\\.stl")',
            "require(!entry.isDirectory",
            "require(entries.add(entry.name))",
            "entry.method == ZipEntry.DEFLATED || entry.method == ZipEntry.STORED",
            "readArchiveBytes(",
            "MAX_MODEL_IMPORT_BYTES",
            "checkedArchiveTotal",
            "require(referencedEntries == models.keys)",
            "output.fd.sync()",
            "parseBoundedJsonObject",
            "require(info.triangles > 0)",
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
        ),
    )

    store = sources["ProjectStore.kt"]
    _require_markers(
        "ProjectStore.kt",
        store,
        (
            'File(projectRoot, ".archive-${UUID.randomUUID()}")',
            "ProjectArchiveCodec.read(",
            "moveArchiveModel(stagedModel.file, destination)",
            "val plateOptions = decoded.plates.associate",
            "save(snapshot, plateOptions)",
            "pruneUnreferencedModels(snapshot)",
            "installed.forEach(File::delete)",
            "staging.deleteRecursively()",
            "modelFile.parentFile == modelRoot && modelFile.isFile",
            "StandardCopyOption.ATOMIC_MOVE",
            "recoverAbandonedArchiveStaging",
            'recoverGeneratedStaging(projectRoot, ".archive-")',
            "private fun recoverGeneratedStaging",
            "removePrefix(prefix)",
            "UUID.fromString(identifier)",
            "!Files.isSymbolicLink(candidate.toPath())",
            "checkCancellation: () -> Unit = {}",
            "ProjectArchiveCodec.write(snapshot, plateOptions, output, checkCancellation)",
            "beginCommit: () -> Unit = {}",
            "beginCommit()",
        ),
    )
    if store.index("save(snapshot, plateOptions)") > store.index(
        "pruneUnreferencedModels(snapshot)"
    ):
        raise VerificationError("ProjectStore.kt must commit imported metadata before pruning")

    _require_markers(
        "ProjectOpenRequest.kt",
        sources["ProjectOpenRequest.kt"],
        (
            "intent.action != Intent.ACTION_VIEW",
            "ContentResolver.SCHEME_CONTENT",
            "PROJECT_ARCHIVE_MIME_TYPE",
            "PROJECT_ARCHIVE_FILE_EXTENSION",
            "PROJECT_ARCHIVE_COMPATIBLE_MIME_TYPES",
            '"application/zip"',
            '"application/x-zip-compressed"',
            '"application/octet-stream"',
            "SavedStateHandle",
            "StateFlow<ExternalProjectRequest?>",
        ),
    )

    transfer = sources["ProjectTransfer.kt"]
    _require_markers(
        "ProjectTransfer.kt",
        transfer,
        (
            "AndroidViewModel(application)",
            "viewModelScope.launch(Dispatchers.IO)",
            "ProjectStore.recoverAbandonedArchiveStaging",
            "ProjectTransferState(busy = true)",
            "val history: ProjectHistoryState",
            "val sliceOptions: SliceOptions",
            "val plateOptions: Map<String, SliceOptions>",
            "val restored: Boolean",
            "val sessionRevision: Long",
            "val persistedRevision: Long",
            "val activeTransferId: Long?",
            "val activeTransferDirection: ProjectTransferDirection?",
            "val transferCancellationRequested: Boolean",
            "fun ProjectTransferState.withStartedTransfer(",
            "fun ProjectTransferState.withTransferCancellationRequested(",
            "fun ProjectTransferState.withCompletedTransfer(",
            "fun updateHistory(",
            "fun updateSession(",
            "projectStore.loadProject()",
            "projectStore.save(document.history.current, document.plateOptions)",
            "PROJECT_SAVE_DEBOUNCE_MILLIS = 400L",
            "pendingPersistence?.join()",
            "fun flushPersistence()",
            "override fun onCleared()",
            "hasPersistableChanges",
            "completion != null",
            "projectStore.importArchive",
            "uri.scheme != ContentResolver.SCHEME_CONTENT",
            "DocumentTransferCancellation()",
            "openAssetFileDescriptor(",
            "acquireContentProviderClient(uri)",
            'provider.openAssetFile(uri, "r", cancellation.providerSignal)',
            '"wt",',
            "cancellation.providerSignal",
            "cancellation.attachInput(input)",
            "cancellation::complete",
            "projectStore.exportArchive",
            "cancellation::throwIfRequested",
            "DocumentTransferCancelledException",
            "ProjectTransferCompletion.Canceled",
            "fun cancelProjectExport(): Boolean",
            "fun cancelProjectImport(): Boolean",
            "activeProjectDocumentTransfer?.operation == operation",
            "FinalProjectOwnerCleanup(",
            "activeProjectDocumentTransfer,",
            "pending,",
            "cleanup.transfer?.cancellation?.cancel()",
            "completionWasClaimed()",
            "hasUnpersistedSession()",
            "hasPersistableChanges(allowActiveTransfer = true)",
            "deleteFailedCreatedDocument(application, uri)",
            "SupportEvent.PROJECT_ARCHIVE_EXPORT_FAILED",
            "catch (failure: CancellationException)",
            "consumeCompletion",
        ),
    )
    if "catch (_: Throwable)" in transfer or "catch (failure: Throwable)" in transfer:
        raise VerificationError("ProjectTransfer.kt must not swallow process-level failures")

    _require_markers(
        "CreatedDocument.kt",
        sources["CreatedDocument.kt"],
        (
            "fun deleteFailedCreatedDocument(context: Context, uri: Uri)",
            "ContentResolver.SCHEME_CONTENT",
            "DocumentsContract.deleteDocument",
            "resolver.delete(uri, null, null)",
            "class DocumentTransferCancelledException",
            "class DocumentTransferCancellation",
            "CancellationSignal()",
            "providerSignal.cancel()",
            "completionClaimed = true",
            "fun completionWasClaimed(): Boolean",
        ),
    )

    try:
        manifest = ElementTree.fromstring(sources["AndroidManifest.xml"])
    except ElementTree.ParseError as error:
        raise VerificationError(f"AndroidManifest.xml is not valid XML: {error}") from error
    main_activity = next(
        (
            activity
            for activity in manifest.findall("./application/activity")
            if activity.attrib.get(f"{ANDROID_NAMESPACE}name") == ".MainActivity"
        ),
        None,
    )
    if main_activity is None:
        raise VerificationError("AndroidManifest.xml does not declare MainActivity")
    if main_activity.attrib.get(f"{ANDROID_NAMESPACE}launchMode") != "singleTop":
        raise VerificationError("MainActivity must receive a second project through onNewIntent")
    if main_activity.attrib.get(f"{ANDROID_NAMESPACE}intentMatchingFlags") != "enforceIntentFilter":
        raise VerificationError("MainActivity must enforce its external intent allowlist")
    view_filters: list[tuple[set[str], set[str], set[str], set[str], set[str]]] = []
    for intent_filter in main_activity.findall("intent-filter"):
        actions = {
            value
            for action in intent_filter.findall("action")
            if (value := action.attrib.get(f"{ANDROID_NAMESPACE}name"))
        }
        if "android.intent.action.VIEW" not in actions:
            continue
        categories = {
            value
            for category in intent_filter.findall("category")
            if (value := category.attrib.get(f"{ANDROID_NAMESPACE}name"))
        }
        schemes = {
            value
            for data in intent_filter.findall("data")
            if (value := data.attrib.get(f"{ANDROID_NAMESPACE}scheme"))
        }
        mime_types = {
            value
            for data in intent_filter.findall("data")
            if (value := data.attrib.get(f"{ANDROID_NAMESPACE}mimeType"))
        }
        hosts = {
            value
            for data in intent_filter.findall("data")
            if (value := data.attrib.get(f"{ANDROID_NAMESPACE}host"))
        }
        paths = {
            value
            for data in intent_filter.findall("data")
            if (value := data.attrib.get(f"{ANDROID_NAMESPACE}pathPattern"))
        }
        view_filters.append((categories, schemes, mime_types, hosts, paths))
    expected_category = {"android.intent.category.DEFAULT"}
    custom_filter = (
        expected_category,
        {"content"},
        {"application/vnd.duckyslicer.project+zip"},
        set(),
        set(),
    )
    compatible_filter = (
        expected_category,
        {"content"},
        COMPATIBLE_MIME_TYPES,
        {"*"},
        {r".*\.duckyproject"},
    )
    profile_custom_filter = (
        expected_category,
        {"content"},
        {"application/vnd.duckyslicer.profiles+json"},
        set(),
        set(),
    )
    profile_compatible_filter = (
        expected_category,
        {"content"},
        PROFILE_COMPATIBLE_MIME_TYPES,
        {"*"},
        {r".*\.duckyprofiles"},
    )
    if (
        len(view_filters) != 4
        or custom_filter not in view_filters
        or compatible_filter not in view_filters
        or profile_custom_filter not in view_filters
        or profile_compatible_filter not in view_filters
    ):
        raise VerificationError(
            "AndroidManifest.xml must expose only content project/profile MIME and extension VIEW filters"
        )

    main = sources["MainActivity.kt"]
    _require_markers(
        "MainActivity.kt",
        main,
        (
            "projectOpenPicker = rememberLauncherForActivityResult",
            "ActivityResultContracts.OpenDocument()",
            "projectSavePicker = rememberLauncherForActivityResult",
            "ActivityResultContracts.CreateDocument(PROJECT_ARCHIVE_MIME_TYPE)",
            "SupportEvent.PROJECT_ARCHIVE_IMPORT_FAILED",
            "override fun onNewIntent(intent: Intent)",
            "externalProjectModel.enqueue(intent)",
            "override fun onStop()",
            "projectTransferModel.flushPersistence()",
            "ProjectTransferViewModel",
            "projectTransferState.completion",
            "projectHistory = projectTransferState.history",
            "sliceOptions = projectTransferState.sliceOptions",
            "projectPlates = projectHistory.current.plates",
            "selectedPlateId = projectHistory.current.selectedPlateId",
            "projectRestored = projectTransferState.restored",
            "projectTransferModel.updateHistory(",
            "projectTransferModel::cancelProjectImport",
            "projectTransferModel::cancelProjectExport",
            "ProjectTransferCompletion.Canceled",
            "ProjectReplacementDialog(",
        ),
    )
    for forbidden in (
        "mutableStateOf(ProjectHistoryState())",
        "mutableStateOf(SliceOptions())",
        "projectStore.loadProject()",
        "projectStore.save(projectHistory.current",
    ):
        if forbidden in main:
            raise VerificationError(
                "MainActivity.kt still owns project session persistence: " + forbidden
            )
    if "ACTION_SEND" in main or "HttpURLConnection" in main:
        raise VerificationError("project archives must use user-chosen local documents, not sharing or network upload")

    _require_markers(
        "WorkspaceScreen.kt",
        sources["WorkspaceScreen.kt"],
        (
            "ProjectSheet(",
            "onOpenProject",
            "onSaveProject",
            "onPlateSelected",
            "onAddPlate",
            "onRemovePlate",
            "PlateSwitcher(",
            "confirmReplacement",
            "R.string.replace_project_title",
            "R.string.replace_project_body",
            "projectImporting: Boolean",
            "projectTransferCancellationRequested: Boolean",
            "onCancelProjectImport: () -> Unit",
            "onCancelProjectExport: () -> Unit",
            "R.string.cancel_project_import",
            "R.string.canceling_project_import",
            "R.string.cancel_project_export",
            "R.string.canceling_project_export",
            "if (exporting) onCancelProjectExport() else onSaveProject()",
        ),
    )

    for source_name in ("strings.xml", "strings-ko.xml"):
        values = _strings(source_name, sources[source_name])
        missing = sorted(REQUIRED_STRINGS - values.keys())
        blank = sorted(name for name in REQUIRED_STRINGS if not values.get(name, ""))
        if missing or blank:
            raise VerificationError(
                f"{source_name} has incomplete project actions: missing={missing}, blank={blank}"
            )

    _require_markers(
        "PRIVACY.md",
        sources["PRIVACY.md"],
        (
            "Exported DuckySlicer project files contain plate organization, model geometry",
            "support, seam, and multi-color painting, manual Brim-ear points, variable layer-height ranges,",
            "They do not contain G-code, saved printer addresses, or printer",
            "형상, 오브젝트 배치, 서포트·심·다중 색상 채색, 수동 Brim 이어 점, 가변 레이어",
            "프린터 접속 키는 포함되지",
        ),
    )
    _require_markers(
        "SUPPORT.md",
        sources["SUPPORT.md"],
        ("`.duckyproject`", "model geometry", "include saved printer addresses, access keys, or G-code"),
    )
    _require_markers(
        "PROJECT_FORMAT.md",
        sources["PROJECT_FORMAT.md"],
        (
            "manifest.json",
            "models/000.stl",
            "schema version `13`",
            "Schema 1 through 11 projects remain readable",
            "up to 16 plates",
            "plate-local objects and settings",
            "stable, bounded `volumes` list",
            "up to 64 volumes per object",
            "independent X, Y, and Z scale",
            "multi-color painting",
            "manual Brim-ear points",
            "variable layer-height",
            "rejects duplicate, directory, traversal, and unknown entries",
            "A failed import leaves the",
            "current project unchanged and removes staged data",
            "it in Files. External opening",
            "accepts only a granted `content://` URI",
            "requires confirmation before the current project is replaced",
            "the transfer. If Android terminates the process",
            "exact generated UUID form",
            "1 GiB total uncompressed content",
        ),
    )
    _require_markers(
        "ProjectArchiveTest.kt",
        sources["ProjectArchiveTest.kt"],
        (
            "projectArchiveRoundTripsModelsTransformsPaintAndResolvedProfilesDeterministically",
            "multiplePlatesAndTheirSettingsRoundTripThroughThePortableArchive",
            "invalidArchiveCannotEscapeStagingOrReplaceTheCurrentProject",
            "oversizedManifestIsRejectedBeforeProjectStateChanges",
            "startupRecoveryRemovesOnlyExactAbandonedArchiveDirectories",
            "canceledArchiveCopyRemovesStagingAndPreservesTheCurrentProject",
            "cancellationWinningTheCommitGateRemovesInstalledModelsAndPreservesCurrentProject",
        ),
    )
    _require_markers(
        "ProjectTransferStateTest.kt",
        sources["ProjectTransferStateTest.kt"],
        (
            "retainedSessionMutationKeepsHistoryAndOptionsTogether",
            "staleOrBusySessionMutationIsRejected",
            "withUpdatedSession",
            "projectExportCancellationIsBoundToTheExactActiveTransfer",
            "projectImportCancellationIsBoundToTheExactActiveTransfer",
            "switchingPlatesRestoresEachPlatesIndependentSliceOptions",
        ),
    )
    _require_markers(
        "ProjectArchiveIntentInstrumentedTest.kt",
        sources["ProjectArchiveIntentInstrumentedTest.kt"],
        (
            "customProjectIntentSurvivesRecreationRestoresAndSlices",
            "unsavedProjectEditAndUndoSurviveImmediateActivityRecreation",
            "clearingRetainedOwnerFlushesProjectBeforeDebounce",
            "compatibleZipIntentConfirmsBeforeReplacingTheCurrentProject",
            "projectViewIntentRejectsNetworkAndUnrelatedBinaryUris",
            "Intent.ACTION_VIEW",
            "Intent.FLAG_GRANT_READ_URI_PERMISSION",
            "scenario.recreate()",
            "OnDeviceSlicer.slice(",
        ),
    )
    _require_markers(
        "ProjectImportLifecycleInstrumentedTest.kt",
        sources["ProjectImportLifecycleInstrumentedTest.kt"],
        (
            "projectImportCancellationSurvivesRecreationAndPreservesTheCurrentProject",
            "projectImportCancellationInterruptsProviderOpen",
            "finalProjectOwnerClearStopsItsImportAndPreservesTheCurrentProject",
            "BlockingImportProvider.METHOD_PREPARE",
            "BlockingImportProvider.METHOD_PREPARE_OPEN_BLOCK",
            "retained.cancelProjectImport()",
            "store.clear()",
            "model.updateSession(",
            "unsavedOptions",
            "waitForStagingCleanup()",
        ),
    )
    _require_markers(
        "BlockingImportProvider.java",
        sources["BlockingImportProvider.java"],
        (
            "openAssetFile(",
            "CancellationSignal signal",
            "signal.setOnCancelListener(target.release::countDown)",
            "signal.throwIfCanceled()",
            "ParcelFileDescriptor.createPipe()",
            "Blocking import provider is read-only",
        ),
    )
    if "com.ashcastle.duckyslicer.test.blocking-import" not in sources["AndroidTestManifest.xml"]:
        raise VerificationError("AndroidTestManifest.xml is missing the blocking import provider")
    _require_markers(
        "CreatedDocumentLifecycleInstrumentedTest.kt",
        sources["CreatedDocumentLifecycleInstrumentedTest.kt"],
        (
            "failedProjectArchiveExportDeletesTheNewDocument",
            "BlockingExportProvider.METHOD_PREPARE_FAILURE",
            "model.exportProject(",
            "BlockingExportProvider.KEY_DELETED",
            "projectExportCancellationSurvivesRecreationAndDeletesThePartialDocument",
            "projectExportCancellationInterruptsProviderOpen",
            "finalProjectOwnerClearStopsItsExportAndDeletesThePartialDocument",
            "BlockingExportProvider.METHOD_PREPARE",
            "BlockingExportProvider.METHOD_PREPARE_OPEN_BLOCK",
            "scenario.recreate()",
            "retained.cancelProjectExport()",
            "store.clear()",
        ),
    )
    _require_markers(
        "BlockingExportProvider.java",
        sources["BlockingExportProvider.java"],
        (
            "openAssetFile(",
            "CancellationSignal signal",
            "signal.setOnCancelListener(target.release::countDown)",
            "signal.throwIfCanceled()",
        ),
    )
    _require_markers(
        "AccessibilityInstrumentedTest.kt",
        sources["AccessibilityInstrumentedTest.kt"],
        (
            "cancelProjectImportActionIsReachable",
            "cancelProjectExportActionIsReachable",
            "plateSwitcherExposesSelectionAddAndConfirmedRemovalActions",
        ),
    )
    _require_markers(
        "CONTRIBUTING.md",
        sources["CONTRIBUTING.md"],
        (
            "Project history, active slicing options, restoration, and debounced persistence",
            "same Activity-retained owner",
            "process-death recovery",
            "Every `CreateDocument` writer",
            "delete it after cancellation or failure",
            "Project archive export",
            "exact provider open and ZIP write",
            "Project archive import",
            "atomic commit",
        ),
    )
    _require_markers(
        "NativeEngineInstrumentedTest.kt",
        sources["NativeEngineInstrumentedTest.kt"],
        (
            "projectArchiveRoundTripReinspectsAndSlicesOnArm64",
            "inspectModel(",
            "OnDeviceSlicer.slice(",
        ),
    )


def read_sources() -> dict[str, str]:
    package = ROOT / "android/app/src/main/java/com/ashcastle/duckyslicer"
    tests = ROOT / "android/app/src"
    return {
        "ProjectArchive.kt": (package / "ProjectArchive.kt").read_text(encoding="utf-8"),
        "ProjectStore.kt": (package / "ProjectStore.kt").read_text(encoding="utf-8"),
        "ProjectOpenRequest.kt": (package / "ProjectOpenRequest.kt").read_text(encoding="utf-8"),
        "ProjectTransfer.kt": (package / "ProjectTransfer.kt").read_text(encoding="utf-8"),
        "CreatedDocument.kt": (package / "CreatedDocument.kt").read_text(encoding="utf-8"),
        "MainActivity.kt": (package / "MainActivity.kt").read_text(encoding="utf-8"),
        "WorkspaceScreen.kt": (package / "WorkspaceScreen.kt").read_text(encoding="utf-8"),
        "AndroidManifest.xml": (tests / "main/AndroidManifest.xml").read_text(encoding="utf-8"),
        "AndroidTestManifest.xml": (tests / "androidTest/AndroidManifest.xml").read_text(
            encoding="utf-8"
        ),
        "ProjectArchiveTest.kt": (
            tests / "test/java/com/ashcastle/duckyslicer/ProjectArchiveTest.kt"
        ).read_text(encoding="utf-8"),
        "ProjectTransferStateTest.kt": (
            tests / "test/java/com/ashcastle/duckyslicer/ProjectTransferStateTest.kt"
        ).read_text(encoding="utf-8"),
        "ProjectArchiveIntentInstrumentedTest.kt": (
            tests
            / "androidTest/java/com/ashcastle/duckyslicer/ProjectArchiveIntentInstrumentedTest.kt"
        ).read_text(encoding="utf-8"),
        "CreatedDocumentLifecycleInstrumentedTest.kt": (
            tests
            / "androidTest/java/com/ashcastle/duckyslicer/CreatedDocumentLifecycleInstrumentedTest.kt"
        ).read_text(encoding="utf-8"),
        "ProjectImportLifecycleInstrumentedTest.kt": (
            tests
            / "androidTest/java/com/ashcastle/duckyslicer/ProjectImportLifecycleInstrumentedTest.kt"
        ).read_text(encoding="utf-8"),
        "BlockingExportProvider.java": (
            tests / "androidTest/java/com/ashcastle/duckyslicer/BlockingExportProvider.java"
        ).read_text(encoding="utf-8"),
        "BlockingImportProvider.java": (
            tests / "androidTest/java/com/ashcastle/duckyslicer/BlockingImportProvider.java"
        ).read_text(encoding="utf-8"),
        "AccessibilityInstrumentedTest.kt": (
            tests / "androidTest/java/com/ashcastle/duckyslicer/AccessibilityInstrumentedTest.kt"
        ).read_text(encoding="utf-8"),
        "NativeEngineInstrumentedTest.kt": (
            tests / "androidTest/java/com/ashcastle/duckyslicer/NativeEngineInstrumentedTest.kt"
        ).read_text(encoding="utf-8"),
        "strings.xml": (tests / "main/res/values/strings.xml").read_text(encoding="utf-8"),
        "strings-ko.xml": (tests / "main/res/values-ko/strings.xml").read_text(encoding="utf-8"),
        "PRIVACY.md": (ROOT / "PRIVACY.md").read_text(encoding="utf-8"),
        "SUPPORT.md": (ROOT / "SUPPORT.md").read_text(encoding="utf-8"),
        "PROJECT_FORMAT.md": (ROOT / "docs/PROJECT_FORMAT.md").read_text(encoding="utf-8"),
        "CONTRIBUTING.md": (ROOT / "CONTRIBUTING.md").read_text(encoding="utf-8"),
    }


def main() -> None:
    try:
        verify_project_archive(read_sources())
    except (OSError, VerificationError) as error:
        raise SystemExit(f"Project-archive verification failed: {error}") from error
    print("Verified bounded atomic offline DuckySlicer project archives")


if __name__ == "__main__":
    main()
