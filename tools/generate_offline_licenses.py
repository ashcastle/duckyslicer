#!/usr/bin/env python3
"""Build the deterministic, offline license bundle packaged in each APK."""

from __future__ import annotations

import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

if __package__:
    from .generate_license_inventory import normalize_cargo_expression
    from .native_license_policy import (
        NativeLicenseError,
        expected_vendored_directories,
        native_components,
        native_notice_sources,
    )
else:
    from generate_license_inventory import normalize_cargo_expression
    from native_license_policy import (
        NativeLicenseError,
        expected_vendored_directories,
        native_components,
        native_notice_sources,
    )


ROOT = Path(__file__).resolve().parent.parent
LICENSE_FILE_PREFIXES = ("license", "copying", "unlicense", "copyright")
KNOWN_CARGO_LICENSES = frozenset({"Apache-2.0", "MIT", "Unicode-3.0", "Unlicense"})
EXPRESSION_WORDS = frozenset({"AND", "OR", "WITH"})


class BundleError(ValueError):
    """The offline license bundle would be incomplete or non-deterministic."""


def parse_gradle_inventory(source: str) -> tuple[str, ...]:
    coordinates: list[str] = []
    for line_number, line in enumerate(source.splitlines(), start=1):
        parts = line.strip().split(":")
        if len(parts) != 3 or not all(parts):
            raise BundleError(f"invalid Gradle coordinate on line {line_number}: {line!r}")
        coordinates.append(":".join(parts))
    if len(coordinates) != len(set(coordinates)):
        raise BundleError("Gradle inventory contains duplicate components")
    return tuple(sorted(coordinates))


def discover_vendored_directories(ninja_dependencies: str) -> frozenset[str]:
    observed: set[str] = set()
    valid_target = False
    for line in ninja_dependencies.splitlines():
        if line and not line[0].isspace():
            valid_target = line.rstrip().endswith("(VALID)")
            continue
        if not valid_target:
            continue
        match = re.search(r"orcaslicer/deps_src/([^/\\\s]+)", line)
        if match:
            observed.add(match.group(1))
    return frozenset(observed)


def verify_vendored_policy(ninja_dependencies: str) -> None:
    observed = discover_vendored_directories(ninja_dependencies)
    expected = expected_vendored_directories()
    if observed != expected:
        raise BundleError(
            f"vendored native license policy mismatch; missing={sorted(observed - expected)}, "
            f"stale={sorted(expected - observed)}"
        )


def _read_text(path: Path) -> str:
    try:
        data = path.read_bytes()
        text = data.decode("utf-8")
    except (OSError, UnicodeDecodeError) as error:
        raise BundleError(f"cannot read license text {path}: {error}") from error
    normalized = text.replace("\r\n", "\n").replace("\r", "\n").strip()
    if not normalized:
        raise BundleError(f"empty license text: {path}")
    return normalized


def _cargo_packages(metadata: dict[str, object]) -> list[dict[str, object]]:
    packages = metadata.get("packages")
    if not isinstance(packages, list):
        raise BundleError("Cargo metadata has no package list")
    result = []
    for package in packages:
        if not isinstance(package, dict) or package.get("name") == "duckyslicer-jni":
            continue
        if not all(isinstance(package.get(field), str) for field in ("name", "version", "license", "manifest_path")):
            raise BundleError(f"Cargo package metadata is incomplete: {package!r}")
        result.append(package)
    return sorted(result, key=lambda item: (str(item["name"]), str(item["version"])))


def _cargo_license_files(package: dict[str, object]) -> tuple[Path, ...]:
    directory = Path(str(package["manifest_path"])).parent
    try:
        files = sorted(
            path
            for path in directory.iterdir()
            if path.is_file() and path.name.lower().startswith(LICENSE_FILE_PREFIXES)
        )
    except OSError as error:
        raise BundleError(f"cannot inspect Cargo package {package['name']}: {error}") from error
    return tuple(files)


def _license_identifiers(expression: str) -> frozenset[str]:
    return frozenset(re.findall(r"[A-Za-z][A-Za-z0-9.-]*", expression)) - EXPRESSION_WORDS


def _canonical_cargo_documents(packages: list[dict[str, object]]) -> dict[str, Path]:
    candidates: dict[str, list[Path]] = {identifier: [] for identifier in KNOWN_CARGO_LICENSES}
    for package in packages:
        for path in _cargo_license_files(package):
            name = path.name.lower()
            if "apache" in name:
                candidates["Apache-2.0"].append(path)
            if "unicode" in name:
                candidates["Unicode-3.0"].append(path)
            if "unlicense" in name:
                candidates["Unlicense"].append(path)
            if "mit" in name or name == "license":
                candidates["MIT"].append(path)
    missing = sorted(identifier for identifier, paths in candidates.items() if not paths)
    if missing:
        raise BundleError(f"Cargo graph has no canonical text for licenses: {missing}")
    return {identifier: sorted(paths)[0] for identifier, paths in candidates.items()}


