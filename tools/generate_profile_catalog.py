#!/usr/bin/env python3
"""Normalize the pinned OrcaSlicer profile tree into a compact Android catalog."""

from __future__ import annotations

import hashlib
import json
import math
import re
import struct
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 79
MAX_FILAMENT_SLOTS = 16
SUPPORTED_GCODE_FLAVORS = {"marlin", "marlin2", "klipper"}
INFILL_PATTERNS = {
    "monotonic", "monotonicline", "rectilinear", "alignedrectilinear",
    "zigzag", "crosszag", "lockedzag", "line", "grid", "triangles",
    "tri-hexagon", "cubic", "adaptivecubic", "quartercubic", "supportcubic",
    "lightning", "honeycomb", "3dhoneycomb", "lateral-honeycomb",
    "lateral-lattice", "crosshatch", "tpmsd", "tpmsfk", "gyroid",
    "concentric", "hilbertcurve", "archimedeanchords", "octagramspiral",
}

BINARY_MAGIC = b"DUCKYPC1"
BINARY_STRING = 1
BINARY_FLOAT = 2
BINARY_INT = 3
BINARY_BOOL = 4
BINARY_STRING_LIST = 5
BINARY_FLOAT_LIST = 6
BINARY_NULLABLE_FLOAT = 7
BINARY_NULLABLE_BOOL = 8
BINARY_NULLABLE_STRING = 9


def scalar(value: Any, default: Any = None) -> Any:
    if isinstance(value, list):
        return value[0] if value else default
    return default if value is None else value


def number(value: Any, default: float) -> float:
    candidate = str(scalar(value, default)).strip().removesuffix("%")
    try:
        parsed = float(candidate)
    except ValueError:
        return default
    return parsed if math.isfinite(parsed) else default


def integer(value: Any, default: int) -> int:
    return round(number(value, float(default)))


def boolean(value: Any, default: bool = False) -> bool:
    candidate = str(scalar(value, "1" if default else "0")).strip().lower()
    return candidate in {"1", "true", "yes", "on"}


def nullable_scalar(value: Any) -> Any | None:
    candidate = scalar(value)
    if candidate is None or str(candidate).strip().lower() == "nil":
        return None
    return candidate


def nullable_number(value: Any) -> float | None:
    candidate = nullable_scalar(value)
    if candidate is None:
        return None
    try:
        parsed = float(str(candidate).strip().removesuffix("%"))
    except ValueError:
        return None
    return parsed if math.isfinite(parsed) else None


def nullable_boolean(value: Any) -> bool | None:
    candidate = nullable_scalar(value)
    if candidate is None:
        return None
    return str(candidate).strip().lower() in {"1", "true", "yes", "on"}


def z_hop_type(value: Any, default: str | None) -> str | None:
    candidate = nullable_scalar(value)
    if candidate is None:
        return default
    normalized = str(candidate).strip().lower()
    return {
        "auto": "auto", "auto lift": "auto",
        "normal": "normal", "normal lift": "normal",
        "slope": "slope", "slope lift": "slope",
        "spiral": "spiral", "spiral lift": "spiral",
    }.get(normalized, default)


def retract_lift_enforcement(value: Any, default: str | None) -> str | None:
    candidate = nullable_scalar(value)
    if candidate is None:
        return default
    normalized = str(candidate).strip().lower()
    return {
        "all surfaces": "all",
        "top only": "top",
        "bottom only": "bottom",
        "top and bottom": "top_bottom",
    }.get(normalized, default)


DEFAULT_RAMMING_PARAMETERS = (
    "120 100 6.6 6.8 7.2 7.6 7.9 8.2 8.7 9.4 9.9 10.0|"
    " 0.05 6.6 0.45 6.8 0.95 7.8 1.45 8.3 1.95 9.7 2.45 10"
    " 2.95 7.6 3.45 7.6 3.95 7.6 4.45 7.6 4.95 7.6"
)


def ramming_parameters(value: Any) -> str:
    candidate = str(scalar(value, DEFAULT_RAMMING_PARAMETERS)).strip()
    if len(candidate) >= 2 and candidate[0] == candidate[-1] == '"':
        candidate = candidate[1:-1].strip()
    parts = candidate.split("|")
    if len(parts) != 2:
        return DEFAULT_RAMMING_PARAMETERS
    left = parts[0].split()
    right = parts[1].split()
    if len(left) < 3 or len(right) < 2 or len(right) % 2:
        return DEFAULT_RAMMING_PARAMETERS
    try:
        numbers = [float(token) for token in left + right]
    except ValueError:
        return DEFAULT_RAMMING_PARAMETERS
    if not all(math.isfinite(number) and 0 <= number <= 1_000 for number in numbers):
        return DEFAULT_RAMMING_PARAMETERS
    return f"{' '.join(left)}| {' '.join(right)}"


def values(value: Any) -> list[str]:
    if value is None:
        return []
    if not isinstance(value, list):
        value = [value]
    return [str(item) for item in value if str(item).strip()]


def number_values(value: Any, default: float) -> list[float]:
    candidates = values(value)
    return [number(candidate, default) for candidate in candidates] if candidates else [default]


def point_values(value: Any) -> tuple[list[float], list[float]]:
    """Parse Orca's ConfigOptionPoints JSON representation into parallel XY arrays."""
    points: list[tuple[float, float]] = []
    for candidate in values(value) or ["0x0"]:
        # Orca accepts comma-separated points inside one vector value and uses `x`
        # between the two coordinates. A missing second coordinate resolves to zero.
        for point_text in candidate.split(","):
            coordinates = point_text.strip().lower().split("x", 1)
            points.append(
                (
                    number(coordinates[0], 0),
                    number(coordinates[1], 0) if len(coordinates) == 2 else 0,
                )
            )
    return [point[0] for point in points], [point[1] for point in points]


def bed_exclude_geometry(
    value: Any,
    origin_x: float,
    origin_y: float,
    width: float,
    depth: float,
) -> list[float]:
    xs, ys = point_values(value)
    # Orca uses a single machine-space 0x0 point as the cross-profile sentinel
    # for "no excluded area", including beds whose printable origin is offset.
    if len(xs) == 1 and abs(xs[0]) <= 0.05 and abs(ys[0]) <= 0.05:
        return [0.0, 0.0]
    normalized = [
        coordinate
        for x, y in zip(xs, ys)
        for coordinate in (x - origin_x, y - origin_y)
    ]
    if len(normalized) > 512 or len(normalized) % 2:
        raise ValueError("oversized bed exclusion area")
    if len(normalized) == 2:
        raise ValueError("invalid bed exclusion point")
    if len(normalized) < 6:
        raise ValueError("invalid bed exclusion area")
    for index, coordinate in enumerate(normalized):
        maximum = width if index % 2 == 0 else depth
        if not math.isfinite(coordinate) or coordinate < -0.05 or coordinate > maximum + 0.05:
            raise ValueError("bed exclusion area lies outside the printable bounds")
    return normalized


def stable_id(kind: str, brand: str, name: str) -> str:
    digest = hashlib.sha256(f"{kind}\0{brand}\0{name}".encode()).hexdigest()[:20]
    return f"orca-{kind}-{digest}"


class Resolver:
    def __init__(self, profile_root: Path) -> None:
        self.profile_root = profile_root
        self.entries: list[tuple[Path, str, dict[str, Any]]] = []
        self.index: dict[tuple[str, str], list[int]] = defaultdict(list)
        self.cache: dict[int, dict[str, Any]] = {}
        for path in sorted(profile_root.rglob("*.json")):
            raw = json.loads(path.read_text(encoding="utf-8"))
            kind = raw.get("type")
            name = raw.get("name")
            if kind not in {"machine", "filament", "process"} or not name:
                continue
            relative = path.relative_to(profile_root)
            brand = relative.parts[0] if len(relative.parts) > 2 else "Common"
            entry_id = len(self.entries)
            self.entries.append((path, brand, raw))
            self.index[(kind, str(name))].append(entry_id)

    def resolve(self, entry_id: int, stack: frozenset[int] = frozenset()) -> dict[str, Any]:
        if entry_id in self.cache:
            return self.cache[entry_id]
        if entry_id in stack:
            raise ValueError("inheritance cycle")
        _, brand, raw = self.entries[entry_id]
        result: dict[str, Any] = {}
        parent_name = raw.get("inherits")
        if parent_name:
            candidates = self.index.get((str(raw["type"]), str(parent_name)), [])
            parent_id = next(
                (candidate for candidate in candidates if self.entries[candidate][1] == brand),
                next(
                    (
                        candidate
                        for candidate in candidates
                        if self.entries[candidate][1] in {"Blocks", "Common", "BBL"}
                    ),
                    candidates[0] if candidates else None,
                ),
            )
            if parent_id is None:
                raise ValueError(f"missing parent: {parent_name}")
            result.update(self.resolve(parent_id, stack | {entry_id}))
        result.update(raw)
        self.cache[entry_id] = result
        return result


def printable_geometry(area: Any) -> tuple[float, float, float, float, list[float]]:
    points: list[tuple[float, float]] = []
    for item in values(area):
        try:
            x_text, y_text = item.lower().split("x", 1)
            points.append((float(x_text), float(y_text)))
        except ValueError:
            continue
    if len(points) < 3:
        raise ValueError("invalid printable area")
    if points[-1] == points[0]:
        points.pop()
    if len(points) < 3 or len(points) > 256:
        raise ValueError("unsafe printable area point count")
    minimum_x = min(point[0] for point in points)
    minimum_y = min(point[1] for point in points)
    width = max(point[0] for point in points) - minimum_x
    depth = max(point[1] for point in points) - minimum_y
    if not (50 <= width <= 1_500 and 50 <= depth <= 1_500):
        raise ValueError("unsafe printable area")
    normalized = [coordinate for point in points for coordinate in (point[0] - minimum_x, point[1] - minimum_y)]
    signed_double_area = sum(
        normalized[index] * normalized[(index + 3) % len(normalized)] -
        normalized[(index + 2) % len(normalized)] * normalized[index + 1]
        for index in range(0, len(normalized), 2)
    )
    if not all(math.isfinite(value) for value in normalized) or abs(signed_double_area) < 2.0:
        raise ValueError("degenerate printable area")
    return width, depth, minimum_x, minimum_y, normalized


