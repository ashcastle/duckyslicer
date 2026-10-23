from __future__ import annotations

import subprocess
import unittest
from unittest.mock import patch

from tools.wait_for_android_boot import wait_for_boot


class WaitForAndroidBootTest(unittest.TestCase):
    @patch("tools.wait_for_android_boot.os.kill")
    @patch("tools.wait_for_android_boot.subprocess.run")
    def test_accepts_completed_boot(self, run, kill) -> None:
        run.return_value = subprocess.CompletedProcess([], 0, "1\r\n", "")
        wait_for_boot(42)
        kill.assert_called_once_with(42, 0)
        self.assertEqual(10, run.call_args.kwargs["timeout"])

    @patch("tools.wait_for_android_boot.os.kill", side_effect=ProcessLookupError)
    @patch("tools.wait_for_android_boot.subprocess.run")
    def test_fails_immediately_if_emulator_exits(self, run, kill) -> None:
        with self.assertRaisesRegex(RuntimeError, "exited"):
            wait_for_boot(42)
        run.assert_not_called()

    @patch("tools.wait_for_android_boot.time.sleep")
    @patch("tools.wait_for_android_boot.time.monotonic", side_effect=[0, 0, 12, 12])
    @patch("tools.wait_for_android_boot.os.kill")
    @patch("tools.wait_for_android_boot.subprocess.run")
    def test_disconnected_adb_cannot_bypass_deadline(self, run, kill, monotonic, sleep) -> None:
        run.side_effect = subprocess.TimeoutExpired("adb", 10)
        with self.assertRaisesRegex(TimeoutError, "12 seconds"):
            wait_for_boot(42, 12)
        self.assertEqual(10, run.call_args.kwargs["timeout"])

    @patch("tools.wait_for_android_boot.time.sleep")
    @patch("tools.wait_for_android_boot.time.monotonic", side_effect=[0, 0, 2, 2])
    @patch("tools.wait_for_android_boot.os.kill")
    @patch("tools.wait_for_android_boot.subprocess.run")
    def test_nonzero_adb_exit_is_not_a_success(self, run, kill, monotonic, sleep) -> None:
        run.return_value = subprocess.CompletedProcess([], 1, "1", "offline")
        with self.assertRaises(TimeoutError):
            wait_for_boot(42, 2)
        self.assertEqual(2, run.call_args.kwargs["timeout"])

    def test_rejects_invalid_process_and_timeout(self) -> None:
        for pid, timeout in [(0, 1), (-2, 1), (1, 1), (42, 0), (42, -1)]:
            with self.subTest(pid=pid, timeout=timeout), self.assertRaises(ValueError):
                wait_for_boot(pid, timeout)


if __name__ == "__main__":
    unittest.main()
