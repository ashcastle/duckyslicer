from __future__ import annotations

import unittest

from tools.verify_android_isolation import (
    VerificationError,
    verify_debug_recovery_harness,
    verify_manifest,
    verify_process_reattachment_harness,
    verify_sources,
)


VALID_MANIFEST = """\
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
  <application android:name=".DuckySlicerApplication">
    <service android:name=".SlicerProcessService"
             android:exported="false"
             android:foregroundServiceType="dataSync"
             android:process=":slicer" />
  </application>
</manifest>
"""

VALID_SERVICE = """
class SlicerProcessService {
  fun client() { bindService(); IBinder.DeathRecipient; }
  fun native() { NativeLibrary(); }
  val path = "Model is outside private storage"
  val max = MAX_OPTIONS_BYTES
  val terminate = MESSAGE_TERMINATE_FOR_TEST
  val worker = HandlerThread("DuckySlicer native work")
  val cancel = MESSAGE_CANCEL
  fun cancelUserSliceAsync(session: ForegroundSliceSession) {
    val requestId = session.requestId
    val active = activeRequestId.get() == requestId
    runCatching(session::requestCancellation)
  }
  val attach = MESSAGE_ATTACH
  val completed = completedForegroundResult
  fun containPreBindCancellation() { if (cancellationRequested()) return }
  fun containCancellation() { Process.killProcess(Process.myPid()) }
  fun beginForeground() { startForegroundService(); FOREGROUND_SERVICE_TYPE_DATA_SYNC }
  fun notificationCancel() {
    ACTION_CANCEL_SLICE
    ACTION_FINISH_SLICE
    ForegroundSliceSession.markCanceled()
    ForegroundSliceSession.wasCanceled(this, requestId)
  }
  fun safePendingIntents(context: Context) {
    Intent(this, MainActivity::class.java)
    action = Intent.ACTION_MAIN
    addCategory(Intent.CATEGORY_LAUNCHER)
    Intent(context, SlicerProcessService::class.java)
    data = Uri.Builder()
    PendingIntent.FLAG_IMMUTABLE
    PendingIntent.FLAG_IMMUTABLE
  }
  override fun onTimeout(startId: Int, fgsType: Int) = Unit
  override fun onUnbind() = foregroundRequestId.get() != abandonedRequestId
}
"""

VALID_FOREGROUND_STORE = """
ForegroundSlicePhase.ACTIVE
ForegroundSlicePhase.COMPLETED
StandardCopyOption.ATOMIC_MOVE
output.fd.sync()
outcome.isRestorableFrom(context.filesDir)
plateId
RECORD_VERSION = 2
synchronized(localLock)
channel.lock()
loadUnlocked(context)
"""

VALID_DEVICE_TEST = """
nativeSlicerWorkerCrashLeavesAppAliveAndRestartsCleanly
imperfectMeshCorpusIsRepairableOrFailsWithoutKillingTheApp
activeSliceCancellationKeepsServiceResponsiveAndRestartsCleanly
clearingIdleSliceOwnerAndStaleSessionCannotCancelLaterNativeRequest
A stale foreground session must not cancel another request
Clearing an idle slice owner canceled later native work
clearingFinalActiveSliceOwnerCancelsItsExactSessionAndRecovers
Final owner cancellation left a recoverable foreground session
A clean slice must succeed after final-owner cancellation
activeSliceSurvivesActivityRecreationAndCompletes
projectTransfer.state.value.history.current.selectedPlateId
assertEquals(selectedPlateId, initial.state.value.plateId)
assertEquals(selectedPlateId, ForegroundSliceStore.load(context)?.plateId)
assertEquals(livePlateId, completed.plateId)
Stopping the Activity must not cancel the slice
The slicer service must be foreground while a stopped Activity is slicing
A slice that finishes during the background transition must retain its result
Configuration recreation must retain the operation
An idle canceled slice must release ownership before allowing restart
"""

VALID_PROCESS_REATTACHMENT_HARNESS = """
page_size != "16384"
"android.intent.action.MAIN"
"android.intent.category.LAUNCHER"
run_as(serial, "kill", "-9", str(old_ui_pid))
surviving_service_pid != old_service_pid
current_boot_id != boot_id
"Recovered foreground slice"
SESSION_PATH
"""

VALID_DEBUG_RECOVERY_ACTIVITY = """
class ProcessRecoveryHarnessActivity
ForegroundSlicePhase.ACTIVE
Process.myPid()
output.fd.sync()
Process recovery slice finished too early
"""

VALID_DEBUG_MANIFEST = """
android:name=".ProcessRecoveryHarnessActivity"
android:exported="true"
android:permission="android.permission.DUMP"
"""