def render_bundle(
    summary: str,
    component_records: list[tuple[str, str, tuple[tuple[str, str], ...]]],
) -> str:
    """Render component index plus content-addressed documents."""
    if not component_records:
        raise BundleError("license bundle has no components")
    seen_components: set[str] = set()
    documents: dict[str, tuple[str, str]] = {}
    index_lines: list[str] = []
    for reference, expression, sources in sorted(component_records):
        if reference in seen_components:
            raise BundleError(f"duplicate license component: {reference}")
        if not expression.strip() or not sources:
            raise BundleError(f"component has no reviewed license material: {reference}")
        seen_components.add(reference)
        document_ids: list[str] = []
        for label, content in sources:
            normalized = content.replace("\r\n", "\n").replace("\r", "\n").strip()
            if not normalized:
                raise BundleError(f"empty license document for {reference}: {label}")
            digest = hashlib.sha256(normalized.encode("utf-8")).hexdigest()
            document_id = digest[:16]
            existing = documents.setdefault(document_id, (label, normalized))
            if existing[1] != normalized:
                raise BundleError(f"license document digest collision: {document_id}")
            document_ids.append(document_id)
        index_lines.append(f"- {reference}\n  License: {expression}\n  Documents: {', '.join(sorted(set(document_ids)))}")

    sections = [
        "DuckySlicer third-party licenses",
        "================================",
        "",
        summary.strip(),
        "",
        "Resolved component index",
        "------------------------",
        *index_lines,
        "",
        "License and attribution documents",
        "---------------------------------",
    ]
    for document_id, (label, content) in sorted(documents.items()):
        sections.extend(("", f"[{document_id}] {label}", "~" * (19 + len(label)), content))
    return "\n".join(sections).rstrip() + "\n"


def build_component_records(
    root: Path,
    ndk_root: Path,
    gradle_source: str,
    cargo_metadata: dict[str, object],
) -> list[tuple[str, str, tuple[tuple[str, str], ...]]]:
    records: list[tuple[str, str, tuple[tuple[str, str], ...]]] = []

    native_notices = native_notice_sources(root, ndk_root)
    for component in native_components(root):
        reference = str(component["bom-ref"])
        expression = str(component["licenses"][0]["expression"])
        sources = tuple(
            (f"native/{path.name}", _read_text(path))
            for path in native_notices[reference]
        )
        records.append((reference, expression, sources))

    apache_path = root / "build/native-slicer/dependency-sources/onetbb/LICENSE.txt"
    apache_text = _read_text(apache_path)
    for coordinate in parse_gradle_inventory(gradle_source):
        group, name, version = coordinate.split(":")
        records.append(
            (
                f"pkg:maven/{group}/{name}@{version}",
                "Apache-2.0",
                (("Android dependencies/Apache-2.0", apache_text),),
            )
        )

    cargo_packages = _cargo_packages(cargo_metadata)
    canonical = _canonical_cargo_documents(cargo_packages)
    for package in cargo_packages:
        expression = normalize_cargo_expression(str(package["license"]))
        identifiers = _license_identifiers(expression)
        unknown = sorted(identifiers - KNOWN_CARGO_LICENSES)
        if unknown:
            raise BundleError(f"Cargo package has unreviewed license identifiers: {unknown}")
        sources = [
            (f"Cargo/{package['name']}@{package['version']}/{path.name}", _read_text(path))
            for path in _cargo_license_files(package)
        ]
        for identifier in sorted(identifiers):
            path = canonical[identifier]
            sources.append((f"Cargo canonical/{identifier}/{path.name}", _read_text(path)))
        records.append(
            (
                f"pkg:cargo/{package['name']}@{package['version']}",
                expression,
                tuple(sources),
            )
        )
    return records


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit("usage: generate_offline_licenses.py GRADLE_INVENTORY NDK_ROOT OUTPUT")
    gradle_inventory = Path(sys.argv[1]).resolve()
    ndk_root = Path(sys.argv[2]).resolve()
    output = Path(sys.argv[3]).resolve()
    try:
        cargo = subprocess.run(
            [
                "cargo",
                "metadata",
                "--manifest-path",
                str(ROOT / "rust/duckyslicer-jni/Cargo.toml"),
                "--format-version",
                "1",
                "--locked",
            ],
            check=True,
            capture_output=True,
            text=True,
        )
        ninja = subprocess.run(
            ["ninja", "-C", str(ROOT / "build/native-slicer/build/runtime"), "-t", "deps"],
            check=True,
            capture_output=True,
            text=True,
        )
        verify_vendored_policy(ninja.stdout)
        records = build_component_records(
            ROOT,
            ndk_root,
            gradle_inventory.read_text(encoding="utf-8"),
            json.loads(cargo.stdout),
        )
        bundle = render_bundle(
            (ROOT / "THIRD_PARTY_NOTICES.md").read_text(encoding="utf-8"),
            records,
        )
    except (
        OSError,
        subprocess.CalledProcessError,
        json.JSONDecodeError,
        NativeLicenseError,
        BundleError,
    ) as error:
        raise SystemExit(f"Offline license generation failed: {error}") from error
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(bundle, encoding="utf-8")
    print(f"Generated {output} with {len(records)} licensed components")


if __name__ == "__main__":
    main()
