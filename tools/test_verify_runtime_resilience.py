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
            "projectStore.save(document.history.current, document.sliceOptions) "
            "pendingPersistence?.join() fun flushPersistence() "
            "override fun onCleared() hasPersistableChanges "
            "fun autoLaySelectedModel() fun arrangeProjectObjects() "
            "fun splitSelectedModel() "
            "fun cutSelectedModel(heightRatio: Float, placeOnCut: Boolean) "
            "fun createPrimitive( fun importModels(uri: Uri) startEditLocked "
            "withCompletedEdit deleteInstalledModels"
        ),
        "ProfileStore.kt": "DurableJsonFile(",
        "ProfileLibraryViewModel.kt": (
            "class ProfileLibraryViewModel(application: Application) : AndroidViewModel(application) "
            "viewModelScope.launch private val profileStore = ProfileStore(application) "
            "private val recentStore = ProfileRecentStore(application) fun savePrinter( "
            "fun saveFilament( fun saveSlicing( fun recordSelection( activeOperationId "
            "optionsForSession val recentsRevision: Long "
            "val persistedRecentsRevision: Long fun flushRecentPersistence() "
            "override fun onCleared() hasDirtyRecents RECENT_PROFILE_SAVE_DEBOUNCE_MILLIS"
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
            "ViewModelProvider(this)[ProfileLibraryViewModel::class.java] "
            "profileLibraryModel.state.collectAsStateWithLifecycle() "
            "completion.optionsForSession(session.sessionRevision) "
            "profileLibraryModel.recordSelection(options) "
            "profileLibraryModel.flushRecentPersistence() "
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
            "projectTransferModel.importModels(uri)"
        ),
        "DeviceSheet.kt": ".selectable( selected = true enabled = !busy ),",
        "DurableJsonFileTest.kt": (
            "validPrimaryCreatesBackupAndCorruptionRecoversIt "
            "unreadableGenerationsAreNeverOverwritten"
        ),
        "ProjectStoreTest.kt": "unreadablePrimaryAndBackupBlockAutosave",
        "ProfileStoreMigrationTest.kt": "unreadableOrFutureProfilesAreNotOverwritten",
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
        ),
        "RemoteOperationViewModelTest.kt": (
            "resultsAreVisibleOnlyForTheirOriginatingProfile "
            "staleCompletionCannotFinishANewerOperation "
            "invalidatedUploadCannotBecomePrintable "
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
            "remoteRefreshSurvivesActivityRecreationAndRejectsDuplicateWork "
            "remoteProfileSaveAndSelectionSurviveActivityRecreation "
            "remoteDeviceMetadataRecoversFromLastKnownGoodBackup "
            "cleartextHostnameRequestUsesOneValidatedPinnedAddress"
        ),
        "ProfileLibraryInstrumentedTest.kt": (
            "profileSaveAndRecentSelectionSurviveImmediateActivityRecreation "
            "clearingRetainedOwnerFlushesRecentProfilesBeforeDebounce "
            "lateProfileSaveCannotReplaceNewerProjectSettings "
            "The profile save must be active before recreation "
            "The profile save must be active before the newer edit"
        ),
        "AppSettingsLifecycleInstrumentedTest.kt": (
            "latestUnsavedSettingsSurviveImmediateActivityRecreationAndPersist "
            "backgroundingFlushesLatestSettingsBeforeDebounce "
            "Recreate before the 350 ms persistence debounce can run "
            "onStop must replace the pending 350 ms write with an immediate one"
        ),
        "ProjectArchiveIntentInstrumentedTest.kt": (
            "automaticLayKeepsOneRetainedOperationAcrossActivityRecreation "
            "clearingRetainedOwnerFlushesProjectBeforeDebounce "
            "assertSame( ProjectEditKind.AUTO_LAY waitForPersistedTransform"
        ),
        "CONTRIBUTING.md": (
            "pin the connection target and bypass system proxies "
            "bind a replacement printer credential generation "
            "Bind every remote status, upload-progress, and command result "
            "Remote operations and their busy state must "
            "must never become eligible for Start Print "
            "must share that same retained "
            "Profile catalog loading, recent selections, and user-profile saves must share one "
            "only in the project session revision that "
            "Track recent-selection persistence revisions dirty `Recent` list "
            "app enters the background owner is finally cleared "
            "Live app settings and their debounced persistence must share one "
            "Flush the latest dirty settings "
            "Fixed support-event writers must serialize through the process-wide journal boundary "
            "Flush the latest dirty revision app enters the background owner is finally cleared "
            "archive import commits cancel and join any older metadata write "
            "Model import, primitive creation, automatic lay, arrangement, split, and cut must run "
            "UI disposal must not issue a process-wide slicer cancellation"
        ),
        "SECURITY.md": (
            "every current DNS answer DNS rebinding bypass system proxies "
            "platform certificate verifier remains authoritative "
            "Credential updates are staged under a new generation "
            "never carried to a changed connection type or address"
        ),
        "strings.xml": "saved_data_unavailable settings_save_error",
        "strings-ko.xml": "saved_data_unavailable settings_save_error",
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

    def test_rejects_recent_profiles_without_final_owner_flush(self) -> None:
        sources = valid_sources()
        sources["ProfileLibraryViewModel.kt"] = sources["ProfileLibraryViewModel.kt"].replace(
            "override fun onCleared()", "drop pending recents on clear"
        )
        with self.assertRaisesRegex(VerificationError, "profile library lifecycle"):
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
