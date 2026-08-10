from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from tools.verify_localization import (
    LocalizationVerificationError,
    configured_language_tags,
    verify_hand_maintained_strings,
    verify_localization,
)


class VerifyLocalizationTest(unittest.TestCase):
    def test_current_localization_contract_is_complete(self) -> None:
        strings, translations, common = verify_localization()
        self.assertGreaterEqual(strings, 500)
        self.assertGreaterEqual(translations, 3_000)
        self.assertGreaterEqual(common, 100)

    def test_rejects_duplicate_configured_locale(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "locales.xml"
            source.write_text(
                '''<locale-config xmlns:android="http://schemas.android.com/apk/res/android">
                    <locale android:name="en" />
                    <locale android:name="en" />
                </locale-config>''',
                encoding="utf-8",
            )
            with self.assertRaisesRegex(LocalizationVerificationError, "duplicate"):
                configured_language_tags(source)

    def test_rejects_missing_hand_maintained_translation_key(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            default = root / "default.xml"
            localized = root / "localized.xml"
            default.write_text(
                '<resources><string name="first">First</string>'
                '<string name="second">Second</string></resources>',
                encoding="utf-8",
            )
            localized.write_text(
                '<resources><string name="first">첫째</string></resources>',
                encoding="utf-8",
            )
            with self.assertRaisesRegex(LocalizationVerificationError, "keys differ"):
                verify_hand_maintained_strings(default, localized)


if __name__ == "__main__":
    unittest.main()
