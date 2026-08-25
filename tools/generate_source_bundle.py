#!/usr/bin/env python3
"""Create a deterministic release source archive including recursive submodules."""

from __future__ import annotations

import configparser
import gzip
import hashlib
import io
import json
import posixpath
import re
import subprocess
import sys
import tarfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath


ROOT = Path(__file__).resolve().parent.parent
VERSION = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?$")
EXCLUDED_INSTRUCTION_NAMES = {
    "AGENT.md",
    "AGENTS.md",
    "AGENTS.override.md",
    "CLAUDE.md",
    "CLAUDE.local.md",
    "GEMINI.md",
    "GEMINI.local.md",
    "PROMPT.local.md",
}
EXCLUDED_INSTRUCTION_DIRECTORIES = {
    ".agents",
    ".ai",
    ".ai-notes",
    ".claude",
    ".claude.local",
    ".codex",
}
EXCLUDED_INSTRUCTION_SUFFIXES = (
    ".prompt.local.md",
    ".ai.md",
    ".ai-plan.md",
    ".ai-design.md",
)
BUILD_INPUTS = (
    ".github/workflows/android.yml",
    ".github/workflows/sign-local-release.yml",
    "android/app/build.gradle.kts",
    "android/app/src/main/res/values/strings.xml",
    "android/app/src/main/res/values-ko/strings.xml",
    "android/app/src/main/res/xml/locales_config.xml",
    "android/app/gradle.lockfile",
    "android/build.gradle.kts",
    "android/gradle.properties",
    "android/gradle/verification-metadata.xml",
    "android/gradle/wrapper/gradle-wrapper.jar",
    "android/gradle/wrapper/gradle-wrapper.properties",
    "android/settings.gradle.kts",
    "native/slicer-runtime/build.sh",
    "native/slicer-runtime/overlay/openssl/md5.h",
    "native/slicer-runtime/overlay/png.h",
    "native/slicer-runtime/overlay/sapil_gcode_thumbnail.cpp",
    "native/slicer-runtime/overlay/sapil_gcode_thumbnail.h",
    "native/slicer-runtime/adaptive-pressure-advance.patch",
    "native/slicer-runtime/adaptive-layer-height.patch",
    "native/slicer-runtime/engine-profile-options.patch",
    "native/slicer-runtime/engine-nozzle-volume.patch",
    "native/slicer-runtime/engine-branding.patch",
    "native/slicer-runtime/engine-support-flow-ratios.patch",
    "native/slicer-runtime/gcode-thumbnail.patch",
    "native/slicer-runtime/machine-motion-options.patch",
    "native/slicer-runtime/nozzle-hardness-safety.patch",
    "native/slicer-runtime/nozzle-height-safety.patch",
    "native/slicer-runtime/nozzle-volume.patch",
    "native/slicer-runtime/profile-options.patch",
    "native/slicer-runtime/prime-tower-chamfer.patch",
    "native/slicer-runtime/flush-multiplier.patch",
    "native/slicer-runtime/project-3mf.patch",
    "native/slicer-runtime/runtime.patch",
    "native/slicer-runtime/versions.env",
    "localization/i18n/ca/OrcaSlicer_ca.po",
    "localization/i18n/cs/OrcaSlicer_cs.po",
    "localization/i18n/de/OrcaSlicer_de.po",
    "localization/i18n/es/OrcaSlicer_es.po",
    "localization/i18n/fr/OrcaSlicer_fr.po",
    "localization/i18n/hu/OrcaSlicer_hu.po",
    "localization/i18n/it/OrcaSlicer_it.po",
    "localization/i18n/ja/OrcaSlicer_ja.po",
    "localization/i18n/lt/OrcaSlicer_lt.po",
    "localization/i18n/nl/OrcaSlicer_nl.po",
    "localization/i18n/pl/OrcaSlicer_pl.po",
    "localization/i18n/pt_BR/OrcaSlicer_pt_BR.po",
    "localization/i18n/ru/OrcaSlicer_ru.po",
    "localization/i18n/sv/OrcaSlicer_sv.po",
    "localization/i18n/th/OrcaSlicer_th.po",
    "localization/i18n/tr/OrcaSlicer_tr.po",
    "localization/i18n/uk/OrcaSlicer_uk.po",
    "localization/i18n/vi/OrcaSlicer_vi.po",
    "localization/i18n/zh_CN/OrcaSlicer_zh_CN.po",
    "localization/i18n/zh_TW/OrcaSlicer_zh_TW.po",
    "rust-toolchain.toml",
    "rust/duckyslicer-jni/Cargo.lock",
    "rust/duckyslicer-jni/Cargo.toml",
    "tools/generate_android_translations.py",
    "tools/verify_artifact_localization.py",
    "tools/verify_artifact_manifest.py",
)


