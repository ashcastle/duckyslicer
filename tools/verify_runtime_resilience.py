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
        "ProfileStore.kt",
        "RemoteDevice.kt",
        "MainActivity.kt",
        "DeviceSheet.kt",
        "DurableJsonFileTest.kt",
        "ProjectStoreTest.kt",
        "ProfileStoreMigrationTest.kt",
        "RemoteDeviceClientTest.kt",
        "RemoteDeviceStoreTest.kt",
        "RemoteDeviceInstrumentedTest.kt",
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
    main = sources["MainActivity.kt"]
    for marker in ("projectPersistenceBlocked", "saved_data_unavailable"):
        if marker not in main:
            raise VerificationError(f"project autosave corruption guard is missing: {marker}")

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
    ):
        if marker not in remote:
            raise VerificationError(f"remote input containment is missing: {marker}")
    if "bufferedReader()?.use { it.readText() }" in remote:
        raise VerificationError("remote response uses an unbounded text read")
    if "remoteResultBelongsToSelection" not in remote:
        raise VerificationError("remote operation results are not bound to their printer profile")

    main = sources["MainActivity.kt"]
    if main.count("remoteResultBelongsToSelection") < 7:
        raise VerificationError("remote result and progress callbacks can escape profile binding")
    if "remoteUploadProgress = null\n            remoteMessage = null" not in main:
        raise VerificationError("printer selection does not clear operation progress")

    device_sheet = sources["DeviceSheet.kt"]
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
            "remoteDeviceMetadataRecoversFromLastKnownGoodBackup",
            "cleartextHostnameRequestUsesOneValidatedPinnedAddress",
        ),
    }
    for source_name, markers in test_markers.items():
        for marker in markers:
            if marker not in sources[source_name]:
                raise VerificationError(f"resilience regression is missing: {marker}")

    for strings in ("strings.xml", "strings-ko.xml"):
        if "saved_data_unavailable" not in sources[strings]:
            raise VerificationError(f"saved-data recovery copy is missing from {strings}")

    if "pin the connection target and bypass system proxies" not in sources["CONTRIBUTING.md"]:
        raise VerificationError("contributor guidance does not preserve cleartext DNS pinning")
    if "bind a replacement printer credential generation" not in sources["CONTRIBUTING.md"]:
        raise VerificationError("contributor guidance does not preserve credential generations")
    if "Bind every remote status, upload-progress, and command result" not in sources[
        "CONTRIBUTING.md"
    ]:
        raise VerificationError("contributor guidance does not preserve printer result binding")
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
        "ProfileStore.kt": (main / "ProfileStore.kt").read_text(encoding="utf-8"),
        "RemoteDevice.kt": (main / "RemoteDevice.kt").read_text(encoding="utf-8"),
        "MainActivity.kt": (main / "MainActivity.kt").read_text(encoding="utf-8"),
        "DeviceSheet.kt": (main / "DeviceSheet.kt").read_text(encoding="utf-8"),
        "DurableJsonFileTest.kt": (tests / "DurableJsonFileTest.kt").read_text(encoding="utf-8"),
        "ProjectStoreTest.kt": (tests / "ProjectStoreTest.kt").read_text(encoding="utf-8"),
        "ProfileStoreMigrationTest.kt": (tests / "ProfileStoreMigrationTest.kt").read_text(
            encoding="utf-8"
        ),
        "RemoteDeviceClientTest.kt": (tests / "RemoteDeviceClientTest.kt").read_text(
            encoding="utf-8"
        ),
        "RemoteDeviceStoreTest.kt": (tests / "RemoteDeviceStoreTest.kt").read_text(
            encoding="utf-8"
        ),
        "RemoteDeviceInstrumentedTest.kt": (
            device_tests / "RemoteDeviceInstrumentedTest.kt"
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
    print("Verified durable project/profile/device state and bounded LAN-printer inputs")


if __name__ == "__main__":
    main()
