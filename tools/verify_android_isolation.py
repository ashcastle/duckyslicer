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
LIFECYCLE_DEVICE_TEST = (
    ANDROID_ROOT
    / "androidTest/java/com/ashcastle/duckyslicer/SliceLifecycleInstrumentedTest.kt"
)
PROCESS_REATTACHMENT_HARNESS = ROOT / "tools/run_process_reattachment_test.py"
DEBUG_RECOVERY_ACTIVITY = (
    ANDROID_ROOT
    / "debug/java/com/ashcastle/duckyslicer/ProcessRecoveryHarnessActivity.kt"
)
DEBUG_MANIFEST = ANDROID_ROOT / "debug/AndroidManifest.xml"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
SERVICE_NAME = ".SlicerProcessService"
APPLICATION_NAME = ".DuckySlicerApplication"
FOREGROUND_SERVICE_PERMISSIONS = {
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
}
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
    permissions = {
        permission.get(f"{ANDROID_NS}name")
        for permission in root.findall("uses-permission")
    }
    missing_permissions = sorted(FOREGROUND_SERVICE_PERMISSIONS - permissions)
    if missing_permissions:
        raise VerificationError(
            f"foreground slicer permissions are missing: {missing_permissions}"
        )
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
    if service.get(f"{ANDROID_NS}foregroundServiceType") != "dataSync":
        raise VerificationError("slicer process service must declare the dataSync type")
    if service.find("intent-filter") is not None:
        raise VerificationError("slicer process service must not expose an intent filter")