@dataclass(frozen=True)
class Repository:
    path: str
    checkout: Path
    commit: str
    url: str


def run_git(checkout: Path, *arguments: str, text: bool = True) -> str | bytes:
    return subprocess.check_output(
        ["git", "-C", str(checkout), *arguments],
        text=text,
        stderr=subprocess.DEVNULL,
    )


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def git_file(checkout: Path, commit: str, name: str) -> str | None:
    try:
        return str(run_git(checkout, "show", f"{commit}:{name}"))
    except subprocess.CalledProcessError:
        return None


def submodule_definitions(checkout: Path, commit: str) -> list[tuple[str, str, str]]:
    source = git_file(checkout, commit, ".gitmodules")
    if source is None:
        return []
    parser = configparser.ConfigParser(interpolation=None)
    parser.read_string(source)
    definitions: list[tuple[str, str, str]] = []
    for section in parser.sections():
        if not section.startswith('submodule "'):
            continue
        path = parser.get(section, "path")
        url = parser.get(section, "url")
        tree_entry = str(run_git(checkout, "ls-tree", commit, "--", path)).strip()
        fields = tree_entry.split(maxsplit=3)
        if len(fields) != 4 or fields[0] != "160000" or fields[1] != "commit":
            raise ValueError(f"invalid gitlink for submodule {path}")
        definitions.append((path, url, fields[2]))
    return sorted(definitions)


def discover_repositories(root: Path, commit: str) -> list[Repository]:
    origin = str(run_git(root, "remote", "get-url", "origin")).strip()
    repositories: list[Repository] = []

    def visit(checkout: Path, path: str, revision: str, url: str) -> None:
        if not checkout.is_dir():
            raise ValueError(f"submodule is not initialized: {path}")
        actual = str(run_git(checkout, "rev-parse", "HEAD")).strip()
        if actual != revision:
            raise ValueError(f"submodule pin mismatch at {path}: expected {revision}, found {actual}")
        repositories.append(Repository(path, checkout, revision, url))
        for child_path, child_url, child_commit in submodule_definitions(checkout, revision):
            combined = child_path if path == "." else f"{path}/{child_path}"
            visit(checkout / child_path, combined, child_commit, child_url)

    visit(root, ".", commit, origin)
    return repositories


def parse_native_inputs(source: str) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in source.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        key, separator, value = stripped.partition("=")
        if not separator or not re.fullmatch(r"[A-Z][A-Z0-9_]+", key):
            raise ValueError(f"invalid native input line: {line}")
        values[key] = value
    return dict(sorted(values.items()))


def source_manifest(
    root: Path,
    repositories: list[Repository],
    version: str,
    version_code: int,
    epoch: int,
) -> dict[str, object]:
    native_inputs_source = git_file(
        root, repositories[0].commit, "native/slicer-runtime/versions.env"
    )
    if native_inputs_source is None:
        raise ValueError("required native input lock is missing")
    native_inputs = parse_native_inputs(native_inputs_source)
    repository_commits = {repository.path: repository.commit for repository in repositories}
    expected_native_commits = {
        "ANDROID_SLICER_RUNTIME_COMMIT": "third_party/android-slicer-runtime",
        "SLICER_ENGINE_COMMIT": (
            "third_party/android-slicer-runtime/app/src/main/cpp/orcaslicer"
        ),
    }
    for key, repository_path in expected_native_commits.items():
        if native_inputs.get(key) != repository_commits.get(repository_path):
            raise ValueError(f"{key} does not match recursive submodule {repository_path}")
    build_inputs = []
    root_commit = repositories[0].commit
    for relative in BUILD_INPUTS:
        if relative.endswith("gradle-wrapper.jar"):
            try:
                encoded = run_git(root, "show", f"{root_commit}:{relative}", text=False)
            except subprocess.CalledProcessError as error:
                raise ValueError(f"required build input is missing: {relative}") from error
            assert isinstance(encoded, bytes)
        else:
            contents = git_file(root, root_commit, relative)
            if contents is None:
                raise ValueError(f"required build input is missing: {relative}")
            encoded = contents.encode("utf-8")
        build_inputs.append(
            {
                "path": relative,
                "sha256": hashlib.sha256(encoded).hexdigest(),
                "bytes": len(encoded),
            }
        )
    return {
        "schemaVersion": 1,
        "project": "DuckySlicer",
        "version": version,
        "androidVersionCode": version_code,
        "sourceDateEpoch": epoch,
        "rootCommit": root_commit,
        "repositories": [
            {"path": repository.path, "url": repository.url, "commit": repository.commit}
            for repository in repositories
        ],
        "nativeInputs": native_inputs,
        "buildInputs": build_inputs,
        "excludedNonBuildInstructions": {
            "fileNames": sorted(EXCLUDED_INSTRUCTION_NAMES),
            "directoryNames": sorted(EXCLUDED_INSTRUCTION_DIRECTORIES),
            "fileSuffixes": sorted(EXCLUDED_INSTRUCTION_SUFFIXES),
        },
    }


