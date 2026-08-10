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
            "val persistenceBlocked: Boolean restored.storageUnavailable "
            "!current.persistenceBlocked "
            "projectStore.save(document.history.current, document.sliceOptions)"
        ),
        "ProfileStore.kt": "DurableJsonFile(",
        "ProfileLibraryViewModel.kt": (
            "class ProfileLibraryViewModel(application: Application) : AndroidViewModel(application) "
            "viewModelScope.launch private val profileStore = ProfileStore(application) "
            "private val recentStore = ProfileRecentStore(application) fun savePrinter( "
            "fun saveFilament( fun saveSlicing( fun recordSelection( activeOperationId "
            "optionsForSession RECENT_PROFILE_SAVE_DEBOUNCE_MILLIS"
        ),
        "AppSettings.kt": (
            "fun AppSettings.normalized() fun save(settings: AppSettings): Boolean .commit()"
        ),
        "AppSettingsViewModel.kt": (
            "class AppSettingsViewModel(application: Application) : AndroidViewModel(application) "
            "viewModelScope.launch withUpdatedSettings SETTINGS_SAVE_DEBOUNCE_MILLIS "
            "current.revision != revision override fun onCleared() "
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
            "ViewModelProvider(this)[AppSettingsViewModel::class.java] "
            "appSettingsModel.state.collectAsStateWithLifecycle() "
            "appSettingsModel.updateSettings(next)"
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
            "lateProfileSaveCannotReplaceNewerProjectSettings "
            "The profile save must be active before recreation "
            "The profile save must be active before the newer edit"
        ),
        "AppSettingsLifecycleInstrumentedTest.kt": (
            "latestUnsavedSettingsSurviveImmediateActivityRecreationAndPersist "
            "Recreate before the 350 ms persistence debounce can run"
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
            "Live app settings and their debounced persistence must share one"
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
