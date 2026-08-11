#!/usr/bin/env python3
"""Enforce durable local state and bounded LAN-printer input contracts."""

from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent


class VerificationError(ValueError):
    pass


def verify_resilience(sources: dict[str, str]) -> None:
    required_files = {
        "BoundedJson.kt",
        "DurableJsonFile.kt",
        "ProjectStore.kt",
        "ProjectTransfer.kt",
        "ModelImport.kt",
        "SlicerProcessService.kt",
        "ProfileStore.kt",
        "ProfileLibraryViewModel.kt",
        "AppSettings.kt",
        "AppSettingsViewModel.kt",
        "RemoteDevice.kt",
        "RemoteOperationViewModel.kt",
        "MainActivity.kt",
        "WorkspaceScreen.kt",
        "DeviceSheet.kt",
        "DurableJsonFileTest.kt",
        "ProjectStoreTest.kt",
        "ModelImportTest.kt",
        "ProfileStoreMigrationTest.kt",
        "ProfileLibraryViewModelTest.kt",
        "AppSettingsViewModelTest.kt",
        "RemoteDeviceClientTest.kt",
        "RemoteOperationViewModelTest.kt",
        "RemoteDeviceStoreTest.kt",
        "RemoteDeviceInstrumentedTest.kt",
        "ProfileLibraryInstrumentedTest.kt",
        "AppSettingsLifecycleInstrumentedTest.kt",
        "ProjectArchiveIntentInstrumentedTest.kt",
        "ProjectEditCancellationInstrumentedTest.kt",
        "CONTRIBUTING.md",
        "SECURITY.md",
        "strings.xml",
        "strings-ko.xml",
    }
    missing = sorted(required_files - sources.keys())
    if missing:
        raise VerificationError(f"runtime resilience sources are missing: {missing}")

    bounded = sources["BoundedJson.kt"]
    for marker in ("CodingErrorAction.REPORT", "maximumBytes", "maximumDepth"):
        if marker not in bounded:
            raise VerificationError(f"bounded JSON contract is missing: {marker}")

    durable = sources["DurableJsonFile.kt"]
    for marker in (
        "RECOVERED_BACKUP",
        "INCOMPATIBLE",
        "UNREADABLE",
        "StandardCopyOption.ATOMIC_MOVE",
        "output.fd.sync()",
        "saved_data_unreadable",
        "parseBoundedJsonObject",
    ):
        if marker not in durable:
            raise VerificationError(f"durable JSON contract is missing: {marker}")

    for store in ("ProjectStore.kt", "ProfileStore.kt", "RemoteDevice.kt"):
        if "DurableJsonFile(" not in sources[store]:
            raise VerificationError(f"{store} does not use durable JSON storage")

    project = sources["ProjectStore.kt"]
    if "storageUnavailable" not in project or "validateProjectRoot" not in project:
        raise VerificationError("project corruption is not surfaced and blocked")
    project_session = sources["ProjectTransfer.kt"]
    for marker in (
        "val persistenceBlocked: Boolean",
        "val persistedRevision: Long",
        "val activeTransferDirection: ProjectTransferDirection?",
        "restored.storageUnavailable",
        "!current.persistenceBlocked",
        "projectStore.save(document.history.current, document.sliceOptions)",
        "pendingPersistence?.join()",
        "fun flushPersistence()",
        "override fun onCleared()",
        "hasPersistableChanges",
        "fun autoLaySelectedModel()",
        "fun arrangeProjectObjects()",
        "fun splitSelectedModel()",
        "fun cutSelectedModel(heightRatio: Float, placeOnCut: Boolean)",
        "fun createPrimitive(",
        "fun importModels(uri: Uri)",
        "startEditLocked",
        "withCompletedEdit",
        "deleteInstalledModels",
        "val requestId: String",
        "val cancellationRequested: Boolean",
        "ProjectEditFailure.CANCELED",
        "fun cancelActiveEdit()",
        "SlicerProcessClient.cancelProjectRequestAsync(operation.requestId)",
        "SlicerProcessClient.releaseProjectRequest(baseline.operation.requestId)",
        "activeRequestId?.let(SlicerProcessClient::cancelProjectRequestAsync)",
    ):
        if marker not in project_session:
            raise VerificationError(f"project autosave corruption guard is missing: {marker}")
    main = sources["MainActivity.kt"]
    if "saved_data_unavailable" not in main:
        raise VerificationError("project autosave corruption warning is missing")
    for marker in (
        "override fun onStop()",
        "projectTransferModel.flushPersistence()",
    ):
        if marker not in main:
            raise VerificationError(
                f"project persistence lifecycle contract is missing: {marker}"
            )
    for marker in (
        "projectTransferModel.autoLaySelectedModel()",
        "projectTransferModel.arrangeProjectObjects()",
        "projectTransferModel.splitSelectedModel()",
        "projectTransferModel.cutSelectedModel(heightRatio, placeOnCut)",
        "projectTransferModel.createPrimitive(primitive, sizeMm, displayName)",
        "projectTransferModel.importModels(uri)",
        "projectTransferModel::cancelActiveEdit",
    ):
        if marker not in main:
            raise VerificationError(f"retained project edit dispatch is missing: {marker}")

    model_import = sources["ModelImport.kt"]
    for marker in (
        "cancellationRequested: () -> Boolean",
        "if (cancellationRequested()) throw ProjectEditCancelledException()",
    ):
        if marker not in model_import:
            raise VerificationError(f"model import cancellation contract is missing: {marker}")

    slicer_process = sources["SlicerProcessService.kt"]
    for marker in (
        "cancelledProjectRequestIds",
        "fun cancelProjectRequestAsync(requestId: String)",
        "activeRequestId.get() != requestId",
        "projectRequestCancellationRequested(requestId)",
        "fun releaseProjectRequest(requestId: String)",
        "throw ProjectEditCancelledException()",
        "what == SlicerProcessContract.MESSAGE_CUT_MODEL",
    ):
        if marker not in slicer_process:
            raise VerificationError(f"request-scoped project cancellation is missing: {marker}")

    workspace = sources["WorkspaceScreen.kt"]
    for marker in (
        "projectEditActive: Boolean",
        "projectEditCancellationRequested: Boolean",
        "onCancelProjectEdit: () -> Unit",
        "R.string.cancel_model_edit",
        "R.string.canceling_model_edit",
    ):
        if marker not in workspace:
            raise VerificationError(f"project cancellation UI is missing: {marker}")
    for forbidden in (
        "SlicerProcessClient.autoOrient(",
        "OnDeviceSlicer.arrange(",
        "splitProjectObject(",
        "cutProjectObject(",
        "createOrcaPrimitive(",
        "importOrcaModels(",
        "ProjectStore(",
        "var autoLaying by remember",
        "SlicerProcessClient.cancelActiveSliceAsync()",
    ):
        if forbidden in main:
            raise VerificationError("project edit work is still owned by the Activity composition")

    remote = sources["RemoteDevice.kt"]
    for marker in (
        "MAX_REMOTE_RESPONSE_BYTES",
        "MAX_REMOTE_CREDENTIAL_BYTES",
        "MAX_REMOTE_GCODE_BYTES",
        "readBoundedBytes",
        "parseBoundedJsonObject",
        "instanceFollowRedirects = false",
        "resolveRemoteEndpoint",
        "addresses.all(::isPrivateOrLocalAddress)",
        "val url = endpoint.uri.toURL()",
        "url.openConnection(Proxy.NO_PROXY)",
        "endpoint.hostHeader?.let",
        "isUniqueLocalIpv6",
        "safeRemotePath",
        "connection.disconnect()",
        "class RemoteUploadCancellation",
        "connection?.disconnect()",
        "cancellation.attach(connection)",
        "cancellation.throwIfRequested()",
        "cancellation.complete()",
        "RemoteUploadCancelledException",
    ):
        if marker not in remote:
            raise VerificationError(f"remote input containment is missing: {marker}")
    if "bufferedReader()?.use { it.readText() }" in remote:
        raise VerificationError("remote response uses an unbounded text read")
    if "remoteResultBelongsToSelection" not in remote:
        raise VerificationError("remote operation results are not bound to their printer profile")

    remote_operation = sources["RemoteOperationViewModel.kt"]
    for marker in (
        "viewModelScope.launch",
        "beginRemoteOperation",
        "finishRemoteOperation",
        "activeArtifactRevision",
        "invalidateRemoteUpload",
        "withRemoteUploadProgress",
        "RemoteStatusSnapshot",
        "SupportEvent.REMOTE_COMMAND_FAILED",
        "fun saveProfile(",
        "fun deleteProfile(",
        "profilesLoaded",
        "selectedProfileId",
        "ActiveRemoteUpload",
        "activeRemoteUpload",
        "fun cancelUpload()",
        "withRemoteUploadCancellationRequested",
        "RemoteOperationOutcome.UploadCanceled",
        "finishOperation(",
        "override fun onCleared()",
        "remoteDeviceStore.load()",
        "remoteDeviceStore.save(draft)",
        "remoteDeviceStore.delete(profileId)",
    ):
        if marker not in remote_operation:
            raise VerificationError(f"remote operation lifecycle contract is missing: {marker}")
    if remote_operation.count("remoteResultBelongsToSelection") < 4:
        raise VerificationError("remote operation state can escape profile binding")

    main = sources["MainActivity.kt"]
    for marker in (
        "ViewModelProvider(this)[RemoteOperationViewModel::class.java]",
        "remoteOperationModel.state.collectAsStateWithLifecycle()",
        "selectedRemoteDeviceId = remoteOperationState.selectedProfileId",
        "remoteOperationModel.invalidateUpload()",
        "remoteOperationModel.cancelUpload()",
    ):
        if marker not in main:
            raise VerificationError(f"remote operation Activity-recreation contract is missing: {marker}")
    if "RemoteDeviceClient(" in main:
        raise VerificationError("remote network work is still owned by the Activity composition")
    if "RemoteDeviceStore(" in main or "remoteProfileBusy" in main:
        raise VerificationError("remote profile persistence is still owned by the Activity composition")

    profile_library = sources["ProfileLibraryViewModel.kt"]
    for marker in (
        "class ProfileLibraryViewModel(application: Application) : AndroidViewModel(application)",
        "viewModelScope.launch",
        "private val profileStore = ProfileStore(application)",
        "private val recentStore = ProfileRecentStore(application)",
        "fun savePrinter(",
        "fun saveFilament(",
        "fun saveSlicing(",
        "fun recordSelection(",
        "activeOperationId",
        "optionsForSession",
        "val recentsRevision: Long",
        "val persistedRecentsRevision: Long",
        "fun flushRecentPersistence()",
        "override fun onCleared()",
        "hasDirtyRecents",
        "RECENT_PROFILE_SAVE_DEBOUNCE_MILLIS",
    ):
        if marker not in profile_library:
            raise VerificationError(f"profile library lifecycle contract is missing: {marker}")
    for marker in (
        "ViewModelProvider(this)[ProfileLibraryViewModel::class.java]",
        "profileLibraryModel.state.collectAsStateWithLifecycle()",
        "completion.optionsForSession(session.sessionRevision)",
        "profileLibraryModel.recordSelection(options)",
        "profileLibraryModel.flushRecentPersistence()",
    ):
        if marker not in main:
            raise VerificationError(f"profile library Activity-recreation contract is missing: {marker}")
    if "ProfileStore(" in main or "ProfileRecentStore(" in main:
        raise VerificationError("profile library persistence is still owned by the Activity composition")

    app_settings = sources["AppSettingsViewModel.kt"]
    for marker in (
        "class AppSettingsViewModel(application: Application) : AndroidViewModel(application)",
        "viewModelScope.launch",
        "withUpdatedSettings",
        "SETTINGS_SAVE_DEBOUNCE_MILLIS",
        "current.revision != revision",
        "fun flushPersistence()",
        "override fun onCleared()",
        "SupportEvent.APP_SETTINGS_SAVE_FAILED",
    ):
        if marker not in app_settings:
            raise VerificationError(f"app-settings lifecycle contract is missing: {marker}")
    settings_store = sources["AppSettings.kt"]
    for marker in (
        "fun AppSettings.normalized()",
        "fun save(settings: AppSettings): Boolean",
        ".commit()",
    ):
        if marker not in settings_store:
            raise VerificationError(f"app-settings durable commit contract is missing: {marker}")
    for marker in (
        "ViewModelProvider(this)[AppSettingsViewModel::class.java]",
        "appSettingsModel.state.collectAsStateWithLifecycle()",
        "appSettingsModel.updateSettings(next)",
        "appSettingsModel.flushPersistence()",
    ):
        if marker not in main:
            raise VerificationError(f"app-settings Activity-recreation contract is missing: {marker}")
    if "AppSettingsStore(" in main:
        raise VerificationError("app-settings persistence is still owned by the Activity composition")

    device_sheet = sources["DeviceSheet.kt"]
    for marker in (
        "uploadActive: Boolean",
        "uploadCancellationRequested: Boolean",
        "onCancelUpload: () -> Unit",
        "R.string.cancel_upload",
        "R.string.canceling_upload",
    ):
        if marker not in device_sheet:
            raise VerificationError(f"remote upload cancellation UI is missing: {marker}")
    selection_start = device_sheet.find(".selectable(")
    selection_end = device_sheet.find("),", selection_start)
    if selection_start < 0 or selection_end < 0 or "enabled = !busy" not in device_sheet[
        selection_start:selection_end
    ]:
        raise VerificationError("printer selection remains enabled during a remote operation")

    save_start = remote.find("fun save(draft: RemoteDeviceDraft)")
    save_end = remote.find("fun delete(profileId: String)", save_start)
    if save_start < 0 or save_end < 0:
        raise VerificationError("remote credential save boundary is missing")
    save = remote[save_start:save_end]
    for marker in (
        "endpointChanged",
        "stagedCredential",
        "credentialKey = credentialKey",
        "secrets.remove(stagedCredentialKey)",
        "return load().first",
    ):
        if marker not in save:
            raise VerificationError(f"credential generation contract is missing: {marker}")
    if not (
        save.find("stagedCredential?.let")
        < save.find("write(profiles.sortedBy")
        < save.find("return load().first")
    ):
        raise VerificationError("credential and metadata generations commit out of order")
    if "REMOTE_DEVICE_SCHEMA_VERSION = 2" not in remote:
        raise VerificationError("credential generation schema is not active")

    delete_start = remote.find("fun delete(profileId: String)")
    delete_end = remote.find("fun credential(profile: RemoteDeviceProfile)", delete_start)
    if delete_start < 0 or delete_end < 0:
        raise VerificationError("remote credential deletion boundary is missing")
    delete = remote[delete_start:delete_end]
    delete_order = (
        delete.find("write(existing.filterNot"),
        delete.rfind("load()"),
        delete.rfind('check(!storageUnavailable) { "saved_data_unreadable" }'),
        delete.find("removedCredentialKey?.let(secrets::remove)"),
    )
    if any(position < 0 for position in delete_order) or not (
        delete_order[0] < delete_order[1] < delete_order[2] < delete_order[3]
    ):
        raise VerificationError("credential deletion precedes durable metadata backup")

    test_markers = {
        "DurableJsonFileTest.kt": (
            "validPrimaryCreatesBackupAndCorruptionRecoversIt",
            "unreadableGenerationsAreNeverOverwritten",
        ),
        "ProjectStoreTest.kt": ("unreadablePrimaryAndBackupBlockAutosave",),
        "ProfileStoreMigrationTest.kt": ("unreadableOrFutureProfilesAreNotOverwritten",),
        "RemoteDeviceClientTest.kt": (
            "remoteResultsOnlyBelongToTheirOriginatingSelection",
            "redirectsOversizedResponsesAndDeepJsonFailClosed",
            "unsafeServerUploadPathIsRejected",
            "cleartextDnsResultsAreValidatedAndPinnedBeforeCredentialsAreAttached",
            "cleartextHostnameRequestUsesThePinnedResolverAddress",
            "cancelingUploadDisconnectsItsSocketAndDoesNotPoisonTheNextUpload",
        ),
        "RemoteOperationViewModelTest.kt": (
            "resultsAreVisibleOnlyForTheirOriginatingProfile",
            "staleCompletionCannotFinishANewerOperation",
            "invalidatedUploadCannotBecomePrintable",
            "explicitUploadCancellationStopsProgressAndReportsOneTerminalNotice",
            "invalidatingAnActiveUploadKeepsItsCancellationSilent",
            "commandCompletionRetainsFileAndUpdatesState",
            "profileSaveSelectsTheDurableResultAndClearsOldPrinterState",
            "deletingTheSelectedProfileChoosesTheFirstRemainingProfile",
        ),
        "RemoteDeviceStoreTest.kt": (
            "credentialsUseGenerationsAndDoNotFollowAChangedEndpoint",
            "legacyProfileCredentialsMigrateWithoutEnteringPlaintextMetadata",
            "failedMetadataCommitCannotBindAStagedCredentialToTheOldProfile",
            "deletingAProfileRemovesItsExactCredentialAfterMetadataIsDurable",
            "deleteRetainsCredentialWhenBackupRefreshFailsAfterMetadataCommit",
            "orphanCleanupFailureKeepsProfilesVisibleAndRetriesLater",
        ),
        "RemoteDeviceInstrumentedTest.kt": (
            "retainedUploadCancellationStopsItsConnectionAcrossActivityRecreation",
            "remoteRefreshSurvivesActivityRecreationAndRejectsDuplicateWork",
            "remoteProfileSaveAndSelectionSurviveActivityRecreation",
            "remoteDeviceMetadataRecoversFromLastKnownGoodBackup",
            "cleartextHostnameRequestUsesOneValidatedPinnedAddress",
        ),
        "ProfileLibraryViewModelTest.kt": (
            "savedProfileAppliesOnlyToTheSessionRevisionThatStartedTheSave",
            "eachSavedProfileKindBuildsItsExpectedSelection",
        ),
        "ProfileLibraryInstrumentedTest.kt": (
            "profileSaveAndRecentSelectionSurviveImmediateActivityRecreation",
            "clearingRetainedOwnerFlushesRecentProfilesBeforeDebounce",
            "lateProfileSaveCannotReplaceNewerProjectSettings",
            "The profile save must be active before recreation",
            "The profile save must be active before the newer edit",
        ),
        "AppSettingsViewModelTest.kt": (
            "settingsUpdatesNormalizeValuesAdvanceRevisionAndClearFailure",
            "equivalentNormalizedSettingsDoNotScheduleAnotherWrite",
        ),
        "AppSettingsLifecycleInstrumentedTest.kt": (
            "latestUnsavedSettingsSurviveImmediateActivityRecreationAndPersist",
            "backgroundingFlushesLatestSettingsBeforeDebounce",
            "Recreate before the 350 ms persistence debounce can run",
            "onStop must replace the pending 350 ms write with an immediate one",
        ),
        "ProjectArchiveIntentInstrumentedTest.kt": (
            "automaticLayKeepsOneRetainedOperationAcrossActivityRecreation",
            "clearingRetainedOwnerFlushesProjectBeforeDebounce",
            "assertSame(",
            "ProjectEditKind.AUTO_LAY",
            "waitForPersistedTransform",
        ),
        "ModelImportTest.kt": (
            "cooperativeCancellationStopsBeforeAnotherImportChunkIsWritten",
        ),
        "ProjectEditCancellationInstrumentedTest.kt": (
            "retainedOwnerCancelsOnlyItsNativeEditAndKeepsTheProjectUnchanged",
            "Canceling the exact edit request must restart the isolated worker",
            "Pre-bind cancellation must be accepted",
            "Clearing the final owner did not stop its exact native edit",
            "assertEquals(baseline.history, completed.history)",
        ),
    }
    for source_name, markers in test_markers.items():
        for marker in markers:
            if marker not in sources[source_name]:
                raise VerificationError(f"resilience regression is missing: {marker}")

    for strings in ("strings.xml", "strings-ko.xml"):
        if not all(
            marker in sources[strings]
            for marker in (
                "saved_data_unavailable",
                "settings_save_error",
                "cancel_model_edit",
                "model_edit_canceled",
                "cancel_upload",
                "canceling_upload",
                "upload_canceled",
            )
        ):
            raise VerificationError(f"saved-data recovery copy is missing from {strings}")

    if "pin the connection target and bypass system proxies" not in sources["CONTRIBUTING.md"]:
        raise VerificationError("contributor guidance does not preserve cleartext DNS pinning")
    if "bind a replacement printer credential generation" not in sources["CONTRIBUTING.md"]:
        raise VerificationError("contributor guidance does not preserve credential generations")
    if "Bind every remote status, upload-progress, and command result" not in sources[
        "CONTRIBUTING.md"
    ]:
        raise VerificationError("contributor guidance does not preserve printer result binding")
    if "Remote operations and their busy state must" not in sources["CONTRIBUTING.md"]:
        raise VerificationError("contributor guidance does not preserve remote operation lifetime")
    if "must never become eligible for Start Print" not in sources["CONTRIBUTING.md"]:
        raise VerificationError("contributor guidance does not reject stale uploaded G-code")
    if "must share that same retained" not in sources["CONTRIBUTING.md"]:
        raise VerificationError("contributor guidance does not retain device-profile persistence")
    for marker in (
        "disconnecting only its request-bound connection",
        "stale cancellation must never stop a later upload or printer command",
    ):
        if marker not in sources["CONTRIBUTING.md"]:
            raise VerificationError(
                f"contributor guidance does not scope remote upload cancellation: {marker}"
            )
    if "Profile catalog loading, recent selections, and user-profile saves must share one" not in sources[
        "CONTRIBUTING.md"
    ]:
        raise VerificationError("contributor guidance does not retain profile-library persistence")
    if "only in the project session revision that" not in sources["CONTRIBUTING.md"]:
        raise VerificationError("contributor guidance does not bind late profile-save completion")
    for marker in (
        "Track recent-selection persistence revisions",
        "dirty `Recent` list",
        "app enters the background",
        "owner is finally cleared",
    ):
        if marker not in sources["CONTRIBUTING.md"]:
            raise VerificationError(
                f"contributor guidance does not preserve recent-profile durability: {marker}"
            )
    if "Live app settings and their debounced persistence must share one" not in sources[
        "CONTRIBUTING.md"
    ]:
        raise VerificationError("contributor guidance does not retain live app settings")
    for marker in (
        "Flush the latest dirty settings",
        "app enters the background",
        "owner is finally cleared",
    ):
        if marker not in sources["CONTRIBUTING.md"]:
            raise VerificationError(
                f"contributor guidance does not preserve app-settings durability: {marker}"
            )
    if "Fixed support-event writers must serialize through the process-wide journal boundary" not in sources[
        "CONTRIBUTING.md"
    ]:
        raise VerificationError("contributor guidance does not serialize support diagnostics")
    for marker in (
        "Flush the latest dirty revision",
        "app enters the background",
        "finally cleared",
        "archive import commits",
        "cancel and join",
        "older metadata write",
    ):
        if marker not in sources["CONTRIBUTING.md"]:
            raise VerificationError(
                f"contributor guidance does not preserve project persistence ordering: {marker}"
            )
    if "Model import, primitive creation, automatic lay, arrangement, split, and cut must run" not in sources[
        "CONTRIBUTING.md"
    ]:
        raise VerificationError("contributor guidance does not retain project edit operations")
    if "UI disposal must not issue a process-wide slicer cancellation" not in sources[
        "CONTRIBUTING.md"
    ]:
        raise VerificationError("contributor guidance allows UI disposal to cancel retained work")
    for marker in (
        "Foreground-slice cancellation must carry its",
        "idle or stale slice owner must never cancel a later",
    ):
        if marker not in sources["CONTRIBUTING.md"]:
            raise VerificationError(
                f"contributor guidance does not scope foreground cancellation: {marker}"
            )
    for marker in (
        "request-scoped cancellation",
        "preserve the starting project on cancellation",
        "remove every generated model that was not accepted",
        "Final retained-owner clearance",
        "ordinary Activity recreation must not",
    ):
        if marker not in sources["CONTRIBUTING.md"]:
            raise VerificationError(
                f"contributor guidance does not preserve project cancellation: {marker}"
            )
    security = sources["SECURITY.md"]
    for marker in (
        "every current DNS answer",
        "DNS rebinding",
        "bypass system proxies",
        "platform certificate verifier remains authoritative",
        "Credential updates are staged under a new generation",
        "never carried to a changed connection type or address",
    ):
        if marker not in security:
            raise VerificationError(f"security guidance is missing: {marker}")


