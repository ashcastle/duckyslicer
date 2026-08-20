#!/usr/bin/env python3
"""Generate and validate DuckySlicer's redistributable qualification corpus."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
from collections.abc import Iterable, Sequence
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
CORPUS_ROOT = ROOT / "qualification/corpus"
MANIFEST = CORPUS_ROOT / "manifest.json"
REQUIRED_CASES = {
    "simple-part",
    "hollow-topology",
    "bridge-overhang",
    "automatic-supports",
    "feature-filament-routing",
    "dual-support-filaments",
    "tree-supports",
    "multi-object",
    "dense-preview",
}
ROLE_NAMES = {
    "outerWall",
    "innerWall",
    "sparseInfill",
    "topSurface",
    "internalSolid",
    "support",
    "bridge",
    "adhesion",
    "other",
    "bottomSurface",
}
Vec3 = tuple[float, float, float]
Triangle = tuple[Vec3, Vec3, Vec3]


class CorpusError(RuntimeError):
    """The checked-in qualification corpus is incomplete or inconsistent."""


def _number(value: float) -> str:
    if value == int(value):
        return str(int(value))
    return f"{value:.6f}".rstrip("0").rstrip(".")


def _quad(a: Vec3, b: Vec3, c: Vec3, d: Vec3) -> list[Triangle]:
    return [(a, b, c), (a, c, d)]


def _box(x0: float, x1: float, y0: float, y1: float, z0: float, z1: float) -> list[Triangle]:
    p000 = (x0, y0, z0)
    p100 = (x1, y0, z0)
    p110 = (x1, y1, z0)
    p010 = (x0, y1, z0)
    p001 = (x0, y0, z1)
    p101 = (x1, y0, z1)
    p111 = (x1, y1, z1)
    p011 = (x0, y1, z1)
    return [
        *_quad(p000, p010, p110, p100),
        *_quad(p001, p101, p111, p011),
        *_quad(p000, p100, p101, p001),
        *_quad(p100, p110, p111, p101),
        *_quad(p110, p010, p011, p111),
        *_quad(p010, p000, p001, p011),
    ]


def _open_box() -> list[Triangle]:
    outer = (-15.0, 15.0)
    inner = (-11.0, 11.0)
    bottom = 0.0
    cavity_bottom = 3.0
    top = 30.0
    low, high = outer
    hole_low, hole_high = inner
    triangles: list[Triangle] = []

    triangles += _quad((low, high, bottom), (high, high, bottom), (high, low, bottom), (low, low, bottom))
    triangles += _quad((low, low, bottom), (high, low, bottom), (high, low, top), (low, low, top))
    triangles += _quad((high, low, bottom), (high, high, bottom), (high, high, top), (high, low, top))
    triangles += _quad((high, high, bottom), (low, high, bottom), (low, high, top), (high, high, top))
    triangles += _quad((low, high, bottom), (low, low, bottom), (low, low, top), (low, high, top))

    triangles += _quad(
        (hole_low, hole_low, cavity_bottom),
        (hole_low, hole_high, cavity_bottom),
        (hole_high, hole_high, cavity_bottom),
        (hole_high, hole_low, cavity_bottom),
    )
    triangles += _quad((hole_low, hole_low, cavity_bottom), (hole_low, hole_low, top), (hole_high, hole_low, top), (hole_high, hole_low, cavity_bottom))
    triangles += _quad((hole_high, hole_low, cavity_bottom), (hole_high, hole_low, top), (hole_high, hole_high, top), (hole_high, hole_high, cavity_bottom))
    triangles += _quad((hole_high, hole_high, cavity_bottom), (hole_high, hole_high, top), (hole_low, hole_high, top), (hole_low, hole_high, cavity_bottom))
    triangles += _quad((hole_low, hole_high, cavity_bottom), (hole_low, hole_high, top), (hole_low, hole_low, top), (hole_low, hole_low, cavity_bottom))

    strips = (
        (low, high, low, hole_low),
        (low, high, hole_high, high),
        (low, hole_low, hole_low, hole_high),
        (hole_high, high, hole_low, hole_high),
    )
    for x0, x1, y0, y1 in strips:
        triangles += _quad((x0, y0, top), (x1, y0, top), (x1, y1, top), (x0, y1, top))
    return triangles


def _cylinder(radius: float, height: float, sides: int) -> list[Triangle]:
    bottom_center = (0.0, 0.0, 0.0)
    top_center = (0.0, 0.0, height)
    bottom = [
        (radius * math.cos(2 * math.pi * index / sides), radius * math.sin(2 * math.pi * index / sides), 0.0)
        for index in range(sides)
    ]
    top = [(x, y, height) for x, y, _ in bottom]
    triangles: list[Triangle] = []
    for index in range(sides):
        following = (index + 1) % sides
        triangles.append((bottom_center, bottom[following], bottom[index]))
        triangles.append((top_center, top[index], top[following]))
        triangles += _quad(bottom[index], bottom[following], top[following], top[index])
    return triangles


def generated_models() -> dict[str, list[Triangle]]:
    return {
        "models/simple-cube.stl": _box(-10, 10, -10, 10, 0, 20),
        "models/hollow-open-box.stl": _open_box(),
        "models/bridge.stl": [
            *_box(-25, -16, -8, 8, 0, 20),
            *_box(16, 25, -8, 8, 0, 20),
            *_box(-25, 25, -8, 8, 20, 24),
        ],
        "models/support-overhang.stl": [
            *_box(-4, 4, -4, 4, 0, 22),
            *_box(-22, 22, -22, 22, 22, 26),
        ],
        "models/dense-cylinder.stl": _cylinder(25, 100, 128),
    }


def encode_stl(name: str, triangles: Iterable[Triangle]) -> bytes:
    lines = [f"solid {name}"]
    for triangle in triangles:
        lines.extend(("  facet normal 0 0 0", "    outer loop"))
        lines.extend(
            f"      vertex {_number(x)} {_number(y)} {_number(z)}"
            for x, y, z in triangle
        )
        lines.extend(("    endloop", "  endfacet"))
    lines.append(f"endsolid {name}")
    return ("\n".join(lines) + "\n").encode("ascii")


def generated_bytes() -> dict[str, bytes]:
    return {
        relative: encode_stl(Path(relative).stem.replace("-", "_"), triangles)
        for relative, triangles in generated_models().items()
    }


def sha256(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def load_manifest(path: Path = MANIFEST) -> dict[str, object]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise CorpusError(f"Could not read {path}: {error}") from error
    if not isinstance(value, dict):
        raise CorpusError("Qualification manifest must be a JSON object")
    return value


def _locked_engine_revision() -> str:
    values: dict[str, str] = {}
    path = ROOT / "native/slicer-runtime/versions.env"
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            values[key] = value
    return values.get("SLICER_ENGINE_COMMIT", "")


def validate(manifest: dict[str, object], *, check_files: bool = True) -> None:
    if manifest.get("schemaVersion") != 1:
        raise CorpusError("Unsupported qualification manifest schema")
    engine = manifest.get("engine")
    if not isinstance(engine, dict) or engine.get("revision") != _locked_engine_revision():
        raise CorpusError("Qualification manifest does not pin the locked Orca engine revision")
    profile = manifest.get("effectiveProfile")
    required_profile = {
        "printerId": "snapmaker-u1-04",
        "processId": "snapmaker-u1-04-020",
        "filamentId": "generic-pla",
    }
    if not isinstance(profile, dict) or any(profile.get(key) != value for key, value in required_profile.items()):
        raise CorpusError("Qualification manifest profile identity is incomplete")

    models = manifest.get("models")
    generated = generated_bytes()
    if not isinstance(models, list) or len(models) != len(generated):
        raise CorpusError("Qualification manifest model inventory is incomplete")
    model_entries: dict[str, dict[str, object]] = {}
    for entry in models:
        if not isinstance(entry, dict) or not isinstance(entry.get("path"), str):
            raise CorpusError("Qualification model entry is invalid")
        relative = str(entry["path"])
        if relative in model_entries:
            raise CorpusError(f"Duplicate qualification model: {relative}")
        model_entries[relative] = entry
    if model_entries.keys() != generated.keys():
        raise CorpusError("Qualification model paths differ from the deterministic generator")
    for relative, expected in generated.items():
        entry = model_entries[relative]
        if entry.get("sha256") != sha256(expected):
            raise CorpusError(f"Qualification model digest is stale: {relative}")
        if entry.get("triangles") != len(generated_models()[relative]):
            raise CorpusError(f"Qualification model triangle count is stale: {relative}")
        if check_files:
            path = CORPUS_ROOT / relative
            if not path.is_file() or path.read_bytes() != expected:
                raise CorpusError(f"Qualification model is not reproducible: {relative}")

    cases = manifest.get("cases")
    if not isinstance(cases, list):
        raise CorpusError("Qualification cases are missing")
    identifiers = [case.get("id") for case in cases if isinstance(case, dict)]
    if len(identifiers) != len(cases) or set(identifiers) != REQUIRED_CASES:
        raise CorpusError(
            f"Qualification cases must cover the {len(REQUIRED_CASES)} required scenarios exactly once"
        )
    case_by_id = {str(case["id"]): case for case in cases if isinstance(case, dict)}
    for case in cases:
        assert isinstance(case, dict)
        case_models = case.get("models")
        expected = case.get("expected")
        if not isinstance(case_models, list) or not case_models:
            raise CorpusError(f"Qualification case has no models: {case.get('id')}")
        if any(path not in generated for path in case_models):
            raise CorpusError(f"Qualification case references an unknown model: {case.get('id')}")
        filament_ids = case.get("filamentIds", ["generic-pla"])
        if (
            not isinstance(filament_ids, list)
            or not 1 <= len(filament_ids) <= 4
            or filament_ids[0] != "generic-pla"
            or any(value not in {"generic-pla", "generic-petg"} for value in filament_ids)
        ):
            raise CorpusError(f"Qualification case has invalid filament slots: {case.get('id')}")
        model_slots = case.get("modelFilamentSlots", [0] * len(case_models))
        if (
            not isinstance(model_slots, list)
            or len(model_slots) != len(case_models)
            or any(
                not isinstance(value, int)
                or isinstance(value, bool)
                or value not in range(len(filament_ids))
                for value in model_slots
            )
        ):
            raise CorpusError(f"Qualification case has invalid model filament routing: {case.get('id')}")
        support_enabled = case.get("supportEnabled", False)
        support_type = case.get("supportType", "normal(auto)")
        if not isinstance(support_enabled, bool) or support_type not in {
            "normal(auto)",
            "tree(auto)",
        }:
            raise CorpusError(f"Qualification case has invalid support mode: {case.get('id')}")
        support_style = case.get("supportStyle", "default")
        compatible_styles = (
            {"default", "organic", "tree_slim", "tree_strong", "tree_hybrid"}
            if support_type == "tree(auto)"
            else {"default", "grid", "snug"}
        )
        if support_style not in compatible_styles:
            raise CorpusError(f"Qualification case has invalid support style: {case.get('id')}")
        if not support_enabled and any(
            key in case
            for key in (
                "supportType",
                "supportStyle",
                "supportFilament",
                "supportInterfaceFilament",
            )
        ):
            raise CorpusError(f"Qualification case configures disabled support: {case.get('id')}")
        for key in ("supportFilament", "supportInterfaceFilament"):
            value = case.get(key, 0)
            if (
                not isinstance(value, int)
                or isinstance(value, bool)
                or value not in range(len(filament_ids) + 1)
            ):
                raise CorpusError(f"Qualification case has invalid {key}: {case.get('id')}")
        for key in ("supportInterfaceTopLayers", "supportInterfaceBottomLayers"):
            value = case.get(key, 0)
            if (
                not isinstance(value, int)
                or isinstance(value, bool)
                or value not in range(101)
            ):
                raise CorpusError(f"Qualification case has invalid {key}: {case.get('id')}")
        feature_filaments = case.get("featureFilaments")
        if feature_filaments is not None:
            required_feature_keys = {
                "infillOverrideEnabled",
                "baseFirstLayers",
                "baseLastLayers",
                "sparseInfillFilament",
                "wallFilament",
                "solidInfillFilament",
            }
            if not isinstance(feature_filaments, dict) or set(feature_filaments) != required_feature_keys:
                raise CorpusError(f"Qualification case has invalid feature routing: {case.get('id')}")
            if not isinstance(feature_filaments["infillOverrideEnabled"], bool):
                raise CorpusError(f"Qualification case has invalid feature routing: {case.get('id')}")
            if any(
                not isinstance(feature_filaments[key], int)
                or isinstance(feature_filaments[key], bool)
                or feature_filaments[key] < 0
                for key in ("baseFirstLayers", "baseLastLayers")
            ) or any(
                not isinstance(feature_filaments[key], int)
                or isinstance(feature_filaments[key], bool)
                or feature_filaments[key] not in range(1, len(filament_ids) + 1)
                for key in ("sparseInfillFilament", "wallFilament", "solidInfillFilament")
            ):
                raise CorpusError(f"Qualification case has invalid feature routing: {case.get('id')}")
        if not isinstance(expected, dict) or not expected.get("requiredRoles"):
            raise CorpusError(f"Qualification case has no observable expectations: {case.get('id')}")
        required_roles = expected["requiredRoles"]
        if (
            not isinstance(required_roles, list)
            or len(set(required_roles)) != len(required_roles)
            or not set(required_roles) <= ROLE_NAMES
        ):
            raise CorpusError(f"Qualification case has invalid required roles: {case.get('id')}")
        minimum_layers = expected.get("minRoleLayers")
        minimum_extrusion = expected.get("minRoleExtrusionMm")
        if (
            not isinstance(minimum_layers, dict)
            or set(minimum_layers) != set(required_roles)
            or any(not isinstance(value, int) or value <= 0 for value in minimum_layers.values())
        ):
            raise CorpusError(f"Qualification case has incomplete role-layer bounds: {case.get('id')}")
        if (
            not isinstance(minimum_extrusion, dict)
            or set(minimum_extrusion) != set(required_roles)
            or any(
                not isinstance(value, (int, float)) or isinstance(value, bool) or value <= 0
                for value in minimum_extrusion.values()
            )
        ):
            raise CorpusError(f"Qualification case has incomplete role-extrusion bounds: {case.get('id')}")
        forbidden = expected.get("forbiddenRoles")
        first = expected.get("firstLayerRoles")
        last = expected.get("lastLayerRoles")
        interior = expected.get("interiorRoles")
        if (
            not isinstance(forbidden, list)
            or not set(forbidden) <= ROLE_NAMES - set(required_roles)
            or any(
                not isinstance(values, list) or not set(values) <= set(required_roles)
                for values in (first, last, interior)
            )
            or not set(interior).isdisjoint(set(first) | set(last))
        ):
            raise CorpusError(f"Qualification case has invalid role windows: {case.get('id')}")
        precedence = expected.get("rolePrecedence")
        if not isinstance(precedence, list) or any(
            not isinstance(rule, dict)
            or set(rule) != {"before", "after"}
            or rule["before"] not in required_roles
            or rule["after"] not in required_roles
            or rule["before"] == rule["after"]
            for rule in precedence
        ):
            raise CorpusError(f"Qualification case has invalid role precedence: {case.get('id')}")
        required_tools = expected.get("requiredTools", [0])
        exact_role_tools = expected.get("exactRoleTools", {})
        minimum_tool_changes = expected.get("minToolChanges", 0)
        if (
            not isinstance(required_tools, list)
            or not required_tools
            or len(set(required_tools)) != len(required_tools)
            or any(
                not isinstance(value, int)
                or isinstance(value, bool)
                or value not in range(len(filament_ids))
                for value in required_tools
            )
            or not isinstance(exact_role_tools, dict)
            or not set(exact_role_tools) <= set(required_roles)
            or any(
                not isinstance(values, list)
                or not values
                or len(set(values)) != len(values)
                or any(
                    not isinstance(value, int)
                    or isinstance(value, bool)
                    or value not in range(len(filament_ids))
                    for value in values
                )
                for values in exact_role_tools.values()
            )
            or not isinstance(minimum_tool_changes, int)
            or isinstance(minimum_tool_changes, bool)
            or minimum_tool_changes < 0
        ):
            raise CorpusError(f"Qualification case has invalid tool-routing expectations: {case.get('id')}")
        different_from = expected.get("supportGeometryDifferentFrom")
        if different_from is not None:
            baseline = case_by_id.get(str(different_from))
            if (
                not support_enabled
                or not isinstance(different_from, str)
                or different_from == case.get("id")
                or not isinstance(baseline, dict)
                or not baseline.get("supportEnabled", False)
                or baseline.get("models") != case_models
            ):
                raise CorpusError(f"Qualification case has invalid support comparison: {case.get('id')}")
    dense = next(case for case in cases if case["id"] == "dense-preview")
    dense_expected = dense["expected"]
    if dense_expected.get("minPreviewLayerCoverage", 0) < 0.9:
        raise CorpusError("Dense Preview must require broad layer coverage")
    if dense_expected.get("minPreviewSegments", 0) < 50_000:
        raise CorpusError("Dense Preview does not exercise the bounded path renderer")


def write_models() -> None:
    for relative, payload in generated_bytes().items():
        destination = CORPUS_ROOT / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(payload)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--write", action="store_true", help="write deterministic STL fixtures")
    mode.add_argument("--check", action="store_true", help="validate manifest and checked-in fixtures")
    args = parser.parse_args(argv)
    try:
        if args.write:
            write_models()
        validate(load_manifest())
    except CorpusError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    print(f"Qualification corpus is reproducible: {len(REQUIRED_CASES)} cases, {_locked_engine_revision()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
