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
        "DurableJsonFileTest.kt",
        "ProjectStoreTest.kt",
        "ProfileStoreMigrationTest.kt",
        "RemoteDeviceClientTest.kt",
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

    test_markers = {
        "DurableJsonFileTest.kt": (
            "validPrimaryCreatesBackupAndCorruptionRecoversIt",
            "unreadableGenerationsAreNeverOverwritten",
        ),
        "ProjectStoreTest.kt": ("unreadablePrimaryAndBackupBlockAutosave",),
        "ProfileStoreMigrationTest.kt": ("unreadableOrFutureProfilesAreNotOverwritten",),
        "RemoteDeviceClientTest.kt": (
            "redirectsOversizedResponsesAndDeepJsonFailClosed",
            "unsafeServerUploadPathIsRejected",
            "cleartextDnsResultsAreValidatedAndPinnedBeforeCredentialsAreAttached",
            "cleartextHostnameRequestUsesThePinnedResolverAddress",
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
    security = sources["SECURITY.md"]
    for marker in (
        "every current DNS answer",
        "DNS rebinding",
        "bypass system proxies",
        "platform certificate verifier remains authoritative",
    ):
        if marker not in security:
            raise VerificationError(f"security guidance is missing DNS containment: {marker}")


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
        "DurableJsonFileTest.kt": (tests / "DurableJsonFileTest.kt").read_text(encoding="utf-8"),
        "ProjectStoreTest.kt": (tests / "ProjectStoreTest.kt").read_text(encoding="utf-8"),
        "ProfileStoreMigrationTest.kt": (tests / "ProfileStoreMigrationTest.kt").read_text(
            encoding="utf-8"
        ),
        "RemoteDeviceClientTest.kt": (tests / "RemoteDeviceClientTest.kt").read_text(
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