def read_sources() -> dict[str, str]:
    main = ROOT / "android/app/src/main/java/com/ashcastle/duckyslicer"
    tests = ROOT / "android/app/src/test/java/com/ashcastle/duckyslicer"
    device_tests = ROOT / "android/app/src/androidTest/java/com/ashcastle/duckyslicer"
    return {
        "BoundedJson.kt": (main / "BoundedJson.kt").read_text(encoding="utf-8"),
        "DurableJsonFile.kt": (main / "DurableJsonFile.kt").read_text(encoding="utf-8"),
        "ProjectStore.kt": (main / "ProjectStore.kt").read_text(encoding="utf-8"),
        "ProjectTransfer.kt": (main / "ProjectTransfer.kt").read_text(encoding="utf-8"),
        "ModelImport.kt": (main / "ModelImport.kt").read_text(encoding="utf-8"),
        "SlicerProcessService.kt": (main / "SlicerProcessService.kt").read_text(
            encoding="utf-8"
        ),
        "ProfileStore.kt": (main / "ProfileStore.kt").read_text(encoding="utf-8"),
        "ProfileLibraryViewModel.kt": (main / "ProfileLibraryViewModel.kt").read_text(
            encoding="utf-8"
        ),
        "AppSettings.kt": (main / "AppSettings.kt").read_text(encoding="utf-8"),
        "AppSettingsViewModel.kt": (main / "AppSettingsViewModel.kt").read_text(
            encoding="utf-8"
        ),
        "RemoteDevice.kt": (main / "RemoteDevice.kt").read_text(encoding="utf-8"),
        "RemoteOperationViewModel.kt": (main / "RemoteOperationViewModel.kt").read_text(
            encoding="utf-8"
        ),
        "MainActivity.kt": (main / "MainActivity.kt").read_text(encoding="utf-8"),
        "WorkspaceScreen.kt": (main / "WorkspaceScreen.kt").read_text(encoding="utf-8"),
        "DeviceSheet.kt": (main / "DeviceSheet.kt").read_text(encoding="utf-8"),
        "DurableJsonFileTest.kt": (tests / "DurableJsonFileTest.kt").read_text(encoding="utf-8"),
        "ProjectStoreTest.kt": (tests / "ProjectStoreTest.kt").read_text(encoding="utf-8"),
        "ModelImportTest.kt": (tests / "ModelImportTest.kt").read_text(encoding="utf-8"),
        "ProfileStoreMigrationTest.kt": (tests / "ProfileStoreMigrationTest.kt").read_text(
            encoding="utf-8"
        ),
        "ProfileLibraryViewModelTest.kt": (
            tests / "ProfileLibraryViewModelTest.kt"
        ).read_text(encoding="utf-8"),
        "AppSettingsViewModelTest.kt": (
            tests / "AppSettingsViewModelTest.kt"
        ).read_text(encoding="utf-8"),
        "RemoteDeviceClientTest.kt": (tests / "RemoteDeviceClientTest.kt").read_text(
            encoding="utf-8"
        ),
        "RemoteOperationViewModelTest.kt": (
            tests / "RemoteOperationViewModelTest.kt"
        ).read_text(encoding="utf-8"),
        "RemoteDeviceStoreTest.kt": (tests / "RemoteDeviceStoreTest.kt").read_text(
            encoding="utf-8"
        ),
        "RemoteDeviceInstrumentedTest.kt": (
            device_tests / "RemoteDeviceInstrumentedTest.kt"
        ).read_text(encoding="utf-8"),
        "ProfileLibraryInstrumentedTest.kt": (
            device_tests / "ProfileLibraryInstrumentedTest.kt"
        ).read_text(encoding="utf-8"),
        "AppSettingsLifecycleInstrumentedTest.kt": (
            device_tests / "AppSettingsLifecycleInstrumentedTest.kt"
        ).read_text(encoding="utf-8"),
        "ProjectArchiveIntentInstrumentedTest.kt": (
            device_tests / "ProjectArchiveIntentInstrumentedTest.kt"
        ).read_text(encoding="utf-8"),
        "ProjectEditCancellationInstrumentedTest.kt": (
            device_tests / "ProjectEditCancellationInstrumentedTest.kt"
        ).read_text(encoding="utf-8"),
        "CONTRIBUTING.md": (ROOT / "CONTRIBUTING.md").read_text(encoding="utf-8"),
        "SECURITY.md": (ROOT / "SECURITY.md").read_text(encoding="utf-8"),
        "strings.xml": (ROOT / "android/app/src/main/res/values/strings.xml").read_text(
            encoding="utf-8"
        ),
        "strings-ko.xml": (
            ROOT / "android/app/src/main/res/values-ko/strings.xml"
        ).read_text(encoding="utf-8"),
    }


def main() -> None:
    try:
        verify_resilience(read_sources())
    except (OSError, VerificationError) as error:
        raise SystemExit(f"Runtime resilience verification failed: {error}") from error
    print("Verified durable project/profile/settings/device state and bounded LAN-printer inputs")


if __name__ == "__main__":
    main()
