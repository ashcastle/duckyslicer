from __future__ import annotations

import unittest

from tools.verify_data_practices import DATA_STRING_NAMES, VerificationError
from tools.verify_data_practices import verify_data_practices


def string_resources() -> str:
    values = "".join(
        f'<string name="{name}">{name} value</string>' for name in DATA_STRING_NAMES
    )
    return f"<resources>{values}</resources>"


def valid_sources() -> dict[str, str]:
    settings_markers = " ".join(
        f"R.string.{resource_name}" for resource_name in DATA_STRING_NAMES
    )
    return {
        "AppSettingsSheet.kt": (
            f"showDataPractices DataPracticesDialog( {settings_markers}"
        ),
        "RemoteDevice.kt": (
            'KeyStore.getInstance("AndroidKeyStore") secrets.remove(profileId) '
            '"print" to "false" instanceFollowRedirects = false'
        ),
        "AndroidManifest.xml": (
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
            '<uses-permission android:name="android.permission.INTERNET" />'
            "</manifest>"
        ),
        "build.gradle.kts": "dependencies { implementation(libs.compose) }",
        "strings.xml": string_resources(),
        "strings-ko.xml": string_resources(),
    }


class VerifyDataPracticesTest(unittest.TestCase):
    def test_accepts_aligned_data_explanation(self) -> None:
        verify_data_practices(valid_sources())

    def test_rejects_undisclosed_permission(self) -> None:
        sources = valid_sources()
        sources["AndroidManifest.xml"] = sources["AndroidManifest.xml"].replace(
            "</manifest>",
            '<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />'
            "</manifest>",
        )
        with self.assertRaisesRegex(VerificationError, "permissions"):
            verify_data_practices(sources)

    def test_rejects_tracking_dependency(self) -> None:
        sources = valid_sources()
        sources["build.gradle.kts"] += ' implementation("firebase-analytics")'
        with self.assertRaisesRegex(VerificationError, "disclosure review"):
            verify_data_practices(sources)

    def test_rejects_missing_korean_copy(self) -> None:
        sources = valid_sources()
        sources["strings-ko.xml"] = sources["strings-ko.xml"].replace(
            '<string name="no_tracking_body">no_tracking_body value</string>', ""
        )
        with self.assertRaisesRegex(VerificationError, "strings-ko.xml"):
            verify_data_practices(sources)

    def test_rejects_missing_credential_removal(self) -> None:
        sources = valid_sources()
        sources["RemoteDevice.kt"] = sources["RemoteDevice.kt"].replace(
            "secrets.remove(profileId)", ""
        )
        with self.assertRaisesRegex(VerificationError, "connection behavior"):
            verify_data_practices(sources)


if __name__ == "__main__":
    unittest.main()
