from __future__ import annotations

import unittest

from tools.run_physical_qualification import (
    DeviceIdentity,
    crash_anr_evidence,
    exit_failure_evidence,
    parse_memory_total_kb,
    parse_exit_entries,
    parse_thermal_readings,
    parse_thermal_status,
    parse_total_pss_kb,
    physical_rejection,
    validate_dense_render,
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
        validate_dense_render(
            {
                "previewRender": {
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
                        "framebufferWidth": 256,
                        "framebufferHeight": 256,
                        "frameCountPerPhase": 2,
                    },
                },
            )

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