def include_member(path: PurePosixPath) -> bool:
    return not (
        path.name in EXCLUDED_INSTRUCTION_NAMES
        or any(part in EXCLUDED_INSTRUCTION_DIRECTORIES for part in path.parts)
        or path.name.endswith(EXCLUDED_INSTRUCTION_SUFFIXES)
    )


def normalized_member(member: tarfile.TarInfo, name: str, epoch: int) -> tarfile.TarInfo:
    output = tarfile.TarInfo(name)
    output.size = member.size
    output.mode = member.mode
    output.type = member.type
    output.linkname = member.linkname
    output.mtime = epoch
    output.uid = 0
    output.gid = 0
    output.uname = ""
    output.gname = ""
    return output


def validate_archive_path(name: str) -> None:
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts or not path.parts:
        raise ValueError(f"unsafe archive path: {name}")


def validate_archive_link(name: str, linkname: str) -> None:
    if not linkname:
        raise ValueError(f"archive link has no target: {name}")
    target = PurePosixPath(linkname)
    if target.is_absolute():
        raise ValueError(f"archive link has an absolute target: {name} -> {linkname}")
    resolved = posixpath.normpath(posixpath.join(posixpath.dirname(name), linkname))
    prefix = PurePosixPath(name).parts[0]
    if resolved != prefix and not resolved.startswith(f"{prefix}/"):
        raise ValueError(f"archive link escapes the release prefix: {name} -> {linkname}")


def write_repository(
    output: tarfile.TarFile,
    repository: Repository,
    prefix: str,
    epoch: int,
    names: set[str],
) -> None:
    with subprocess.Popen(
        ["git", "-C", str(repository.checkout), "archive", "--format=tar", repository.commit],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    ) as process:
        assert process.stdout is not None
        assert process.stderr is not None
        with tarfile.open(fileobj=process.stdout, mode="r|") as source:
            for member in source:
                relative = PurePosixPath(member.name)
                if not include_member(relative):
                    continue
                repository_prefix = "" if repository.path == "." else f"{repository.path}/"
                name = f"{prefix}/{repository_prefix}{relative.as_posix()}"
                validate_archive_path(name)
                if member.issym():
                    validate_archive_link(name, member.linkname)
                if name in names:
                    if member.isdir():
                        continue
                    raise ValueError(f"duplicate archive path: {name}")
                names.add(name)
                file_object = source.extractfile(member) if member.isfile() else None
                output.addfile(normalized_member(member, name, epoch), file_object)
        process.stdout.close()
        error = process.stderr.read().decode("utf-8", errors="replace")
        process.stderr.close()
        if process.wait() != 0:
            raise ValueError(f"git archive failed for {repository.path}: {error.strip()}")


