from __future__ import annotations

import unittest

from tools.verify_open_source_distribution import VerificationError, verify_distribution


def valid_sources() -> dict[str, str]:
    strings = """<resources>
      <string name="about">About</string><string name="app_version">Version</string>
      <string name="open_source_summary">Free software without any warranty</string>
      <string name="open_source_license">License</string><string name="third_party_notices">Notices</string>
      <string name="opening_legal_document">Opening</string><string name="legal_document_open_error">Error</string>
      <string name="view_source_code">Source</string><string name="close">Close</string>
    </resources>"""
    korean = strings.replace("Free software without any warranty", "보증 없이 제공되는 자유 소프트웨어")
    return {
        "LICENSE.txt": "GNU AFFERO GENERAL PUBLIC LICENSE\nWITHOUT ANY WARRANTY",
        "THIRD_PARTY_NOTICES.md": "[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)\nruntime-rev\nengine-rev",
        "README.md": "[GNU Affero General Public License v3](LICENSE.txt)\n[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)",
        "native/slicer-runtime/versions.env": "ANDROID_SLICER_RUNTIME_COMMIT=runtime-rev\nSLICER_ENGINE_COMMIT=engine-rev",
        "tools/generate_offline_licenses.py": "verify_vendored_policy native_notice_sources render_bundle",
        "tools/generate_license_inventory.py": (
            "build_inventory gradle_license normalize_cargo_expression"
        ),
        "tools/generate_sbom.py": (
            'CycloneDX "specVersion": "1.5" verify_apk_license_bundle'
        ),
        "tools/native_license_policy.py": "VENDORED_COMPONENTS native_components native_notice_sources",
        "tools/run_local_gate.py": (
            "generate_license_inventory.py generate_sbom.py duckyslicer-debug.cdx.json "
            "DEBUG_DEPENDENCY_INVENTORY DEBUG_LICENSE_INVENTORY"
        ),
        "CONTRIBUTING.md": "CycloneDX SBOM matches the offline license index",
        "SECURITY.md": "CycloneDX SBOM matches the offline license index",
        "android/app/build.gradle.kts": (
            "prepareOpenSourceNotices registerOfflineLicenseBundle generate_offline_licenses.py "
            "generatedLegalAssets LICENSE.txt THIRD_PARTY_NOTICES.md THIRD_PARTY_LICENSES.txt"
        ),
        "android/app/src/main/java/com/ashcastle/duckyslicer/AppSettingsSheet.kt": (
            "legal/AGPL-3.0.txt legal/THIRD_PARTY_NOTICES.md legal/THIRD_PARTY_LICENSES.txt "
            "https://github.com/ashcastle/duckyslicer BuildConfig.VERSION_NAME open_source_summary "
            "produceState<LegalDocumentContent> withContext(Dispatchers.IO) "
            "BoundedLegalInputStream MAX_LEGAL_DOCUMENT_BYTES cancellationRequested "
            "LegalDocumentContent.Failed"
        ),
        "android/app/src/test/java/com/ashcastle/duckyslicer/LegalTextChunksTest.kt": (
            "largeLegalDocumentIsLosslesslySplitForLazyRendering "
            "oversizedLegalDocumentIsRejectedWithoutReadingItIntoOneString "
            "canceledLegalDocumentReadStopsBetweenBoundedChunks"
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

    def test_rejects_eager_main_thread_legal_document_read(self) -> None:
        sources = valid_sources()
        sources[
            "android/app/src/main/java/com/ashcastle/duckyslicer/AppSettingsSheet.kt"
        ] += " context.assets.open(path).bufferedReader().use { it.readText() }"
        with self.assertRaisesRegex(VerificationError, "main thread"):
            verify_distribution(sources)

    def test_rejects_legal_document_loading_without_size_bound(self) -> None:
        sources = valid_sources()
        sources[
            "android/app/src/main/java/com/ashcastle/duckyslicer/AppSettingsSheet.kt"
        ] = sources[
            "android/app/src/main/java/com/ashcastle/duckyslicer/AppSettingsSheet.kt"
        ].replace("MAX_LEGAL_DOCUMENT_BYTES", "unbounded legal document")
        with self.assertRaisesRegex(VerificationError, "bounded off-main"):
            verify_distribution(sources)

    def test_rejects_stale_third_party_revision(self) -> None:
        sources = valid_sources()
        sources["THIRD_PARTY_NOTICES.md"] = sources["THIRD_PARTY_NOTICES.md"].replace("engine-rev", "stale")
        with self.assertRaisesRegex(VerificationError, "SLICER_ENGINE_COMMIT"):
            verify_distribution(sources)

    def test_rejects_local_gate_without_actual_sbom_generation(self) -> None:
        sources = valid_sources()
        sources["tools/run_local_gate.py"] = sources["tools/run_local_gate.py"].replace(
            "generate_sbom.py", "unit-test-only"
        )
        with self.assertRaisesRegex(VerificationError, "actual SBOM generation"):
            verify_distribution(sources)


if __name__ == "__main__":
    unittest.main()
