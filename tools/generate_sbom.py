#!/usr/bin/env python3
"""Generate a deterministic CycloneDX SBOM for a DuckySlicer APK."""

from __future__ import annotations

import hashlib
import json
import re
import sys
import tomllib
import uuid
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def native_components(root: Path) -> list[dict[str, object]]:
    values: dict[str, str] = {}
    for line in (root / "native/slicer-runtime/versions.env").read_text().splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            values[key] = value
    names = {
        "ANDROID_SLICER_RUNTIME": "Android slicer runtime",
        "SLICER_ENGINE": "OrcaSlicer",
        "EIGEN": "Eigen",
        "CEREAL": "cereal",
        "NLOHMANN_JSON": "nlohmann-json",
        "ZLIB": "zlib",
        "EXPAT": "Expat",
        "CLIPPER2": "Clipper2",
        "ONETBB": "oneTBB",
        "BOOST_ANDROID": "Boost-for-Android",
        "OCCT": "Open CASCADE Technology",
        "NLOPT": "NLopt",
        "LIBJPEG_TURBO": "libjpeg-turbo",
    }
    components = []
    for prefix, name in names.items():
        revision = values.get(f"{prefix}_COMMIT")
        if revision:
            components.append(
                {
                    "type": "library",
                    "name": name,
                    "version": revision,
                    "bom-ref": f"native:{prefix.lower()}@{revision}",
                    "externalReferences": [
                        {"type": "vcs", "url": values[f"{prefix}_REPOSITORY"]}
                    ] if f"{prefix}_REPOSITORY" in values else [],
                }
            )
    for prefix, name, version in [
        ("BOOST_ARCHIVE", "Boost", "1.84.0"),
        ("CGAL", "CGAL", "5.6"),
        ("GMP", "GMP", "6.3.0"),
        ("MPFR", "MPFR", "4.2.1"),
    ]:
        components.append(
            {
                "type": "library",
                "name": name,
                "version": version,
                "bom-ref": f"native:{prefix.lower()}@{version}",
                "hashes": [{"alg": "SHA-256", "content": values[f"{prefix}_SHA256"]}],
                "externalReferences": [{"type": "distribution", "url": values[f"{prefix}_URL"]}],
            }
        )
    return components


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


def main() -> None:
    if len(sys.argv) not in (4, 5):
        raise SystemExit("usage: generate_sbom.py APK OUTPUT VERSION [GRADLE_INVENTORY]")
    root = Path(__file__).resolve().parent.parent
    apk = Path(sys.argv[1]).resolve()
    output = Path(sys.argv[2]).resolve()
    version = sys.argv[3]
    inventory = Path(sys.argv[4]).resolve() if len(sys.argv) == 5 else None
    if inventory is not None and not inventory.is_file():
        raise SystemExit(f"Gradle dependency inventory is unavailable: {inventory}")
    components = (
        native_components(root)
        + cargo_components(root)
        + gradle_components(root, inventory)
    )
    components.sort(key=lambda item: str(item["bom-ref"]))
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
            }
        },
        "components": components,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(document, indent=2, sort_keys=True) + "\n")
    print(f"Generated {output} with {len(components)} components")


if __name__ == "__main__":
    main()
