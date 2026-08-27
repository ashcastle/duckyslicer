#!/usr/bin/env python3
"""Reject unreviewed Orca profile options that the Android catalog would drop."""

from __future__ import annotations

import argparse
import ast
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parent.parent
DEFAULT_PROFILE_ROOT = (
    ROOT / "build/native-slicer/source/app/src/main/cpp/orcaslicer/resources/profiles"
)
DEFAULT_ENGINE_CONFIG = (
    ROOT
    / "build/native-slicer/source/app/src/main/cpp/orcaslicer/src/libslic3r/PrintConfig.cpp"
)
DEFAULT_GENERATOR = ROOT / "tools/generate_profile_catalog.py"

# These settings are intentionally handled outside the generated slicing catalog.
# Keeping the list explicit makes a newly introduced engine-backed profile option fail
# the production gate until it is mapped or reviewed here.
INTENTIONALLY_UNMAPPED_OPTIONS = {
    # Profile identity, compatibility, UI assets, or arrangement metadata.
    "bed_custom_model",
    "bed_custom_texture",
    "best_object_pos",
    "compatible_printers_condition",
    "compatible_prints",
    "default_bed_type",
    "extruder_colour",
    "filament_notes",
    "filament_settings_id",
    "notes",
    "print_settings_id",
    "printer_model",
    "printer_notes",
    "printer_settings_id",
    "printer_technology",
    "printer_variant",
    "support_multi_bed_types",
    "upward_compatible_machine",
    "wiping_volumes_extruders",
    # Desktop connection state and external post-processing are not imported into
    # the offline Android catalog, especially credentials and machine-local paths.
    "host_type",
    "post_process",
    "print_host",
    "print_host_webui",
    "printhost_apikey",
    "printhost_authorization_type",
    "printhost_cafile",
    "printhost_password",
    "printhost_port",
    "printhost_ssl_ignore_revoke",
    "printhost_user",
    # Engine-generated placeholder, not a user/profile input.
    "filament_extruder_id",
    # Known specialized capabilities that remain explicit production backlog.
    "pellet_flow_coefficient",
    "pellet_modded_printer",
    "template_custom_gcode",
}

# The pinned profile tree only contains neutral values for these legacy or dormant
# options. A future meaningful value must fail the gate instead of being discarded.
DEFAULT_ONLY_OPTIONS = {
    "bbl_use_printhost",
    "calib_flowrate_topinfill_special_order",
    "change_extrusion_role_gcode",
    "compatible_prints_condition",
    "default_junction_deviation",
    "preferred_orientation",
    "time_cost",
    "tree_support_with_infill",
    "z_offset",
}
NEUTRAL_VALUES = {"", "0", "0.0", "false", "none"}
OPTION_NAME = re.compile(r"[a-z][a-z0-9_]*")
ENGINE_DEFINITION = re.compile(r'(?:this->add|new_def)\("([a-z0-9_]+)"')


class CoverageError(RuntimeError):
    """The pinned profile tree contains an unreviewed dropped option."""


@dataclass(frozen=True)
class CoverageReport:
    profile_options: frozenset[str]
    engine_options: frozenset[str]
    mapped_options: frozenset[str]
    intentionally_unmapped: frozenset[str]
    default_only: frozenset[str]


def _without_cpp_comments(source: str) -> str:
    source = re.sub(r"/\*.*?\*/", "", source, flags=re.DOTALL)
    return re.sub(r"//[^\n]*", "", source)


def engine_option_names(source: str) -> set[str]:
    return set(ENGINE_DEFINITION.findall(_without_cpp_comments(source)))


def generator_option_names(source: str) -> set[str]:
    tree = ast.parse(source)
    return {
        node.value
        for node in ast.walk(tree)
        if isinstance(node, ast.Constant)
        and isinstance(node.value, str)
        and OPTION_NAME.fullmatch(node.value)
    }


def _flatten(value: Any) -> Iterable[Any]:
    if isinstance(value, list):
        for item in value:
            yield from _flatten(item)
    else:
        yield value


def load_profile_options(profile_root: Path) -> tuple[set[str], dict[str, list[Any]]]:
    names: set[str] = set()
    values: dict[str, list[Any]] = {}
    for path in sorted(profile_root.rglob("*.json")):
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise CoverageError(f"cannot read profile JSON {path}: {error}") from error
        if not isinstance(raw, dict):
            continue
        names.update(raw)
        for name, value in raw.items():
            values.setdefault(name, []).append(value)
    return names, values


def verify_coverage(
    profile_root: Path,
    engine_config: Path,
    generator: Path,
    intentionally_unmapped: set[str] = INTENTIONALLY_UNMAPPED_OPTIONS,
    default_only: set[str] = DEFAULT_ONLY_OPTIONS,
) -> CoverageReport:
    for path in (profile_root, engine_config, generator):
        if not path.exists():
            raise CoverageError(f"required coverage input is missing: {path}")

    profile_options, profile_values = load_profile_options(profile_root)
    engine_options = engine_option_names(engine_config.read_text(encoding="utf-8"))
    mapped_options = generator_option_names(generator.read_text(encoding="utf-8"))
    relevant = profile_options & engine_options

    unexpected = sorted(relevant - mapped_options - intentionally_unmapped - default_only)
    material_defaults: list[str] = []
    for name in sorted(relevant & default_only):
        material = {
            str(item).strip().casefold()
            for value in profile_values.get(name, [])
            for item in _flatten(value)
            if str(item).strip().casefold() not in NEUTRAL_VALUES
        }
        if material:
            material_defaults.append(f"{name}={sorted(material)[:3]}")

    problems: list[str] = []
    if unexpected:
        problems.append("unreviewed options: " + ", ".join(unexpected))
    if material_defaults:
        problems.append("default-only options gained values: " + "; ".join(material_defaults))
    if problems:
        raise CoverageError("profile option coverage failed: " + " | ".join(problems))

    return CoverageReport(
        profile_options=frozenset(profile_options),
        engine_options=frozenset(engine_options),
        mapped_options=frozenset(relevant & mapped_options),
        intentionally_unmapped=frozenset(relevant & intentionally_unmapped),
        default_only=frozenset(relevant & default_only),
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profiles", type=Path, default=DEFAULT_PROFILE_ROOT)
    parser.add_argument("--engine-config", type=Path, default=DEFAULT_ENGINE_CONFIG)
    parser.add_argument("--generator", type=Path, default=DEFAULT_GENERATOR)
    args = parser.parse_args()
    try:
        report = verify_coverage(args.profiles, args.engine_config, args.generator)
    except CoverageError as error:
        raise SystemExit(str(error)) from error
    print(
        "Profile option coverage passed: "
        f"{len(report.mapped_options)} mapped, "
        f"{len(report.intentionally_unmapped)} reviewed omissions, "
        f"{len(report.default_only)} neutral-only"
    )


if __name__ == "__main__":
    main()
