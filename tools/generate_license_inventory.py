#!/usr/bin/env python3
"""Generate a deterministic license inventory for resolved Gradle and Cargo inputs."""

from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
GRADLE_LICENSES = {
    "androidx.": "Apache-2.0",
    "com.google.guava": "Apache-2.0",
    "org.jetbrains": "Apache-2.0",
    "org.jetbrains.kotlin": "Apache-2.0",
    "org.jetbrains.kotlinx": "Apache-2.0",
    "org.jspecify": "Apache-2.0",
}
KNOWN_LICENSE_IDS = {
    "Apache-2.0",
    "MIT",
    "Unicode-3.0",
    "Unlicense",
}
EXPRESSION_WORDS = {"AND", "OR", "WITH"}


class InventoryError(ValueError):
    """A resolved component has no reviewed license policy."""


def normalize_cargo_expression(raw: str) -> str:
    expression = raw.strip().replace("Apache-2.0/MIT", "Apache-2.0 OR MIT")
    expression = expression.replace("MIT/Apache-2.0", "MIT OR Apache-2.0")
    expression = expression.replace("Unlicense/MIT", "Unlicense OR MIT")
    if not expression:
        raise InventoryError("Cargo package has no license expression")
    identifiers = set(re.findall(r"[A-Za-z][A-Za-z0-9.-]*", expression)) - EXPRESSION_WORDS
    unknown = sorted(identifiers - KNOWN_LICENSE_IDS)
    if unknown:
        raise InventoryError(f"Cargo package uses unreviewed license identifiers: {unknown}")
    return expression


def gradle_license(group: str) -> str:
    matches = {
        expression
        for prefix, expression in GRADLE_LICENSES.items()
        if group == prefix or group.startswith(prefix)
    }
    if len(matches) != 1:
        raise InventoryError(f"Gradle group has no unambiguous license policy: {group}")
    return matches.pop()


def build_inventory(gradle_source: str, cargo_metadata: dict[str, object]) -> dict[str, object]:
    components: dict[str, str] = {}
    for line_number, line in enumerate(gradle_source.splitlines(), start=1):
        coordinate = line.strip().split(":")
        if len(coordinate) != 3 or not all(coordinate):
            raise InventoryError(f"invalid Gradle coordinate on line {line_number}: {line!r}")
        group, name, version = coordinate
        reference = f"pkg:maven/{group}/{name}@{version}"
        if reference in components:
            raise InventoryError(f"duplicate Gradle component: {reference}")
        components[reference] = gradle_license(group)

    packages = cargo_metadata.get("packages")
    if not isinstance(packages, list):
        raise InventoryError("Cargo metadata has no package list")
    for package in packages:
        if not isinstance(package, dict) or package.get("name") == "duckyslicer-jni":
            continue
        name = package.get("name")
        version = package.get("version")
        license_text = package.get("license")
        if not isinstance(name, str) or not isinstance(version, str) or not isinstance(license_text, str):
            raise InventoryError(f"Cargo package metadata is incomplete: {package!r}")
        reference = f"pkg:cargo/{name}@{version}"
        expression = normalize_cargo_expression(license_text)
        previous = components.setdefault(reference, expression)
        if previous != expression:
            raise InventoryError(f"Cargo component has conflicting licenses: {reference}")

    return {
        "schemaVersion": 1,
        "components": dict(sorted(components.items())),
    }


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: generate_license_inventory.py GRADLE_INVENTORY OUTPUT")
    gradle_inventory = Path(sys.argv[1]).resolve()
    output = Path(sys.argv[2]).resolve()
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
        inventory = build_inventory(
            gradle_inventory.read_text(encoding="utf-8"),
            json.loads(cargo.stdout),
        )
    except (OSError, subprocess.CalledProcessError, json.JSONDecodeError, InventoryError) as error:
        raise SystemExit(f"License inventory generation failed: {error}") from error
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(inventory, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Generated {output} with {len(inventory['components'])} licensed components")


if __name__ == "__main__":
    main()
