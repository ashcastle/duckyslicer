#!/usr/bin/env python3
"""Keep portable projects bounded, atomic, offline, and free of printer secrets."""

from __future__ import annotations

import xml.etree.ElementTree as ElementTree
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
REQUIRED_STRINGS = {
    "open_project",
    "save_project",
    "replace_project_title",
    "replace_project_body",
    "project_opened",
    "project_saved",
    "project_open_error",
    "project_export_error",
}


class VerificationError(ValueError):
    pass


def _require_markers(name: str, source: str, markers: tuple[str, ...]) -> None:
    missing = [marker for marker in markers if marker not in source]
    if missing:
        raise VerificationError(f"{name} is missing project-archive safeguards: {missing}")


def _strings(name: str, source: str) -> dict[str, str]:
    try:
        root = ElementTree.fromstring(source)
    except ElementTree.ParseError as error:
        raise VerificationError(f"{name} is not valid XML: {error}") from error
    return {
        element.attrib["name"]: "".join(element.itertext()).strip()
        for element in root.findall("string")
        if "name" in element.attrib
    }


def verify_project_archive(sources: dict[str, str]) -> None:
    required_files = {
        "ProjectArchive.kt",
        "ProjectStore.kt",
        "MainActivity.kt",
        "WorkspaceScreen.kt",
        "ProjectArchiveTest.kt",
        "NativeEngineInstrumentedTest.kt",
        "strings.xml",
        "strings-ko.xml",
        "PRIVACY.md",
        "SUPPORT.md",
        "PROJECT_FORMAT.md",
    }
    missing_files = sorted(required_files - sources.keys())
    if missing_files:
        raise VerificationError(f"project-archive sources are missing: {missing_files}")

    archive = sources["ProjectArchive.kt"]
    _require_markers(
        "ProjectArchive.kt",
        archive,
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
            "require(!entry.isDirectory",
            "require(entries.add(entry.name))",
            "entry.method == ZipEntry.DEFLATED || entry.method == ZipEntry.STORED",
            "readArchiveBytes(archive, MAX_PROJECT_ARCHIVE_MANIFEST_BYTES)",
            "MAX_MODEL_IMPORT_BYTES",
            "checkedArchiveTotal",
            "require(referencedEntries == models.keys)",
            "output.fd.sync()",
            "parseBoundedJsonObject",
            "require(info.triangles > 0)",
            "supportPaint.facets.keys.all",
        ),
    )

    store = sources["ProjectStore.kt"]
    _require_markers(
        "ProjectStore.kt",
        store,
        (
            'File(projectRoot, ".archive-${UUID.randomUUID()}")',
            "ProjectArchiveCodec.read(input, staging, inspectModel)",
            "moveArchiveModel(stagedModel.file, destination)",
            "save(snapshot, decoded.sliceOptions)",
            "pruneUnreferencedModels(snapshot)",
            "installed.forEach(File::delete)",
            "staging.deleteRecursively()",
            "modelFile.parentFile == modelRoot && modelFile.isFile",
            "StandardCopyOption.ATOMIC_MOVE",
        ),
    )
    if store.index("save(snapshot, decoded.sliceOptions)") > store.index(
        "pruneUnreferencedModels(snapshot)"
    ):
        raise VerificationError("ProjectStore.kt must commit imported metadata before pruning")

    main = sources["MainActivity.kt"]
    _require_markers(
        "MainActivity.kt",
        main,
        (
            "projectOpenPicker = rememberLauncherForActivityResult",
            "ActivityResultContracts.OpenDocument()",
            "projectSavePicker = rememberLauncherForActivityResult",
            "ActivityResultContracts.CreateDocument(PROJECT_ARCHIVE_MIME_TYPE)",
            "openInputStream(uri)",
            "projectStore.importArchive",
            "openOutputStream(uri)",
            "projectStore.exportArchive",
            "SupportEvent.PROJECT_ARCHIVE_IMPORT_FAILED",
            "SupportEvent.PROJECT_ARCHIVE_EXPORT_FAILED",
        ),
    )
    if "ACTION_SEND" in main or "HttpURLConnection" in main:
        raise VerificationError("project archives must use user-chosen local documents, not sharing or network upload")

    _require_markers(
        "WorkspaceScreen.kt",
        sources["WorkspaceScreen.kt"],
        (
            "ProjectSheet(",
            "onOpenProject",
            "onSaveProject",
            "confirmReplacement",
            "R.string.replace_project_title",
            "R.string.replace_project_body",
        ),
    )

    for source_name in ("strings.xml", "strings-ko.xml"):
        values = _strings(source_name, sources[source_name])
        missing = sorted(REQUIRED_STRINGS - values.keys())
        blank = sorted(name for name in REQUIRED_STRINGS if not values.get(name, ""))
        if missing or blank:
            raise VerificationError(
                f"{source_name} has incomplete project actions: missing={missing}, blank={blank}"
            )

    _require_markers(
        "PRIVACY.md",
        sources["PRIVACY.md"],
        (
            "Exported DuckySlicer project files contain the model geometry",
            "support painting, and active printer, filament, and slicing settings",
            "They do not contain G-code, saved printer addresses, or printer",
            "형상, 오브젝트 배치, 서포트 채색",
            "프린터 접속 키는 포함되지",
        ),
    )
    _require_markers(
        "SUPPORT.md",
        sources["SUPPORT.md"],
        ("`.duckyproject`", "model geometry", "include saved printer addresses, access keys, or G-code"),
    )
    _require_markers(
        "PROJECT_FORMAT.md",
        sources["PROJECT_FORMAT.md"],
        (
            "manifest.json",
            "models/000.stl",
            "schema version `1`",
            "rejects duplicate, directory, traversal, and unknown entries",
            "A failed import leaves the",
            "current project unchanged and removes staged data",
            "1 GiB total uncompressed content",
        ),
    )
    _require_markers(
        "ProjectArchiveTest.kt",
        sources["ProjectArchiveTest.kt"],
        (
            "projectArchiveRoundTripsModelsTransformsPaintAndResolvedProfilesDeterministically",
            "invalidArchiveCannotEscapeStagingOrReplaceTheCurrentProject",
            "oversizedManifestIsRejectedBeforeProjectStateChanges",
        ),
    )
    _require_markers(
        "NativeEngineInstrumentedTest.kt",
        sources["NativeEngineInstrumentedTest.kt"],
        (
            "projectArchiveRoundTripReinspectsAndSlicesOnArm64",
            "NativeEngine.inspectStl",
            "OnDeviceSlicer.slice(",
        ),
    )


