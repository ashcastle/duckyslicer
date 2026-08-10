from __future__ import annotations

import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from tools.generate_android_translations import (
    EXPECTED_TRANSLATION_COUNTS,
    ORCA_LOCALE_TO_ANDROID,
    ROOT,
    SUPPORTED_ORCA_LOCALES,
    TranslationGenerationError,
    generate_translation_resources,
    parse_po_catalog,
    read_android_strings,
    translation_resource,
)


class GenerateAndroidTranslationsTest(unittest.TestCase):
    def test_po_parser_accepts_only_safe_unambiguous_singular_entries(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "fixture.po"
            source.write_text(
                '''msgid ""
msgstr ""
"Language: test\\n"

msgid "Preview"
msgstr "Aperçu"

#, fuzzy
msgid "Settings"
msgstr "Réglages provisoires"

msgctxt "verb"
msgid "Prepare"
msgstr "Préparer"

msgid "One object"
msgid_plural "Many objects"
msgstr[0] "Un objet"
msgstr[1] "Plusieurs objets"

#~ msgid "Obsolete"
#~ msgstr "Ancien"

msgid "Split "
"to objects"
msgstr "Séparer "
"en objets"

msgid "Preview"
msgstr "Prévisualisation différente"
''',
                encoding="utf-8",
            )

            self.assertEqual(
                {"Split to objects": "Séparer en objets"},
                parse_po_catalog(source),
            )

    def test_resource_generation_escapes_android_text_and_omits_formats(self) -> None:
        strings = [
            ("preview", "Preview"),
            ("quoted", "Quoted"),
            ("formatted", "%1$d objects"),
        ]
        document, count = translation_resource(
            strings,
            {
                "Preview": "Aperçu",
                "Quoted": "L'app \"test\" & plus",
                "%1$d objects": "%1$d objets",
            },
        )

        self.assertEqual(2, count)
        self.assertIn("Aperçu", document)
        self.assertIn("L\\'app \\\"test\\\" &amp; plus", document)
        self.assertNotIn("formatted", document)
        ET.fromstring(document)

    def test_pinned_orca_catalog_generates_only_inherited_locales(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "res"
            counts = generate_translation_resources(
                ROOT / "android/app/src/main/res/values/strings.xml",
                ROOT / "localization/i18n",
                output,
            )

            self.assertEqual(set(ORCA_LOCALE_TO_ANDROID), set(counts))
            self.assertEqual(EXPECTED_TRANSLATION_COUNTS, counts)
            self.assertTrue(all(count >= 100 for count in counts.values()))
            generated = {
                path.parent.name.removeprefix("values-")
                for path in output.glob("values-*/strings.xml")
            }
            self.assertEqual(
                {qualifier for _, qualifier in ORCA_LOCALE_TO_ANDROID.values()},
                generated,
            )
            base_names = {
                name
                for name, _ in read_android_strings(
                    ROOT / "android/app/src/main/res/values/strings.xml"
                )
            }
            for resource in output.glob("values-*/strings.xml"):
                root = ET.parse(resource).getroot()
                names = {element.attrib["name"] for element in root.findall("string")}
                self.assertTrue(names)
                self.assertTrue(names <= base_names)
            self.assertEqual(
                {"en", "ko", *ORCA_LOCALE_TO_ANDROID},
                set(SUPPORTED_ORCA_LOCALES),
            )

    def test_rejects_a_broad_or_misnamed_output_directory(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(TranslationGenerationError, "narrow generated res"):
                generate_translation_resources(
                    ROOT / "android/app/src/main/res/values/strings.xml",
                    ROOT / "localization/i18n",
                    Path(directory) / "resources",
                )


if __name__ == "__main__":
    unittest.main()