def valid_sources() -> dict[str, str]:
    return {
        "com/ashcastle/duckyslicer/SlicerProcessService.kt": VALID_SERVICE,
        "com/ashcastle/duckyslicer/SliceArtifactStore.kt": (
            "output.fd.sync() MAXIMUM_RETAINED_OUTPUTS MAXIMUM_RETAINED_BYTES"
        ),
        "com/ashcastle/duckyslicer/OnDeviceSlicer.kt": (
            "SlicerProcessClient.slice() cancellationRequested: () -> Boolean "
            "if (cancellationRequested()) throw SlicingCancelledException()"
        ),
        "com/ashcastle/duckyslicer/MainActivity.kt": (
            "ViewModelProvider(this)[SliceOperationViewModel::class.java]"
        ),
        "com/ashcastle/duckyslicer/SliceOperationViewModel.kt": (
            "class SliceOperationViewModel : ViewModel() viewModelScope.launch "
            "SlicerProcessClient.beginUserSlice(plateId) foregroundSession.cancellationRequested() "
            "SlicerProcessClient.recoverUserSlice() "
            "SlicerProcessClient.awaitRecoveredSlice( "
            "Recovered foreground slice "
            "foregroundSession.close() "
            "operationJob.set(null) "
            "completedState?.let { mutableState.value = it } "
            "operationCancellation.get() "
            "operationCancellation.set(true) "
            "private val operationSession = AtomicReference<ForegroundSliceSession?>(null) "
            "SlicerProcessClient.cancelUserSliceAsync(session) "
            "override fun onCleared() "
            "operationSession.get()?.let(SlicerProcessClient::cancelUserSliceAsync)"
        ),
        "com/ashcastle/duckyslicer/ForegroundSliceStore.kt": VALID_FOREGROUND_STORE,
        "com/ashcastle/duckyslicer/WorkspaceScreen.kt": "onCancelSlice canceling_slice",
        "com/u1/slicer/NativeLibrary.kt": "class NativeLibrary()",
    }