def create_source_bundle(
    root: Path,
    version: str,
    version_code: int,
    archive_path: Path,
    manifest_path: Path,
    commit: str = "HEAD",
) -> dict[str, object]:
    if VERSION.fullmatch(version) is None:
        raise ValueError(f"invalid release version: {version}")
    if version_code <= 0:
        raise ValueError("Android versionCode must be positive")
    resolved_commit = str(run_git(root, "rev-parse", f"{commit}^{{commit}}")).strip()
    epoch = int(str(run_git(root, "show", "-s", "--format=%ct", resolved_commit)).strip())
    repositories = discover_repositories(root, resolved_commit)
    manifest = source_manifest(root, repositories, version, version_code, epoch)
    manifest_bytes = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode("utf-8")
    prefix = f"DuckySlicer-{version}-source"
    archive_path.parent.mkdir(parents=True, exist_ok=True)
    names: set[str] = set()
    with archive_path.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=9, mtime=epoch) as compressed:
            with tarfile.open(fileobj=compressed, mode="w|", format=tarfile.PAX_FORMAT) as archive:
                for repository in repositories:
                    write_repository(archive, repository, prefix, epoch, names)
                internal_name = f"{prefix}/SOURCE_MANIFEST.json"
                if internal_name in names:
                    raise ValueError(f"repository already contains reserved path: {internal_name}")
                info = tarfile.TarInfo(internal_name)
                info.size = len(manifest_bytes)
                info.mode = 0o644
                info.mtime = epoch
                info.uid = 0
                info.gid = 0
                archive.addfile(info, io.BytesIO(manifest_bytes))
    release_manifest: dict[str, object] = {
        "schemaVersion": 1,
        "source": manifest,
        "archive": {
            "fileName": archive_path.name,
            "sha256": file_sha256(archive_path),
            "bytes": archive_path.stat().st_size,
        },
    }
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(
        json.dumps(release_manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return release_manifest


def verify_source_bundle(archive_path: Path, manifest_path: Path) -> dict[str, object]:
    release_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    archive_record = release_manifest.get("archive", {})
    if archive_record.get("fileName") != archive_path.name:
        raise ValueError("source archive filename does not match its manifest")
    if archive_record.get("sha256") != file_sha256(archive_path):
        raise ValueError("source archive SHA-256 does not match its manifest")
    if archive_record.get("bytes") != archive_path.stat().st_size:
        raise ValueError("source archive size does not match its manifest")
    source = release_manifest.get("source")
    if not isinstance(source, dict) or source.get("schemaVersion") != 1:
        raise ValueError("unsupported source manifest")
    prefix = f"DuckySlicer-{source.get('version')}-source"
    internal_name = f"{prefix}/SOURCE_MANIFEST.json"
    names: set[str] = set()
    internal: dict[str, object] | None = None
    with tarfile.open(archive_path, "r:gz") as archive:
        for member in archive:
            validate_archive_path(member.name)
            if member.name in names:
                raise ValueError(f"duplicate archive path: {member.name}")
            names.add(member.name)
            if not (member.name == prefix or member.name.startswith(f"{prefix}/")):
                raise ValueError(f"archive entry is outside the release prefix: {member.name}")
            if member.issym():
                validate_archive_link(member.name, member.linkname)
            if member.name == internal_name:
                extracted = archive.extractfile(member)
                if extracted is None:
                    raise ValueError("internal source manifest is not a regular file")
                internal = json.load(extracted)
    if internal != source:
        raise ValueError("internal and detached source manifests do not match")
    repository_paths = {entry["path"] for entry in source.get("repositories", [])}
    if "." not in repository_paths or len(repository_paths) < 3:
        raise ValueError("source bundle must record the root and recursive native submodules")
    return release_manifest


def main(argv: list[str]) -> None:
    if len(argv) == 4 and argv[1] == "--verify":
        try:
            verified = verify_source_bundle(Path(argv[2]), Path(argv[3]))
        except (OSError, ValueError, json.JSONDecodeError, tarfile.TarError) as error:
            raise SystemExit(str(error)) from error
        print(f"Verified corresponding source for {verified['source']['rootCommit']}")
        return
    if len(argv) != 5:
        raise SystemExit(f"usage: {argv[0]} VERSION VERSION_CODE ARCHIVE MANIFEST")
    try:
        result = create_source_bundle(
            ROOT,
            argv[1],
            int(argv[2]),
            Path(argv[3]),
            Path(argv[4]),
        )
        verify_source_bundle(Path(argv[3]), Path(argv[4]))
    except (OSError, ValueError, json.JSONDecodeError, tarfile.TarError) as error:
        raise SystemExit(str(error)) from error
    print(
        f"Generated {result['archive']['fileName']} for {len(result['source']['repositories'])} "
        "repositories"
    )


if __name__ == "__main__":
    main(sys.argv)
