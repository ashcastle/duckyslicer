from __future__ import annotations

import unittest

from tools.verify_android_isolation import VerificationError, verify_manifest, verify_sources


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
  val worker = HandlerThread("DuckySlicer Orca work")
  val cancel = MESSAGE_CANCEL
  fun containPreBindCancellation() { if (cancellationRequested()) return }
  fun containCancellation() { Process.killProcess(Process.myPid()) }
  fun beginForeground() { startForegroundService(); FOREGROUND_SERVICE_TYPE_DATA_SYNC }
  fun notificationCancel() {
    ACTION_CANCEL_SLICE
    ACTION_FINISH_SLICE
    ForegroundSliceSession.markCanceled()
    ForegroundSliceSession.wasCanceled(this, requestId)
  }
  override fun onTimeout(startId: Int, fgsType: Int) = Unit
  override fun onUnbind() = false
}
"""

VALID_DEVICE_TEST = """
nativeSlicerWorkerCrashLeavesAppAliveAndRestartsCleanly
imperfectMeshCorpusIsRepairableOrFailsWithoutKillingTheApp
activeSliceCancellationKeepsServiceResponsiveAndRestartsCleanly
activeSliceSurvivesActivityRecreationAndCompletes
Configuration recreation must retain the active operation
Background Activity must retain the foreground slice
The slicer service must be foreground while the Activity is stopped
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
            "ViewModelProvider(this)[SliceOperationViewModel::class.java] "
            "DisposableEffect(sliceOperationModel) "
            "if (!sliceOperationModel.state.value.busy) "
            "SlicerProcessClient.cancelActiveSliceAsync()"
        ),
        "com/ashcastle/duckyslicer/SliceOperationViewModel.kt": (
            "class SliceOperationViewModel : ViewModel() viewModelScope.launch "
            "SlicerProcessClient.beginUserSlice() foregroundSession.cancellationRequested() "
            "foregroundSession.close() "
            "operationCancellation.get() "
            "operationCancellation.set(true) "
            "override fun onCleared() SlicerProcessClient.cancelActiveSliceAsync()"
        ),
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
            ("activeSliceSurvivesActivityRecreationAndCompletes", "configuration/background"),
            ("Background Activity must retain the foreground slice", "configuration/background"),
            (
                "The slicer service must be foreground while the Activity is stopped",
                "configuration/background",
            ),
        ):
            with self.assertRaisesRegex(VerificationError, message):
                verify_sources(valid_sources(), VALID_DEVICE_TEST.replace(missing, ""))

    def test_requires_retained_ui_and_final_disposal_cancellation_paths(self) -> None:
        for source_path, marker in (
            ("com/ashcastle/duckyslicer/MainActivity.kt", "ViewModelProvider(this)"),
            ("com/ashcastle/duckyslicer/MainActivity.kt", "if (!sliceOperationModel.state.value.busy)"),
            ("com/ashcastle/duckyslicer/SliceOperationViewModel.kt", "viewModelScope.launch"),
            ("com/ashcastle/duckyslicer/SliceOperationViewModel.kt", "override fun onCleared()"),
            ("com/ashcastle/duckyslicer/WorkspaceScreen.kt", "onCancelSlice"),
        ):
            sources = valid_sources()
            sources[source_path] = sources[source_path].replace(marker, "")
            with self.assertRaises(VerificationError):
                verify_sources(sources, VALID_DEVICE_TEST)

    def test_rejects_configuration_disposal_cancellation(self) -> None:
        sources = valid_sources()
        sources["com/ashcastle/duckyslicer/MainActivity.kt"] += (
            " DisposableEffect(Unit) { onDispose { "
            "SlicerProcessClient.cancelActiveSliceAsync() } }"
        )
        with self.assertRaisesRegex(VerificationError, "configuration disposal"):
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


if __name__ == "__main__":
    unittest.main()