def build_printer(brand: str, raw: dict[str, Any]) -> dict[str, Any]:
    name = str(raw["name"])
    width, depth, bed_origin_x, bed_origin_y, bed_polygon = printable_geometry(raw.get("printable_area"))
    bed_exclude_area = bed_exclude_geometry(
        raw.get("bed_exclude_area"), bed_origin_x, bed_origin_y, width, depth
    )
    height = number(raw.get("printable_height"), 0)
    nozzle = number(raw.get("nozzle_diameter"), 0)
    configured_min_layer_height = number(raw.get("min_layer_height"), 0)
    configured_max_layer_height = number(raw.get("max_layer_height"), 0)
    min_layer_height = configured_min_layer_height if configured_min_layer_height > 0 else 0.07
    max_layer_height = configured_max_layer_height if configured_max_layer_height > 0 else nozzle * 0.75
    retract_length = number(raw.get("retraction_length"), 0.8)
    tool_change_retract_lengths = number_values(raw.get("retract_length_toolchange"), retract_length)
    tool_change_retract_restart_extras = number_values(
        raw.get("retract_restart_extra_toolchange"), 0
    )
    extruder_offsets_x, extruder_offsets_y = point_values(raw.get("extruder_offset"))
    physical_extruder_count = len(values(raw.get("nozzle_diameter")))
    supports_multi_material = str(
        scalar(raw.get("single_extruder_multi_material"), "0")
    ).lower() in {"1", "true"}
    extruder_count = MAX_FILAMENT_SLOTS if supports_multi_material else physical_extruder_count
    flavor = str(scalar(raw.get("gcode_flavor"), "")).lower()
    if not (
        50 <= height <= 1_500 and
        0.1 <= nozzle <= 2.0 and
        1 <= physical_extruder_count <= MAX_FILAMENT_SLOTS
    ):
        raise ValueError("unsafe printer dimensions")
    if flavor not in SUPPORTED_GCODE_FLAVORS:
        raise ValueError(f"unsupported G-code flavor: {flavor}")

    def motion(key: str, default: float) -> float:
        parsed = number(raw.get(key), default)
        return parsed if parsed > 0 else default

    profile = {
        "id": stable_id("printer", brand, name),
        "name": name,
        "brand": brand,
        "bedSizeX": width,
        "bedSizeY": depth,
        "bedOriginX": bed_origin_x,
        "bedOriginY": bed_origin_y,
        "bedPolygon": bed_polygon,
        "bedExcludeArea": bed_exclude_area,
        "maxPrintHeight": height,
        "nozzleDiameter": nozzle,
        "minLayerHeight": min_layer_height,
        "maxLayerHeight": max_layer_height,
        "singleExtruderMultiMaterial": supports_multi_material,
        "coolingTubeRetraction": number(raw.get("cooling_tube_retraction"), 91.5),
        "coolingTubeLength": number(raw.get("cooling_tube_length"), 5),
        "parkingPosRetraction": number(raw.get("parking_pos_retraction"), 92),
        "extraLoadingMove": number(raw.get("extra_loading_move"), -2),
        "enableFilamentRamming": boolean(raw.get("enable_filament_ramming"), True),
        "purgeInPrimeTower": boolean(raw.get("purge_in_prime_tower"), True),
        "highCurrentOnFilamentSwap": boolean(raw.get("high_current_on_filament_swap")),
        "extruderCount": extruder_count,
        "auxiliaryFan": boolean(raw.get("auxiliary_fan")),
        "supportsChamberTemperatureControl": boolean(
            raw.get("support_chamber_temp_control")
        ),
        "supportsAirFiltration": boolean(raw.get("support_air_filtration")),
        "machineStartGcode": str(raw.get("machine_start_gcode", "")),
        "machineEndGcode": str(raw.get("machine_end_gcode", "")),
        "beforeLayerChangeGcode": str(raw.get("before_layer_change_gcode", "")),
        "layerChangeGcode": str(raw.get("layer_change_gcode", "")),
        "changeFilamentGcode": str(raw.get("change_filament_gcode", "")),
        "printingByObjectGcode": str(raw.get("printing_by_object_gcode", "")),
        "useRelativeEDistances": boolean(raw.get("use_relative_e_distances"), True),
        "emitMachineLimitsToGcode": boolean(raw.get("emit_machine_limits_to_gcode"), True),
        "manualFilamentChange": boolean(raw.get("manual_filament_change")),
        "disableM73": boolean(raw.get("disable_m73")),
        "machineLoadFilamentTime": number(raw.get("machine_load_filament_time"), 0),
        "machineUnloadFilamentTime": number(raw.get("machine_unload_filament_time"), 0),
        "machineToolChangeTime": number(raw.get("machine_tool_change_time"), 0),
        "toolChangeTemperatureWait": boolean(raw.get("tool_change_temprature_wait"), True),
        "gcodeFlavor": flavor,
        "maxSpeedX": motion("machine_max_speed_x", 300),
        "maxSpeedY": motion("machine_max_speed_y", 300),
        "maxSpeedZ": motion("machine_max_speed_z", 15),
        "maxSpeedE": motion("machine_max_speed_e", 25),
        "maxAccelerationX": motion("machine_max_acceleration_x", 3_000),
        "maxAccelerationY": motion("machine_max_acceleration_y", 3_000),
        "maxAccelerationZ": motion("machine_max_acceleration_z", 200),
        "maxAccelerationE": motion("machine_max_acceleration_e", 2_000),
        "maxAccelerationExtruding": motion("machine_max_acceleration_extruding", 3_000),
        "maxAccelerationRetracting": motion("machine_max_acceleration_retracting", 2_000),
        "maxAccelerationTravel": motion("machine_max_acceleration_travel", 3_000),
        "maxJerkX": motion("machine_max_jerk_x", 8),
        "maxJerkY": motion("machine_max_jerk_y", 8),
        "maxJerkZ": motion("machine_max_jerk_z", 0.4),
        "maxJerkE": motion("machine_max_jerk_e", 5),
        "retractLength": retract_length,
        "retractSpeed": number(raw.get("retraction_speed"), 30),
        "deretractSpeed": number(raw.get("deretraction_speed"), 0),
        "retractionMinimumTravel": number(raw.get("retraction_minimum_travel"), 2),
        "retractWhenChangingLayer": boolean(raw.get("retract_when_changing_layer")),
        "wipeWhileRetracting": boolean(raw.get("wipe")),
        "wipeDistance": number(raw.get("wipe_distance"), 1),
        "retractBeforeWipe": number(raw.get("retract_before_wipe"), 100),
        "retractRestartExtra": number(raw.get("retract_restart_extra"), 0),
        "extruderOffsetsX": extruder_offsets_x,
        "extruderOffsetsY": extruder_offsets_y,
        "toolChangeRetractLengths": tool_change_retract_lengths,
        "toolChangeRetractRestartExtras": tool_change_retract_restart_extras,
        "zHop": number(raw.get("z_hop"), 0.4),
        "zHopType": z_hop_type(raw.get("z_hop_types"), "slope"),
        "retractLiftAbove": number(raw.get("retract_lift_above"), 0),
        "retractLiftBelow": number(raw.get("retract_lift_below"), 0),
        "retractLiftEnforce": retract_lift_enforcement(raw.get("retract_lift_enforce"), "all"),
        "travelSlope": number(raw.get("travel_slope"), 3),
        "zHopWhenPrime": boolean(raw.get("z_hop_when_prime"), True),
        "useFirmwareRetraction": boolean(raw.get("use_firmware_retraction")),
        "longRetractionWhenCutLevel": integer(raw.get("enable_long_retraction_when_cut"), 0),
        "longRetractionWhenCut": boolean(raw.get("long_retractions_when_cut")),
        "retractionDistanceWhenCut": number(raw.get("retraction_distances_when_cut"), 18),
        "extruderClearanceRadius": number(raw.get("extruder_clearance_radius"), 40),
        "extruderClearanceHeightToRod": number(raw.get("extruder_clearance_height_to_rod"), 40),
        "extruderClearanceHeightToLid": number(raw.get("extruder_clearance_height_to_lid"), 120),
    }
    if not (
        all(0.1 <= profile[key] <= 2_000 for key in ["maxSpeedX", "maxSpeedY", "maxSpeedZ", "maxSpeedE"])
        and all(
            0.1 <= profile[key] <= 100_000
            for key in [
                "maxAccelerationX",
                "maxAccelerationY",
                "maxAccelerationZ",
                "maxAccelerationE",
                "maxAccelerationExtruding",
                "maxAccelerationRetracting",
                "maxAccelerationTravel",
            ]
        )
        and 0.1 <= profile["extruderClearanceRadius"] <= 1_000
        and 0.1 <= profile["extruderClearanceHeightToRod"] <= 1_500
        and 0.1 <= profile["extruderClearanceHeightToLid"] <= 1_500
        and 0 <= profile["retractLength"] <= 100
        and 0 <= profile["retractSpeed"] <= 500
        and 0 <= profile["deretractSpeed"] <= 500
        and 0 <= profile["retractionMinimumTravel"] <= 1_000
        and 0 <= profile["wipeDistance"] <= 100
        and 0 <= profile["retractBeforeWipe"] <= 100
        and -100 <= profile["retractRestartExtra"] <= 100
        and len(profile["printingByObjectGcode"].encode("utf-8")) <= 262_144
        and 1 <= len(profile["extruderOffsetsX"]) <= MAX_FILAMENT_SLOTS
        and all(-1_000 <= value <= 1_000 for value in profile["extruderOffsetsX"])
        and 1 <= len(profile["extruderOffsetsY"]) <= MAX_FILAMENT_SLOTS
        and all(-1_000 <= value <= 1_000 for value in profile["extruderOffsetsY"])
        and 1 <= len(profile["toolChangeRetractLengths"]) <= MAX_FILAMENT_SLOTS
        and all(0 <= value <= 100 for value in profile["toolChangeRetractLengths"])
        and 1 <= len(profile["toolChangeRetractRestartExtras"]) <= MAX_FILAMENT_SLOTS
        and all(-100 <= value <= 100 for value in profile["toolChangeRetractRestartExtras"])
        and 0 <= profile["zHop"] <= 5
        and profile["zHopType"] in {"auto", "normal", "slope", "spiral"}
        and 0 <= profile["retractLiftAbove"] <= 1_500
        and 0 <= profile["retractLiftBelow"] <= 1_500
        and (profile["retractLiftBelow"] == 0 or
             profile["retractLiftAbove"] <= profile["retractLiftBelow"])
        and profile["retractLiftEnforce"] in {"all", "top", "bottom", "top_bottom"}
        and 1 <= profile["travelSlope"] <= 90
        and profile["longRetractionWhenCutLevel"] in {0, 1, 2}
        and 10 <= profile["retractionDistanceWhenCut"] <= 18
        and all(0 <= profile[key] <= 3_600 for key in [
            "machineLoadFilamentTime", "machineUnloadFilamentTime", "machineToolChangeTime"
        ])
        and 0.01 <= profile["minLayerHeight"] <= profile["maxLayerHeight"] <= 2
    ):
        raise ValueError("unsafe motion limits")
    return profile


def first_present(raw: dict[str, Any], names: list[str], default: Any) -> Any:
    return next((raw[name] for name in names if name in raw), default)


def line_width_mm(value: Any, nozzle: float) -> float:
    candidate = str(scalar(value, "0")).strip()
    if candidate.endswith("%"):
        return nozzle * number(candidate, 0) / 100
    return number(candidate, 0)


def relative_number(value: Any, base: float, default: float) -> float:
    candidate = str(scalar(value, default)).strip()
    if candidate.endswith("%"):
        return base * number(candidate, 100) / 100
    return number(candidate, default)


def absolute_number(value: Any, default: float) -> float:
    """Match Orca's legacy handling for options that no longer accept percentages."""
    candidate = str(scalar(value, default)).strip()
    return default if candidate.endswith("%") else number(candidate, default)


def float_or_percent(value: Any, default: Any) -> tuple[float, bool]:
    candidate = str(scalar(value, default)).strip()
    match = re.match(r"^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?", candidate)
    if match is not None:
        parsed = float(match.group(0))
        if math.isfinite(parsed):
            return parsed, candidate.endswith("%")
    fallback = str(scalar(default, 0)).strip()
    return number(fallback, 0), fallback.endswith("%")


def infill_pattern(value: Any, default: str) -> str:
    candidate = str(scalar(value, default)).strip().lower()
    if candidate == "zig-zag":
        candidate = "rectilinear"
    return candidate if candidate in INFILL_PATTERNS else default


def enum_value(value: Any, allowed: set[str], default: str) -> str:
    candidate = str(scalar(value, default)).strip().lower()
    return candidate if candidate in allowed else default


def support_type(value: Any) -> str:
    candidate = str(scalar(value, "normal(auto)")).strip().lower()
    return {
        "normal": "normal(auto)",
        "tree": "tree(auto)",
        "hybrid(auto)": "tree(auto)",
        "normal(auto)": "normal(auto)",
        "tree(auto)": "tree(auto)",
        "normal(manual)": "normal(manual)",
        "tree(manual)": "tree(manual)",
    }.get(candidate, "normal(auto)")


def support_style(value: Any, normalized_support_type: str) -> str:
    allowed = (
        {"default", "organic", "tree_slim", "tree_strong", "tree_hybrid"}
        if normalized_support_type.startswith("tree(")
        else {"default", "grid", "snug"}
    )
    return enum_value(value, allowed, "default")


