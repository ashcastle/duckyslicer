from __future__ import annotations

import unittest

from tools.verify_runtime_resilience import VerificationError, verify_resilience


def valid_sources() -> dict[str, str]:
    return {
        "BoundedJson.kt": "CodingErrorAction.REPORT maximumBytes maximumDepth",
        "DurableJsonFile.kt": (
            "RECOVERED_BACKUP INCOMPATIBLE UNREADABLE StandardCopyOption.ATOMIC_MOVE output.fd.sync() "
            "saved_data_unreadable parseBoundedJsonObject"
        ),
        "ProjectStore.kt": "DurableJsonFile( storageUnavailable validateProjectRoot",
        "ProjectTransfer.kt": (
            "val persistenceBlocked: Boolean val persistedRevision: Long "
            "val activeTransferDirection: ProjectTransferDirection? "
            "restored.storageUnavailable "
            "!current.persistenceBlocked "
            "projectStore.save( document.history.current document.plateOptions "
            "document.linkedDocument document.linkedDocumentDirty "
            "pendingPersistence?.join() fun flushPersistence() "
            "override fun onCleared() hasPersistableChanges "
            "fun autoLaySelectedModel() fun arrangeProjectObjects() "
            "fun splitSelectedModel() "
            "fun cutSelectedModel(heightRatio: Float, placeOnCut: Boolean) "
            "fun createPrimitive( fun importModels(uri: Uri) fun importModels(uris: List<Uri>) "
            "startEditLocked "
            "withCompletedEdit deleteInstalledModels val requestId: String "
            "val cancellationRequested: Boolean ProjectEditFailure.CANCELED "
            "fun cancelActiveEdit() SlicerProcessClient.cancelProjectRequestAsync(operation.requestId) "
            "SlicerProcessClient.releaseProjectRequest(baseline.operation.requestId) "
            "mutableState.value.withEditCancellationRequested(activeEdit.id) "
            "SlicerProcessClient.cancelProjectRequestAsync(activeEdit.requestId) "
            "private var activeModelImportTransfer: ActiveModelImportTransfer? "
            "activeModelImportTransfer = ActiveModelImportTransfer(baseline.operation, cancellation) "
            "val providerCanceled = modelImport?.cancellation?.cancel() ?: false "
            "cleanup.modelImport?.cancellation?.cancel()"
        ),
        "CreatedDocument.kt": (
            "class DocumentTransferCancellation val providerSignal = CancellationSignal() "
            "fun attachInput(value: InputStream) resources.second.closeQuietly()"
        ),
        "ModelImport.kt": (
            "cancellationRequested: () -> Boolean "
            "if (cancellationRequested()) throw ProjectEditCancelledException()"
        ),
        "OrcaModelImport.kt": (
            "transferCancellation: DocumentTransferCancellation? = null "
            "context.contentResolver.acquireContentProviderClient(uri) provider.query( "
            "cancellation.providerSignal "
            'provider.openAssetFile(uri, "r", cancellation.providerSignal) '
            "cancellation.attachInput(input) cancellation.detachInput(input) "
            "cancellationRequested = ::cancellationRequested "
            "if (transferCancellation == null) cancellation.close()"
        ),
        "SlicerProcessService.kt": (
            "cancelledProjectRequestIds fun cancelProjectRequestAsync(requestId: String) "
            "activeRequestId.get() != requestId projectRequestCancellationRequested(requestId) "
            "fun releaseProjectRequest(requestId: String) throw ProjectEditCancelledException() "
            "what == SlicerProcessContract.MESSAGE_CUT_MODEL"
        ),
        "ProfileStore.kt": (
            "DurableJsonFile( internal fun importBundle( mergeProfileBundle( "
            "beforeCommit() writeRoot(merged.root) private fun append("
        ),
        "ProfileBundle.kt": (
            "MAX_PROFILE_BUNDLE_BYTES PROFILE_BUNDLE_KEYS PROFILE_ARRAY_KEYS "
            "parseBoundedJsonObject(bytes, MAX_PROFILE_BUNDLE_BYTES) portableProfile "
            "importedPrinterIds remapPrinterIds "
            "importedId MAX_USER_PROFILES renamedConflicts resolveImportedName "
            "normalizedProfileName ProfileValidation.printer(parsed) "
            "ProfileValidation.filament(parsed) ProfileValidation.slicing(parsed) "
            "cancellation.throwIfRequested() copy(builtIn = false)"
        ),
        "ProfileBundleShare.kt": (
            "PROFILE_BUNDLE_SHARE_RETAINED_FILES = 3 context.filesDir.canonicalFile "
            "Files.createDirectories(shareRoot.toPath()) Files.createTempFile( "
            '"${context.packageName}.profile-share" PROFILE_BUNDLE_MIME_TYPE '
            "Intent(Intent.ACTION_SEND) Intent.EXTRA_STREAM Intent.EXTRA_TITLE "
            "ClipData.newUri( Intent.FLAG_GRANT_READ_URI_PERMISSION "
            "discardProfileBundleShare(context: Context, path: String?)"
        ),
        "ProfileBundleShareActions.kt": (
            "rememberSaveable { mutableStateOf<Long?>(null) } "
            "rememberSaveable { mutableStateOf<String?>(null) } "
            "completed.id != pendingOperationId "
            "completed.outcome == ProfileTransferOutcome.SUCCEEDED "
            "Intent.createChooser(shareIntent, shareTitle) "
            "discardProfileBundleShare(context, pendingPath) "
            "model.exportBundle(target.uri) "
            "pendingOperationId = model.state.value.activeOperationId"
        ),
        "ProfileBundleShareProvider.kt": (
            "FileProvider(R.xml.profile_bundle_share_paths)"
        ),
        "profile_bundle_share_paths.xml": (
            '<paths><files-path name="profile-bundles" path="profile-shares/" /></paths>'
        ),
        "AndroidManifest.xml": (
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
            '<application><provider android:name=".ProfileBundleShareProvider" '
            'android:authorities="${applicationId}.profile-share" android:exported="false" '
            'android:grantUriPermissions="true"><meta-data '
            'android:name="android.support.FILE_PROVIDER_PATHS" '
            'android:resource="@xml/profile_bundle_share_paths" />'
            '</provider></application></manifest>'
        ),
        "ProfileOpenRequest.kt": (
            "Intent.ACTION_VIEW Intent.ACTION_SEND sharedDocumentUriOrNull(intent) "
            "ContentResolver.SCHEME_CONTENT "
            "PROFILE_BUNDLE_MIME_TYPE PROFILE_BUNDLE_FILE_EXTENSION "
            "PROFILE_BUNDLE_COMPATIBLE_MIME_TYPES application/json application/octet-stream "
            "class ExternalProfileRequestViewModel( SavedStateHandle "
            "startedOperationId: Long? = null "
            "fun markStarted(requestId: Long, operationId: Long) "
            "current.startedOperationId != null "
            "fun consume(requestId: Long, operationId: Long) "
            "current.startedOperationId != operationId"
        ),
        "ProfileLibraryViewModel.kt": (
            "class ProfileLibraryViewModel(application: Application) : AndroidViewModel(application) "
            "viewModelScope.launch private val profileStore = ProfileStore(application) "
            "private val recentStore = ProfileRecentStore(application) fun savePrinter( "
            "fun saveFilament( fun saveSlicing( fun recordSelection( activeOperationId "
            "optionsForSession val recentsRevision: Long "
            "val persistedRecentsRevision: Long fun flushRecentPersistence() "
            "override fun onCleared() hasDirtyRecents RECENT_PROFILE_SAVE_DEBOUNCE_MILLIS "
            "fun importBundle(uri: Uri) fun exportBundle(uri: Uri) "
            "DocumentTransferCancellation() cancellation.providerSignal "
            "application.contentResolver.acquireContentProviderClient(uri) "
            'provider.openAssetFile(uri, "r", cancellation.providerSignal) '
            "cancellation.attachInput(input) cancellation.attachOutput(output) "
            "profileStore.importBundle(bytes, cancellation::complete) "
            "deleteFailedCreatedDocument(application, uri) fun cancelTransfer() "
            "activeTransfer?.cancellation?.cancel()"
        ),
        "AppSettings.kt": (
            "fun AppSettings.normalized() fun save(settings: AppSettings): Boolean .commit()"
        ),
        "AppSettingsViewModel.kt": (
            "class AppSettingsViewModel(application: Application) : AndroidViewModel(application) "
            "viewModelScope.launch withUpdatedSettings SETTINGS_SAVE_DEBOUNCE_MILLIS "
            "current.revision != revision fun flushPersistence() override fun onCleared() "
            "SupportEvent.APP_SETTINGS_SAVE_FAILED"
        ),
        "RemoteDevice.kt": (
            "DurableJsonFile( MAX_REMOTE_RESPONSE_BYTES MAX_REMOTE_CREDENTIAL_BYTES "
            "MAX_REMOTE_GCODE_BYTES readBoundedBytes parseBoundedJsonObject "
            "instanceFollowRedirects = false resolveRemoteEndpoint "
            "addresses.all(::isPrivateOrLocalAddress) val url = endpoint.uri.toURL() "
            "url.openConnection(Proxy.NO_PROXY) "
            "endpoint.hostHeader?.let isUniqueLocalIpv6 safeRemotePath connection.disconnect() "
            "class RemoteRequestCancellation connection?.disconnect() "
            "cancellation.attach(connection) cancellation.attach(connection) "
            "cancellation.throwIfRequested() cancellation.complete() "
            "RemoteRequestCancelledException internal fun status( internal fun upload( "
            "internal fun start( internal fun pause( internal fun resume( internal fun cancel( "
            "fun save(draft: RemoteDeviceDraft) endpointChanged stagedCredential "
            "credentialKey = credentialKey stagedCredential?.let write(profiles.sortedBy "
            "secrets.remove(stagedCredentialKey) return load().first "
            "fun delete(profileId: String) removedCredentialKey "
            'write(existing.filterNot load() check(!storageUnavailable) { "saved_data_unreadable" } '
            "removedCredentialKey?.let(secrets::remove) "
            "fun credential(profile: RemoteDeviceProfile) REMOTE_DEVICE_SCHEMA_VERSION = 2"
            " remoteResultBelongsToSelection"
        ),
        "RemoteOperationViewModel.kt": (
            "viewModelScope.launch beginRemoteOperation finishRemoteOperation "
            "activeArtifactRevision invalidateRemoteUpload withRemoteUploadProgress "
            "RemoteStatusSnapshot SupportEvent.REMOTE_COMMAND_FAILED "
            "fun saveProfile( fun deleteProfile( profilesLoaded selectedProfileId "
            "RemoteNetworkOperationKind activeNetworkOperation "
            "ActiveRemoteRequest activeRemoteRequest fun cancelActiveRequest() "
            "withRemoteRequestCancellationRequested RemoteOperationOutcome.RequestCanceled "
            "finishOperation( override fun onCleared() "
            "remoteDeviceStore.load() remoteDeviceStore.save(draft) "
            "remoteDeviceStore.delete(profileId) "
            + "remoteResultBelongsToSelection " * 4
        ),
        "MainActivity.kt": (
            "saved_data_unavailable "
            "ViewModelProvider(this)[RemoteOperationViewModel::class.java] "
            "remoteOperationModel.state.collectAsStateWithLifecycle() "
            "selectedRemoteDeviceId = remoteOperationState.selectedProfileId "
            "remoteOperationModel.invalidateUpload() "
            "remoteOperationModel.cancelActiveRequest() "
            "ViewModelProvider(this)[ProfileLibraryViewModel::class.java] "
            "profileLibraryModel.state.collectAsStateWithLifecycle() "
            "completion.optionsForSession(session.sessionRevision) "
            "profileLibraryModel.recordSelection(options) "
            "profileLibraryModel.flushRecentPersistence() "
            "profileImportPicker profileExportPicker profileLibraryModel.importBundle(uri) "
            "profileLibraryModel.exportBundle(uri) profileLibraryModel::cancelTransfer "
            "ViewModelProvider(this)[ExternalProfileRequestViewModel::class.java] "
            "externalProfileModel.enqueue(intent) "
            "externalProfileModel.request.collectAsStateWithLifecycle() "
            "ProfileTransferCompletionEffect( "
            "onExternalConsumed = onExternalProfileRequestConsumed "
            "request.startedOperationId == completed.id "
            "onExternalConsumed(request.id, completed.id) "
            "if (request.startedOperationId != null) return@LaunchedEffect "
            "profileLibraryModel.state.value.activeOperationId "
            "onExternalProfileRequestStarted(request.id, operationId) "
            "rememberProfileBundleShareActions( "
            "profileShareOperationId = profileShareActions.pendingOperationId "
            "onShareProfiles = profileShareActions.start "
            "ViewModelProvider(this)[AppSettingsViewModel::class.java] "
            "appSettingsModel.state.collectAsStateWithLifecycle() "
            "appSettingsModel.updateSettings(next) "
            "appSettingsModel.flushPersistence() "
            "override fun onStop() projectTransferModel.flushPersistence() "
            "projectTransferModel.autoLaySelectedModel() "
            "projectTransferModel.arrangeProjectObjects() "
            "projectTransferModel.splitSelectedModel() "
            "projectTransferModel.cutSelectedModel(heightRatio, placeOnCut) "
            "projectTransferModel.createPrimitive(primitive, sizeMm, displayName) "
            "projectTransferModel.importModels(uris) "
            "projectTransferModel.importModels(request.uris) "
            "projectTransferModel::cancelActiveEdit "
            "ViewModelProvider(this)[PlateSliceBatchViewModel::class.java] "
            "plateSliceBatchModel.state.collectAsStateWithLifecycle() "
            "rememberSliceStartControls( R.string.all_plates_sliced"
        ),
        "PlateSliceBatchViewModel.kt": (
            "SavedStateHandle val plateIds: List<String> val completedCount: Int "
            "val currentPlateId: String? fun start(plateIds: List<String>) fun claimNext() "
            "fun complete(plateId: String) fun requestCancellation() "
            "PlateSliceBatchTerminalStatus.COMPLETED"
        ),
        "PlateSliceBatchEffect.kt": (
            "snapshot.sliceInput(plateId, plateOptions) operationModel.start( "
            "batchModel.complete(ownerPlateId) operationModel.clearCompleted() "
            "batchModel.requestCancellation()"
        ),
        "WorkspaceScreen.kt": (
            "projectEditActive: Boolean projectEditCancellationRequested: Boolean "
            "onCancelProjectEdit: () -> Unit R.string.cancel_model_edit "
            "R.string.canceling_model_edit "
            "profileTransferDirection: ProfileTransferDirection? "
            "profileTransferCancellationRequested: Boolean "
            "onImportProfiles: () -> Unit onExportProfiles: () -> Unit "
            "onShareProfiles: () -> Unit R.string.share_profiles onShareProfiles() "
            "onCancelProfileTransfer: () -> Unit R.string.cancel_profile_import "
            "R.string.cancel_profile_export R.string.slice_all_plates "
            "R.string.slicing_all_plates_progress "
            "projectPlates.count { it.objects.isNotEmpty() } >= 2"
        ),
        "DeviceSheet.kt": (
            ".selectable( selected = true enabled = !busy ), "
            "requestActive: Boolean uploadActive: Boolean requestCancellationRequested: Boolean "
            "onCancelRequest: () -> Unit R.string.cancel_upload R.string.canceling_upload "
            "R.string.stop_remote_request R.string.stopping_remote_request"
        ),
        "DurableJsonFileTest.kt": (
            "validPrimaryCreatesBackupAndCorruptionRecoversIt "
            "unreadableGenerationsAreNeverOverwritten"
        ),
        "ProjectStoreTest.kt": "unreadablePrimaryAndBackupBlockAutosave",
        "ModelImportTest.kt": "cooperativeCancellationStopsBeforeAnotherImportChunkIsWritten",
        "ProfileStoreMigrationTest.kt": "unreadableOrFutureProfilesAreNotOverwritten",
        "ProfileBundleTest.kt": (
            "bundleRoundTripCarriesOnlyUserProfilesAndRepeatImportIsStable "
            "changedProfileWithCollidingIdentityReceivesANewUserIdentity "
            "conflictingNamesAreRenamedAndRepeatImportRemainsStable "
            "nameConflictsInsideOneBundleAreResolvedWithoutDroppingSettings "
            "conflictSuffixKeepsMaximumLengthNamesValid "
            "malformedOrFutureBundleNeverChangesSavedProfiles "
            "unknownPerProfileFieldsAreDiscardedBeforeTheAtomicWrite "
            "providerInputIsBoundedAndHonorsCancellation "
            "unreadableSavedProfilesCannotBeMisrepresentedAsAnEmptyExport"
        ),
        "ProfileLibraryViewModelTest.kt": (
            "savedProfileAppliesOnlyToTheSessionRevisionThatStartedTheSave "
            "eachSavedProfileKindBuildsItsExpectedSelection"
        ),
        "AppSettingsViewModelTest.kt": (
            "settingsUpdatesNormalizeValuesAdvanceRevisionAndClearFailure "
            "equivalentNormalizedSettingsDoNotScheduleAnotherWrite"
        ),
        "RemoteDeviceClientTest.kt": (
            "remoteResultsOnlyBelongToTheirOriginatingSelection "
            "redirectsOversizedResponsesAndDeepJsonFailClosed unsafeServerUploadPathIsRejected "
            "cleartextDnsResultsAreValidatedAndPinnedBeforeCredentialsAreAttached "
            "cleartextHostnameRequestUsesThePinnedResolverAddress"
            " cancelingUploadDisconnectsItsSocketAndDoesNotPoisonTheNextUpload"
        ),
        "RemoteOperationViewModelTest.kt": (
            "resultsAreVisibleOnlyForTheirOriginatingProfile "
            "staleCompletionCannotFinishANewerOperation "
            "invalidatedUploadCannotBecomePrintable "
            "explicitUploadCancellationStopsProgressAndReportsOneTerminalNotice "
            "invalidatingAnActiveUploadKeepsItsCancellationSilent "
            "refreshCancellationRejectsDuplicatesAndLateSuccess "
            "commandCancellationCannotApplyALateStateChange "
            "commandCompletionRetainsFileAndUpdatesState "
            "profileSaveSelectsTheDurableResultAndClearsOldPrinterState "
            "deletingTheSelectedProfileChoosesTheFirstRemainingProfile"
        ),
        "RemoteDeviceStoreTest.kt": (
            "credentialsUseGenerationsAndDoNotFollowAChangedEndpoint "
            "legacyProfileCredentialsMigrateWithoutEnteringPlaintextMetadata "
            "failedMetadataCommitCannotBindAStagedCredentialToTheOldProfile "
            "deletingAProfileRemovesItsExactCredentialAfterMetadataIsDurable "
            "deleteRetainsCredentialWhenBackupRefreshFailsAfterMetadataCommit "
            "orphanCleanupFailureKeepsProfilesVisibleAndRetriesLater"
        ),
        "RemoteDeviceInstrumentedTest.kt": (
            "retainedUploadCancellationStopsItsConnectionAcrossActivityRecreation "
            "remoteRefreshSurvivesActivityRecreationAndRejectsDuplicateWork "
            "retainedRefreshCancellationDisconnectsExactRequestAndAllowsFollowUp "
            "finalRemoteOwnerDisconnectsBlockedCommand "
            "remoteProfileSaveAndSelectionSurviveActivityRecreation "
            "remoteDeviceMetadataRecoversFromLastKnownGoodBackup "
            "cleartextHostnameRequestUsesOneValidatedPinnedAddress"
        ),
        "AccessibilityInstrumentedTest.kt": (
            "activeRemoteRequestExposesOneNamedStopAction SCREEN_REMOTE_REQUEST "
            "profileBundleShareIsExplicitInWorkspaceMenu "
            "TEST_PROFILE_SHARE_REQUESTED_LABEL R.string.share_profiles"
        ),
        "AccessibilityHarnessActivity.kt": (
            "SCREEN_REMOTE_REQUEST DeviceAccessibilityHarness(requestActive = true) "
            "SCREEN_SLICE_ALL SCREEN_SLICE_ALL_PROGRESS TEST_SLICE_ALL_REQUESTED_LABEL "
            "onShareProfiles = { harnessNotice = TEST_PROFILE_SHARE_REQUESTED_LABEL } "
            "Accessibility profile share requested"
        ),
        "PlateSliceBatchViewModelTest.kt": (
            "queueRunsInStableOrderAndReportsCompletion "
            "activeQueueSurvivesViewModelRecreationWithoutGeometry "
            "cancellationWaitsForClaimedPlateAndThenClearsTheQueue "
            "failureMustBelongToTheClaimedPlate"
        ),
        "ProfileLibraryInstrumentedTest.kt": (
            "profileSaveAndRecentSelectionSurviveImmediateActivityRecreation "
            "clearingRetainedOwnerFlushesRecentProfilesBeforeDebounce "
            "lateProfileSaveCannotReplaceNewerProjectSettings "
            "The profile save must be active before recreation "
            "The profile save must be active before the newer edit"
        ),
        "ProfileBundleLifecycleInstrumentedTest.kt": (
            "profileExportSurvivesRecreationAndWritesTheExactBoundedBundle "
            "profileExportCancellationSurvivesRecreationAndDeletesThePartialDocument "
            "profileImportCancellationSurvivesRecreationAndPreservesSavedProfiles "
            "providerBackedProfileImportPublishesTheMergedCatalogOnlyAfterCommit"
        ),
        "ProfileBundleIntentInstrumentedTest.kt": (
            "externalProfileRequestBindsOneOperationAndRestoresAsRetryableAfterProcessLoss "
            "profileDocumentIntentsAcceptOneContentStreamAndRejectUnsafeDocuments "
            "sharedProfileIntentSurvivesRecreationAndImportsExactlyOnce "
            "Intent.ACTION_SEND Intent.EXTRA_STREAM ClipData.newRawUri "
            "BlockingImportProvider.PROFILE_URI assertSame( scenario.recreate() "
            "retainedRequest.request.value == null"
        ),
        "ProfileBundleShareInstrumentedTest.kt": (
            "preparedProfileBundleSharesExactReadOnlyDocumentAndRejectsOtherUris "
            "preparedProfileBundleStorageRetainsOnlyThreePrivateOutputs "
            "Intent.FLAG_GRANT_READ_URI_PERMISSION "
            "Intent.FLAG_GRANT_WRITE_URI_PERMISSION FileProvider.getUriForFile( "
            "assertEquals(3, retained.size) assertArrayEquals("
        ),
        "AppSettingsLifecycleInstrumentedTest.kt": (
            "latestUnsavedSettingsSurviveImmediateActivityRecreationAndPersist "
            "backgroundingFlushesLatestSettingsBeforeDebounce "
            "Recreate before the 350 ms persistence debounce can run "
            "onStop must replace the pending 350 ms write with an immediate one"
        ),
        "ProjectArchiveIntentInstrumentedTest.kt": (
            "automaticLayButtonKeepsOneRetainedOperationAcrossActivityRecreation "
            "clearingRetainedOwnerFlushesProjectBeforeDebounce "
            "assertSame( ProjectEditKind.AUTO_LAY R.string.auto_lay "
            "layFlat.performAction(AccessibilityNodeInfo.ACTION_CLICK) "
            "waitForPersistedTransform"
        ),
        "ProjectEditCancellationInstrumentedTest.kt": (
            "retainedOwnerCancelsOnlyItsNativeEditAndKeepsTheProjectUnchanged "
            "retainedModelImportCancellationInterruptsProviderOpenAcrossRecreation "
            "AccessibilityHarnessActivity::class.java "
            "finalProjectOwnerStopsBlockedModelReadAndRemovesItsStaging "
            "BlockingImportProvider.MODEL_URI waitForModelStagingCleanup "
            "Canceling the exact edit request must restart the isolated worker "
            "Pre-bind cancellation must be accepted "
            "Clearing the final owner did not stop its exact native edit "
            "assertEquals(baseline.history, completed.history)"
        ),
        "BlockingImportProvider.java": (
            "MODEL_URI signal.setOnCancelListener(target.release::countDown) "
            'target.error = "OperationCanceledException"'
        ),
        "BlockingExportProvider.java": "blocking export provider",
        "CONTRIBUTING.md": (
            "pin the connection target and bypass system proxies "
            "bind a replacement printer credential generation "
            "Bind every remote status, upload-progress, and command result "
            "exact request-scoped cancellation must survive Activity recreation "
            "must never become eligible for Start Print "
            "must share that same retained "
            "Profile catalog loading, recent selections, and user-profile saves must share one "
            "only in the project session revision that "
            "Profile bundle import and export must use that same retained owner "
            "External profile documents must remain `content://`-only "
            "bind one pending request to one import operation "
            "consume the request only after that `docs/PROFILE_BUNDLE_FORMAT.md` "
            "Track recent-selection persistence revisions dirty `Recent` list "
            "app enters the background owner is finally cleared "
            "Live app settings and their debounced persistence must share one "
            "Flush the latest dirty settings "
            "Fixed support-event writers must serialize through the process-wide journal boundary "
            "Flush the latest dirty revision app enters the background owner is finally cleared "
            "archive import commits cancel and join any older metadata write "
            "Model import, primitive creation, automatic lay, arrangement, split, and cut must run "
            "UI disposal must not issue a process-wide slicer cancellation "
            "Foreground-slice cancellation must carry its "
            "idle or stale slice owner must never cancel a later "
            "request-scoped cancellation preserve the starting project on cancellation "
            "remove every generated model that was not accepted Final retained-owner clearance "
            "ordinary Activity recreation must not "
            "Ordinary STL, 3MF, and OBJ import cancellation must interrupt the exact provider open "
            "matching isolated-worker request without deleting the user-selected source document"
            " disconnecting only its request-bound connection"
            " Final retained-owner clearance must stop that exact active connection"
            " Stale cancellation must never stop a later refresh, upload, or printer command"
        ),
        "SECURITY.md": (
            "every current DNS answer DNS rebinding bypass system proxies "
            "platform certificate verifier remains authoritative "
            "Credential updates are staged under a new generation "
            "never carried to a changed connection type or address"
        ),
        "PROFILE_BUNDLE_FORMAT.md": (
            "`.duckyprofiles` application/vnd.duckyslicer.profiles+json "
            '"bundleVersion": 1 "profileSchemaVersion": 107 '
            "exact profile duplicates are skipped "
            "profile names are trimmed and compared case-insensitively `Name (2)` "
            "recognizes an earlier conflict-adjusted copy additive and atomic 24 MiB 4,096 "
            "does not contain projects remote printer addresses `content://` "
            "another app's Share sheet "
            "Web, `file://`, unrelated JSON, and unrelated binary"
        ),
        "strings.xml": (
            "saved_data_unavailable settings_save_error cancel_model_edit model_edit_canceled "
            "cancel_upload canceling_upload upload_canceled stop_remote_request "
            "stopping_remote_request remote_request_canceled"
            " import_profiles export_profiles cancel_profile_import cancel_profile_export"
            " profile_import_error profile_export_error"
        ),
        "strings-ko.xml": (
            "saved_data_unavailable settings_save_error cancel_model_edit model_edit_canceled "
            "cancel_upload canceling_upload upload_canceled stop_remote_request "
            "stopping_remote_request remote_request_canceled"
            " import_profiles export_profiles cancel_profile_import cancel_profile_export"
            " profile_import_error profile_export_error"
        ),
    }


