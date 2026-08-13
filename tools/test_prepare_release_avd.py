from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

from tools.prepare_release_avd import (
    AvdPreparationError,
    image_marker,
    launch_command,
    resolve_sdk_root,
)


class PrepareReleaseAvdTest(unittest.TestCase):
    def test_android_sdk_root_takes_precedence(self) -> None:
        with tempfile.TemporaryDirectory() as primary, tempfile.TemporaryDirectory() as legacy:
            self.assertEqual(
                Path(primary).resolve(),
                resolve_sdk_root(
                    {"ANDROID_SDK_ROOT": primary, "ANDROID_HOME": legacy},
                ),
            )

    def test_missing_sdk_environment_is_rejected(self) -> None:
        with self.assertRaisesRegex(AvdPreparationError, "ANDROID_SDK_ROOT"):
            resolve_sdk_root({})

    def test_image_marker_is_the_android_16_arm64_16k_package(self) -> None:
        marker = image_marker(Path("/sdk"))
        self.assertEqual(
            Path("/sdk/system-images/android-36/google_apis_ps16k/arm64-v8a/package.xml"),
            marker,
        )

    def test_launch_command_is_headless_and_uses_an_explicit_port(self) -> None:
        command = launch_command(Path("/sdk/emulator/emulator"), "release-avd", 5558)
        self.assertEqual("release-avd", command[command.index("-avd") + 1])
        self.assertEqual("5558", command[command.index("-port") + 1])
        self.assertIn("-no-window", command)
        self.assertIn("swiftshader_indirect", command)


if __name__ == "__main__":
    unittest.main()