def wall_sequence(value: Any) -> str:
    return {
        "inner wall/outer wall": "inner-outer",
        "outer wall/inner wall": "outer-inner",
        "inner-outer-inner wall": "inner-outer-inner",
        "inner wall/outer wall/infill": "inner-outer",
        "infill/inner wall/outer wall": "inner-outer",
        "outer wall/inner wall/infill": "outer-inner",
        "infill/outer wall/inner wall": "outer-inner",
        "inner-outer-inner wall/infill": "inner-outer-inner",
    }.get(str(scalar(value, "inner wall/outer wall")), "inner-outer")


def wall_generator(value: Any) -> str:
    candidate = str(scalar(value, "arachne")).strip().lower()
    return candidate if candidate in {"arachne", "classic"} else "arachne"


def vertical_shell_mode(value: Any) -> str:
    candidate = str(scalar(value, "ensure_all")).strip().lower()
    candidate = {"1": "ensure_all", "0": "ensure_moderate"}.get(candidate, candidate)
    return candidate if candidate in {
        "none", "ensure_critical_only", "ensure_moderate", "ensure_all"
    } else "ensure_all"


def rotation_template(value: Any, default: str = "") -> str:
    candidate = str(scalar(value, default)).strip()
    if not candidate:
        return ""
    tokens = [token.strip() for token in candidate.split(",")]
    if not (1 <= len(tokens) <= 32):
        return default
    try:
        angles = [float(token) for token in tokens]
    except ValueError:
        return default
    if not all(math.isfinite(angle) and -360 <= angle <= 360 for angle in angles):
        return default
    return ",".join(f"{angle:g}" for angle in angles)


def build_filament(brand: str, raw: dict[str, Any]) -> dict[str, Any]:
    name = str(raw["name"])
    filament_type = str(scalar(raw.get("filament_type"), "")).strip()
    nozzle = integer(raw.get("nozzle_temperature"), 0)
    first_nozzle = integer(raw.get("nozzle_temperature_initial_layer"), nozzle)
    bed_value = first_present(
        raw,
        ["hot_plate_temp", "textured_plate_temp", "hot_plate_temp_initial_layer"],
        0,
    )
    first_bed_value = first_present(
        raw,
        ["hot_plate_temp_initial_layer", "textured_plate_temp_initial_layer"],
        bed_value,
    )
    bed = integer(bed_value, 0)
    first_bed = integer(first_bed_value, bed)
    textured_bed = integer(raw.get("textured_plate_temp"), bed)
    first_textured_bed = integer(
        raw.get("textured_plate_temp_initial_layer"), textured_bed
    )
    engineering_bed = integer(raw.get("eng_plate_temp"), bed)
    first_engineering_bed = integer(
        raw.get("eng_plate_temp_initial_layer"), engineering_bed
    )
    cool_bed = integer(raw.get("cool_plate_temp"), bed)
    first_cool_bed = integer(raw.get("cool_plate_temp_initial_layer"), cool_bed)
    textured_cool_bed = integer(raw.get("textured_cool_plate_temp"), cool_bed)
    first_textured_cool_bed = integer(
        raw.get("textured_cool_plate_temp_initial_layer"), textured_cool_bed
    )
    supertack_bed = integer(raw.get("supertack_plate_temp"), cool_bed)
    first_supertack_bed = integer(
        raw.get("supertack_plate_temp_initial_layer"), supertack_bed
    )
    graphic_effect_bed = integer(raw.get("graphic_effect_plate_temp"), textured_bed)
    first_graphic_effect_bed = integer(
        raw.get("graphic_effect_plate_temp_initial_layer"), graphic_effect_bed
    )
    if not filament_type or not (150 <= nozzle <= 400 and 0 <= bed <= 160):
        raise ValueError("unsafe filament temperatures")
    profile = {
        "id": stable_id("filament", brand, name),
        "name": name,
        "brand": brand,
        "nativeName": filament_type,
        "nozzleTemp": nozzle,
        "firstLayerNozzleTemp": first_nozzle,
        "idleTemperature": integer(raw.get("idle_temperature"), 0),
        "bedTemp": bed,
        "firstLayerBedTemp": first_bed,
        "texturedPlateTemp": textured_bed,
        "firstLayerTexturedPlateTemp": first_textured_bed,
        "engineeringPlateTemp": engineering_bed,
        "firstLayerEngineeringPlateTemp": first_engineering_bed,
        "coolPlateTemp": cool_bed,
        "firstLayerCoolPlateTemp": first_cool_bed,
        "texturedCoolPlateTemp": textured_cool_bed,
        "firstLayerTexturedCoolPlateTemp": first_textured_cool_bed,
        "superTackPlateTemp": supertack_bed,
        "firstLayerSuperTackPlateTemp": first_supertack_bed,
        "graphicEffectPlateTemp": graphic_effect_bed,
        "firstLayerGraphicEffectPlateTemp": first_graphic_effect_bed,
        "flowRatio": number(raw.get("filament_flow_ratio"), 1.0),
        "maxVolumetricSpeed": number(raw.get("filament_max_volumetric_speed"), 12),
        "diameter": number(raw.get("filament_diameter"), 1.75),
        "density": number(raw.get("filament_density"), 1.24),
        "costPerKilogram": number(raw.get("filament_cost"), 0),
        "shrinkageXyPercent": number(raw.get("filament_shrink"), 100),
        "shrinkageZPercent": number(raw.get("filament_shrinkage_compensation_z"), 100),
        "soluble": boolean(raw.get("filament_soluble")),
        "supportMaterial": boolean(raw.get("filament_is_support")),
        "minimalPurgeOnWipeTower": number(raw.get("filament_minimal_purge_on_wipe_tower"), 15),
        "additionalCoolingFanSpeed": integer(raw.get("additional_cooling_fan_speed"), 0),
        "loadingSpeed": number(raw.get("filament_loading_speed"), 28),
        "loadingSpeedStart": number(raw.get("filament_loading_speed_start"), 3),
        "unloadingSpeed": number(raw.get("filament_unloading_speed"), 90),
        "unloadingSpeedStart": number(raw.get("filament_unloading_speed_start"), 100),
        "toolchangeDelay": number(raw.get("filament_toolchange_delay"), 0),
        "coolingMoves": integer(raw.get("filament_cooling_moves"), 4),
        "stampingLoadingSpeed": number(raw.get("filament_stamping_loading_speed"), 0),
        "stampingDistance": number(raw.get("filament_stamping_distance"), 0),
        "coolingInitialSpeed": number(raw.get("filament_cooling_initial_speed"), 2.2),
        "coolingFinalSpeed": number(raw.get("filament_cooling_final_speed"), 3.4),
        "rammingParameters": ramming_parameters(raw.get("filament_ramming_parameters")),
        "multitoolRamming": boolean(raw.get("filament_multitool_ramming")),
        "multitoolRammingVolume": number(raw.get("filament_multitool_ramming_volume"), 10),
        "multitoolRammingFlow": number(raw.get("filament_multitool_ramming_flow"), 10),
        "softeningTemperature": integer(raw.get("temperature_vitrification"), 100),
        "nozzleTemperatureRangeLow": integer(raw.get("nozzle_temperature_range_low"), 190),
        "nozzleTemperatureRangeHigh": integer(raw.get("nozzle_temperature_range_high"), 240),
        "chamberTemperatureControl": boolean(raw.get("activate_chamber_temp_control")),
        "chamberTemperature": integer(raw.get("chamber_temperature"), 0),
        "airFiltration": boolean(raw.get("activate_air_filtration")),
        "duringPrintExhaustFanSpeed": integer(raw.get("during_print_exhaust_fan_speed"), 60),
        "completePrintExhaustFanSpeed": integer(
            raw.get("complete_print_exhaust_fan_speed"), 80
        ),
        "filamentStartGcode": str(scalar(raw.get("filament_start_gcode"), "")),
        "filamentEndGcode": str(scalar(raw.get("filament_end_gcode"), "")),
        "retractLength": nullable_number(raw.get("filament_retraction_length")),
        "retractSpeed": nullable_number(raw.get("filament_retraction_speed")),
        "deretractSpeed": nullable_number(raw.get("filament_deretraction_speed")),
        "retractionMinimumTravel": nullable_number(raw.get("filament_retraction_minimum_travel")),
        "retractWhenChangingLayer": nullable_boolean(raw.get("filament_retract_when_changing_layer")),
        "wipeWhileRetracting": nullable_boolean(raw.get("filament_wipe")),
        "wipeDistance": nullable_number(raw.get("filament_wipe_distance")),
        "retractBeforeWipe": nullable_number(raw.get("filament_retract_before_wipe")),
        "retractRestartExtra": nullable_number(raw.get("filament_retract_restart_extra")),
        "zHop": nullable_number(raw.get("filament_z_hop")),
        "zHopType": z_hop_type(raw.get("filament_z_hop_types"), None),
        "retractLiftAbove": nullable_number(raw.get("filament_retract_lift_above")),
        "retractLiftBelow": nullable_number(raw.get("filament_retract_lift_below")),
        "retractLiftEnforce": retract_lift_enforcement(
            raw.get("filament_retract_lift_enforce"), None
        ),
        "longRetractionWhenCut": nullable_boolean(
            raw.get("filament_long_retractions_when_cut")
        ),
        "retractionDistanceWhenCut": nullable_number(
            raw.get("filament_retraction_distances_when_cut")
        ),
        "fanMinSpeed": integer(raw.get("fan_min_speed"), 30),
        "fanMaxSpeed": integer(raw.get("fan_max_speed"), 100),
        "fanCoolingLayerTime": number(raw.get("fan_cooling_layer_time"), 60),
        "slowDownForLayerCooling": boolean(raw.get("slow_down_for_layer_cooling"), True),
        "keepFanAlwaysOn": boolean(raw.get("reduce_fan_stop_start_freq")),
        "dontSlowDownOuterWall": boolean(raw.get("dont_slow_down_outer_wall")),
        "enableOverhangBridgeFan": boolean(raw.get("enable_overhang_bridge_fan"), True),
        "overhangFanSpeed": integer(raw.get("overhang_fan_speed"), 100),
        "overhangFanThreshold": enum_value(
            raw.get("overhang_fan_threshold"),
            {"0%", "10%", "25%", "50%", "75%", "95%"},
            "95%",
        ),
        "internalBridgeFanSpeed": integer(raw.get("internal_bridge_fan_speed"), -1),
        "supportInterfaceFanSpeed": integer(
            raw.get("support_material_interface_fan_speed"), -1
        ),
        "slowDownLayerTime": number(raw.get("slow_down_layer_time"), 8),
        "slowDownMinSpeed": number(raw.get("slow_down_min_speed"), 10),
        "closeFanFirstLayers": integer(raw.get("close_fan_the_first_x_layers"), 1),
        "fullFanSpeedLayer": integer(raw.get("full_fan_speed_layer"), 3),
        "pressureAdvanceEnabled": boolean(raw.get("enable_pressure_advance")),
        "pressureAdvance": number(raw.get("pressure_advance"), 0),
        "compatiblePrinters": values(raw.get("compatible_printers")),
    }
    if not (
        all(
            0 <= profile[key] <= 160
            for key in [
                "bedTemp",
                "firstLayerBedTemp",
                "texturedPlateTemp",
                "firstLayerTexturedPlateTemp",
                "engineeringPlateTemp",
                "firstLayerEngineeringPlateTemp",
                "coolPlateTemp",
                "firstLayerCoolPlateTemp",
                "texturedCoolPlateTemp",
                "firstLayerTexturedCoolPlateTemp",
                "superTackPlateTemp",
                "firstLayerSuperTackPlateTemp",
                "graphicEffectPlateTemp",
                "firstLayerGraphicEffectPlateTemp",
            ]
        )
        and 0.5 <= profile["flowRatio"] <= 1.5
        and 0.1 <= profile["maxVolumetricSpeed"] <= 100
        and 0.5 <= profile["diameter"] <= 4
        and 0 <= profile["density"] <= 10
        and 0 <= profile["costPerKilogram"] <= 1_000_000
        and 10 <= profile["shrinkageXyPercent"] <= 200
        and 10 <= profile["shrinkageZPercent"] <= 200
        and len(profile["filamentStartGcode"].encode("utf-8")) <= 262_144
        and len(profile["filamentEndGcode"].encode("utf-8")) <= 262_144
        and all(
            0 <= profile[key] <= 100
            for key in [
                "fanMinSpeed",
                "fanMaxSpeed",
                "overhangFanSpeed",
                "additionalCoolingFanSpeed",
            ]
        )
        and 0 <= profile["minimalPurgeOnWipeTower"] <= 1_000
        and all(0 <= profile[key] <= 1_000 for key in [
            "loadingSpeed", "loadingSpeedStart", "unloadingSpeed", "unloadingSpeedStart",
            "toolchangeDelay", "stampingLoadingSpeed", "stampingDistance",
            "coolingInitialSpeed", "coolingFinalSpeed", "multitoolRammingVolume",
            "multitoolRammingFlow",
        ])
        and 0 <= profile["coolingMoves"] <= 20
        and len(profile["rammingParameters"].encode("utf-8")) <= 16_384
        and 0 <= profile["softeningTemperature"] <= 500
        and 0 <= profile["nozzleTemperatureRangeLow"] <= profile["nozzleTemperatureRangeHigh"] <= 500
        and 0 <= profile["chamberTemperature"] <= 200
        and 0 <= profile["duringPrintExhaustFanSpeed"] <= 100
        and 0 <= profile["completePrintExhaustFanSpeed"] <= 100
        and 0 <= profile["idleTemperature"] <= 500
        and 0 <= profile["fanCoolingLayerTime"] <= 1_000
        and profile["overhangFanThreshold"] in {"0%", "10%", "25%", "50%", "75%", "95%"}
        and -1 <= profile["internalBridgeFanSpeed"] <= 100
        and -1 <= profile["supportInterfaceFanSpeed"] <= 100
        and (profile["retractLength"] is None or 0 <= profile["retractLength"] <= 100)
        and (profile["retractSpeed"] is None or 0 <= profile["retractSpeed"] <= 500)
        and (profile["deretractSpeed"] is None or 0 <= profile["deretractSpeed"] <= 500)
        and (profile["retractionMinimumTravel"] is None or 0 <= profile["retractionMinimumTravel"] <= 1_000)
        and (profile["wipeDistance"] is None or 0 <= profile["wipeDistance"] <= 100)
        and (profile["retractBeforeWipe"] is None or 0 <= profile["retractBeforeWipe"] <= 100)
        and (profile["retractRestartExtra"] is None or -100 <= profile["retractRestartExtra"] <= 100)
        and (profile["zHop"] is None or 0 <= profile["zHop"] <= 5)
        and (profile["zHopType"] is None or profile["zHopType"] in {"auto", "normal", "slope", "spiral"})
        and (profile["retractLiftAbove"] is None or 0 <= profile["retractLiftAbove"] <= 1_500)
        and (profile["retractLiftBelow"] is None or 0 <= profile["retractLiftBelow"] <= 1_500)
        and (profile["retractLiftEnforce"] is None or
             profile["retractLiftEnforce"] in {"all", "top", "bottom", "top_bottom"})
        and (profile["retractionDistanceWhenCut"] is None or
             10 <= profile["retractionDistanceWhenCut"] <= 18)
    ):
        raise ValueError("unsafe filament limits")
    return profile


