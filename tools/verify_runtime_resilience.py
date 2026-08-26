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
        "CreatedDocument.kt",
        "ModelImport.kt",
        "OrcaModelImport.kt",
        "SlicerProcessService.kt",
        "ProfileStore.kt",
        "ProfileBundle.kt",
        "ProfileOpenRequest.kt",
        "ProfileLibraryViewModel.kt",
        "AppSettings.kt",
        "AppSettingsViewModel.kt",
        "RemoteDevice.kt",
        "RemoteOperationViewModel.kt",
        "MainActivity.kt",
        "PlateSliceBatchViewModel.kt",
        "PlateSliceBatchEffect.kt",
        "PlateSliceBatchViewModelTest.kt",
        "WorkspaceScreen.kt",
        "DeviceSheet.kt",
        "DurableJsonFileTest.kt",
        "ProjectStoreTest.kt",
        "ModelImportTest.kt",
        "ProfileStoreMigrationTest.kt",
        "ProfileBundleTest.kt",
        "ProfileLibraryViewModelTest.kt",
        "AppSettingsViewModelTest.kt",
        "RemoteDeviceClientTest.kt",
        "RemoteOperationViewModelTest.kt",
        "RemoteDeviceStoreTest.kt",
        "RemoteDeviceInstrumentedTest.kt",
        "AccessibilityInstrumentedTest.kt",
        "AccessibilityHarnessActivity.kt",
        "ProfileLibraryInstrumentedTest.kt",
        "ProfileBundleLifecycleInstrumentedTest.kt",
        "ProfileBundleIntentInstrumentedTest.kt",
        "AppSettingsLifecycleInstrumentedTest.kt",
        "ProjectArchiveIntentInstrumentedTest.kt",
        "ProjectEditCancellationInstrumentedTest.kt",
        "BlockingImportProvider.java",
        "BlockingExportProvider.java",
        "CONTRIBUTING.md",
        "PROFILE_BUNDLE_FORMAT.md",
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
        "projectStore.save(",
        "document.history.current",
        "document.plateOptions",
        "document.linkedDocument",
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
        "fun importModels(uris: List<Uri>)",
        "startEditLocked",
        "withCompletedEdit",
        "deleteInstalledModels",
        "val requestId: String",
        "val cancellationRequested: Boolean",
        "ProjectEditFailure.CANCELED",
        "fun cancelActiveEdit()",
        "SlicerProcessClient.cancelProjectRequestAsync(operation.requestId)",
        "SlicerProcessClient.releaseProjectRequest(baseline.operation.requestId)",
        "mutableState.value.withEditCancellationRequested(activeEdit.id)",
        "SlicerProcessClient.cancelProjectRequestAsync(activeEdit.requestId)",
        "private var activeModelImportTransfer: ActiveModelImportTransfer?",
        "activeModelImportTransfer = ActiveModelImportTransfer(baseline.operation, cancellation)",
        "val providerCanceled = modelImport?.cancellation?.cancel() ?: false",
        "cleanup.modelImport?.cancellation?.cancel()",
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
        "projectTransferModel.importModels(uris)",
        "projectTransferModel.importModels(request.uri)",
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

    document_transfer = sources["CreatedDocument.kt"]
    for marker in (
        "class DocumentTransferCancellation",
        "val providerSignal = CancellationSignal()",
        "fun attachInput(value: InputStream)",
        "resources.second.closeQuietly()",
    ):
        if marker not in document_transfer:
            raise VerificationError(f"model document interruption primitive is missing: {marker}")

    orca_import = sources["OrcaModelImport.kt"]
    for marker in (
        "transferCancellation: DocumentTransferCancellation? = null",
        "context.contentResolver.acquireContentProviderClient(uri)",
        "provider.query(",
        "cancellation.providerSignal",
        'provider.openAssetFile(uri, "r", cancellation.providerSignal)',
        "cancellation.attachInput(input)",
        "cancellation.detachInput(input)",
        "cancellationRequested = ::cancellationRequested",
        "if (transferCancellation == null) cancellation.close()",
    ):
        if marker not in orca_import:
            raise VerificationError(f"model provider cancellation contract is missing: {marker}")
    if "contentResolver.openInputStream(uri)" in orca_import:
        raise VerificationError("model import bypasses provider-open cancellation")

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
        "class RemoteRequestCancellation",
        "connection?.disconnect()",
        "cancellation.attach(connection)",
        "cancellation.throwIfRequested()",
        "cancellation.complete()",
        "RemoteRequestCancelledException",
    ):
        if marker not in remote:
            raise VerificationError(f"remote input containment is missing: {marker}")
    if "bufferedReader()?.use { it.readText() }" in remote:
        raise VerificationError("remote response uses an unbounded text read")
    if remote.count("cancellation.attach(connection)") < 2:
        raise VerificationError("every remote request type must bind its exact connection")
    for marker in (
        "internal fun status(",
        "internal fun upload(",
        "internal fun start(",
        "internal fun pause(",
        "internal fun resume(",
        "internal fun cancel(",
    ):
        if marker not in remote:
            raise VerificationError(f"remote request cancellation overload is missing: {marker}")
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
        "RemoteNetworkOperationKind",
        "activeNetworkOperation",
        "ActiveRemoteRequest",
        "activeRemoteRequest",
        "fun cancelActiveRequest()",
        "withRemoteRequestCancellationRequested",
        "RemoteOperationOutcome.RequestCanceled",
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
        "remoteOperationModel.cancelActiveRequest()",
    ):
        if marker not in main:
            raise VerificationError(f"remote operation Activity-recreation contract is missing: {marker}")
    if "RemoteDeviceClient(" in main:
        raise VerificationError("remote network work is still owned by the Activity composition")
    if "RemoteDeviceStore(" in main or "remoteProfileBusy" in main:
        raise VerificationError("remote profile persistence is still owned by the Activity composition")

    plate_batch = sources["PlateSliceBatchViewModel.kt"]
    for marker in (
        "SavedStateHandle",
        "val plateIds: List<String>",
        "val completedCount: Int",
        "val currentPlateId: String?",
        "fun start(plateIds: List<String>)",
        "fun claimNext()",
        "fun complete(plateId: String)",
        "fun requestCancellation()",
        "PlateSliceBatchTerminalStatus.COMPLETED",
    ):
        if marker not in plate_batch:
            raise VerificationError(f"retained plate batch contract is missing: {marker}")
    if "ProjectObject" in plate_batch or "SliceOptions" in plate_batch:
        raise VerificationError("plate batch saved state retains project geometry or profiles")
    plate_batch_effect = sources["PlateSliceBatchEffect.kt"]
    for marker in (
        "snapshot.sliceInput(plateId, plateOptions)",
        "operationModel.start(",
        "batchModel.complete(ownerPlateId)",
        "operationModel.clearCompleted()",
        "batchModel.requestCancellation()",
    ):
        if marker not in plate_batch_effect:
            raise VerificationError(f"sequential plate execution contract is missing: {marker}")
    for marker in (
        "ViewModelProvider(this)[PlateSliceBatchViewModel::class.java]",
        "plateSliceBatchModel.state.collectAsStateWithLifecycle()",
        "rememberSliceStartControls(",
        "R.string.all_plates_sliced",
    ):
        if marker not in main:
            raise VerificationError(f"plate batch Activity recovery contract is missing: {marker}")
    for marker in (
        "R.string.slice_all_plates",
        "R.string.slicing_all_plates_progress",
        "projectPlates.count { it.objects.isNotEmpty() } >= 2",
    ):
        if marker not in workspace:
            raise VerificationError(f"plate batch UI contract is missing: {marker}")

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

    profile_bundle = sources["ProfileBundle.kt"]
    for marker in (
        "MAX_PROFILE_BUNDLE_BYTES",
        "PROFILE_BUNDLE_KEYS",
        "PROFILE_ARRAY_KEYS",
        "parseBoundedJsonObject(bytes, MAX_PROFILE_BUNDLE_BYTES)",
        "portableProfile",
        "importedPrinterIds",
        "remapPrinterIds",
        "importedId",
        "MAX_USER_PROFILES",
        "renamedConflicts",
        "resolveImportedName",
        "normalizedProfileName",
        "ProfileValidation.printer(parsed)",
        "ProfileValidation.filament(parsed)",
        "ProfileValidation.slicing(parsed)",
        "cancellation.throwIfRequested()",
        "copy(builtIn = false)",
    ):
        if marker not in profile_bundle:
            raise VerificationError(f"profile portability boundary is missing: {marker}")
    for forbidden in (
        "RemoteDeviceProfile",
        "credentialCiphertext",
        '"baseUrl"',
        "ProjectSnapshot",
        "AppSettings",
    ):
        if forbidden in profile_bundle:
            raise VerificationError(f"profile bundle includes out-of-scope data: {forbidden}")

    profile_store = sources["ProfileStore.kt"]
    import_start = profile_store.find("internal fun importBundle(")
    import_end = profile_store.find("private fun append(", import_start)
    profile_import = profile_store[import_start:import_end]
    import_order = (
        profile_import.find("mergeProfileBundle("),
        profile_import.find("beforeCommit()"),
        profile_import.find("writeRoot(merged.root)"),
    )
    if any(position < 0 for position in import_order) or not (
        import_order[0] < import_order[1] < import_order[2]
    ):
        raise VerificationError("profile import is not validated before its atomic commit")

    for marker in (
        "fun importBundle(uri: Uri)",
        "fun exportBundle(uri: Uri)",
        "DocumentTransferCancellation()",
        "application.contentResolver.acquireContentProviderClient(uri)",
        'provider.openAssetFile(uri, "r", cancellation.providerSignal)',
        "cancellation.providerSignal",
        "cancellation.attachInput(input)",
        "cancellation.attachOutput(output)",
        "profileStore.importBundle(bytes, cancellation::complete)",
        "deleteFailedCreatedDocument(application, uri)",
        "fun cancelTransfer()",
        "activeTransfer?.cancellation?.cancel()",
    ):
        if marker not in profile_library:
            raise VerificationError(f"profile transfer lifecycle contract is missing: {marker}")
    for marker in (
        "profileImportPicker",
        "profileExportPicker",
        "profileLibraryModel.importBundle(uri)",
        "profileLibraryModel.exportBundle(uri)",
        "profileLibraryModel::cancelTransfer",
    ):
        if marker not in main:
            raise VerificationError(f"profile transfer Activity contract is missing: {marker}")
    for marker in (
        "profileTransferDirection: ProfileTransferDirection?",
        "profileTransferCancellationRequested: Boolean",
        "onImportProfiles: () -> Unit",
        "onExportProfiles: () -> Unit",
        "onCancelProfileTransfer: () -> Unit",
        "R.string.cancel_profile_import",
        "R.string.cancel_profile_export",
    ):
        if marker not in workspace:
            raise VerificationError(f"profile transfer UI is missing: {marker}")

    profile_open = sources["ProfileOpenRequest.kt"]
    for marker in (
        "intent.action != Intent.ACTION_VIEW",
        "ContentResolver.SCHEME_CONTENT",
        "PROFILE_BUNDLE_MIME_TYPE",
        "PROFILE_BUNDLE_FILE_EXTENSION",
        "PROFILE_BUNDLE_COMPATIBLE_MIME_TYPES",
        "application/json",
        "application/octet-stream",
        "class ExternalProfileRequestViewModel(",
        "SavedStateHandle",
        "startedOperationId: Long? = null",
        "fun markStarted(requestId: Long, operationId: Long)",
        "current.startedOperationId != null",
        "fun consume(requestId: Long, operationId: Long)",
        "current.startedOperationId != operationId",
    ):
        if marker not in profile_open:
            raise VerificationError(f"external profile-document boundary is missing: {marker}")
    for forbidden in (
        "ContentResolver.SCHEME_FILE",
        "http://",
        "https://",
        '"text/plain"',
    ):
        if forbidden in profile_open:
            raise VerificationError(
                f"external profile-document boundary accepts an unsafe surface: {forbidden}"
            )
    for marker in (
        "ViewModelProvider(this)[ExternalProfileRequestViewModel::class.java]",
        "externalProfileModel.enqueue(intent)",
        "externalProfileModel.request.collectAsStateWithLifecycle()",
        "ProfileTransferCompletionEffect(",
        "onExternalConsumed = onExternalProfileRequestConsumed",
        "request.startedOperationId == completed.id",
        "onExternalConsumed(request.id, completed.id)",
        "if (request.startedOperationId != null) return@LaunchedEffect",
        "profileLibraryModel.state.value.activeOperationId",
        "onExternalProfileRequestStarted(request.id, operationId)",
    ):
        if marker not in main:
            raise VerificationError(f"external profile Activity contract is missing: {marker}")

    profile_intent_test = sources["ProfileBundleIntentInstrumentedTest.kt"]
    for marker in (
        "externalProfileRequestBindsOneOperationAndRestoresAsRetryableAfterProcessLoss",
        "profileViewIntentRejectsNetworkFileAndUnrelatedDocuments",
        "customProfileIntentSurvivesRecreationAndImportsExactlyOnce",
        "BlockingImportProvider.PROFILE_URI",
        "assertSame(",
        "scenario.recreate()",
        "retainedRequest.request.value == null",
    ):
        if marker not in profile_intent_test:
            raise VerificationError(f"external profile regression is missing: {marker}")

    profile_format = sources["PROFILE_BUNDLE_FORMAT.md"]
    for marker in (
        "`.duckyprofiles`",
        "application/vnd.duckyslicer.profiles+json",
        '"bundleVersion": 1',
        '"profileSchemaVersion": 102',
        "exact profile duplicates are skipped",
        "profile names are trimmed and compared case-insensitively",
        "`Name (2)`",
        "recognizes an earlier conflict-adjusted copy",
        "additive and atomic",
        "24 MiB",
        "4,096",
        "does not contain projects",
        "remote printer addresses",
        "`content://`",
        "Web, `file://`, unrelated JSON, and unrelated binary",
    ):
        if marker not in profile_format:
            raise VerificationError(f"public profile-bundle contract is missing: {marker}")

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
        "requestActive: Boolean",
        "uploadActive: Boolean",
        "requestCancellationRequested: Boolean",
        "onCancelRequest: () -> Unit",
        "R.string.cancel_upload",
        "R.string.canceling_upload",
        "R.string.stop_remote_request",
        "R.string.stopping_remote_request",
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
        "ProfileBundleTest.kt": (
            "bundleRoundTripCarriesOnlyUserProfilesAndRepeatImportIsStable",
            "changedProfileWithCollidingIdentityReceivesANewUserIdentity",
            "conflictingNamesAreRenamedAndRepeatImportRemainsStable",
            "nameConflictsInsideOneBundleAreResolvedWithoutDroppingSettings",
            "conflictSuffixKeepsMaximumLengthNamesValid",
            "malformedOrFutureBundleNeverChangesSavedProfiles",
            "unknownPerProfileFieldsAreDiscardedBeforeTheAtomicWrite",
            "providerInputIsBoundedAndHonorsCancellation",
            "unreadableSavedProfilesCannotBeMisrepresentedAsAnEmptyExport",
        ),
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
            "refreshCancellationRejectsDuplicatesAndLateSuccess",
            "commandCancellationCannotApplyALateStateChange",
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
            "retainedRefreshCancellationDisconnectsExactRequestAndAllowsFollowUp",
            "finalRemoteOwnerDisconnectsBlockedCommand",
            "remoteProfileSaveAndSelectionSurviveActivityRecreation",
            "remoteDeviceMetadataRecoversFromLastKnownGoodBackup",
            "cleartextHostnameRequestUsesOneValidatedPinnedAddress",
        ),
        "AccessibilityInstrumentedTest.kt": (
            "activeRemoteRequestExposesOneNamedStopAction",
            "SCREEN_REMOTE_REQUEST",
        ),
        "AccessibilityHarnessActivity.kt": (
            "SCREEN_REMOTE_REQUEST",
            "DeviceAccessibilityHarness(requestActive = true)",
            "SCREEN_SLICE_ALL",
            "SCREEN_SLICE_ALL_PROGRESS",
            "TEST_SLICE_ALL_REQUESTED_LABEL",
        ),
        "PlateSliceBatchViewModelTest.kt": (
            "queueRunsInStableOrderAndReportsCompletion",
            "activeQueueSurvivesViewModelRecreationWithoutGeometry",
            "cancellationWaitsForClaimedPlateAndThenClearsTheQueue",
            "failureMustBelongToTheClaimedPlate",
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
        "ProfileBundleLifecycleInstrumentedTest.kt": (
            "profileExportSurvivesRecreationAndWritesTheExactBoundedBundle",
            "profileExportCancellationSurvivesRecreationAndDeletesThePartialDocument",
            "profileImportCancellationSurvivesRecreationAndPreservesSavedProfiles",
            "providerBackedProfileImportPublishesTheMergedCatalogOnlyAfterCommit",
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
            "automaticLayButtonKeepsOneRetainedOperationAcrossActivityRecreation",
            "clearingRetainedOwnerFlushesProjectBeforeDebounce",
            "assertSame(",
            "ProjectEditKind.AUTO_LAY",
            "R.string.auto_lay",
            "layFlat.performAction(AccessibilityNodeInfo.ACTION_CLICK)",
            "waitForPersistedTransform",
        ),
        "ModelImportTest.kt": (
            "cooperativeCancellationStopsBeforeAnotherImportChunkIsWritten",
        ),
        "ProjectEditCancellationInstrumentedTest.kt": (
            "retainedOwnerCancelsOnlyItsNativeEditAndKeepsTheProjectUnchanged",
            "retainedModelImportCancellationInterruptsProviderOpenAcrossRecreation",
            "AccessibilityHarnessActivity::class.java",
            "finalProjectOwnerStopsBlockedModelReadAndRemovesItsStaging",
            "BlockingImportProvider.MODEL_URI",
            "waitForModelStagingCleanup",
            "Canceling the exact edit request must restart the isolated worker",
            "Pre-bind cancellation must be accepted",
            "Clearing the final owner did not stop its exact native edit",
            "assertEquals(baseline.history, completed.history)",
        ),
        "BlockingImportProvider.java": (
            "MODEL_URI",
            "signal.setOnCancelListener(target.release::countDown)",
            'target.error = "OperationCanceledException"',
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
                "stop_remote_request",
                "stopping_remote_request",
                "remote_request_canceled",
                "import_profiles",
                "export_profiles",
                "cancel_profile_import",
                "cancel_profile_export",
                "profile_import_error",
                "profile_export_error",
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
    if "exact request-scoped cancellation must survive Activity recreation" not in sources[
        "CONTRIBUTING.md"
    ]:
        raise VerificationError("contributor guidance does not preserve remote operation lifetime")
    if "must never become eligible for Start Print" not in sources["CONTRIBUTING.md"]:
        raise VerificationError("contributor guidance does not reject stale uploaded G-code")
    if "must share that same retained" not in sources["CONTRIBUTING.md"]:
        raise VerificationError("contributor guidance does not retain device-profile persistence")
    for marker in (
        "exact request-scoped cancellation must survive Activity recreation",
        "disconnecting only its request-bound connection",
        "Final retained-owner clearance must stop that exact active connection",
        "Stale cancellation must never stop a later refresh, upload, or printer command",
    ):
        if marker not in sources["CONTRIBUTING.md"]:
            raise VerificationError(
                f"contributor guidance does not scope remote request cancellation: {marker}"
            )
    if "Profile catalog loading, recent selections, and user-profile saves must share one" not in sources[
        "CONTRIBUTING.md"
    ]:
        raise VerificationError("contributor guidance does not retain profile-library persistence")
    if "only in the project session revision that" not in sources["CONTRIBUTING.md"]:
        raise VerificationError("contributor guidance does not bind late profile-save completion")
    for marker in (
        "Profile bundle import and export must use that same retained owner",
        "External profile documents must remain `content://`-only",
        "bind one pending request to one import operation",
        "consume the request only after that",
        "`docs/PROFILE_BUNDLE_FORMAT.md`",
    ):
        if marker not in sources["CONTRIBUTING.md"]:
            raise VerificationError(
                f"contributor guidance does not preserve portable profiles: {marker}"
            )
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
    for marker in (
        "Ordinary STL, 3MF, and OBJ import cancellation must interrupt the exact provider open",
        "matching isolated-worker request",
        "without deleting the user-selected source document",
    ):
        if marker not in sources["CONTRIBUTING.md"]:
            raise VerificationError(
                f"contributor guidance does not interrupt model import: {marker}"
            )
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
    debug = ROOT / "android/app/src/debug/java/com/ashcastle/duckyslicer"
    return {
        "BoundedJson.kt": (main / "BoundedJson.kt").read_text(encoding="utf-8"),
        "DurableJsonFile.kt": (main / "DurableJsonFile.kt").read_text(encoding="utf-8"),
        "ProjectStore.kt": (main / "ProjectStore.kt").read_text(encoding="utf-8"),
        "ProjectTransfer.kt": (main / "ProjectTransfer.kt").read_text(encoding="utf-8"),
        "CreatedDocument.kt": (main / "CreatedDocument.kt").read_text(encoding="utf-8"),
        "ModelImport.kt": (main / "ModelImport.kt").read_text(encoding="utf-8"),
        "OrcaModelImport.kt": (main / "OrcaModelImport.kt").read_text(encoding="utf-8"),
        "SlicerProcessService.kt": (main / "SlicerProcessService.kt").read_text(
            encoding="utf-8"
        ),
        "ProfileStore.kt": (main / "ProfileStore.kt").read_text(encoding="utf-8"),
        "ProfileBundle.kt": (main / "ProfileBundle.kt").read_text(encoding="utf-8"),
        "ProfileOpenRequest.kt": (main / "ProfileOpenRequest.kt").read_text(encoding="utf-8"),
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
        "PlateSliceBatchViewModel.kt": (
            main / "PlateSliceBatchViewModel.kt"
        ).read_text(encoding="utf-8"),
        "PlateSliceBatchEffect.kt": (main / "PlateSliceBatchEffect.kt").read_text(
            encoding="utf-8"
        ),
        "WorkspaceScreen.kt": (main / "WorkspaceScreen.kt").read_text(encoding="utf-8"),
        "DeviceSheet.kt": (main / "DeviceSheet.kt").read_text(encoding="utf-8"),
        "DurableJsonFileTest.kt": (tests / "DurableJsonFileTest.kt").read_text(encoding="utf-8"),
        "ProjectStoreTest.kt": (tests / "ProjectStoreTest.kt").read_text(encoding="utf-8"),
        "PlateSliceBatchViewModelTest.kt": (
            tests / "PlateSliceBatchViewModelTest.kt"
        ).read_text(encoding="utf-8"),
        "ModelImportTest.kt": (tests / "ModelImportTest.kt").read_text(encoding="utf-8"),
        "ProfileStoreMigrationTest.kt": (tests / "ProfileStoreMigrationTest.kt").read_text(
            encoding="utf-8"
        ),
        "ProfileBundleTest.kt": (tests / "ProfileBundleTest.kt").read_text(encoding="utf-8"),
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
        "AccessibilityInstrumentedTest.kt": (
            device_tests / "AccessibilityInstrumentedTest.kt"
        ).read_text(encoding="utf-8"),
        "AccessibilityHarnessActivity.kt": (
            debug / "AccessibilityHarnessActivity.kt"
        ).read_text(encoding="utf-8"),
        "ProfileLibraryInstrumentedTest.kt": (
            device_tests / "ProfileLibraryInstrumentedTest.kt"
        ).read_text(encoding="utf-8"),
        "ProfileBundleLifecycleInstrumentedTest.kt": (
            device_tests / "ProfileBundleLifecycleInstrumentedTest.kt"
        ).read_text(encoding="utf-8"),
        "ProfileBundleIntentInstrumentedTest.kt": (
            device_tests / "ProfileBundleIntentInstrumentedTest.kt"
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
        "BlockingImportProvider.java": (
            device_tests / "BlockingImportProvider.java"
        ).read_text(encoding="utf-8"),
        "BlockingExportProvider.java": (
            device_tests / "BlockingExportProvider.java"
        ).read_text(encoding="utf-8"),
        "CONTRIBUTING.md": (ROOT / "CONTRIBUTING.md").read_text(encoding="utf-8"),
        "PROFILE_BUNDLE_FORMAT.md": (ROOT / "docs/PROFILE_BUNDLE_FORMAT.md").read_text(
            encoding="utf-8"
        ),
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
