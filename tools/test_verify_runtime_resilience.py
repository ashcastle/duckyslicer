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
            "endpoint.hostHeader?.let isUniqueLocalIpv6 safeRemotePath connection.disconnect()"
        ),
        "MainActivity.kt": "projectPersistenceBlocked saved_data_unavailable",
        "DurableJsonFileTest.kt": (
            "validPrimaryCreatesBackupAndCorruptionRecoversIt "
            "unreadableGenerationsAreNeverOverwritten"
        ),
        "ProjectStoreTest.kt": "unreadablePrimaryAndBackupBlockAutosave",
        "ProfileStoreMigrationTest.kt": "unreadableOrFutureProfilesAreNotOverwritten",
        "RemoteDeviceClientTest.kt": (
            "redirectsOversizedResponsesAndDeepJsonFailClosed unsafeServerUploadPathIsRejected "
            "cleartextDnsResultsAreValidatedAndPinnedBeforeCredentialsAreAttached "
            "cleartextHostnameRequestUsesThePinnedResolverAddress"
        ),
        "RemoteDeviceInstrumentedTest.kt": (
            "remoteDeviceMetadataRecoversFromLastKnownGoodBackup "
            "cleartextHostnameRequestUsesOneValidatedPinnedAddress"
        ),
        "CONTRIBUTING.md": "pin the connection target and bypass system proxies",
        "SECURITY.md": (
            "every current DNS answer DNS rebinding bypass system proxies "
            "platform certificate verifier remains authoritative"
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

    def test_rejects_cleartext_connection_without_dns_pinning(self) -> None:
        sources = valid_sources()
        sources["RemoteDevice.kt"] = sources["RemoteDevice.kt"].replace(
            "url.openConnection(Proxy.NO_PROXY)",
            "url.openConnection()",
        )
        with self.assertRaisesRegex(VerificationError, "remote input containment"):
            verify_resilience(sources)


if __name__ == "__main__":
    unittest.main()