def build_process(brand: str, raw: dict[str, Any], printer_nozzles: dict[str, float]) -> dict[str, Any]:
    name = str(raw["name"])
    compatible = values(raw.get("compatible_printers"))
    nozzles = {printer_nozzles[item] for item in compatible if item in printer_nozzles}
    nozzle = nozzles.pop() if len(nozzles) == 1 else 0.4
    layer_height = number(raw.get("layer_height"), 0)
    first_layer = number(raw.get("initial_layer_print_height"), layer_height)
    if not (0.02 <= layer_height <= nozzle * 0.9 and 0.02 <= first_layer <= 1.0):
        raise ValueError("unsafe layer height")
    normalized_support_type = support_type(raw.get("support_type"))
    density_source = str(scalar(raw.get("sparse_infill_density"), "15%"))
    density_value = number(density_source, 15)
    density = density_value / 100 if density_source.endswith("%") or density_value > 1 else density_value
    general_line_width = raw.get("line_width", 0)
    general_line_width_mm = line_width_mm(general_line_width, nozzle)
    outer_wall_line_width = (
        line_width_mm(raw.get("outer_wall_line_width"), nozzle)
        or general_line_width_mm
        or nozzle * 1.05
    )
    inner_wall_line_width = (
        line_width_mm(raw.get("inner_wall_line_width"), nozzle)
        or general_line_width_mm
        or nozzle * 1.125
    )
    top_surface_line_width = (
        line_width_mm(raw.get("top_surface_line_width"), nozzle)
        or general_line_width_mm
        or nozzle * 1.05
    )
    sparse_infill_line_width = (
        line_width_mm(raw.get("sparse_infill_line_width"), nozzle)
        or general_line_width_mm
        or nozzle * 1.125
    )
    internal_solid_infill_line_width = (
        line_width_mm(raw.get("internal_solid_infill_line_width"), nozzle)
        or general_line_width_mm
        or nozzle * 1.125
    )
    support_line_width = (
        line_width_mm(raw.get("support_line_width"), nozzle)
        or general_line_width_mm
        or nozzle * 1.05
    )
    initial_layer_line_width = (
        line_width_mm(raw.get("initial_layer_line_width"), nozzle)
        or general_line_width_mm
        or nozzle * 1.25
    )
    outer_wall_speed = number(raw.get("outer_wall_speed"), 100)
    first_layer_speed = absolute_number(raw.get("initial_layer_speed"), 30)
    support_speed = number(raw.get("support_speed"), 100)
    overhang_1_speed, overhang_1_percent = float_or_percent(raw.get("overhang_1_4_speed"), 0)
    overhang_2_speed, overhang_2_percent = float_or_percent(raw.get("overhang_2_4_speed"), 0)
    overhang_3_speed, overhang_3_percent = float_or_percent(raw.get("overhang_3_4_speed"), 0)
    overhang_4_speed, overhang_4_percent = float_or_percent(raw.get("overhang_4_4_speed"), 0)
    internal_bridge_speed, internal_bridge_speed_percent = float_or_percent(
        raw.get("internal_bridge_speed"), "150%"
    )
    initial_layer_travel_speed, initial_layer_travel_speed_percent = float_or_percent(
        raw.get("initial_layer_travel_speed"), "100%"
    )
    min_width_top_surface, min_width_top_surface_percent = float_or_percent(
        raw.get("min_width_top_surface"), "300%"
    )
    overhang_reverse_threshold, overhang_reverse_threshold_percent = float_or_percent(
        raw.get("overhang_reverse_threshold"), "50%"
    )
    bridge_acceleration, bridge_acceleration_percent = float_or_percent(
        raw.get("bridge_acceleration"), "50%"
    )
    sparse_infill_acceleration, sparse_infill_acceleration_percent = float_or_percent(
        raw.get("sparse_infill_acceleration"), "100%"
    )
    internal_solid_infill_acceleration, internal_solid_infill_acceleration_percent = float_or_percent(
        raw.get("internal_solid_infill_acceleration"), "100%"
    )
    infill_combination_height, infill_combination_height_percent = float_or_percent(
        raw.get("infill_combination_max_layer_height"), "100%"
    )
    infill_anchor, infill_anchor_percent = float_or_percent(
        first_present(raw, ["infill_anchor", "sparse_infill_anchor"], "400%"), "400%"
    )
    infill_anchor_max, infill_anchor_max_percent = float_or_percent(
        first_present(raw, ["infill_anchor_max", "sparse_infill_anchor_max"], 20), 20
    )
    skin_infill_line_width, skin_infill_line_width_percent = float_or_percent(
        raw.get("skin_infill_line_width"), "100%"
    )
    skeleton_infill_line_width, skeleton_infill_line_width_percent = float_or_percent(
        raw.get("skeleton_infill_line_width"), "100%"
    )
    max_travel_detour_distance, max_travel_detour_distance_percent = float_or_percent(
        raw.get("max_travel_detour_distance"), 0
    )
    small_perimeter_speed, small_perimeter_speed_percent = float_or_percent(
        raw.get("small_perimeter_speed"), "50%"
    )
    seam_gap, seam_gap_percent = float_or_percent(raw.get("seam_gap"), "10%")
    scarf_joint_speed, scarf_joint_speed_percent = float_or_percent(
        raw.get("scarf_joint_speed"), "100%"
    )
    scarf_start_height, scarf_start_height_percent = float_or_percent(
        raw.get("seam_slope_start_height"), 0
    )
    wipe_speed, wipe_speed_percent = float_or_percent(raw.get("wipe_speed"), "80%")
    spiral_xy_smoothing, spiral_xy_smoothing_percent = float_or_percent(
        raw.get("spiral_mode_max_xy_smoothing"), "200%"
    )
    support_threshold_overlap, support_threshold_overlap_percent = float_or_percent(
        raw.get("support_threshold_overlap"), "50%"
    )
    hole_to_polyhole_threshold, hole_to_polyhole_threshold_percent = float_or_percent(
        raw.get("hole_to_polyhole_threshold"), 0.01
    )
    legacy_wall_order = str(scalar(raw.get("wall_infill_order"), ""))
    resolved_wall_order = raw.get("wall_sequence", legacy_wall_order)
    wall_filament = integer(raw.get("wall_filament"), 1)
    sparse_infill_filament = integer(raw.get("sparse_infill_filament"), 1)
    solid_infill_filament = integer(raw.get("solid_infill_filament"), 1)
    # Normalize the legacy zero value to the effective first tool for these
    # otherwise strictly one-based options.
    wall_filament = 1 if wall_filament == 0 else wall_filament
    sparse_infill_filament = 1 if sparse_infill_filament == 0 else sparse_infill_filament
    solid_infill_filament = 1 if solid_infill_filament == 0 else solid_infill_filament
    profile = {
        "id": stable_id("process", brand, name),
        "name": name,
        "brand": brand,
        "layerHeightMm": layer_height,
        "firstLayerHeightMm": first_layer,
        "perimeters": integer(raw.get("wall_loops"), 2),
        "fillDensity": density,
        "printSpeed": outer_wall_speed,
        "innerWallSpeed": relative_number(raw.get("inner_wall_speed"), outer_wall_speed, outer_wall_speed * 1.5),
        "sparseInfillSpeed": relative_number(raw.get("sparse_infill_speed"), outer_wall_speed, outer_wall_speed * 1.35),
        "internalSolidInfillSpeed": absolute_number(raw.get("internal_solid_infill_speed"), 100),
        "topSurfaceSpeed": absolute_number(raw.get("top_surface_speed"), 100),
        "supportSpeed": support_speed,
        "bridgeSpeed": number(raw.get("bridge_speed"), 50),
        "gapInfillSpeed": number(raw.get("gap_infill_speed"), outer_wall_speed * 1.25),
        "firstLayerInfillSpeed": number(raw.get("initial_layer_infill_speed"), 60),
        "supportInterfaceSpeed": absolute_number(raw.get("support_interface_speed"), 80),
        "internalBridgeSpeed": internal_bridge_speed,
        "internalBridgeSpeedPercent": internal_bridge_speed_percent,
        "overhangSpeedEnabled": boolean(raw.get("enable_overhang_speed"), True),
        "overhangSpeed1": overhang_1_speed,
        "overhangSpeed1Percent": overhang_1_percent,
        "overhangSpeed2": overhang_2_speed,
        "overhangSpeed2Percent": overhang_2_percent,
        "overhangSpeed3": overhang_3_speed,
        "overhangSpeed3Percent": overhang_3_percent,
        "overhangSpeed4": overhang_4_speed,
        "overhangSpeed4Percent": overhang_4_percent,
        "printFlowRatio": number(raw.get("print_flow_ratio"), 1),
        "bridgeFlowRatio": number(raw.get("bridge_flow"), 1),
        "internalBridgeFlowRatio": number(raw.get("internal_bridge_flow"), 1),
        "topSurfaceFlowRatio": number(raw.get("top_solid_infill_flow_ratio"), 1),
        "bottomSurfaceFlowRatio": number(raw.get("bottom_solid_infill_flow_ratio"), 1),
        "bridgeDensity": number(raw.get("bridge_density"), 100),
        "internalBridgeDensity": number(raw.get("internal_bridge_density"), 100),
        "bridgeAngle": number(raw.get("bridge_angle"), 0),
        "internalBridgeAngle": number(raw.get("internal_bridge_angle"), 0),
        "bridgeNoSupport": boolean(raw.get("bridge_no_support")),
        "thickBridges": boolean(raw.get("thick_bridges")),
        "thickInternalBridges": boolean(raw.get("thick_internal_bridges"), True),
        "extraBridgeLayer": enum_value(
            raw.get("enable_extra_bridge_layer"),
            {"disabled", "external_bridge_only", "internal_bridge_only", "apply_to_all"},
            "disabled",
        ),
        "internalBridgeFilter": enum_value(
            raw.get("dont_filter_internal_bridges"),
            {"disabled", "limited", "nofilter"},
            "disabled",
        ),
        "defaultAcceleration": number(raw.get("default_acceleration"), 0),
        "outerWallAcceleration": number(raw.get("outer_wall_acceleration"), 0),
        "innerWallAcceleration": number(raw.get("inner_wall_acceleration"), 0),
        "topSurfaceAcceleration": number(raw.get("top_surface_acceleration"), 0),
        "travelAcceleration": number(raw.get("travel_acceleration"), 0),
        "firstLayerAcceleration": number(raw.get("initial_layer_acceleration"), 0),
        "bridgeAcceleration": bridge_acceleration,
        "bridgeAccelerationPercent": bridge_acceleration_percent,
        "sparseInfillAcceleration": sparse_infill_acceleration,
        "sparseInfillAccelerationPercent": sparse_infill_acceleration_percent,
        "internalSolidInfillAcceleration": internal_solid_infill_acceleration,
        "internalSolidInfillAccelerationPercent": internal_solid_infill_acceleration_percent,
        "nozzleDiameter": nozzle,
        "supportEnabled": boolean(raw.get("enable_support")),
        "enforceSupportLayers": integer(raw.get("enforce_support_layers"), 0),
        "brimType": enum_value(
            raw.get("brim_type"),
            {"auto_brim", "brim_ears", "outer_only", "inner_only", "outer_and_inner", "no_brim"},
            "auto_brim",
        ),
        "brimWidth": number(raw.get("brim_width"), 0),
        "brimObjectGap": number(raw.get("brim_object_gap"), 0),
        "brimEarsMaxAngle": number(raw.get("brim_ears_max_angle"), 125),
        "brimEarsDetectionLength": number(raw.get("brim_ears_detection_length"), 1),
        "topSolidLayers": integer(raw.get("top_shell_layers"), 5),
        "bottomSolidLayers": integer(raw.get("bottom_shell_layers"), 4),
        "topShellThickness": number(raw.get("top_shell_thickness"), 0),
        "bottomShellThickness": number(raw.get("bottom_shell_thickness"), 0),
        "topSurfaceDensity": number(raw.get("top_surface_density"), 100),
        "bottomSurfaceDensity": number(raw.get("bottom_surface_density"), 100),
        "fillPattern": infill_pattern(raw.get("sparse_infill_pattern"), "gyroid"),
        "fillMultiline": integer(raw.get("fill_multiline"), 1),
        "lateralLatticeAngle1": number(raw.get("lateral_lattice_angle_1"), -45),
        "lateralLatticeAngle2": number(raw.get("lateral_lattice_angle_2"), 45),
        "infillOverhangAngle": number(raw.get("infill_overhang_angle"), 60),
        "topSurfacePattern": infill_pattern(raw.get("top_surface_pattern"), "monotonicline"),
        "bottomSurfacePattern": infill_pattern(raw.get("bottom_surface_pattern"), "monotonic"),
        "internalSolidInfillPattern": infill_pattern(raw.get("internal_solid_infill_pattern"), "monotonic"),
        "infillFirst": boolean(raw.get("is_infill_first"), legacy_wall_order.startswith("infill/")),
        "infillWallOverlap": number(raw.get("infill_wall_overlap"), 15),
        "topBottomInfillWallOverlap": number(raw.get("top_bottom_infill_wall_overlap"), 25),
        "infillCombination": boolean(raw.get("infill_combination")),
        "infillCombinationMaxLayerHeight": infill_combination_height,
        "infillCombinationMaxLayerHeightPercent": infill_combination_height_percent,
        "infillDirection": number(raw.get("infill_direction"), 45),
        "solidInfillDirection": number(raw.get("solid_infill_direction"), 45),
        "sparseInfillRotationTemplate": rotation_template(raw.get("sparse_infill_rotate_template")),
        "solidInfillRotationTemplate": rotation_template(
            raw.get("solid_infill_rotate_template"),
            "0,90" if boolean(raw.get("rotate_solid_infill_direction")) else "",
        ),
        "alignInfillDirectionToModel": boolean(raw.get("align_infill_direction_to_model")),
        "minimumSparseInfillArea": number(raw.get("minimum_sparse_infill_area"), 15),
        "infillAnchor": infill_anchor,
        "infillAnchorPercent": infill_anchor_percent,
        "infillAnchorMax": infill_anchor_max,
        "infillAnchorMaxPercent": infill_anchor_max_percent,
        "skeletonInfillDensity": number(raw.get("skeleton_infill_density"), 25),
        "skinInfillDensity": number(raw.get("skin_infill_density"), 25),
        "skinInfillDepth": number(raw.get("skin_infill_depth"), 2),
        "infillLockDepth": number(raw.get("infill_lock_depth"), 1),
        "infillShiftStep": number(raw.get("infill_shift_step"), 0.4),
        "symmetricInfillYAxis": boolean(raw.get("symmetric_infill_y_axis")),
        "skinInfillLineWidth": skin_infill_line_width,
        "skinInfillLineWidthPercent": skin_infill_line_width_percent,
        "skeletonInfillLineWidth": skeleton_infill_line_width,
        "skeletonInfillLineWidthPercent": skeleton_infill_line_width_percent,
        "gapFillTarget": enum_value(
            raw.get("gap_fill_target"), {"everywhere", "topbottom", "nowhere"}, "nowhere"
        ),
        "filterOutGapFill": number(raw.get("filter_out_gap_fill"), 0),
        "reduceCrossingWall": boolean(raw.get("reduce_crossing_wall")),
        "maxTravelDetourDistance": max_travel_detour_distance,
        "maxTravelDetourDistancePercent": max_travel_detour_distance_percent,
        "reduceInfillRetraction": boolean(raw.get("reduce_infill_retraction")),
        "travelSpeed": number(raw.get("travel_speed"), 300),
        "travelSpeedZ": number(raw.get("travel_speed_z"), 0),
        "firstLayerSpeed": first_layer_speed,
        "supportType": normalized_support_type,
        "supportAngle": number(raw.get("support_threshold_angle"), 45),
        "supportInterfaceTopLayers": integer(raw.get("support_interface_top_layers"), 3),
        "supportInterfaceBottomLayers": integer(raw.get("support_interface_bottom_layers"), 0),
        "supportInterfaceSpacing": number(raw.get("support_interface_spacing"), 0.5),
        "supportBottomInterfaceSpacing": number(raw.get("support_bottom_interface_spacing"), 0.5),
        "supportTopZDistance": number(raw.get("support_top_z_distance"), 0.2),
        "supportBottomZDistance": number(raw.get("support_bottom_z_distance"), 0.2),
        "supportObjectXYDistance": absolute_number(raw.get("support_object_xy_distance"), 0.35),
        "supportBasePattern": enum_value(
            raw.get("support_base_pattern"),
            {"default", "rectilinear", "rectilinear-grid", "honeycomb", "lightning", "hollow"},
            "default",
        ),
        "supportInterfacePattern": enum_value(
            raw.get("support_interface_pattern"),
            {"auto", "rectilinear", "concentric", "rectilinear_interlaced", "grid"},
            "auto",
        ),
        "supportStyle": support_style(raw.get("support_style"), normalized_support_type),
        "supportPatternAngle": number(raw.get("support_angle"), 0),
        "supportThresholdOverlap": support_threshold_overlap,
        "supportThresholdOverlapPercent": support_threshold_overlap_percent,
        "supportObjectFirstLayerGap": absolute_number(raw.get("support_object_first_layer_gap"), 0.2),
        "avoidSupportInterfaceFilamentForBase": boolean(raw.get("support_interface_not_for_body"), True),
        "supportIroning": boolean(raw.get("support_ironing")),
        "supportIroningPattern": enum_value(
            raw.get("support_ironing_pattern"), {"rectilinear", "concentric"}, "rectilinear"
        ),
        "supportIroningFlow": number(raw.get("support_ironing_flow"), 10),
        "supportIroningSpacing": number(raw.get("support_ironing_spacing"), 0.1),
        "skirtType": enum_value(raw.get("skirt_type"), {"combined", "perobject"}, "combined"),
        "skirtLoops": integer(raw.get("skirt_loops"), 0),
        "skirtDistance": number(raw.get("skirt_distance"), 6),
        "skirtStartAngle": number(raw.get("skirt_start_angle"), -135),
        "skirtHeight": integer(raw.get("skirt_height"), 1),
        "skirtSpeed": number(raw.get("skirt_speed"), 50),
        "minimumSkirtLength": number(raw.get("min_skirt_length"), 0),
        "draftShield": enum_value(raw.get("draft_shield"), {"disabled", "enabled"}, "disabled"),
        "singleLoopDraftShield": boolean(raw.get("single_loop_draft_shield")),
        "raftLayers": integer(raw.get("raft_layers"), 0),
        "raftContactDistance": number(raw.get("raft_contact_distance"), 0.1),
        "raftExpansion": number(raw.get("raft_expansion"), 1.5),
        "raftFirstLayerDensity": number(raw.get("raft_first_layer_density"), 90),
        "raftFirstLayerExpansion": number(raw.get("raft_first_layer_expansion"), 2),
        "outerWallLineWidth": outer_wall_line_width,
        "innerWallLineWidth": inner_wall_line_width,
        "topSurfaceLineWidth": top_surface_line_width,
        "sparseInfillLineWidth": sparse_infill_line_width,
        "internalSolidInfillLineWidth": internal_solid_infill_line_width,
        "supportLineWidth": support_line_width,
        "initialLayerLineWidth": initial_layer_line_width,
        "smallPerimeterSpeed": small_perimeter_speed,
        "smallPerimeterSpeedPercent": small_perimeter_speed_percent,
        "smallPerimeterThreshold": number(raw.get("small_perimeter_threshold"), 0),
        "slowdownForCurledPerimeters": boolean(raw.get("slowdown_for_curled_perimeters"), True),
        "resolution": max(number(raw.get("resolution"), 0.01), 0.001),
        "slicingMode": enum_value(
            raw.get("slicing_mode"), {"regular", "even_odd", "close_holes"}, "regular"
        ),
        "sliceClosingRadius": number(raw.get("slice_closing_radius"), 0.049),
        "preciseZHeight": boolean(raw.get("precise_z_height")),
        "holeToPolyhole": boolean(raw.get("hole_to_polyhole")),
        "holeToPolyholeThreshold": hole_to_polyhole_threshold,
        "holeToPolyholeThresholdPercent": hole_to_polyhole_threshold_percent,
        "holeToPolyholeTwisted": boolean(raw.get("hole_to_polyhole_twisted"), True),
        "seamPosition": enum_value(
            raw.get("seam_position"), {"nearest", "aligned", "aligned_back", "back", "random"}, "aligned"
        ),
        "staggeredInnerSeams": boolean(raw.get("staggered_inner_seams")),
        "seamGap": seam_gap,
        "seamGapPercent": seam_gap_percent,
        "scarfSeamType": enum_value(
            raw.get("seam_slope_type"), {"none", "external", "all"}, "none"
        ),
        "scarfSeamConditional": boolean(raw.get("seam_slope_conditional")),
        "scarfAngleThreshold": integer(raw.get("scarf_angle_threshold"), 155),
        "scarfOverhangThreshold": number(raw.get("scarf_overhang_threshold"), 40),
        "scarfJointSpeed": scarf_joint_speed,
        "scarfJointSpeedPercent": scarf_joint_speed_percent,
        "scarfJointFlowRatio": number(raw.get("scarf_joint_flow_ratio"), 1),
        "scarfStartHeight": scarf_start_height,
        "scarfStartHeightPercent": scarf_start_height_percent,
        "scarfEntireLoop": boolean(raw.get("seam_slope_entire_loop")),
        "scarfLength": number(raw.get("seam_slope_min_length"), 20),
        "scarfSteps": integer(raw.get("seam_slope_steps"), 10),
        "scarfInnerWalls": boolean(raw.get("seam_slope_inner_walls")),
        "wipeBeforeExternalLoop": boolean(raw.get("wipe_before_external_loop")),
        "wipeOnLoops": boolean(raw.get("wipe_on_loops")),
        "roleBasedWipeSpeed": boolean(raw.get("role_based_wipe_speed"), True),
        "wipeSpeed": wipe_speed,
        "wipeSpeedPercent": wipe_speed_percent,
        "ironingType": enum_value(
            raw.get("ironing_type"), {"no ironing", "top", "topmost", "solid"}, "no ironing"
        ),
        "ironingPattern": infill_pattern(raw.get("ironing_pattern"), "rectilinear"),
        "ironingFlow": number(raw.get("ironing_flow"), 10),
        "ironingSpacing": number(raw.get("ironing_spacing"), 0.1),
        "ironingInset": number(raw.get("ironing_inset"), 0),
        "ironingSpeed": number(raw.get("ironing_speed"), 20),
        "ironingAngle": number(raw.get("ironing_angle"), -1),
        "wallGenerator": wall_generator(raw.get("wall_generator")),
        "wallTransitionLength": number(raw.get("wall_transition_length"), 100),
        "wallTransitionFilterDeviation": number(raw.get("wall_transition_filter_deviation"), 25),
        "wallTransitionAngle": number(raw.get("wall_transition_angle"), 10),
        "wallDistributionCount": integer(raw.get("wall_distribution_count"), 1),
        "minimumFeatureSize": number(raw.get("min_feature_size"), 25),
        "minimumWallWidth": number(raw.get("min_bead_width"), 85),
        "firstLayerMinimumWallWidth": number(raw.get("initial_layer_min_bead_width"), 85),
        "minimumWallLengthFactor": number(raw.get("min_length_factor"), 0.5),
        "wallSequence": wall_sequence(resolved_wall_order),
        "wallDirection": enum_value(raw.get("wall_direction"), {"auto", "ccw", "cw"}, "auto"),
        "detectThinWalls": boolean(raw.get("detect_thin_wall")),
        "detectOverhangWalls": boolean(raw.get("detect_overhang_wall"), True),
        "makeOverhangPrintable": boolean(raw.get("make_overhang_printable")),
        "makeOverhangPrintableAngle": number(raw.get("make_overhang_printable_angle"), 55),
        "makeOverhangPrintableHoleSize": number(raw.get("make_overhang_printable_hole_size"), 0),
        "onlyOneWallOnTop": boolean(raw.get("only_one_wall_top")),
        "minWidthTopSurface": min_width_top_surface,
        "minWidthTopSurfacePercent": min_width_top_surface_percent,
        "onlyOneWallFirstLayer": boolean(raw.get("only_one_wall_first_layer")),
        "extraPerimetersOnOverhangs": boolean(raw.get("extra_perimeters_on_overhangs")),
        "overhangReverse": boolean(raw.get("overhang_reverse")),
        "overhangReverseInternalOnly": boolean(raw.get("overhang_reverse_internal_only")),
        "overhangReverseThreshold": overhang_reverse_threshold,
        "overhangReverseThresholdPercent": overhang_reverse_threshold_percent,
        "counterboreHoleBridging": enum_value(
            raw.get("counterbore_hole_bridging"),
            {"none", "partiallybridge", "sacrificiallayer"},
            "none",
        ),
        "alternateExtraWall": boolean(raw.get("alternate_extra_wall")),
        "ensureVerticalShellThickness": vertical_shell_mode(raw.get("ensure_vertical_shell_thickness")),
        "detectNarrowInternalSolidInfill": boolean(raw.get("detect_narrow_internal_solid_infill"), True),
        "xyHoleCompensation": number(raw.get("xy_hole_compensation"), 0),
        "xyContourCompensation": number(raw.get("xy_contour_compensation"), 0),
        "elephantFootCompensation": number(raw.get("elefant_foot_compensation"), 0),
        "elephantFootCompensationLayers": integer(raw.get("elefant_foot_compensation_layers"), 1),
        "maxBridgeLength": number(raw.get("max_bridge_length"), 10),
        "preciseOuterWalls": boolean(raw.get("precise_outer_wall"), True),
        "printSequence": enum_value(raw.get("print_sequence"), {"by layer", "by object"}, "by layer"),
        "printOrder": enum_value(raw.get("print_order"), {"default", "as_obj_list"}, "default"),
        "supportFilament": integer(raw.get("support_filament"), 0),
        "supportInterfaceFilament": integer(raw.get("support_interface_filament"), 0),
        "infillFilamentOverrideEnabled": boolean(raw.get("enable_infill_filament_override")),
        "infillFilamentBaseFirstLayers": integer(raw.get("infill_filament_use_base_first_layers"), 0),
        "infillFilamentBaseLastLayers": integer(raw.get("infill_filament_use_base_last_layers"), 0),
        "sparseInfillFilament": sparse_infill_filament,
        "wallFilament": wall_filament,
        "solidInfillFilament": solid_infill_filament,
        "wipeTowerFilament": integer(raw.get("wipe_tower_filament"), 0),
        "wipeTowerEnabled": boolean(raw.get("enable_prime_tower")),
        "wipeTowerWidth": number(raw.get("prime_tower_width"), 60),
        "primeVolume": number(raw.get("prime_volume"), 45),
        "primeTowerBrimWidth": number(raw.get("prime_tower_brim_width"), 3),
        "wipeTowerNoSparseLayers": boolean(raw.get("wipe_tower_no_sparse_layers")),
        "wipeTowerRotationAngle": number(raw.get("wipe_tower_rotation_angle"), 0),
        "wipeTowerBridging": number(raw.get("wipe_tower_bridging"), 10),
        "wipeTowerExtraSpacing": number(raw.get("wipe_tower_extra_spacing"), 100),
        "wipeTowerExtraFlow": number(raw.get("wipe_tower_extra_flow"), 100),
        "wipeTowerMaxPurgeSpeed": number(raw.get("wipe_tower_max_purge_speed"), 90),
        "wipeTowerWallType": enum_value(
            raw.get("wipe_tower_wall_type"), {"rectangle", "cone", "rib"}, "rectangle"
        ),
        "wipeTowerConeAngle": number(raw.get("wipe_tower_cone_angle"), 30),
        "wipeTowerExtraRibLength": number(raw.get("wipe_tower_extra_rib_length"), 0),
        "wipeTowerRibWidth": number(raw.get("wipe_tower_rib_width"), 8),
        "wipeTowerFilletWall": boolean(raw.get("wipe_tower_fillet_wall"), True),
        "singleExtruderMultiMaterialPriming": boolean(
            raw.get("single_extruder_multi_material_priming")
        ),
        "flushIntoInfill": boolean(raw.get("flush_into_infill")),
        "flushIntoSupport": boolean(raw.get("flush_into_support"), True),
        "flushIntoObjects": boolean(raw.get("flush_into_objects")),
        "oozePrevention": boolean(raw.get("ooze_prevention")),
        "standbyTemperatureDelta": integer(raw.get("standby_temperature_delta"), -5),
        "preheatTime": number(raw.get("preheat_time"), 30),
        "preheatDeltaTemperature": integer(raw.get("delta_temperature"), 0),
        "preheatSteps": integer(raw.get("preheat_steps"), 1),
        "interfaceShells": boolean(raw.get("interface_shells")),
        "segmentedRegionMaxWidth": number(raw.get("mmu_segmented_region_max_width"), 0),
        "segmentedRegionInterlockingDepth": number(
            raw.get("mmu_segmented_region_interlocking_depth"), 0
        ),
        "interlockingBeam": boolean(raw.get("interlocking_beam")),
        "interlockingBeamWidth": number(raw.get("interlocking_beam_width"), 0.8),
        "interlockingOrientation": number(raw.get("interlocking_orientation"), 22.5),
        "interlockingBeamLayerCount": integer(raw.get("interlocking_beam_layer_count"), 2),
        "interlockingDepth": integer(raw.get("interlocking_depth"), 2),
        "interlockingBoundaryAvoidance": integer(raw.get("interlocking_boundary_avoidance"), 2),
        "maxVolumetricExtrusionRateSlope": number(
            raw.get("max_volumetric_extrusion_rate_slope"), 0
        ),
        "maxVolumetricExtrusionRateSlopeSegmentLength": number(
            raw.get("max_volumetric_extrusion_rate_slope_segment_length"), 3
        ),
        "extrusionRateSmoothingExternalOnly": boolean(
            raw.get("extrusion_rate_smoothing_external_perimeter_only")
        ),
        "enableArcFitting": boolean(raw.get("enable_arc_fitting")),
        "gcodeLabelObjects": boolean(raw.get("gcode_label_objects"), True),
        "excludeObject": boolean(raw.get("exclude_object")),
        "gcodeComments": boolean(raw.get("gcode_comments")),
        "initialLayerTravelSpeed": initial_layer_travel_speed,
        "initialLayerTravelSpeedPercent": initial_layer_travel_speed_percent,
        "slowDownLayers": integer(raw.get("slow_down_layers"), 0),
        "accelToDecelEnabled": boolean(raw.get("accel_to_decel_enable"), True),
        "accelToDecelFactor": number(raw.get("accel_to_decel_factor"), 50),
        "spiralMode": boolean(raw.get("spiral_mode")),
        "spiralModeSmooth": boolean(raw.get("spiral_mode_smooth")),
        "spiralModeMaxXySmoothing": spiral_xy_smoothing,
        "spiralModeMaxXySmoothingPercent": spiral_xy_smoothing_percent,
        "spiralStartingFlowRatio": number(raw.get("spiral_starting_flow_ratio"), 0),
        "spiralFinishingFlowRatio": number(raw.get("spiral_finishing_flow_ratio"), 0),
        "supportOnBuildPlateOnly": boolean(raw.get("support_on_build_plate_only")),
        "supportCriticalRegionsOnly": boolean(raw.get("support_critical_regions_only")),
        "supportRemoveSmallOverhangs": boolean(raw.get("support_remove_small_overhang"), True),
        "supportBasePatternSpacing": number(raw.get("support_base_pattern_spacing"), 2.5),
        "supportExpansion": number(raw.get("support_expansion"), 0),
        "supportInterfaceLoopPattern": boolean(raw.get("support_interface_loop_pattern")),
        "independentSupportLayerHeight": boolean(raw.get("independent_support_layer_height"), True),
        "treeSupportBranchAngle": number(raw.get("tree_support_branch_angle"), 40),
        "treeSupportBranchDistance": number(raw.get("tree_support_branch_distance"), 5),
        "treeSupportBranchDiameter": number(raw.get("tree_support_branch_diameter"), 5),
        "treeSupportWallCount": integer(raw.get("tree_support_wall_count"), 0),
        "treeSupportTipDiameter": number(raw.get("tree_support_tip_diameter"), 0.8),
        "treeSupportPreferredBranchAngle": number(raw.get("tree_support_angle_slow"), 25),
        "treeSupportBranchDensity": number(raw.get("tree_support_top_rate"), 30),
        "treeSupportOrganicBranchAngle": number(raw.get("tree_support_branch_angle_organic"), 40),
        "treeSupportOrganicBranchDistance": number(raw.get("tree_support_branch_distance_organic"), 1),
        "treeSupportOrganicBranchDiameter": number(raw.get("tree_support_branch_diameter_organic"), 2),
        "treeSupportBranchDiameterAngle": number(raw.get("tree_support_branch_diameter_angle"), 5),
        "treeSupportAdaptiveLayerHeight": boolean(raw.get("tree_support_adaptive_layer_height"), True),
        "treeSupportAutoBrim": boolean(raw.get("tree_support_auto_brim"), True),
        "treeSupportBrimWidth": number(raw.get("tree_support_brim_width"), 3),
        "compatiblePrinters": compatible,
        "defaultJerk": number(raw.get("default_jerk"), 0),
        "outerWallJerk": number(raw.get("outer_wall_jerk"), 9),
        "innerWallJerk": number(raw.get("inner_wall_jerk"), 9),
        "topSurfaceJerk": number(raw.get("top_surface_jerk"), 9),
        "infillJerk": number(raw.get("infill_jerk"), 9),
        "firstLayerJerk": number(raw.get("initial_layer_jerk"), 9),
        "travelJerk": number(raw.get("travel_jerk"), 12),
        "fuzzySkinType": enum_value(
            raw.get("fuzzy_skin"), {"none", "external", "all", "allwalls"}, "none"
        ),
        "fuzzySkinFirstLayer": boolean(raw.get("fuzzy_skin_first_layer")),
        "fuzzySkinPointDistance": number(raw.get("fuzzy_skin_point_distance"), 0.3),
        "fuzzySkinThickness": number(raw.get("fuzzy_skin_thickness"), 0.2),
        "fuzzySkinMode": enum_value(
            raw.get("fuzzy_skin_mode"), {"displacement", "extrusion", "combined"}, "displacement"
        ),
        "fuzzySkinNoiseType": enum_value(
            raw.get("fuzzy_skin_noise_type"),
            {"classic", "perlin", "billow", "ridgedmulti", "voronoi"},
            "classic",
        ),
        "fuzzySkinScale": number(raw.get("fuzzy_skin_scale"), 1),
        "fuzzySkinOctaves": integer(raw.get("fuzzy_skin_octaves"), 4),
        "fuzzySkinPersistence": number(raw.get("fuzzy_skin_persistence"), 0.5),
    }
    if not (
        0 <= profile["fillDensity"] <= 1
        and 1 <= profile["printSpeed"] <= 2_000
        and 0 <= profile["printFlowRatio"] <= 2
        and all(
            1 <= profile[key] <= 2_000
            for key in [
                "innerWallSpeed",
                "sparseInfillSpeed",
                "internalSolidInfillSpeed",
                "topSurfaceSpeed",
                "supportSpeed",
                "bridgeSpeed",
                "gapInfillSpeed",
                "firstLayerInfillSpeed",
                "supportInterfaceSpeed",
            ]
        )
        and 1 <= profile["internalBridgeSpeed"] <= (
            1_000 if profile["internalBridgeSpeedPercent"] else 2_000
        )
        and 1 <= profile["travelSpeed"] <= 2_000
        and 0 <= profile["travelSpeedZ"] <= 2_000
        and 1 <= profile["initialLayerTravelSpeed"] <= (
            1_000 if profile["initialLayerTravelSpeedPercent"] else 2_000
        )
        and 0 <= profile["slowDownLayers"] <= 1_000
        and 1 <= profile["accelToDecelFactor"] <= 100
        and all(
            0 <= profile[key] <= (100 if profile[f"{key}Percent"] else 2_000)
            for key in ["overhangSpeed1", "overhangSpeed2", "overhangSpeed3", "overhangSpeed4"]
        )
        and all(
            0.1 <= profile[key] <= 2
            for key in [
                "bridgeFlowRatio",
                "internalBridgeFlowRatio",
                "topSurfaceFlowRatio",
                "bottomSurfaceFlowRatio",
            ]
        )
        and all(
            0 <= profile[key] <= 100_000
            for key in [
                "defaultAcceleration",
                "outerWallAcceleration",
                "innerWallAcceleration",
                "topSurfaceAcceleration",
                "travelAcceleration",
                "firstLayerAcceleration",
            ]
        )
        and all(
            0 <= profile[key] <= (1_000 if profile[f"{key}Percent"] else 100_000)
            for key in ["bridgeAcceleration", "sparseInfillAcceleration", "internalSolidInfillAcceleration"]
        )
        and all(
            0 <= profile[key] <= 2_000
            for key in [
                "defaultJerk",
                "outerWallJerk",
                "innerWallJerk",
                "topSurfaceJerk",
                "infillJerk",
                "firstLayerJerk",
                "travelJerk",
            ]
        )
        and 0 <= profile["maxVolumetricExtrusionRateSlope"] <= 10_000
        and 0.5 <= profile["maxVolumetricExtrusionRateSlopeSegmentLength"] <= 5
        and 10 <= profile["bridgeDensity"] <= 100
        and 10 <= profile["internalBridgeDensity"] <= 100
        and 0 <= profile["bridgeAngle"] <= 360
        and 0 <= profile["fuzzySkinThickness"] <= 1
        and 0 <= profile["fuzzySkinPointDistance"] <= 5
        and 0.1 <= profile["fuzzySkinScale"] <= 500
        and 1 <= profile["fuzzySkinOctaves"] <= 10
        and 0.01 <= profile["fuzzySkinPersistence"] <= 1
        and 0 <= profile["supportFilament"] <= 16
        and 0 <= profile["supportInterfaceFilament"] <= 16
        and 0 <= profile["enforceSupportLayers"] <= 5_000
        and 0 <= profile["infillFilamentBaseFirstLayers"] <= 1_000
        and 0 <= profile["infillFilamentBaseLastLayers"] <= 1_000
        and all(1 <= profile[key] <= 16 for key in [
            "sparseInfillFilament", "wallFilament", "solidInfillFilament"
        ])
        and 0 <= profile["wipeTowerFilament"] <= 16
        and 10 <= profile["wipeTowerWidth"] <= 300
        and 1 <= profile["primeVolume"] <= 1_000
        and 0 <= profile["primeTowerBrimWidth"] <= 100
        and -500 <= profile["standbyTemperatureDelta"] <= 500
        and 0 <= profile["internalBridgeAngle"] <= 360
        and 0 <= profile["infillWallOverlap"] <= 100
        and 0 <= profile["topBottomInfillWallOverlap"] <= 100
        and 0 <= profile["infillCombinationMaxLayerHeight"] <= (
            1_000 if profile["infillCombinationMaxLayerHeightPercent"] else 10
        )
        and all(0 <= profile[key] <= 360 for key in ["infillDirection", "solidInfillDirection"])
        and -75 <= profile["lateralLatticeAngle1"] <= 75
        and -75 <= profile["lateralLatticeAngle2"] <= 75
        and 15 <= profile["infillOverhangAngle"] <= 75
        and 0 <= profile["minimumSparseInfillArea"] <= 1_000_000
        and 0 <= profile["filterOutGapFill"] <= 1_000_000
        and 0 <= profile["maxTravelDetourDistance"] <= 1_000
        and 0 <= profile["smallPerimeterSpeed"] <= (
            1_000 if profile["smallPerimeterSpeedPercent"] else 2_000
        )
        and 0 <= profile["smallPerimeterThreshold"] <= 1_000_000
        and 0.001 <= profile["resolution"] <= 100
        and profile["slicingMode"] in {"regular", "even_odd", "close_holes"}
        and 0 <= profile["sliceClosingRadius"] <= 10
        and 0 <= profile["holeToPolyholeThreshold"] <= 10
        and 0 <= profile["seamGap"] <= 1_000
        and profile["scarfSeamType"] in {"none", "external", "all"}
        and 0 <= profile["scarfAngleThreshold"] <= 180
        and 0 <= profile["scarfOverhangThreshold"] <= 100
        and 1 <= profile["scarfJointSpeed"] <= (
            1_000 if profile["scarfJointSpeedPercent"] else 2_000
        )
        and 0 <= profile["scarfJointFlowRatio"] <= 2
        and 0 <= profile["scarfStartHeight"] <= (
            1_000 if profile["scarfStartHeightPercent"] else 10
        )
        and 0 <= profile["scarfLength"] <= 1_000_000
        and 1 <= profile["scarfSteps"] <= 1_000
        and 0 <= profile["minWidthTopSurface"] <= 1_500
        and 0 <= profile["overhangReverseThreshold"] <= 2_000
        and 0 <= profile["wipeSpeed"] <= (
            1_000 if profile["wipeSpeedPercent"] else 2_000
        )
        and all(
            0 <= profile[key] <= 1_000
            for key in ["infillAnchor", "infillAnchorMax"]
        )
        and all(
            0 <= profile[key] <= 100
            for key in ["skeletonInfillDensity", "skinInfillDensity", "skinInfillDepth", "infillLockDepth"]
        )
        and 0 <= profile["infillShiftStep"] <= 10
        and 0 <= profile["skinInfillLineWidth"] <= (
            1_000 if profile["skinInfillLineWidthPercent"] else 10
        )
        and 0 <= profile["skeletonInfillLineWidth"] <= (
            1_000 if profile["skeletonInfillLineWidthPercent"] else 10
        )
        and all(abs(profile[key]) <= 2 for key in ["xyHoleCompensation", "xyContourCompensation"])
        and 0 <= profile["elephantFootCompensation"] <= 2
        and 1 <= profile["elephantFootCompensationLayers"] <= 100
        and 0 <= profile["maxBridgeLength"] <= 1_000_000
        and profile["printSequence"] in {"by layer", "by object"}
        and profile["printOrder"] in {"default", "as_obj_list"}
        and 0 <= profile["spiralModeMaxXySmoothing"] <= (
            1_000 if profile["spiralModeMaxXySmoothingPercent"] else 10
        )
        and 0 <= profile["spiralStartingFlowRatio"] <= 1
        and 0 <= profile["spiralFinishingFlowRatio"] <= 1
        and 0 <= profile["supportBasePatternSpacing"] <= 100
        and -100 <= profile["supportExpansion"] <= 100
        and 0 <= profile["treeSupportBranchAngle"] <= 60
        and 1 <= profile["treeSupportBranchDistance"] <= 10
        and 1 <= profile["treeSupportBranchDiameter"] <= 10
        and 0 <= profile["treeSupportWallCount"] <= 2
        and 0.1 <= profile["treeSupportTipDiameter"] <= 100
        and 10 <= profile["treeSupportPreferredBranchAngle"] <= 85
        and 5 <= profile["treeSupportBranchDensity"] <= 100
        and 0 <= profile["treeSupportOrganicBranchAngle"] <= 60
        and 1 <= profile["treeSupportOrganicBranchDistance"] <= 10
        and 1 <= profile["treeSupportOrganicBranchDiameter"] <= 10
        and profile["treeSupportOrganicBranchDiameter"] >= profile["treeSupportTipDiameter"]
        and 0 <= profile["treeSupportBranchDiameterAngle"] <= 15
        and 0 <= profile["treeSupportBrimWidth"] <= 100
        and all(
            0.1 <= profile[key] <= 3
            for key in [
                "outerWallLineWidth",
                "innerWallLineWidth",
                "topSurfaceLineWidth",
                "sparseInfillLineWidth",
                "internalSolidInfillLineWidth",
                "supportLineWidth",
                "initialLayerLineWidth",
            ]
        )
        and 0 <= profile["topShellThickness"] <= 100
        and 0 <= profile["bottomShellThickness"] <= 100
        and 0 <= profile["topSurfaceDensity"] <= 100
        and 10 <= profile["bottomSurfaceDensity"] <= 100
        and 0 <= profile["supportInterfaceTopLayers"] <= 20
        and -1 <= profile["supportInterfaceBottomLayers"] <= 20
        and 0 <= profile["supportInterfaceSpacing"] <= 20
        and 0 <= profile["supportBottomInterfaceSpacing"] <= 20
        and 0 <= profile["supportTopZDistance"] <= 20
        and 0 <= profile["supportBottomZDistance"] <= 20
        and 0 <= profile["supportObjectXYDistance"] <= 20
        and 0 <= profile["supportPatternAngle"] <= 359
        and 0 <= profile["supportThresholdOverlap"] <= (
            100 if profile["supportThresholdOverlapPercent"] else 0.5
        )
        and 0 <= profile["supportObjectFirstLayerGap"] <= 10
        and profile["supportIroningPattern"] in {"rectilinear", "concentric"}
        and 0 <= profile["supportIroningFlow"] <= 100
        and 0 <= profile["supportIroningSpacing"] <= 1
        and 0 <= profile["brimObjectGap"] <= 20
        and 0 <= profile["brimEarsMaxAngle"] <= 180
        and 0 <= profile["brimEarsDetectionLength"] <= 1_000
        and 0 <= profile["preheatTime"] <= 120
        and -50 <= profile["preheatDeltaTemperature"] <= 50
        and 1 <= profile["preheatSteps"] <= 10
        and 0.01 <= profile["interlockingBeamWidth"] <= 1_000
        and 0 <= profile["segmentedRegionMaxWidth"] <= 1_000
        and 0 <= profile["segmentedRegionInterlockingDepth"] <= 1_000
        and (
            profile["segmentedRegionInterlockingDepth"] == 0
            or profile["segmentedRegionInterlockingDepth"] <= profile["segmentedRegionMaxWidth"]
        )
        and 0 <= profile["interlockingOrientation"] <= 360
        and 1 <= profile["interlockingBeamLayerCount"] <= 1_000
        and 1 <= profile["interlockingDepth"] <= 1_000
        and 0 <= profile["interlockingBoundaryAvoidance"] <= 1_000
        and profile["skirtType"] in {"combined", "perobject"}
        and -180 <= profile["skirtStartAngle"] <= 180
        and 0 <= profile["skirtHeight"] <= 10_000
        and 0 <= profile["skirtSpeed"] <= 2_000
        and 0 <= profile["minimumSkirtLength"] <= 1_000_000
        and 0 <= profile["raftLayers"] <= 100
        and 0 <= profile["raftContactDistance"] <= 20
        and 0 <= profile["raftExpansion"] <= 1_000
        and 10 <= profile["raftFirstLayerDensity"] <= 100
        and 0 <= profile["raftFirstLayerExpansion"] <= 1_000
        and profile["fillPattern"] in INFILL_PATTERNS
        and 1 <= profile["fillMultiline"] <= 5
        and profile["topSurfacePattern"] in INFILL_PATTERNS
        and profile["bottomSurfacePattern"] in INFILL_PATTERNS
        and profile["internalSolidInfillPattern"] in INFILL_PATTERNS
        and profile["ironingPattern"] in INFILL_PATTERNS
        and 0 <= profile["ironingFlow"] <= 100
        and 0 <= profile["ironingSpacing"] <= 1
        and 0 <= profile["ironingInset"] <= 100
        and 1 <= profile["ironingSpeed"] <= 2_000
        and -1 <= profile["ironingAngle"] <= 359
        and 0 <= profile["wallTransitionLength"] <= 10_000
        and 0 <= profile["wallTransitionFilterDeviation"] <= 10_000
        and 1 <= profile["wallTransitionAngle"] <= 59
        and 1 <= profile["wallDistributionCount"] <= 100
        and 0 <= profile["minimumFeatureSize"] <= 10_000
        and 0 <= profile["minimumWallWidth"] <= 1_000
        and 0 <= profile["firstLayerMinimumWallWidth"] <= 1_000
        and 0 <= profile["minimumWallLengthFactor"] <= 100
    ):
        raise ValueError("unsafe process limits")
    return profile