def verify_sources(sources: dict[str, str], device_test: str) -> int:
    service_path = "com/ashcastle/duckyslicer/SlicerProcessService.kt"
    service = sources.get(service_path)
    artifacts = sources.get("com/ashcastle/duckyslicer/SliceArtifactStore.kt")
    orchestrator = sources.get("com/ashcastle/duckyslicer/OnDeviceSlicer.kt")
    main = sources.get("com/ashcastle/duckyslicer/MainActivity.kt")
    lifecycle = sources.get("com/ashcastle/duckyslicer/SliceOperationViewModel.kt")
    foreground_store = sources.get("com/ashcastle/duckyslicer/ForegroundSliceStore.kt")
    workspace = sources.get("com/ashcastle/duckyslicer/WorkspaceScreen.kt")
    if any(
        source is None
        for source in (
            service,
            artifacts,
            orchestrator,
            main,
            lifecycle,
            foreground_store,
            workspace,
        )
    ):
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
    for marker in (
        "class SliceOperationViewModel : ViewModel()",
        "viewModelScope.launch",
        "SlicerProcessClient.beginUserSlice(plateId)",
        "SlicerProcessClient.recoverUserSlice()",
        "SlicerProcessClient.awaitRecoveredSlice(",
        "Recovered foreground slice",
        "foregroundSession.cancellationRequested()",
        "foregroundSession.close()",
        "operationCancellation.get()",
        "operationCancellation.set(true)",
        "private val operationSession = AtomicReference<ForegroundSliceSession?>(null)",
        "SlicerProcessClient.cancelUserSliceAsync(session)",
        "override fun onCleared()",
        "operationSession.get()?.let(SlicerProcessClient::cancelUserSliceAsync)",
    ):
        if marker not in lifecycle:
            raise VerificationError(f"retained slice lifecycle is missing: {marker}")
    session_release = lifecycle.find("foregroundSession.close()")
    job_release = lifecycle.find("operationJob.set(null)", session_release)
    idle_publication = lifecycle.find(
        "completedState?.let { mutableState.value = it }",
        job_release,
    )
    if not 0 <= session_release < job_release < idle_publication:
        raise VerificationError(
            "foreground slice cleanup must release the session and job before publishing idle state"
        )
    if "ViewModelProvider(this)[SliceOperationViewModel::class.java]" not in main:
        raise VerificationError("the Activity must retain active slicing across configuration changes")
    if "SlicerProcessClient.cancelUserSliceAsync(" in main:
        raise VerificationError(
            "the Activity must not cancel retained native work during UI disposal"
        )
    for forbidden in ("fun cancelActiveSlice(", "fun cancelActiveSliceAsync("):
        if forbidden in service or forbidden in lifecycle:
            raise VerificationError("slice cancellation must not target a global active request")
    if "onCancelSlice" not in workspace or "canceling_slice" not in workspace:
        raise VerificationError("the Slice workspace must expose cancellation progress")
    for marker in (
        "cancellationRequested: () -> Boolean",
        "if (cancellationRequested()) throw SlicingCancelledException()",
    ):
        if marker not in orchestrator:
            raise VerificationError(f"pre-service slice cancellation is missing: {marker}")
    required_service_markers = {
        "bound service connection": "bindService(",
        "Binder death handling": "IBinder.DeathRecipient",
        "private-path validation": "Model is outside private storage",
        "bounded settings payload": "MAX_OPTIONS_BYTES",
        "debug worker termination": "MESSAGE_TERMINATE_FOR_TEST",
        "dedicated Orca thread": "HandlerThread(\"DuckySlicer Orca work\")",
        "request-scoped cancellation": "MESSAGE_CANCEL",
        "retained-session cancellation":
            "fun cancelUserSliceAsync(session: ForegroundSliceSession)",
        "exact foreground request identity": "val active = activeRequestId.get() == requestId",
        "durable retained-session cancellation": "session::requestCancellation",
        "pre-bind cancellation race containment": "if (cancellationRequested())",
        "cancellation process containment": "Process.killProcess(Process.myPid())",
        "abandoned-client containment": "override fun onUnbind",
        "user-initiated foreground start": "startForegroundService(",
        "data-sync foreground type": "FOREGROUND_SERVICE_TYPE_DATA_SYNC",
        "foreground timeout containment": "override fun onTimeout(",
        "notification cancellation": "ACTION_CANCEL_SLICE",
        "ordered foreground completion": "ACTION_FINISH_SLICE",
        "pre-bind notification cancellation": "ForegroundSliceSession.markCanceled(",
        "post-bind notification cancellation race containment":
            "ForegroundSliceSession.wasCanceled(this, requestId)",
        "foreground observer reattachment": "MESSAGE_ATTACH",
        "completed result handoff": "completedForegroundResult",
        "foreground unbind survival":
            "foregroundRequestId.get() != abandonedRequestId",
    }
    missing = [description for description, marker in required_service_markers.items() if marker not in service]
    if missing:
        raise VerificationError(f"slicer service safety markers are missing: {missing}")
    for description, marker in {
        "explicit notification content intent": "Intent(this, MainActivity::class.java)",
        "filter-matched notification content action": "action = Intent.ACTION_MAIN",
        "filter-matched notification content category":
            "addCategory(Intent.CATEGORY_LAUNCHER)",
        "explicit notification cancel intent":
            "Intent(context, SlicerProcessService::class.java)",
        "request-scoped notification cancel identity": "data = Uri.Builder()",
    }.items():
        if marker not in service:
            raise VerificationError(f"notification PendingIntent is unsafe: {description}")
    if (
        service.count("PendingIntent.FLAG_IMMUTABLE") < 2
        or "PendingIntent.FLAG_MUTABLE" in service
        or "PendingIntent.FLAG_UPDATE_CURRENT" in service
    ):
        raise VerificationError(
            "notification PendingIntents must remain explicit, request-scoped, and immutable"
        )
    for marker in ("output.fd.sync()", "MAXIMUM_RETAINED_OUTPUTS", "MAXIMUM_RETAINED_BYTES"):
        if marker not in artifacts:
            raise VerificationError(f"slicer artifact safety marker is missing: {marker}")
    for marker in (
        "ForegroundSlicePhase.ACTIVE",
        "ForegroundSlicePhase.COMPLETED",
        "StandardCopyOption.ATOMIC_MOVE",
        "output.fd.sync()",
        "outcome.isRestorableFrom(context.filesDir)",
        "plateId",
        "RECORD_VERSION = 2",
        "synchronized(localLock)",
        "channel.lock()",
        "loadUnlocked(context)",
    ):
        if marker not in foreground_store:
            raise VerificationError(f"foreground slice checkpoint is missing: {marker}")
    if "nativeSlicerWorkerCrashLeavesAppAliveAndRestartsCleanly" not in device_test:
        raise VerificationError("ARM64 worker-crash recovery regression is missing")
    if "imperfectMeshCorpusIsRepairableOrFailsWithoutKillingTheApp" not in device_test:
        raise VerificationError("ARM64 imperfect-mesh recovery corpus is missing")
    if "activeSliceCancellationKeepsServiceResponsiveAndRestartsCleanly" not in device_test:
        raise VerificationError("ARM64 active-slice cancellation regression is missing")
    if (
        "clearingIdleSliceOwnerAndStaleSessionCannotCancelLaterNativeRequest" not in device_test
        or "A stale foreground session must not cancel another request" not in device_test
        or "Clearing an idle slice owner canceled later native work" not in device_test
    ):
        raise VerificationError("ARM64 stale-slice-owner cancellation regression is missing")
    if (
        "clearingFinalActiveSliceOwnerCancelsItsExactSessionAndRecovers" not in device_test
        or "Final owner cancellation left a recoverable foreground session" not in device_test
        or "A clean slice must succeed after final-owner cancellation" not in device_test
    ):
        raise VerificationError("ARM64 final-slice-owner cancellation regression is missing")
    if (
        "activeSliceSurvivesActivityRecreationAndCompletes" not in device_test
        or "Stopping the Activity must not cancel the slice" not in device_test
        or "The slicer service must be foreground while a stopped Activity is slicing"
        not in device_test
        or "A slice that finishes during the background transition must retain its result"
        not in device_test
        or "Configuration recreation must retain the operation" not in device_test
        or "An idle canceled slice must release ownership before allowing restart"
        not in device_test
    ):
        raise VerificationError("ARM64 configuration/background slice regression is missing")
    if (
        "projectTransfer.state.value.history.current.selectedPlateId" not in device_test
        or "assertEquals(selectedPlateId, initial.state.value.plateId)" not in device_test
        or "assertEquals(selectedPlateId, ForegroundSliceStore.load(context)?.plateId)"
        not in device_test
        or "assertEquals(livePlateId, completed.plateId)" not in device_test
    ):
        raise VerificationError("ARM64 foreground slice plate ownership regression is missing")
    return len(direct_calls)