class VerifyAndroidIsolationTest(unittest.TestCase):
    def test_accepts_private_worker_and_single_native_construction(self) -> None:
        verify_manifest(VALID_MANIFEST)
        self.assertEqual(
            1,
            verify_sources(
                valid_sources(),
                VALID_DEVICE_TEST,
            ),
        )

    def test_rejects_exported_or_in_process_service(self) -> None:
        for invalid in (
            VALID_MANIFEST.replace('android:exported="false"', 'android:exported="true"'),
            VALID_MANIFEST.replace('android:process=":slicer"', 'android:process=":main"'),
        ):
            with self.assertRaises(VerificationError):
                verify_manifest(invalid)

    def test_requires_data_sync_foreground_declaration_and_permissions(self) -> None:
        for invalid in (
            VALID_MANIFEST.replace('android:foregroundServiceType="dataSync"', ""),
            VALID_MANIFEST.replace(
                '<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />',
                "",
            ),
            VALID_MANIFEST.replace(
                '<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />',
                "",
            ),
        ):
            with self.assertRaisesRegex(VerificationError, "foreground|dataSync"):
                verify_manifest(invalid)

    def test_rejects_native_runtime_construction_outside_worker(self) -> None:
        sources = valid_sources()
        sources["com/ashcastle/duckyslicer/OnDeviceSlicer.kt"] += "\nNativeLibrary()"
        with self.assertRaisesRegex(VerificationError, "exactly once"):
            verify_sources(sources, "nativeSlicerWorkerCrashLeavesAppAliveAndRestartsCleanly")

    def test_requires_device_crash_recovery_regression(self) -> None:
        for missing, message in (
            ("imperfectMeshCorpusIsRepairableOrFailsWithoutKillingTheApp", "imperfect-mesh"),
            ("nativeSlicerWorkerCrashLeavesAppAliveAndRestartsCleanly", "crash recovery"),
            ("activeSliceCancellationKeepsServiceResponsiveAndRestartsCleanly", "active-slice"),
            (
                "clearingIdleSliceOwnerAndStaleSessionCannotCancelLaterNativeRequest",
                "stale-slice-owner",
            ),
            (
                "A stale foreground session must not cancel another request",
                "stale-slice-owner",
            ),
            (
                "Clearing an idle slice owner canceled later native work",
                "stale-slice-owner",
            ),
            (
                "clearingFinalActiveSliceOwnerCancelsItsExactSessionAndRecovers",
                "final-slice-owner",
            ),
            (
                "Final owner cancellation left a recoverable foreground session",
                "final-slice-owner",
            ),
            (
                "A clean slice must succeed after final-owner cancellation",
                "final-slice-owner",
            ),
            ("activeSliceSurvivesActivityRecreationAndCompletes", "configuration/background"),
            ("Stopping the Activity must not cancel the slice", "configuration/background"),
            (
                "The slicer service must be foreground while a stopped Activity is slicing",
                "configuration/background",
            ),
            (
                "A slice that finishes during the background transition must retain its result",
                "configuration/background",
            ),
            (
                "Configuration recreation must retain the operation",
                "configuration/background",
            ),
            (
                "An idle canceled slice must release ownership before allowing restart",
                "configuration/background",
            ),
        ):
            with self.assertRaisesRegex(VerificationError, message):
                verify_sources(valid_sources(), VALID_DEVICE_TEST.replace(missing, ""))

    def test_requires_foreground_cleanup_before_idle_publication(self) -> None:
        sources = valid_sources()
        lifecycle_path = "com/ashcastle/duckyslicer/SliceOperationViewModel.kt"
        sources[lifecycle_path] = sources[lifecycle_path].replace(
            "operationJob.set(null) completedState?.let { mutableState.value = it }",
            "completedState?.let { mutableState.value = it } operationJob.set(null)",
        )
        with self.assertRaisesRegex(VerificationError, "before publishing idle"):
            verify_sources(sources, VALID_DEVICE_TEST)

    def test_requires_durable_process_reattachment(self) -> None:
        for source_path, marker in (
            (
                "com/ashcastle/duckyslicer/SliceOperationViewModel.kt",
                "SlicerProcessClient.recoverUserSlice()",
            ),
            (
                "com/ashcastle/duckyslicer/SliceOperationViewModel.kt",
                "SlicerProcessClient.awaitRecoveredSlice(",
            ),
            ("com/ashcastle/duckyslicer/SlicerProcessService.kt", "MESSAGE_ATTACH"),
            (
                "com/ashcastle/duckyslicer/SlicerProcessService.kt",
                "foregroundRequestId.get() != abandonedRequestId",
            ),
            (
                "com/ashcastle/duckyslicer/ForegroundSliceStore.kt",
                "StandardCopyOption.ATOMIC_MOVE",
            ),
            (
                "com/ashcastle/duckyslicer/ForegroundSliceStore.kt",
                "channel.lock()",
            ),
        ):
            sources = valid_sources()
            sources[source_path] = sources[source_path].replace(marker, "")
            with self.assertRaisesRegex(VerificationError, "reattach|unbind|checkpoint|lifecycle"):
                verify_sources(sources, VALID_DEVICE_TEST)

    def test_requires_local_process_reattachment_harness(self) -> None:
        verify_process_reattachment_harness(VALID_PROCESS_REATTACHMENT_HARNESS)
        for marker in (
            'page_size != "16384"',
            'run_as(serial, "kill", "-9", str(old_ui_pid))',
            "surviving_service_pid != old_service_pid",
            "current_boot_id != boot_id",
            '"Recovered foreground slice"',
            "SESSION_PATH",
        ):
            with self.assertRaisesRegex(VerificationError, "reattachment harness"):
                verify_process_reattachment_harness(
                    VALID_PROCESS_REATTACHMENT_HARNESS.replace(marker, "")
                )

    def test_requires_shell_restricted_debug_recovery_activity(self) -> None:
        verify_debug_recovery_harness(
            VALID_DEBUG_RECOVERY_ACTIVITY,
            VALID_DEBUG_MANIFEST,
        )
        for source, manifest, marker in (
            (
                VALID_DEBUG_RECOVERY_ACTIVITY.replace(
                    "ForegroundSlicePhase.ACTIVE",
                    "",
                ),
                VALID_DEBUG_MANIFEST,
                "activity",
            ),
            (
                VALID_DEBUG_RECOVERY_ACTIVITY,
                VALID_DEBUG_MANIFEST.replace('android:permission="android.permission.DUMP"', ""),
                "manifest",
            ),
        ):
            with self.assertRaisesRegex(VerificationError, marker):
                verify_debug_recovery_harness(source, manifest)

    def test_requires_retained_ui_and_owner_cancellation_paths(self) -> None:
        for source_path, marker in (
            ("com/ashcastle/duckyslicer/MainActivity.kt", "ViewModelProvider(this)"),
            ("com/ashcastle/duckyslicer/SliceOperationViewModel.kt", "viewModelScope.launch"),
            ("com/ashcastle/duckyslicer/SliceOperationViewModel.kt", "override fun onCleared()"),
            ("com/ashcastle/duckyslicer/WorkspaceScreen.kt", "onCancelSlice"),
        ):
            sources = valid_sources()
            sources[source_path] = sources[source_path].replace(marker, "")
            with self.assertRaises(VerificationError):
                verify_sources(sources, VALID_DEVICE_TEST)

    def test_rejects_activity_disposal_cancellation(self) -> None:
        sources = valid_sources()
        sources["com/ashcastle/duckyslicer/MainActivity.kt"] += (
            " DisposableEffect(sliceOperationModel) { onDispose { "
            "SlicerProcessClient.cancelUserSliceAsync(session) } }"
        )
        with self.assertRaisesRegex(VerificationError, "Activity must not cancel"):
            verify_sources(sources, VALID_DEVICE_TEST)

    def test_rejects_global_or_unscoped_slice_cancellation(self) -> None:
        service_path = "com/ashcastle/duckyslicer/SlicerProcessService.kt"
        lifecycle_path = "com/ashcastle/duckyslicer/SliceOperationViewModel.kt"
        mutations = (
            lambda sources: sources.__setitem__(
                service_path,
                sources[service_path] + " fun cancelActiveSlice() = Unit",
            ),
            lambda sources: sources.__setitem__(
                lifecycle_path,
                sources[lifecycle_path].replace(
                    "operationSession.get()?.let(SlicerProcessClient::cancelUserSliceAsync)",
                    "SlicerProcessClient.cancelActiveSliceAsync()",
                ),
            ),
            lambda sources: sources.__setitem__(
                service_path,
                sources[service_path].replace(
                    "val active = activeRequestId.get() == requestId",
                    "val active = activeRequestId.get() != null",
                ),
            ),
        )
        for mutate in mutations:
            sources = valid_sources()
            mutate(sources)
            with self.assertRaises(VerificationError):
                verify_sources(sources, VALID_DEVICE_TEST)

    def test_requires_cancellation_before_the_worker_is_bound(self) -> None:
        for source_path, marker in (
            (
                "com/ashcastle/duckyslicer/OnDeviceSlicer.kt",
                "if (cancellationRequested()) throw SlicingCancelledException()",
            ),
            (
                "com/ashcastle/duckyslicer/SlicerProcessService.kt",
                "if (cancellationRequested())",
            ),
        ):
            sources = valid_sources()
            sources[source_path] = sources[source_path].replace(marker, "")
            with self.assertRaisesRegex(VerificationError, "cancellation"):
                verify_sources(sources, VALID_DEVICE_TEST)

    def test_requires_foreground_lifecycle_and_notification_cancellation(self) -> None:
        for marker in (
            "startForegroundService(",
            "FOREGROUND_SERVICE_TYPE_DATA_SYNC",
            "override fun onTimeout(",
            "ACTION_CANCEL_SLICE",
            "ACTION_FINISH_SLICE",
            "ForegroundSliceSession.markCanceled(",
            "ForegroundSliceSession.wasCanceled(this, requestId)",
        ):
            sources = valid_sources()
            service_path = "com/ashcastle/duckyslicer/SlicerProcessService.kt"
            sources[service_path] = sources[service_path].replace(marker, "")
            with self.assertRaisesRegex(VerificationError, "foreground|notification"):
                verify_sources(sources, VALID_DEVICE_TEST)

    def test_requires_explicit_immutable_notification_pending_intents(self) -> None:
        service_path = "com/ashcastle/duckyslicer/SlicerProcessService.kt"
        mutations = (
            lambda source: source.replace(
                "Intent(this, MainActivity::class.java)",
                "Intent()",
            ),
            lambda source: source.replace(
                "Intent(context, SlicerProcessService::class.java)",
                "Intent(ACTION_CANCEL_SLICE)",
            ),
            lambda source: source.replace(
                "PendingIntent.FLAG_IMMUTABLE",
                "PendingIntent.FLAG_MUTABLE",
                1,
            ),
            lambda source: source.replace(
                "PendingIntent.FLAG_IMMUTABLE",
                "",
                1,
            ),
            lambda source: source.replace(
                "data = Uri.Builder()",
                "",
            ),
            lambda source: source.replace(
                "PendingIntent.FLAG_IMMUTABLE",
                "PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE",
                1,
            ),
        )
        for mutate in mutations:
            sources = valid_sources()
            sources[service_path] = mutate(sources[service_path])
            with self.assertRaisesRegex(VerificationError, "PendingIntent"):
                verify_sources(sources, VALID_DEVICE_TEST)


if __name__ == "__main__":
    unittest.main()