def binary_kind(value: Any) -> int:
    if value is None:
        return 0
    if isinstance(value, bool):
        return BINARY_BOOL
    if isinstance(value, int):
        return BINARY_INT
    if isinstance(value, float):
        return BINARY_FLOAT
    if isinstance(value, str):
        return BINARY_STRING
    if isinstance(value, list) and all(isinstance(item, str) for item in value):
        return BINARY_STRING_LIST
    if isinstance(value, list) and all(isinstance(item, (int, float)) and not isinstance(item, bool) for item in value):
        return BINARY_FLOAT_LIST
    raise ValueError(f"unsupported binary catalog value: {type(value).__name__}")


def infer_binary_kind(records: list[dict[str, Any]], field: str) -> int:
    kinds = {binary_kind(record[field]) for record in records}
    nullable = 0 in kinds
    kinds.discard(0)
    if nullable:
        if kinds <= {BINARY_INT, BINARY_FLOAT}:
            return BINARY_NULLABLE_FLOAT
        if kinds == {BINARY_BOOL}:
            return BINARY_NULLABLE_BOOL
        if kinds == {BINARY_STRING}:
            return BINARY_NULLABLE_STRING
        raise ValueError(f"unsupported nullable binary catalog field: {field}")
    if kinds <= {BINARY_INT, BINARY_FLOAT}:
        return BINARY_FLOAT if BINARY_FLOAT in kinds else BINARY_INT
    if len(kinds) == 1:
        return kinds.pop()
    raise ValueError(f"binary catalog field changed type: {field}")


