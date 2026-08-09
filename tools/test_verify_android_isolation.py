from __future__ import annotations

import unittest

from tools.verify_android_isolation import VerificationError, verify_manifest, verify_sources


VALID_MANIFEST = """\
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <application android:name=".DuckySlicerApplication">
    <service android:name=".SlicerProcessService"
             android:exported="false"
             android:process=":slicer" />
  </application>
</manifest>
"""

VALID_SERVICE = """
class SlicerProcessService {
  fun client() { bindService(); IBinder.DeathRecipient; }
  fun native() { NativeLibrary(); }
  val path = "Model is outside private storage"
  val max = MAX_OPTIONS_BYTES + MAX_RETAINED_OUTPUTS
  fun save() { output.fd.sync() }
  val terminate = MESSAGE_TERMINATE_FOR_TEST
  val worker = HandlerThread("DuckySlicer Orca work")
  val cancel = MESSAGE_CANCEL
  fun containCancellation() { Process.killProcess(Process.myPid()) }
  override fun onUnbind() = false
}
"""

VALID_DEVICE_TEST = """
nativeSlicerWorkerCrashLeavesAppAliveAndRestartsCleanly
imperfectMeshCorpusIsRepairableOrFailsWithoutKillingTheApp
activeSliceCancellationKeepsServiceResponsiveAndRestartsCleanly
"""


def valid_sources() -> dict[str, str]:
    return {
        "com/ashcastle/duckyslicer/SlicerProcessService.kt": VALID_SERVICE,
        "com/ashcastle/duckyslicer/OnDeviceSlicer.kt": "SlicerProcessClient.slice()",
        "com/ashcastle/duckyslicer/MainActivity.kt": (
            "SlicerProcessClient.cancelActiveSlice() "
            "SlicerProcessClient.cancelActiveSliceAsync()"
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
        ):
            with self.assertRaisesRegex(VerificationError, message):
                verify_sources(valid_sources(), VALID_DEVICE_TEST.replace(missing, ""))

    def test_requires_ui_and_disposal_cancellation_paths(self) -> None:
        for source_path, marker in (
            ("com/ashcastle/duckyslicer/MainActivity.kt", "cancelActiveSliceAsync()"),
            ("com/ashcastle/duckyslicer/WorkspaceScreen.kt", "onCancelSlice"),
        ):
            sources = valid_sources()
            sources[source_path] = sources[source_path].replace(marker, "")
            with self.assertRaisesRegex(VerificationError, "cancel"):
                verify_sources(sources, VALID_DEVICE_TEST)


if __name__ == "__main__":
    unittest.main()
