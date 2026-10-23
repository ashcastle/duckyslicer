#!/usr/bin/env python3
"""Wait for a live emulator with a deadline that also covers disconnected ADB."""

from __future__ import annotations

import argparse
import os
import subprocess
import time


def wait_for_boot(emulator_pid: int, timeout_seconds: float = 600) -> None:
    if emulator_pid <= 1 or timeout_seconds <= 0:
        raise ValueError("a valid emulator PID and positive timeout are required")
    deadline = time.monotonic() + timeout_seconds
    while (remaining := deadline - time.monotonic()) > 0:
        try:
            os.kill(emulator_pid, 0)
        except ProcessLookupError as error:
            raise RuntimeError("Android emulator exited before completing boot") from error
        try:
            result = subprocess.run(
                ["adb", "-s", "emulator-5554", "shell", "getprop", "sys.boot_completed"],
                capture_output=True,
                text=True,
                timeout=min(10, remaining),
                check=False,
            )
            if result.returncode == 0 and result.stdout.strip() == "1":
                return
        except subprocess.TimeoutExpired:
            # An offline ADB transport must not outlive the overall boot deadline.
            pass
        time.sleep(max(0, min(2, deadline - time.monotonic())))
    raise TimeoutError(f"Android emulator did not boot within {timeout_seconds:g} seconds")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("emulator_pid", type=int)
    args = parser.parse_args()
    try:
        wait_for_boot(args.emulator_pid)
    except (OSError, RuntimeError, TimeoutError, ValueError) as error:
        raise SystemExit(str(error)) from error
    print("Android emulator boot completed")


if __name__ == "__main__":
    main()