def write_binary_string(output: Any, value: str) -> None:
    encoded = value.encode("utf-8")
    output.write(struct.pack(">I", len(encoded)))
    output.write(encoded)


def write_binary_value(output: Any, kind: int, value: Any) -> None:
    if kind == BINARY_STRING:
        write_binary_string(output, value)
    elif kind == BINARY_FLOAT:
        output.write(struct.pack(">f", value))
    elif kind == BINARY_INT:
        output.write(struct.pack(">i", value))
    elif kind == BINARY_BOOL:
        output.write(b"\x01" if value else b"\x00")
    elif kind == BINARY_STRING_LIST:
        output.write(struct.pack(">I", len(value)))
        for item in value:
            write_binary_string(output, item)
    elif kind == BINARY_FLOAT_LIST:
        output.write(struct.pack(">I", len(value)))
        for item in value:
            output.write(struct.pack(">f", item))
    elif kind in {BINARY_NULLABLE_FLOAT, BINARY_NULLABLE_BOOL, BINARY_NULLABLE_STRING}:
        output.write(b"\x00" if value is None else b"\x01")
        if value is not None:
            if kind == BINARY_NULLABLE_FLOAT:
                output.write(struct.pack(">f", value))
            elif kind == BINARY_NULLABLE_BOOL:
                output.write(b"\x01" if value else b"\x00")
            else:
                write_binary_string(output, value)
    else:
        raise ValueError(f"unsupported binary catalog kind: {kind}")