class VerifyRuntimeResilienceTest(unittest.TestCase):
    def test_accepts_complete_resilience_contract(self) -> None:
        verify_resilience(valid_sources())

    def test_rejects_unbounded_remote_response(self) -> None:
        sources = valid_sources()
        sources["RemoteDevice.kt"] += " bufferedReader()?.use { it.readText() }"
        with self.assertRaisesRegex(VerificationError, "unbounded"):
            verify_resilience(sources)

    def test_rejects_missing_autosave_guard(self) -> None:
        sources = valid_sources()
        sources["ProjectTransfer.kt"] = sources["ProjectTransfer.kt"].replace(
            "!current.persistenceBlocked", "true"
        )
        with self.assertRaisesRegex(VerificationError, "autosave"):
            verify_resilience(sources)

    def test_rejects_autosave_that_drops_the_linked_project_document(self) -> None:
        sources = valid_sources()
        sources["ProjectTransfer.kt"] = sources["ProjectTransfer.kt"].replace(
            "document.linkedDocument", "forget linked project document"
        )
        with self.assertRaisesRegex(VerificationError, "autosave"):
            verify_resilience(sources)

    def test_rejects_autosave_that_drops_linked_project_dirty_state(self) -> None:
        sources = valid_sources()
        sources["ProjectTransfer.kt"] = sources["ProjectTransfer.kt"].replace(
            "document.linkedDocumentDirty", "forget linked project dirty state"
        )
        with self.assertRaisesRegex(VerificationError, "autosave"):
            verify_resilience(sources)

    def test_rejects_import_without_joining_older_project_save(self) -> None:
        sources = valid_sources()
        sources["ProjectTransfer.kt"] = sources["ProjectTransfer.kt"].replace(
            "pendingPersistence?.join()", "import before older save settles"
        )
        with self.assertRaisesRegex(VerificationError, "autosave"):
            verify_resilience(sources)

    def test_rejects_background_transition_without_project_flush(self) -> None:
        sources = valid_sources()
        sources["MainActivity.kt"] = sources["MainActivity.kt"].replace(
            "projectTransferModel.flushPersistence()", "leave project save pending"
        )
        with self.assertRaisesRegex(VerificationError, "project persistence lifecycle"):
            verify_resilience(sources)

    def test_rejects_remote_results_without_profile_binding(self) -> None:
        sources = valid_sources()
        sources["RemoteOperationViewModel.kt"] = sources["RemoteOperationViewModel.kt"].replace(
            "remoteResultBelongsToSelection", "unboundRemoteResult", 1
        )
        with self.assertRaisesRegex(VerificationError, "profile binding"):
            verify_resilience(sources)

    def test_rejects_activity_owned_remote_network_work(self) -> None:
        sources = valid_sources()
        sources["MainActivity.kt"] += " RemoteDeviceClient(15000)"
        with self.assertRaisesRegex(VerificationError, "Activity composition"):
            verify_resilience(sources)

    def test_rejects_activity_owned_remote_profile_storage(self) -> None:
        sources = valid_sources()
        sources["MainActivity.kt"] += " RemoteDeviceStore(context) remoteProfileBusy"
        with self.assertRaisesRegex(VerificationError, "profile persistence"):
            verify_resilience(sources)

    def test_rejects_activity_owned_app_settings_storage(self) -> None:
        sources = valid_sources()
        sources["MainActivity.kt"] += " AppSettingsStore(context)"
        with self.assertRaisesRegex(VerificationError, "app-settings persistence"):
            verify_resilience(sources)

    def test_rejects_background_transition_without_app_settings_flush(self) -> None:
        sources = valid_sources()
        sources["MainActivity.kt"] = sources["MainActivity.kt"].replace(
            "appSettingsModel.flushPersistence()", "leave settings save pending"
        )
        with self.assertRaisesRegex(VerificationError, "app-settings Activity-recreation"):
            verify_resilience(sources)

    def test_rejects_activity_owned_project_edit_work(self) -> None:
        sources = valid_sources()
        sources["MainActivity.kt"] += " SlicerProcessClient.autoOrient(File(path))"
        with self.assertRaisesRegex(VerificationError, "Activity composition"):
            verify_resilience(sources)

    def test_rejects_project_edit_without_retained_cancellation(self) -> None:
        sources = valid_sources()
        sources["ProjectTransfer.kt"] = sources["ProjectTransfer.kt"].replace(
            "fun cancelActiveEdit()", "leave project edit running"
        )
        with self.assertRaisesRegex(VerificationError, "autosave corruption guard"):
            verify_resilience(sources)

    def test_rejects_multiple_document_picker_bypassing_retained_import(self) -> None:
        sources = valid_sources()
        sources["MainActivity.kt"] = sources["MainActivity.kt"].replace(
            "projectTransferModel.importModels(uris)",
            "import selected documents in the Activity",
        )
        with self.assertRaisesRegex(VerificationError, "retained project edit dispatch"):
            verify_resilience(sources)

    def test_rejects_external_model_open_bypassing_retained_import(self) -> None:
        sources = valid_sources()
        sources["MainActivity.kt"] = sources["MainActivity.kt"].replace(
            "projectTransferModel.importModels(request.uris)",
            "import the external model in the Activity",
        )
        with self.assertRaisesRegex(VerificationError, "retained project edit dispatch"):
            verify_resilience(sources)

    def test_rejects_final_project_owner_leaving_native_edit_running(self) -> None:
        sources = valid_sources()
        sources["ProjectTransfer.kt"] = sources["ProjectTransfer.kt"].replace(
            "SlicerProcessClient.cancelProjectRequestAsync(activeEdit.requestId)",
            "leave final native edit running",
        )
        with self.assertRaisesRegex(VerificationError, "autosave corruption guard"):
            verify_resilience(sources)

    def test_rejects_final_project_owner_accepting_late_edit_success(self) -> None:
        sources = valid_sources()
        sources["ProjectTransfer.kt"] = sources["ProjectTransfer.kt"].replace(
            "mutableState.value.withEditCancellationRequested(activeEdit.id)",
            "leave final edit state active",
        )
        with self.assertRaisesRegex(VerificationError, "autosave corruption guard"):
            verify_resilience(sources)

    def test_rejects_model_import_without_provider_open_cancellation(self) -> None:
        sources = valid_sources()
        sources["OrcaModelImport.kt"] = sources["OrcaModelImport.kt"].replace(
            'provider.openAssetFile(uri, "r", cancellation.providerSignal)',
            'provider.openAssetFile(uri, "r", null)',
        )
        with self.assertRaisesRegex(VerificationError, "model provider cancellation"):
            verify_resilience(sources)

    def test_rejects_model_import_without_bound_input_stream(self) -> None:
        sources = valid_sources()
        sources["OrcaModelImport.kt"] = sources["OrcaModelImport.kt"].replace(
            "cancellation.attachInput(input)", "leave model input unbound"
        )
        with self.assertRaisesRegex(VerificationError, "model provider cancellation"):
            verify_resilience(sources)

    def test_rejects_model_import_using_uncancelable_resolver_stream(self) -> None:
        sources = valid_sources()
        sources["OrcaModelImport.kt"] += " contentResolver.openInputStream(uri)"
        with self.assertRaisesRegex(VerificationError, "bypasses provider-open"):
            verify_resilience(sources)

    def test_rejects_model_import_completion_racing_the_main_ui_consumer(self) -> None:
        sources = valid_sources()
        sources["ProjectEditCancellationInstrumentedTest.kt"] = sources[
            "ProjectEditCancellationInstrumentedTest.kt"
        ].replace("AccessibilityHarnessActivity::class.java", "MainActivity::class.java")
        with self.assertRaisesRegex(VerificationError, "resilience regression"):
            verify_resilience(sources)

    def test_rejects_final_project_owner_leaving_model_provider_blocked(self) -> None:
        sources = valid_sources()
        sources["ProjectTransfer.kt"] = sources["ProjectTransfer.kt"].replace(
            "cleanup.modelImport?.cancellation?.cancel()",
            "leave final model provider blocked",
        )
        with self.assertRaisesRegex(VerificationError, "autosave corruption guard"):
            verify_resilience(sources)

    def test_rejects_project_cancellation_without_request_identity(self) -> None:
        sources = valid_sources()
        sources["SlicerProcessService.kt"] = sources["SlicerProcessService.kt"].replace(
            "activeRequestId.get() != requestId", "activeRequestId.get() == null"
        )
        with self.assertRaisesRegex(VerificationError, "request-scoped"):
            verify_resilience(sources)

    def test_rejects_project_cancellation_without_reachable_ui(self) -> None:
        sources = valid_sources()
        sources["WorkspaceScreen.kt"] = sources["WorkspaceScreen.kt"].replace(
            "onCancelProjectEdit: () -> Unit", "hide project cancellation"
        )
        with self.assertRaisesRegex(VerificationError, "cancellation UI"):
            verify_resilience(sources)

    def test_rejects_contributor_guidance_leaving_model_provider_blocked(self) -> None:
        sources = valid_sources()
        sources["CONTRIBUTING.md"] = sources["CONTRIBUTING.md"].replace(
            "Ordinary STL, 3MF, and OBJ import cancellation must interrupt the exact provider open",
            "Model import may leave a provider open running",
        )
        with self.assertRaisesRegex(VerificationError, "interrupt model import"):
            verify_resilience(sources)

    def test_rejects_recent_profiles_without_final_owner_flush(self) -> None:
        sources = valid_sources()
        sources["ProfileLibraryViewModel.kt"] = sources["ProfileLibraryViewModel.kt"].replace(
            "override fun onCleared()", "drop pending recents on clear"
        )
        with self.assertRaisesRegex(VerificationError, "profile library lifecycle"):
            verify_resilience(sources)

    def test_rejects_profile_import_committing_before_completion_claim(self) -> None:
        sources = valid_sources()
        sources["ProfileStore.kt"] = sources["ProfileStore.kt"].replace(
            "beforeCommit() writeRoot(merged.root)",
            "writeRoot(merged.root) beforeCommit()",
        )
        with self.assertRaisesRegex(VerificationError, "validated before its atomic commit"):
            verify_resilience(sources)

    def test_rejects_profile_bundle_that_can_include_remote_credentials(self) -> None:
        sources = valid_sources()
        sources["ProfileBundle.kt"] += " credentialCiphertext"
        with self.assertRaisesRegex(VerificationError, "out-of-scope data"):
            verify_resilience(sources)

    def test_rejects_profile_bundle_without_conflict_safe_name_resolution(self) -> None:
        sources = valid_sources()
        sources["ProfileBundle.kt"] = sources["ProfileBundle.kt"].replace(
            "resolveImportedName",
            "reuse ambiguous imported name",
        )
        with self.assertRaisesRegex(VerificationError, "profile portability boundary"):
            verify_resilience(sources)

    def test_rejects_stale_public_profile_schema_documentation(self) -> None:
        sources = valid_sources()
        sources["PROFILE_BUNDLE_FORMAT.md"] = sources["PROFILE_BUNDLE_FORMAT.md"].replace(
            '"profileSchemaVersion": 107',
            '"profileSchemaVersion": 23',
        )
        with self.assertRaisesRegex(VerificationError, "public profile-bundle contract"):
            verify_resilience(sources)

    def test_rejects_profile_transfer_without_provider_cancellation(self) -> None:
        sources = valid_sources()
        sources["ProfileLibraryViewModel.kt"] = sources["ProfileLibraryViewModel.kt"].replace(
            "cancellation.providerSignal",
            "uncancelable provider",
        )
        with self.assertRaisesRegex(VerificationError, "profile transfer lifecycle"):
            verify_resilience(sources)

    def test_rejects_external_profile_documents_over_network(self) -> None:
        sources = valid_sources()
        sources["ProfileOpenRequest.kt"] += " https://example.invalid/profiles.duckyprofiles"
        with self.assertRaisesRegex(VerificationError, "unsafe surface"):
            verify_resilience(sources)

    def test_rejects_shared_profile_without_single_stream_parser(self) -> None:
        sources = valid_sources()
        sources["ProfileOpenRequest.kt"] = sources["ProfileOpenRequest.kt"].replace(
            "sharedDocumentUriOrNull(intent)", "intent.data",
        )
        with self.assertRaisesRegex(VerificationError, "external profile-document boundary"):
            verify_resilience(sources)

    def test_rejects_external_profile_request_consumed_without_operation_identity(self) -> None:
        sources = valid_sources()
        sources["MainActivity.kt"] = sources["MainActivity.kt"].replace(
            "request.startedOperationId == completed.id",
            "request.uri != null",
        )
        with self.assertRaisesRegex(VerificationError, "external profile Activity contract"):
            verify_resilience(sources)

    def test_rejects_external_profile_consumer_not_bound_to_the_retained_owner(self) -> None:
        sources = valid_sources()
        sources["MainActivity.kt"] = sources["MainActivity.kt"].replace(
            "onExternalConsumed = onExternalProfileRequestConsumed",
            "onExternalConsumed = { _, _ -> true }",
        )
        with self.assertRaisesRegex(VerificationError, "external profile Activity contract"):
            verify_resilience(sources)

    def test_rejects_profile_share_write_grants(self) -> None:
        sources = valid_sources()
        sources["ProfileBundleShare.kt"] += " FLAG_GRANT_WRITE_URI_PERMISSION"
        with self.assertRaisesRegex(VerificationError, "read-only access"):
            verify_resilience(sources)

    def test_rejects_broad_profile_share_path(self) -> None:
        sources = valid_sources()
        sources["profile_bundle_share_paths.xml"] = (
            '<paths><files-path name="all-files" path="." /></paths>'
        )
        with self.assertRaisesRegex(VerificationError, "expose only profile-shares"):
            verify_resilience(sources)

    def test_rejects_profile_share_without_exact_operation_binding(self) -> None:
        sources = valid_sources()
        sources["ProfileBundleShareActions.kt"] = sources[
            "ProfileBundleShareActions.kt"
        ].replace("completed.id != pendingOperationId", "completed.id != 0")
        with self.assertRaisesRegex(VerificationError, "profile share lifecycle"):
            verify_resilience(sources)

    def test_rejects_profile_format_without_private_data_boundary(self) -> None:
        sources = valid_sources()
        sources["PROFILE_BUNDLE_FORMAT.md"] = sources["PROFILE_BUNDLE_FORMAT.md"].replace(
            "does not contain projects",
            "may contain projects",
        )
        with self.assertRaisesRegex(VerificationError, "public profile-bundle contract"):
            verify_resilience(sources)

    def test_rejects_background_transition_without_recent_profile_flush(self) -> None:
        sources = valid_sources()
        sources["MainActivity.kt"] = sources["MainActivity.kt"].replace(
            "profileLibraryModel.flushRecentPersistence()", "leave recent save pending"
        )
        with self.assertRaisesRegex(VerificationError, "profile library Activity-recreation"):
            verify_resilience(sources)

    def test_rejects_ui_disposal_canceling_retained_native_work(self) -> None:
        sources = valid_sources()
        sources["MainActivity.kt"] += " SlicerProcessClient.cancelActiveSliceAsync()"
        with self.assertRaisesRegex(VerificationError, "Activity composition"):
            verify_resilience(sources)

    def test_rejects_contributor_guidance_allowing_stale_slice_cancellation(self) -> None:
        sources = valid_sources()
        sources["CONTRIBUTING.md"] = sources["CONTRIBUTING.md"].replace(
            "idle or stale slice owner must never cancel a later",
            "stale owners may cancel later work",
        )
        with self.assertRaisesRegex(VerificationError, "foreground cancellation"):
            verify_resilience(sources)

    def test_rejects_async_settings_write_without_commit_result(self) -> None:
        sources = valid_sources()
        sources["AppSettings.kt"] = sources["AppSettings.kt"].replace(".commit()", ".apply()")
        with self.assertRaisesRegex(VerificationError, "durable commit"):
            verify_resilience(sources)

    def test_rejects_remote_operations_without_artifact_revision(self) -> None:
        sources = valid_sources()
        sources["RemoteOperationViewModel.kt"] = sources["RemoteOperationViewModel.kt"].replace(
            "activeArtifactRevision", "unboundArtifactRevision"
        )
        with self.assertRaisesRegex(VerificationError, "lifecycle contract"):
            verify_resilience(sources)

    def test_rejects_remote_work_without_request_scoped_socket_cancellation(self) -> None:
        sources = valid_sources()
        sources["RemoteDevice.kt"] = sources["RemoteDevice.kt"].replace(
            "connection?.disconnect()", "leave stale upload connected"
        )
        with self.assertRaisesRegex(VerificationError, "remote input containment"):
            verify_resilience(sources)

    def test_rejects_remote_request_cancellation_without_reachable_ui(self) -> None:
        sources = valid_sources()
        sources["DeviceSheet.kt"] = sources["DeviceSheet.kt"].replace(
            "onCancelRequest: () -> Unit", "hide remote cancellation"
        )
        with self.assertRaisesRegex(VerificationError, "cancellation UI"):
            verify_resilience(sources)

    def test_rejects_missing_final_owner_remote_disconnect_regression(self) -> None:
        sources = valid_sources()
        sources["RemoteDeviceInstrumentedTest.kt"] = sources[
            "RemoteDeviceInstrumentedTest.kt"
        ].replace(
            "finalRemoteOwnerDisconnectsBlockedCommand",
            "leave blocked command alive",
        )
        with self.assertRaisesRegex(VerificationError, "resilience regression"):
            verify_resilience(sources)

    def test_rejects_printer_selection_during_remote_operation(self) -> None:
        sources = valid_sources()
        sources["DeviceSheet.kt"] = sources["DeviceSheet.kt"].replace(
            "enabled = !busy", "enabled = true"
        )
        with self.assertRaisesRegex(VerificationError, "selection remains enabled"):
            verify_resilience(sources)

    def test_rejects_cleartext_connection_without_dns_pinning(self) -> None:
        sources = valid_sources()
        sources["RemoteDevice.kt"] = sources["RemoteDevice.kt"].replace(
            "url.openConnection(Proxy.NO_PROXY)",
            "url.openConnection()",
        )
        with self.assertRaisesRegex(VerificationError, "remote input containment"):
            verify_resilience(sources)

    def test_rejects_metadata_commit_before_credential_staging(self) -> None:
        sources = valid_sources()
        sources["RemoteDevice.kt"] = sources["RemoteDevice.kt"].replace(
            "stagedCredential?.let write(profiles.sortedBy",
            "write(profiles.sortedBy stagedCredential?.let",
        )
        with self.assertRaisesRegex(VerificationError, "commit out of order"):
            verify_resilience(sources)

    def test_rejects_credential_delete_before_backup_refresh(self) -> None:
        sources = valid_sources()
        sources["RemoteDevice.kt"] = sources["RemoteDevice.kt"].replace(
            'write(existing.filterNot load() check(!storageUnavailable) { "saved_data_unreadable" } '
            "removedCredentialKey?.let(secrets::remove)",
            'removedCredentialKey?.let(secrets::remove) write(existing.filterNot load() '
            'check(!storageUnavailable) { "saved_data_unreadable" }',
        )
        with self.assertRaisesRegex(VerificationError, "precedes durable metadata backup"):
            verify_resilience(sources)

    def test_rejects_credential_delete_without_post_backup_guard(self) -> None:
        sources = valid_sources()
        source = sources["RemoteDevice.kt"]
        guard = 'check(!storageUnavailable) { "saved_data_unreadable" }'
        sources["RemoteDevice.kt"] = source[: source.rfind(guard)] + source[
            source.rfind(guard) + len(guard) :
        ]
        with self.assertRaisesRegex(VerificationError, "precedes durable metadata backup"):
            verify_resilience(sources)

    def test_rejects_credential_delete_without_metadata_commit(self) -> None:
        sources = valid_sources()
        sources["RemoteDevice.kt"] = sources["RemoteDevice.kt"].replace(
            "write(existing.filterNot", "metadataCommitRemoved",
        )
        with self.assertRaisesRegex(VerificationError, "precedes durable metadata backup"):
            verify_resilience(sources)


if __name__ == "__main__":
    unittest.main()
