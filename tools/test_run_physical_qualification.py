from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from tools.run_physical_qualification import (
    DeviceIdentity,
    MAX_AUTOMATIC_FIRST_FRAME_MS,
    MAX_AUTOMATIC_INTERACTION_P95_MS,
    MAX_AUTOMATIC_SETTLED_P95_MS,
    MAX_PEAK_TOTAL_PSS_KB,
    MAX_SOAK_UI_PSS_GROWTH_KB,
    QUALIFICATION_APPLICATION_ID,
    QUALIFICATION_TEST_APPLICATION_ID,
    crash_anr_evidence,
    exit_failure_evidence,
    foreground_rejection,
    parse_memory_total_kb,
    parse_exit_entries,
    parse_thermal_readings,
    parse_thermal_status,
    parse_total_pss_kb,
    physical_rejection,
    output_application_id,
    validate_dense_render,
    validate_resource_budget,
)
from tools.run_qualification_corpus import RunnerError


def identity(**overrides: object) -> DeviceIdentity:
    values: dict[str, object] = {
        "serial": "R5CT123456A",
        "manufacturer": "Example",
        "model": "Phone",
        "api": 35,
        "abi": "arm64-v8a",
        "page_size_bytes": 16_384,
        "hardware": "qcom",
        "kernel_qemu": "0",
        "boot_qemu": "0",
        "build_fingerprint": "example/phone/release-keys",
        "memory_total_kb": 8_000_000,
    }
    values.update(overrides)
    return DeviceIdentity(**values)  # type: ignore[arg-type]