def write_binary_section(output: Any, records: list[dict[str, Any]]) -> None:
    if not records:
        output.write(struct.pack(">II", 0, 0))
        return
    fields = list(records[0])
    kinds = [infer_binary_kind(records, field) for field in fields]
    expected_fields = set(fields)
    for record in records:
        if list(record) != fields:
            raise ValueError("binary catalog field order changed within a section")
        if set(record) != expected_fields:
            raise ValueError("binary catalog record has inconsistent fields")
        for field, kind in zip(fields, kinds):
            actual_kind = binary_kind(record[field])
            nullable_match = (
                (kind == BINARY_NULLABLE_FLOAT and actual_kind in {0, BINARY_INT, BINARY_FLOAT})
                or (kind == BINARY_NULLABLE_BOOL and actual_kind in {0, BINARY_BOOL})
                or (kind == BINARY_NULLABLE_STRING and actual_kind in {0, BINARY_STRING})
            )
            if actual_kind != kind and not nullable_match and not (
                kind == BINARY_FLOAT and actual_kind == BINARY_INT
            ):
                raise ValueError(f"binary catalog field changed type: {field}")

    output.write(struct.pack(">I", len(fields)))
    for field, kind in zip(fields, kinds):
        write_binary_string(output, field)
        output.write(bytes([kind]))
    output.write(struct.pack(">I", len(records)))
    for record in records:
        for field, kind in zip(fields, kinds):
            write_binary_value(output, kind, record[field])