def verify_process_reattachment_harness(source: str) -> None:
    for marker in (
        'page_size != "16384"',
        '"android.intent.action.MAIN"',
        '"android.intent.category.LAUNCHER"',
        'run_as(serial, "kill", "-9", str(old_ui_pid))',
        "surviving_service_pid != old_service_pid",
        "current_boot_id != boot_id",
        '"Recovered foreground slice"',
        "SESSION_PATH",
    ):
        if marker not in source:
            raise VerificationError(f"local process-reattachment harness is missing: {marker}")


def verify_debug_recovery_harness(source: str, manifest: str) -> None:
    for marker in (
        "class ProcessRecoveryHarnessActivity",
        "ForegroundSlicePhase.ACTIVE",
        "Process.myPid()",
        "output.fd.sync()",
        "Process recovery slice finished too early",
    ):
        if marker not in source:
            raise VerificationError(f"Debug process-recovery activity is missing: {marker}")
    for marker in (
        'android:name=".ProcessRecoveryHarnessActivity"',
        'android:exported="true"',
        'android:permission="android.permission.DUMP"',
    ):
        if marker not in manifest:
            raise VerificationError(f"Debug process-recovery manifest is missing: {marker}")


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
            DEVICE_TEST.read_text(encoding="utf-8")
            + LIFECYCLE_DEVICE_TEST.read_text(encoding="utf-8"),
        )
        verify_process_reattachment_harness(
            PROCESS_REATTACHMENT_HARNESS.read_text(encoding="utf-8")
        )
        verify_debug_recovery_harness(
            DEBUG_RECOVERY_ACTIVITY.read_text(encoding="utf-8"),
            DEBUG_MANIFEST.read_text(encoding="utf-8"),
        )
    except (OSError, VerificationError) as error:
        raise SystemExit(f"Android isolation verification failed: {error}") from error
    print(
        "Verified Android slicer isolation: private :slicer service, "
        f"{native_call_count} confined NativeLibrary construction, "
        "crash, cancellation, and UI-process reattachment regressions"
    )


if __name__ == "__main__":
    main()