class PhysicalQualificationTest(unittest.TestCase):
    def test_qualification_package_is_isolated_from_the_release(self) -> None:
        self.assertEqual("com.ashcastle.duckyslicer.qualification", QUALIFICATION_APPLICATION_ID)
        self.assertEqual(
            "com.ashcastle.duckyslicer.qualification.test",
            QUALIFICATION_TEST_APPLICATION_ID,
        )
        with tempfile.TemporaryDirectory() as directory:
            metadata = Path(directory) / "output-metadata.json"
            metadata.write_text(
                json.dumps({"applicationId": QUALIFICATION_APPLICATION_ID}),
                encoding="utf-8",
            )
            self.assertEqual(QUALIFICATION_APPLICATION_ID, output_application_id(metadata))

    def test_accepts_only_explicit_modern_physical_arm64_targets(self) -> None:
        self.assertIsNone(physical_rejection(identity()))
        self.assertRegex(
            physical_rejection(identity(serial="emulator-5554", hardware="ranchu", kernel_qemu="1")) or "",
            "emulator serial",
        )
        self.assertRegex(physical_rejection(identity(boot_qemu="1")) or "", "QEMU")
        self.assertRegex(physical_rejection(identity(hardware="goldfish")) or "", "emulator hardware")
        self.assertRegex(physical_rejection(identity(abi="x86_64")) or "", "not ARM64")
        self.assertRegex(physical_rejection(identity(api=29)) or "", r"API 30\+")
        self.assertRegex(
            physical_rejection(identity(memory_total_kb=None)) or "",
            "memory total",
        )

    def test_visible_measurement_requires_an_awake_unlocked_device(self) -> None:
        self.assertIsNone(
            foreground_rejection(
                "  mWakefulness=Awake\n",
                "  mInputRestricted=false\n  mShowingDream=false mDreamingLockscreen=false\n",
            ),
        )
        self.assertRegex(
            foreground_rejection("  mWakefulness=Dozing\n", "  mInputRestricted=false\n") or "",
            "Dozing",
        )
        self.assertRegex(
            foreground_rejection("  mWakefulness=Awake\n", "  mInputRestricted=true\n") or "",
            "lock screen",
        )

    def test_parses_total_memory_and_both_meminfo_pss_formats(self) -> None:
        self.assertEqual(8_123_456, parse_memory_total_kb("MemTotal:        8123456 kB\nMemFree: 1 kB\n"))
        self.assertEqual(123_456, parse_total_pss_kb("TOTAL PSS: 123,456 TOTAL RSS: 456,789"))
        self.assertEqual(
            98_765,
            parse_total_pss_kb(" App Summary\n                       Pss(KB)\n TOTAL                 98,765  3,000  2,000  1,000\n"),
        )
        self.assertIsNone(parse_total_pss_kb("No process found"))

    def test_parses_thermal_status_and_sensor_readings(self) -> None:
        output = """Thermal Status: 2
Temperature{mValue=38.5, mType=3, mName=battery, mStatus=1}
Temperature{mValue=42.25, mType=0, mName=cpu-0, mStatus=2}
"""
        self.assertEqual(2, parse_thermal_status(output))
        readings = parse_thermal_readings(output)
        self.assertEqual(["battery", "cpu-0"], [reading.name for reading in readings])
        self.assertEqual([38.5, 42.25], [reading.value for reading in readings])
        self.assertEqual(4, parse_thermal_status("mStatus: 4\n"))

    def test_crash_scan_is_package_scoped(self) -> None:
        unrelated = "FATAL EXCEPTION: main\nProcess: com.example.other, PID: 10"
        self.assertEqual([], crash_anr_evidence(unrelated))
        startup = (
            "AndroidRuntime: >>>>>> START com.android.internal.os.RuntimeInit uid 2000 <<<<<<\n"
            "ActivityManager: instrument com.ashcastle.duckyslicer"
        )
        self.assertEqual([], crash_anr_evidence(startup))
        fatal = "FATAL EXCEPTION: main\nProcess: com.ashcastle.duckyslicer, PID: 11"
        self.assertEqual(1, len(crash_anr_evidence(fatal)))
        anr = "ActivityManager: ANR in com.ashcastle.duckyslicer (com.ashcastle.duckyslicer/.MainActivity)"
        self.assertEqual(1, len(crash_anr_evidence(anr)))
        native = (
            "libc: Fatal signal 11 (SIGSEGV), code 1, fault addr 0x0 in tid 42 "
            "(duckyslicer), pid 41 (com.ashcastle.duckyslicer:slicer)"
        )
        self.assertEqual(1, len(crash_anr_evidence(native)))

    def test_dense_report_requires_the_full_physical_measurement_shape(self) -> None:
        metrics = {
            "firstFrameMs": 10.0,
            "settledFrameP50Ms": 8.0,
            "settledFrameP95Ms": 12.0,
            "interactionFrameP50Ms": 7.0,
            "interactionFrameP95Ms": 11.0,
            "geometryUploads": 2,
        }
        validate_dense_render(
            {
                "previewRender": {
                    "measurementSurface": "foreground-glsurfaceview",
                    "framebufferWidth": 720,
                    "framebufferHeight": 1280,
                    "frameCountPerPhase": 30,
                    "detail": "BALANCED",
                    "automaticDetail": "BALANCED",
                    "tiers": {
                        "PERFORMANCE": metrics,
                        "BALANCED": metrics,
                        "DETAIL": metrics,
                    },
                },
            },
        )
        with self.assertRaisesRegex(RunnerError, "visible foreground surface"):
            validate_dense_render(
                {
                    "previewRender": {
                        "measurementSurface": "pbuffer",
                        "framebufferWidth": 720,
                        "framebufferHeight": 1280,
                        "frameCountPerPhase": 30,
                    },
                },
            )
        with self.assertRaisesRegex(RunnerError, "reduced measurement shape"):
            validate_dense_render(
                {
                    "previewRender": {
                        "measurementSurface": "foreground-glsurfaceview",
                        "framebufferWidth": 256,
                        "framebufferHeight": 256,
                        "frameCountPerPhase": 2,
                    },
                },
            )

    def test_dense_report_enforces_automatic_frame_and_upload_budgets(self) -> None:
        def report(**automatic_overrides: object) -> dict[str, object]:
            base: dict[str, object] = {
                "firstFrameMs": MAX_AUTOMATIC_FIRST_FRAME_MS,
                "settledFrameP50Ms": 20.0,
                "settledFrameP95Ms": MAX_AUTOMATIC_SETTLED_P95_MS,
                "interactionFrameP50Ms": 20.0,
                "interactionFrameP95Ms": MAX_AUTOMATIC_INTERACTION_P95_MS,
                "geometryUploads": 2,
            }
            automatic = dict(base)
            automatic.update(automatic_overrides)
            return {
                "previewRender": {
                    "measurementSurface": "foreground-glsurfaceview",
                    "framebufferWidth": 720,
                    "framebufferHeight": 1280,
                    "frameCountPerPhase": 30,
                    "detail": "BALANCED",
                    "automaticDetail": "BALANCED",
                    "tiers": {
                        "PERFORMANCE": dict(base),
                        "BALANCED": automatic,
                        "DETAIL": dict(base),
                    },
                },
            }

        validate_dense_render(report())
        for metric, value in (
            ("firstFrameMs", MAX_AUTOMATIC_FIRST_FRAME_MS + 0.1),
            ("settledFrameP95Ms", MAX_AUTOMATIC_SETTLED_P95_MS + 0.1),
            ("interactionFrameP95Ms", MAX_AUTOMATIC_INTERACTION_P95_MS + 0.1),
        ):
            with self.assertRaisesRegex(RunnerError, metric):
                validate_dense_render(report(**{metric: value}))
        with self.assertRaisesRegex(RunnerError, "geometry uploads"):
            validate_dense_render(report(geometryUploads=5))
        mismatched = report()
        mismatched["previewRender"]["detail"] = "PERFORMANCE"  # type: ignore[index]
        with self.assertRaisesRegex(RunnerError, "different detail tier"):
            validate_dense_render(mismatched)

    def test_resource_budget_rejects_excess_pss_and_thermal_severity(self) -> None:
        def metrics() -> dict[str, object]:
            return {
                "peakTotalPssKb": 500_000,
                "thermalBefore": {"status": 0, "readings": []},
                "thermalAfter": {
                    "status": 2,
                    "readings": [{"name": "cpu-0", "status": 2}],
                },
            }

        target = identity()
        validate_resource_budget(metrics(), target, "dense-preview")

        excessive = metrics()
        excessive["peakTotalPssKb"] = MAX_PEAK_TOTAL_PSS_KB + 1
        with self.assertRaisesRegex(RunnerError, "peak PSS budget"):
            validate_resource_budget(excessive, target, "dense-preview")

        hot_start = metrics()
        hot_start["thermalBefore"] = {"status": 2, "readings": []}
        with self.assertRaisesRegex(RunnerError, "thermalBefore severity"):
            validate_resource_budget(hot_start, target, "dense-preview")

        severe_sensor = metrics()
        severe_sensor["thermalAfter"] = {
            "status": 2,
            "readings": [{"name": "gpu-0", "status": 3}],
        }
        with self.assertRaisesRegex(RunnerError, "gpu-0"):
            validate_resource_budget(severe_sensor, target, "dense-preview")

        low_memory_target = identity(memory_total_kb=1_000_000)
        fractional_limit = metrics()
        fractional_limit["peakTotalPssKb"] = 350_001
        with self.assertRaisesRegex(RunnerError, "maximum=350000kB"):
            validate_resource_budget(fractional_limit, low_memory_target, "dense-preview")

        malformed_sensor = metrics()
        malformed_sensor["thermalAfter"] = {
            "status": 1,
            "readings": [{"name": "gpu-0", "status": "hot"}],
        }
        with self.assertRaisesRegex(RunnerError, "invalid thermalAfter sensor"):
            validate_resource_budget(malformed_sensor, target, "dense-preview")

    def test_dense_soak_requires_stable_process_memory_and_timing(self) -> None:
        def render() -> dict[str, object]:
            metrics = {
                "firstFrameMs": 100.0,
                "settledFrameP50Ms": 8.0,
                "settledFrameP95Ms": 10.0,
                "interactionFrameP50Ms": 8.0,
                "interactionFrameP95Ms": 10.0,
                "geometryUploads": 2,
            }
            return {
                "measurementSurface": "foreground-glsurfaceview",
                "framebufferWidth": 720,
                "framebufferHeight": 1280,
                "frameCountPerPhase": 30,
                "detail": "PERFORMANCE",
                "automaticDetail": "PERFORMANCE",
                "tiers": {
                    "PERFORMANCE": dict(metrics),
                    "BALANCED": dict(metrics),
                    "DETAIL": dict(metrics),
                },
            }

        def cycle(index: int, pss: int) -> dict[str, object]:
            return {
                "cycle": index,
                "workerPid": 42,
                "uiPssKb": pss,
                "sliceElapsedMs": 1_000.0,
                "previewParseElapsedMs": 100.0,
                "previewRender": render(),
            }

        def payload() -> dict[str, object]:
            return {
                "previewRender": render(),
                "soakCycles": [cycle(1, 210_000), cycle(2, 200_000), cycle(3, 205_000)],
            }

        validate_dense_render(payload(), required_soak_cycles=3)

        restarted = payload()
        restarted["soakCycles"][2]["workerPid"] = 43  # type: ignore[index]
        with self.assertRaisesRegex(RunnerError, "restarted"):
            validate_dense_render(restarted, required_soak_cycles=3)

        growing = payload()
        growing["soakCycles"][2]["uiPssKb"] = 200_000 + MAX_SOAK_UI_PSS_GROWTH_KB + 1  # type: ignore[index]
        with self.assertRaisesRegex(RunnerError, "continued growing"):
            validate_dense_render(growing, required_soak_cycles=3)

        slower_slice = payload()
        slower_slice["soakCycles"][2]["sliceElapsedMs"] = 2_501.0  # type: ignore[index]
        with self.assertRaisesRegex(RunnerError, "regressed sliceElapsedMs"):
            validate_dense_render(slower_slice, required_soak_cycles=3)

        slower_frames = payload()
        final_render = slower_frames["soakCycles"][2]["previewRender"]  # type: ignore[index]
        final_render["tiers"]["PERFORMANCE"]["settledFrameP95Ms"] = 24.0  # type: ignore[index]
        with self.assertRaisesRegex(RunnerError, "regressed settledFrameP95Ms"):
            validate_dense_render(slower_frames, required_soak_cycles=3)

    def test_exit_history_reports_only_new_crash_native_crash_and_anr_entries(self) -> None:
        previous = """ApplicationExitInfo #0:
  timestamp=2026-08-12 05:39:27.101 pid=1 realUid=1
  process=com.ashcastle.duckyslicer reason=10 (USER REQUESTED) subreason=21
"""
        current = previous + """ApplicationExitInfo #1:
  timestamp=2026-08-12 05:40:00.000 pid=2 realUid=1
  process=com.ashcastle.duckyslicer:slicer reason=5 (CRASH NATIVE) status=11
ApplicationExitInfo #2:
  timestamp=2026-08-12 05:41:00.000 pid=3 realUid=1
  process=com.ashcastle.duckyslicer reason=6 (ANR) status=0
"""
        self.assertEqual(1, len(parse_exit_entries(previous)))
        evidence = exit_failure_evidence(previous, current)
        self.assertEqual(2, len(evidence))
        self.assertTrue(any("CRASH NATIVE" in item for item in evidence))
        self.assertTrue(any("ANR" in item for item in evidence))


if __name__ == "__main__":
    unittest.main()