def write_binary_catalog(output_path: Path, catalog: dict[str, Any]) -> None:
    temporary = output_path.with_suffix(output_path.suffix + ".tmp")
    with temporary.open("wb") as output:
        output.write(BINARY_MAGIC)
        output.write(struct.pack(">I", catalog["schemaVersion"]))
        write_binary_string(output, catalog["sourceRevision"])
        output.write(struct.pack(">I", sum(catalog["rejected"].values())))
        write_binary_section(output, catalog["printers"])
        write_binary_section(output, catalog["filaments"])
        write_binary_section(output, catalog["slicing"])
        output.flush()
    temporary.replace(output_path)


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit("usage: generate_profile_catalog.py PROFILE_ROOT OUTPUT SOURCE_REVISION")
    profile_root = Path(sys.argv[1]).resolve()
    output = Path(sys.argv[2]).resolve()
    revision = sys.argv[3]
    resolver = Resolver(profile_root)
    rejected: Counter[str] = Counter()

    resolved_entries: list[tuple[str, dict[str, Any]]] = []
    for entry_id, (_, brand, raw) in enumerate(resolver.entries):
        if not boolean(raw.get("instantiation")):
            continue
        try:
            resolved_entries.append((brand, resolver.resolve(entry_id)))
        except ValueError as error:
            rejected[f"inheritance: {error}"] += 1

    printers: list[dict[str, Any]] = []
    for brand, raw in resolved_entries:
        if raw.get("type") != "machine":
            continue
        try:
            printers.append(build_printer(brand, raw))
        except ValueError as error:
            rejected[f"printer: {error}"] += 1
    printers.sort(key=lambda profile: (profile["brand"].casefold(), profile["name"].casefold()))
    printer_nozzles = {profile["name"]: profile["nozzleDiameter"] for profile in printers}

    filaments: list[dict[str, Any]] = []
    processes: list[dict[str, Any]] = []
    for brand, raw in resolved_entries:
        try:
            if raw.get("type") == "filament":
                filaments.append(build_filament(brand, raw))
            elif raw.get("type") == "process":
                processes.append(build_process(brand, raw, printer_nozzles))
        except ValueError as error:
            rejected[f"{raw.get('type')}: {error}"] += 1
    filaments.sort(key=lambda profile: (profile["brand"].casefold(), profile["name"].casefold()))
    processes.sort(key=lambda profile: (profile["brand"].casefold(), profile["name"].casefold()))

    catalog = {
        "schemaVersion": SCHEMA_VERSION,
        "sourceRevision": revision,
        "rejected": dict(sorted(rejected.items())),
        "printers": printers,
        "filaments": filaments,
        "slicing": processes,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.suffix == ".bin":
        write_binary_catalog(output, catalog)
    else:
        temporary = output.with_suffix(output.suffix + ".tmp")
        temporary.write_text(json.dumps(catalog, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
        temporary.replace(output)
    print(
        f"Generated {output}: {len(printers)} printers, {len(filaments)} filaments, "
        f"{len(processes)} processes, {sum(rejected.values())} rejected"
    )


if __name__ == "__main__":
    main()
