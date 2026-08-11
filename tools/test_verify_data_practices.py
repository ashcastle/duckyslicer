from __future__ import annotations

import unittest

from tools.verify_data_practices import (
    DATA_STRING_NAMES,
    EXPECTED_PERMISSIONS,
    PRIVACY_POLICY_MARKERS,
    VerificationError,
    verify_data_practices,
)


def string_resources() -> str:
    values = "".join(
        f'<string name="{name}">{name} value</string>' for name in DATA_STRING_NAMES
    )
    return f"<resources>{values}</resources>"


def valid_sources() -> dict[str, str]:
    settings_markers = " ".join(
        f"R.string.{resource_name}" for resource_name in DATA_STRING_NAMES
    )
    permissions = "".join(
        f'<uses-permission android:name="{permission}" />'
        for permission in sorted(EXPECTED_PERMISSIONS)
    )
    return {
        "AppSettingsSheet.kt": (
            "showDataPractices DataPracticesDialog( LegalDocument.PRIVACY "
            'PRIVACY("legal/PRIVACY.md", R.string.privacy_policy) '
            "sliceNotificationsEnabled(context) openSliceNotificationSettings(context) "
            "Settings.ACTION_APP_NOTIFICATION_SETTINGS Settings.EXTRA_APP_PACKAGE "
            "Lifecycle.Event.ON_RESUME stateDescription = notificationState "
            f"{settings_markers}"
        ),
        "MainActivity.kt": (
            "ActivityResultContracts.RequestPermission() Manifest.permission.POST_NOTIFICATIONS "
            "SLICE_NOTIFICATION_PERMISSION_ASKED "
            ".putBoolean(SLICE_NOTIFICATION_PERMISSION_ASKED, true)"
        ),
        "RemoteDevice.kt": (
            'KeyStore.getInstance("AndroidKeyStore") '
            "removedCredentialKey?.let(secrets::remove) "
            '"print" to "false" instanceFollowRedirects = false'
        ),
        "AndroidManifest.xml": (
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
            f"{permissions}"
            "</manifest>"
        ),
        "build.gradle.kts": (
            'dependencies { implementation(libs.compose) } '
            'repositoryRoot.resolve("PRIVACY.md") into("legal")'
        ),
        "PRIVACY.md": "\n".join(sorted(PRIVACY_POLICY_MARKERS)),
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

    def test_rejects_incomplete_privacy_policy(self) -> None:
        sources = valid_sources()
        sources["PRIVACY.md"] = sources["PRIVACY.md"].replace(
            "does not collect, sell, or share", "collects app activity"
        )
        with self.assertRaisesRegex(VerificationError, "privacy policy"):
            verify_data_practices(sources)

    def test_rejects_missing_offline_privacy_policy_access(self) -> None:
        sources = valid_sources()
        sources["AppSettingsSheet.kt"] = sources["AppSettingsSheet.kt"].replace(
            "LegalDocument.PRIVACY", ""
        )
        with self.assertRaisesRegex(VerificationError, "data-practice UI"):
            verify_data_practices(sources)

    def test_rejects_missing_korean_copy(self) -> None:
        sources = valid_sources()
        sources["strings-ko.xml"] = sources["strings-ko.xml"].replace(
            '<string name="no_tracking_body">no_tracking_body value</string>', ""
        )
        with self.assertRaisesRegex(VerificationError, "strings-ko.xml"):
            verify_data_practices(sources)

    def test_rejects_missing_notification_settings_route(self) -> None:
        sources = valid_sources()
        sources["AppSettingsSheet.kt"] = sources["AppSettingsSheet.kt"].replace(
            "Settings.EXTRA_APP_PACKAGE", ""
        )
        with self.assertRaisesRegex(VerificationError, "data-practice UI"):
            verify_data_practices(sources)

    def test_rejects_notification_action_without_current_state(self) -> None:
        sources = valid_sources()
        sources["AppSettingsSheet.kt"] = sources["AppSettingsSheet.kt"].replace(
            "stateDescription = notificationState", ""
        )
        with self.assertRaisesRegex(VerificationError, "data-practice UI"):
            verify_data_practices(sources)

    def test_rejects_missing_notification_permission_request(self) -> None:
        sources = valid_sources()
        sources["MainActivity.kt"] = sources["MainActivity.kt"].replace(
            "Manifest.permission.POST_NOTIFICATIONS", ""
        )
        with self.assertRaisesRegex(VerificationError, "notification permission"):
            verify_data_practices(sources)

    def test_rejects_missing_credential_removal(self) -> None:
        sources = valid_sources()
        sources["RemoteDevice.kt"] = sources["RemoteDevice.kt"].replace(
            "removedCredentialKey?.let(secrets::remove)", ""
        )
        with self.assertRaisesRegex(VerificationError, "connection behavior"):
            verify_data_practices(sources)


if __name__ == "__main__":
    unittest.main()
