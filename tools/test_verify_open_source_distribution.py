from __future__ import annotations

import unittest

from tools.verify_open_source_distribution import VerificationError, verify_distribution


def valid_sources() -> dict[str, str]:
    strings = """<resources>
      <string name="about">About</string><string name="app_version">Version</string>
      <string name="open_source_summary">Free software without any warranty</string>
      <string name="open_source_license">License</string><string name="third_party_notices">Notices</string>
      <string name="view_source_code">Source</string><string name="close">Close</string>
    </resources>"""
    korean = strings.replace("Free software without any warranty", "보증 없이 제공되는 자유 소프트웨어")
    return {
        "LICENSE.txt": "GNU AFFERO GENERAL PUBLIC LICENSE\nWITHOUT ANY WARRANTY",
        "THIRD_PARTY_NOTICES.md": "[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)\nruntime-rev\nengine-rev",
        "README.md": "[GNU Affero General Public License v3](LICENSE.txt)\n[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)",
        "native/slicer-runtime/versions.env": "ANDROID_SLICER_RUNTIME_COMMIT=runtime-rev\nSLICER_ENGINE_COMMIT=engine-rev",
        "android/app/build.gradle.kts": "prepareOpenSourceNotices generatedLegalAssets LICENSE.txt THIRD_PARTY_NOTICES.md",
        "android/app/src/main/java/com/ashcastle/duckyslicer/AppSettingsSheet.kt": (
            "legal/AGPL-3.0.txt legal/THIRD_PARTY_NOTICES.md "
            "https://github.com/ashcastle/duckyslicer BuildConfig.VERSION_NAME open_source_summary"
        ),
        "android/app/src/main/res/values/strings.xml": strings,
        "android/app/src/main/res/values-ko/strings.xml": korean,
    }


class VerifyOpenSourceDistributionTest(unittest.TestCase):
    def test_accepts_complete_offline_distribution(self) -> None:
        verify_distribution(valid_sources())

    def test_rejects_broken_license_link(self) -> None:
        sources = valid_sources()
        sources["README.md"] = sources["README.md"].replace("LICENSE.txt", "LICENSE")
        with self.assertRaisesRegex(VerificationError, "LICENSE.txt"):
            verify_distribution(sources)

    def test_rejects_missing_settings_access(self) -> None:
        sources = valid_sources()
        sources["android/app/src/main/java/com/ashcastle/duckyslicer/AppSettingsSheet.kt"] = ""
        with self.assertRaisesRegex(VerificationError, "Settings"):
            verify_distribution(sources)

    def test_rejects_stale_third_party_revision(self) -> None:
        sources = valid_sources()
        sources["THIRD_PARTY_NOTICES.md"] = sources["THIRD_PARTY_NOTICES.md"].replace("engine-rev", "stale")
        with self.assertRaisesRegex(VerificationError, "SLICER_ENGINE_COMMIT"):
            verify_distribution(sources)


if __name__ == "__main__":
    unittest.main()
