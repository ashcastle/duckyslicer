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
        "ProfileStore.kt": "DurableJsonFile(",
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
        "MainActivity.kt": (
            "projectPersistenceBlocked saved_data_unavailable "
            + "remoteResultBelongsToSelection " * 7
            + "remoteUploadProgress = null\n            remoteMessage = null"
        ),
        "DeviceSheet.kt": ".selectable( selected = true enabled = !busy ),",
        "DurableJsonFileTest.kt": (
            "validPrimaryCreatesBackupAndCorruptionRecoversIt "
            "unreadableGenerationsAreNeverOverwritten"
        ),
        "ProjectStoreTest.kt": "unreadablePrimaryAndBackupBlockAutosave",
        "ProfileStoreMigrationTest.kt": "unreadableOrFutureProfilesAreNotOverwritten",
        "RemoteDeviceClientTest.kt": (
            "remoteResultsOnlyBelongToTheirOriginatingSelection "
            "redirectsOversizedResponsesAndDeepJsonFailClosed unsafeServerUploadPathIsRejected "
            "cleartextDnsResultsAreValidatedAndPinnedBeforeCredentialsAreAttached "
            "cleartextHostnameRequestUsesThePinnedResolverAddress"
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
            "remoteDeviceMetadataRecoversFromLastKnownGoodBackup "
            "cleartextHostnameRequestUsesOneValidatedPinnedAddress"
        ),
        "CONTRIBUTING.md": (
            "pin the connection target and bypass system proxies "
            "bind a replacement printer credential generation "
            "Bind every remote status, upload-progress, and command result"
        ),
        "SECURITY.md": (
            "every current DNS answer DNS rebinding bypass system proxies "
            "platform certificate verifier remains authoritative "
            "Credential updates are staged under a new generation "
            "never carried to a changed connection type or address"
        ),
        "strings.xml": "saved_data_unavailable",
        "strings-ko.xml": "saved_data_unavailable",
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
        sources["MainActivity.kt"] = sources["MainActivity.kt"].replace(
            "projectPersistenceBlocked", ""
        )
        with self.assertRaisesRegex(VerificationError, "autosave"):
            verify_resilience(sources)

    def test_rejects_remote_results_without_profile_binding(self) -> None:
        sources = valid_sources()
        sources["MainActivity.kt"] = sources["MainActivity.kt"].replace(
            "remoteResultBelongsToSelection", "unboundRemoteResult", 1
        )
        with self.assertRaisesRegex(VerificationError, "profile binding"):
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
