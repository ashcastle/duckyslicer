from __future__ import annotations

import unittest

from tools.verify_project_archive import REQUIRED_STRINGS, VerificationError, verify_project_archive


def string_resources() -> str:
    values = "".join(f'<string name="{name}">{name}</string>' for name in REQUIRED_STRINGS)
    return f"<resources>{values}</resources>"


def valid_sources() -> dict[str, str]:
    return {
        "ProjectArchive.kt": " ".join(
            (
                'PROJECT_ARCHIVE_MIME_TYPE = "application/vnd.duckyslicer.project+zip"',
                'PROJECT_ARCHIVE_FILE_EXTENSION = ".duckyproject"',
                "MAX_PROJECT_ARCHIVE_MANIFEST_BYTES = 1_048_576",
                "MAX_PROJECT_ARCHIVE_CONTENT_BYTES = 1_073_741_824L",
                "MAX_PROJECT_ARCHIVE_FILE_BYTES = 1_082_130_432L",
                "MAX_PROJECT_ARCHIVE_ENTRIES = ProjectStore.MAX_PROJECT_OBJECTS + 1",
                'PROJECT_ARCHIVE_FORMAT = "com.ashcastle.duckyslicer.project"',
                "PROJECT_ARCHIVE_SCHEMA_VERSION = 1",
                'Regex("models/[0-9]{3}\\\\.stl")',
                "require(!entry.isDirectory require(entries.add(entry.name))",
                "entry.method == ZipEntry.DEFLATED || entry.method == ZipEntry.STORED",
                "readArchiveBytes(archive, MAX_PROJECT_ARCHIVE_MANIFEST_BYTES)",
                "MAX_MODEL_IMPORT_BYTES checkedArchiveTotal",
                "require(referencedEntries == models.keys)",
                "output.fd.sync() parseBoundedJsonObject require(info.triangles > 0)",
                "supportPaint.facets.keys.all",
            )
        ),
        "ProjectStore.kt": " ".join(
            (
                'File(projectRoot, ".archive-${UUID.randomUUID()}")',
                "ProjectArchiveCodec.read(input, staging, inspectModel)",
                "moveArchiveModel(stagedModel.file, destination)",
                "save(snapshot, decoded.sliceOptions)",
                "pruneUnreferencedModels(snapshot)",
                "installed.forEach(File::delete) staging.deleteRecursively()",
                "modelFile.parentFile == modelRoot && modelFile.isFile",
                "StandardCopyOption.ATOMIC_MOVE",
            )
        ),
        "MainActivity.kt": " ".join(
            (
                "projectOpenPicker = rememberLauncherForActivityResult",
                "ActivityResultContracts.OpenDocument()",
                "projectSavePicker = rememberLauncherForActivityResult",
                "ActivityResultContracts.CreateDocument(PROJECT_ARCHIVE_MIME_TYPE)",
                "openInputStream(uri) projectStore.importArchive",
                "openOutputStream(uri) projectStore.exportArchive",
                "SupportEvent.PROJECT_ARCHIVE_IMPORT_FAILED",
                "SupportEvent.PROJECT_ARCHIVE_EXPORT_FAILED",
            )
        ),
        "WorkspaceScreen.kt": (
            "ProjectSheet( onOpenProject onSaveProject confirmReplacement "
            "R.string.replace_project_title R.string.replace_project_body"
        ),
        "ProjectArchiveTest.kt": (
            "projectArchiveRoundTripsModelsTransformsPaintAndResolvedProfilesDeterministically "
            "invalidArchiveCannotEscapeStagingOrReplaceTheCurrentProject "
            "oversizedManifestIsRejectedBeforeProjectStateChanges"
        ),
        "NativeEngineInstrumentedTest.kt": (
            "projectArchiveRoundTripReinspectsAndSlicesOnArm64 "
            "NativeEngine.inspectStl OnDeviceSlicer.slice("
        ),
        "strings.xml": string_resources(),
        "strings-ko.xml": string_resources(),
        "PRIVACY.md": (
            "Exported DuckySlicer project files contain the model geometry\n"
            "support painting, and active printer, filament, and slicing settings\n"
            "They do not contain G-code, saved printer addresses, or printer\n"
            "형상, 오브젝트 배치, 서포트 채색\n"
            "프린터 접속 키는 포함되지"
        ),
        "SUPPORT.md": "`.duckyproject` model geometry include saved printer addresses, access keys, or G-code",
        "PROJECT_FORMAT.md": (
            "manifest.json models/000.stl schema version `1` "
            "rejects duplicate, directory, traversal, and unknown entries "
            "A failed import leaves the current project unchanged and removes staged data "
            "1 GiB total uncompressed content"
        ),
    }


class VerifyProjectArchiveTest(unittest.TestCase):
    def test_accepts_bounded_atomic_local_archive(self) -> None:
        verify_project_archive(valid_sources())

    def test_rejects_removed_uncompressed_limit(self) -> None:
        sources = valid_sources()
        sources["ProjectArchive.kt"] = sources["ProjectArchive.kt"].replace(
            "MAX_PROJECT_ARCHIVE_CONTENT_BYTES = 1_073_741_824L", ""
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_missing_duplicate_entry_check(self) -> None:
        sources = valid_sources()
        sources["ProjectArchive.kt"] = sources["ProjectArchive.kt"].replace(
            "require(entries.add(entry.name))", "entries.add(entry.name)"
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_direct_non_staged_import(self) -> None:
        sources = valid_sources()
        sources["ProjectStore.kt"] = sources["ProjectStore.kt"].replace(
            'File(projectRoot, ".archive-${UUID.randomUUID()}")', "File(projectRoot, PROJECT_FILE)"
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_automatic_or_shared_export(self) -> None:
        sources = valid_sources()
        sources["MainActivity.kt"] += " ACTION_SEND"
        with self.assertRaisesRegex(VerificationError, "user-chosen"):
            verify_project_archive(sources)

    def test_rejects_missing_privacy_disclosure(self) -> None:
        sources = valid_sources()
        sources["PRIVACY.md"] = ""
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)


if __name__ == "__main__":
    unittest.main()
