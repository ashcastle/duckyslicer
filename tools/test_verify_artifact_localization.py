from __future__ import annotations

import unittest

from tools.verify_artifact_localization import (
    EXPECTED_CONFIGURATIONS,
    LocalizationArtifactError,
    packaged_configurations,
    verify_aapt2_output,
)


def resource_table(configurations: tuple[str, ...] = EXPECTED_CONFIGURATIONS) -> str:
    values = "\n".join(
        f'      ({configuration}) "value-{configuration or "default"}"'
        for configuration in configurations
    )
    return f'''Package name=com.ashcastle.duckyslicer id=7f
  type string id=13 entryCount=2
    resource 0x7f130001 string/prepare
      () "Prepare"
    resource 0x7f130002 string/settings
{values}
    resource 0x7f130003 string/tab_device
      () "Device"
'''


class VerifyArtifactLocalizationTest(unittest.TestCase):
    def test_accepts_exact_packaged_language_set(self) -> None:
        verify_aapt2_output(resource_table())
        self.assertEqual(
            EXPECTED_CONFIGURATIONS,
            packaged_configurations(resource_table()),
        )

    def test_rejects_missing_language(self) -> None:
        with self.assertRaisesRegex(LocalizationArtifactError, "set changed"):
            verify_aapt2_output(resource_table(EXPECTED_CONFIGURATIONS[:-1]))

    def test_rejects_extra_language(self) -> None:
        with self.assertRaisesRegex(LocalizationArtifactError, "set changed"):
            verify_aapt2_output(resource_table((*EXPECTED_CONFIGURATIONS, "fil")))

    def test_rejects_duplicate_language(self) -> None:
        with self.assertRaisesRegex(LocalizationArtifactError, "repeats a configuration"):
            verify_aapt2_output(
                resource_table((*EXPECTED_CONFIGURATIONS, EXPECTED_CONFIGURATIONS[-1]))
            )

    def test_rejects_missing_settings_resource(self) -> None:
        with self.assertRaisesRegex(LocalizationArtifactError, "no string/settings"):
            packaged_configurations(
                resource_table().replace("string/settings", "string/options")
            )


if __name__ == "__main__":
    unittest.main()
