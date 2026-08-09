#!/usr/bin/env python3
"""Generate a deterministic CycloneDX SBOM for a DuckySlicer APK."""

from __future__ import annotations

import hashlib
import json
import re
import sys
import tomllib
import uuid
import zipfile
from pathlib import Path

if __package__:
    from .native_license_policy import native_components
else:
    from native_license_policy import native_components


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def cargo_components(root: Path) -> list[dict[str, str]]:
    lock = tomllib.loads((root / "rust/duckyslicer-jni/Cargo.lock").read_text())
    return [
        {
            "type": "library",
            "name": package["name"],
            "version": package["version"],
            "bom-ref": f"pkg:cargo/{package['name']}@{package['version']}",
            "purl": f"pkg:cargo/{package['name']}@{package['version']}",
        }
        for package in lock["package"]
        if package["name"] != "duckyslicer-jni"
    ]


def gradle_components(root: Path, inventory: Path | None) -> list[dict[str, str]]:
    if inventory is not None:
        coordinates = []
        for line in inventory.read_text().splitlines():
            coordinate = line.strip().split(":")
            if len(coordinate) == 3:
                coordinates.append(tuple(coordinate))
        coordinates = sorted(set(coordinates))
    else:
        build = (root / "android/app/build.gradle.kts").read_text()
        coordinates = sorted(set(re.findall(r'"([\w.-]+):([\w.-]+):([\w.+-]+)"', build)))
    return [
        {
            "type": "library",
            "group": group,
            "name": name,
            "version": version,
            "bom-ref": f"pkg:maven/{group}/{name}@{version}",
            "purl": f"pkg:maven/{group}/{name}@{version}",
        }
        for group, name, version in coordinates
    ]


def apply_license_inventory(
    components: list[dict[str, object]],
    inventory: dict[str, object],
) -> None:
    if inventory.get("schemaVersion") != 1 or not isinstance(inventory.get("components"), dict):
        raise ValueError("license inventory schema is unsupported")
    licenses = inventory["components"]
    required = {
        str(component["bom-ref"])
        for component in components
        if "licenses" not in component
    }
    supplied = set(licenses)
    if required != supplied:
        raise ValueError(
            f"license inventory mismatch; missing={sorted(required - supplied)}, "
            f"unexpected={sorted(supplied - required)}"
        )
    for component in components:
        if "licenses" in component:
            continue
        expression = licenses[str(component["bom-ref"])]
        if not isinstance(expression, str) or not expression.strip():
            raise ValueError(f"empty license expression for {component['bom-ref']}")
        component["licenses"] = [{"expression": expression}]


def parse_apk_license_index(source: str) -> dict[str, str]:
    try:
        index_source = source.split("Resolved component index", 1)[1]
        index_source = index_source.split("License and attribution documents", 1)[0]
    except IndexError as error:
        raise ValueError("APK license index headings are missing") from error
    lines = index_source.splitlines()
    index: dict[str, str] = {}
    for position, line in enumerate(lines):
        if not line.startswith("- "):
            continue
        reference = line[2:]
        if position + 2 >= len(lines):
            raise ValueError(f"truncated APK license index entry: {reference}")
        license_line = lines[position + 1]
        documents_line = lines[position + 2]
        if not license_line.startswith("  License: ") or not documents_line.startswith("  Documents: "):
            raise ValueError(f"malformed APK license index entry: {reference}")
        expression = license_line.removeprefix("  License: ").strip()
        documents = documents_line.removeprefix("  Documents: ").strip()
        if reference in index or not expression or not documents:
            raise ValueError(f"incomplete or duplicate APK license index entry: {reference}")
        index[reference] = expression
    if not index:
        raise ValueError("APK license index is empty")
    return index


def verify_apk_license_bundle(apk: Path, components: list[dict[str, object]]) -> None:
    with zipfile.ZipFile(apk) as archive:
        try:
            source = archive.read("assets/legal/THIRD_PARTY_LICENSES.txt").decode("utf-8")
        except KeyError as error:
            raise ValueError("APK has no offline third-party license bundle") from error
        except UnicodeDecodeError as error:
            raise ValueError("APK third-party license bundle is not UTF-8") from error
    actual = parse_apk_license_index(source)
    expected = {
        str(component["bom-ref"]): str(component["licenses"][0]["expression"])
        for component in components
    }
    if actual != expected:
        raise ValueError(
            f"APK/SBOM license mismatch; missing={sorted(expected.keys() - actual.keys())}, "
            f"unexpected={sorted(actual.keys() - expected.keys())}, "
            f"changed={sorted(ref for ref in actual.keys() & expected.keys() if actual[ref] != expected[ref])}"
        )


def main() -> None:
    if len(sys.argv) != 6:
        raise SystemExit(
            "usage: generate_sbom.py APK OUTPUT VERSION GRADLE_INVENTORY LICENSE_INVENTORY"
        )
    root = Path(__file__).resolve().parent.parent
    apk = Path(sys.argv[1]).resolve()
    output = Path(sys.argv[2]).resolve()
    version = sys.argv[3]
    inventory = Path(sys.argv[4]).resolve()
    license_inventory = Path(sys.argv[5]).resolve()
    if not inventory.is_file():
        raise SystemExit(f"Gradle dependency inventory is unavailable: {inventory}")
    if not license_inventory.is_file():
        raise SystemExit(f"License inventory is unavailable: {license_inventory}")
    components = (
        native_components(root)
        + cargo_components(root)
        + gradle_components(root, inventory)
    )
    components.sort(key=lambda item: str(item["bom-ref"]))
    try:
        apply_license_inventory(
            components,
            json.loads(license_inventory.read_text(encoding="utf-8")),
        )
        verify_apk_license_bundle(apk, components)
    except (OSError, zipfile.BadZipFile, json.JSONDecodeError, ValueError) as error:
        raise SystemExit(f"SBOM license verification failed: {error}") from error
    document = {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "serialNumber": f"urn:uuid:{uuid.UUID(hex=hashlib.sha256((version + sha256(apk)).encode()).hexdigest()[:32])}",
        "version": 1,
        "metadata": {
            "component": {
                "type": "application",
                "name": "DuckySlicer",
                "version": version,
                "hashes": [{"alg": "SHA-256", "content": sha256(apk)}],
                "licenses": [{"expression": "AGPL-3.0-only"}],
            }
        },
        "components": components,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(document, indent=2, sort_keys=True) + "\n")
    print(f"Generated {output} with {len(components)} components")


if __name__ == "__main__":
    main()