def read_sources() -> dict[str, str]:
    package = ROOT / "android/app/src/main/java/com/ashcastle/duckyslicer"
    tests = ROOT / "android/app/src"
    return {
        "ProjectArchive.kt": (package / "ProjectArchive.kt").read_text(encoding="utf-8"),
        "ProjectStore.kt": (package / "ProjectStore.kt").read_text(encoding="utf-8"),
        "MainActivity.kt": (package / "MainActivity.kt").read_text(encoding="utf-8"),
        "WorkspaceScreen.kt": (package / "WorkspaceScreen.kt").read_text(encoding="utf-8"),
        "ProjectArchiveTest.kt": (
            tests / "test/java/com/ashcastle/duckyslicer/ProjectArchiveTest.kt"
        ).read_text(encoding="utf-8"),
        "NativeEngineInstrumentedTest.kt": (
            tests / "androidTest/java/com/ashcastle/duckyslicer/NativeEngineInstrumentedTest.kt"
        ).read_text(encoding="utf-8"),
        "strings.xml": (tests / "main/res/values/strings.xml").read_text(encoding="utf-8"),
        "strings-ko.xml": (tests / "main/res/values-ko/strings.xml").read_text(encoding="utf-8"),
        "PRIVACY.md": (ROOT / "PRIVACY.md").read_text(encoding="utf-8"),
        "SUPPORT.md": (ROOT / "SUPPORT.md").read_text(encoding="utf-8"),
        "PROJECT_FORMAT.md": (ROOT / "docs/PROJECT_FORMAT.md").read_text(encoding="utf-8"),
    }


def main() -> None:
    try:
        verify_project_archive(read_sources())
    except (OSError, VerificationError) as error:
        raise SystemExit(f"Project-archive verification failed: {error}") from error
    print("Verified bounded atomic offline DuckySlicer project archives")


if __name__ == "__main__":
    main()
