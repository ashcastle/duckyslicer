#!/usr/bin/env python3
"""Verify that the inherited C++ slicer is confined to a private Android process."""

from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
ANDROID_ROOT = ROOT / "android/app/src"
MANIFEST = ANDROID_ROOT / "main/AndroidManifest.xml"
MAIN_SOURCE_ROOT = ANDROID_ROOT / "main/java"
DEVICE_TEST = (
    ANDROID_ROOT
    / "androidTest/java/com/ashcastle/duckyslicer/NativeEngineInstrumentedTest.kt"
)
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
SERVICE_NAME = ".SlicerProcessService"
APPLICATION_NAME = ".DuckySlicerApplication"
DIRECT_NATIVE_CONSTRUCTION = re.compile(r"\bNativeLibrary\s*\(")


class VerificationError(ValueError):
    """The Android native-process isolation contract was weakened."""


def verify_manifest(source: str) -> None:
    try:
        root = ET.fromstring(source)
    except ET.ParseError as error:
        raise VerificationError(f"invalid Android manifest: {error}") from error
    application = root.find("application")
    if application is None:
        raise VerificationError("Android manifest has no application")
    if application.get(f"{ANDROID_NS}name") != APPLICATION_NAME:
        raise VerificationError("the process-aware Application must remain installed")
    services = [
        service
        for service in application.findall("service")
        if service.get(f"{ANDROID_NS}name") == SERVICE_NAME
    ]
    if len(services) != 1:
        raise VerificationError("expected exactly one slicer process service")
    service = services[0]
    if service.get(f"{ANDROID_NS}exported") != "false":
        raise VerificationError("slicer process service must remain non-exported")
    if service.get(f"{ANDROID_NS}process") != ":slicer":
        raise VerificationError("slicer process service must remain in :slicer")
    if service.find("intent-filter") is not None:
        raise VerificationError("slicer process service must not expose an intent filter")


def verify_sources(sources: dict[str, str], device_test: str) -> int:
    service_path = "com/ashcastle/duckyslicer/SlicerProcessService.kt"
    service = sources.get(service_path)
    orchestrator = sources.get("com/ashcastle/duckyslicer/OnDeviceSlicer.kt")
    if service is None or orchestrator is None:
        raise VerificationError("required slicer process sources are missing")

    direct_calls = []
    for path, source in sources.items():
        if path == "com/u1/slicer/NativeLibrary.kt":
            continue
        direct_calls.extend((path, match.start()) for match in DIRECT_NATIVE_CONSTRUCTION.finditer(source))
    if len(direct_calls) != 1 or direct_calls[0][0] != service_path:
        raise VerificationError(
            "NativeLibrary construction must occur exactly once inside SlicerProcessService; "
            f"found={[path for path, _ in direct_calls]}"
        )
    if "SlicerProcessClient.slice(" not in orchestrator:
        raise VerificationError("OnDeviceSlicer must delegate through the isolated process client")
    required_service_markers = {
        "bound service connection": "bindService(",
        "Binder death handling": "IBinder.DeathRecipient",
        "private-path validation": "Model is outside private storage",
        "bounded settings payload": "MAX_OPTIONS_BYTES",
        "atomic G-code durability": "output.fd.sync()",
        "bounded output retention": "MAX_RETAINED_OUTPUTS",
        "debug worker termination": "MESSAGE_TERMINATE_FOR_TEST",
    }
    missing = [description for description, marker in required_service_markers.items() if marker not in service]
    if missing:
        raise VerificationError(f"slicer service safety markers are missing: {missing}")
    if "nativeSlicerWorkerCrashLeavesAppAliveAndRestartsCleanly" not in device_test:
        raise VerificationError("ARM64 worker-crash recovery regression is missing")
    if "imperfectMeshCorpusIsRepairableOrFailsWithoutKillingTheApp" not in device_test:
        raise VerificationError("ARM64 imperfect-mesh recovery corpus is missing")
    return len(direct_calls)


def read_sources() -> dict[str, str]:
    return {
        str(path.relative_to(MAIN_SOURCE_ROOT)): path.read_text(encoding="utf-8")
        for path in MAIN_SOURCE_ROOT.rglob("*.kt")
    }


def main() -> None:
    try:
        verify_manifest(MANIFEST.read_text(encoding="utf-8"))
        native_call_count = verify_sources(
            read_sources(),
            DEVICE_TEST.read_text(encoding="utf-8"),
        )
    except (OSError, VerificationError) as error:
        raise SystemExit(f"Android isolation verification failed: {error}") from error
    print(
        "Verified Android slicer isolation: private :slicer service, "
        f"{native_call_count} confined NativeLibrary construction, crash recovery regression"
    )


if __name__ == "__main__":
    main()
