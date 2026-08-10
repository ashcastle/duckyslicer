from __future__ import annotations

import contextlib
import io
import subprocess
import unittest
from pathlib import Path

from tools.run_local_gate import (
    ANDROID,
    DEBUG_APK,
    ROOT,
    STATIC_VERIFIERS,
    DeviceFacts,
    GateError,
    GateStep,
    choose_device,
    device_steps,
    host_steps,
    parse_online_devices,
    run_steps,
)


class LocalProductionGateTest(unittest.TestCase):
    def test_parses_only_online_adb_devices(self) -> None:
        output = """List of devices attached
emulator-5554 device product:test transport_id:1
emulator-5556 offline transport_id:2
physical unauthorized transport_id:3
"""
        self.assertEqual(["emulator-5554"], parse_online_devices(output))

    def test_auto_selects_the_only_api35_arm64_16kb_device(self) -> None:
        devices = {
            "four-kb": DeviceFacts(35, "arm64-v8a", 4096),
            "sixteen-kb": DeviceFacts(35, "arm64-v8a", 16_384),
            "x86": DeviceFacts(35, "x86_64", 16_384),
        }
        self.assertEqual("sixteen-kb", choose_device(None, None, devices))

    def test_explicit_or_environment_device_must_meet_the_gate(self) -> None:
        devices = {
            "four-kb": DeviceFacts(35, "arm64-v8a", 4096),
            "sixteen-kb": DeviceFacts(35, "arm64-v8a", 16_384),
        }
        self.assertEqual("sixteen-kb", choose_device("sixteen-kb", "four-kb", devices))
        with self.assertRaisesRegex(GateError, "not the local 16 KB gate"):
            choose_device(None, "four-kb", devices)

    def test_ambiguous_or_missing_device_fails_closed(self) -> None:
        eligible = DeviceFacts(35, "arm64-v8a", 16_384)
        with self.assertRaisesRegex(GateError, "No API 35"):
            choose_device(None, None, {})
        with self.assertRaisesRegex(GateError, "Multiple eligible"):
            choose_device(None, None, {"first": eligible, "second": eligible})

    def test_host_plan_covers_rust_android_static_and_apk_gates(self) -> None:
        steps = host_steps(python="python-for-test", windows=False)
        commands = [step.command for step in steps]
        self.assertIn(("cargo", "test", "--release", "--locked"), commands)
        self.assertTrue(
            any(":app:assembleDebugAndroidTest" in command for command in commands)
        )
        self.assertTrue(
            any(
                command[:4] == ("python-for-test", "-m", "unittest", "discover")
                for command in commands
            )
        )
        self.assertTrue(
            any(
                len(command) > 1 and "verify_no_embedded_credentials.py" in command[1]
                for command in commands
            )
        )
        self.assertEqual(str(DEBUG_APK), commands[-1][-1])

    def test_host_plan_contains_every_standalone_static_verifier(self) -> None:
        standalone = {
            path.name
            for path in (ROOT / "tools").glob("verify_*.py")
            if path.name not in {"verify_apk.py", "verify_reproducible_release.py"}
        }
        self.assertEqual(standalone, set(STATIC_VERIFIERS))

    def test_device_plan_pins_gradle_and_recovery_to_one_serial(self) -> None:
        steps = device_steps("device-16k", python="python-for-test", windows=False)
        self.assertEqual(2, len(steps))
        self.assertEqual("device-16k", steps[0].environment["ANDROID_SERIAL"])
        self.assertIn(":app:connectedDebugAndroidTest", steps[0].command)
        self.assertEqual("device-16k", steps[1].command[-1])

    def test_runner_stops_at_the_first_failed_step_without_a_shell(self) -> None:
        calls: list[tuple[list[str], Path]] = []

        def runner(command, *, cwd, env, check):
            self.assertFalse(check)
            self.assertIn("PATH", env)
            calls.append((command, cwd))
            return subprocess.CompletedProcess(command, 7 if len(calls) == 2 else 0)

        steps = [
            GateStep("first", ("tool", "one"), ROOT),
            GateStep("second", ("tool", "two"), ANDROID),
            GateStep("third", ("tool", "three"), ROOT),
        ]
        with self.assertRaisesRegex(GateError, "second failed"):
            with contextlib.redirect_stdout(io.StringIO()):
                run_steps(steps, runner=runner)
        self.assertEqual(
            [(["tool", "one"], ROOT), (["tool", "two"], ANDROID)],
            calls,
        )


if __name__ == "__main__":
    unittest.main()
