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

SCHEMA_VERSION = 116
MAX_FILAMENT_SLOTS = 16
NO_FILAMENT_COLOR = -1
MAX_GCODE_THUMBNAILS = 8
SUPPORTED_GCODE_THUMBNAIL_FORMATS = {"PNG", "JPG", "QOI", "BTT_TFT", "COLPIC"}
DEFAULT_GCODE_FILENAME_FORMAT = (
    "{input_filename_base}_{filament_type[initial_tool]}_{print_time}.gcode"
)
DEFAULT_SMALL_AREA_FLOW_COMPENSATION_MODEL = (
    "0,0\n0.2,0.4444\n0.4,0.6145\n0.6,0.7059\n0.8,0.7619\n"
    "1.5,0.8571\n2,0.8889\n3,0.9231\n5,0.9520\n10,1"
)
MAX_GCODE_FILENAME_FORMAT_BYTES = 1_024
MAX_ADAPTIVE_PRESSURE_ADVANCE_MODEL_BYTES = 16_384
SUPPORTED_GCODE_FLAVORS = {"marlin", "marlin2", "klipper", "reprapfirmware"}
SUPPORTED_PRINTER_STRUCTURES = {"undefine", "corexy", "i3", "hbot", "delta"}
NOZZLE_MATERIALS = {"undefine", "hardened_steel", "stainless_steel", "brass"}
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
BINARY_EMPTY_LIST = 255


def scalar(value: Any, default: Any = None) -> Any:
    if isinstance(value, list):
        return value[0] if value else default
    return default if value is None else value


def first_non_blank(raw: dict[str, Any], names: list[str], default: str = "") -> str:
    for name in names:
        if name not in raw:
            continue
        candidate = str(raw[name])
        if candidate.strip():
            return candidate
    return default


def number(value: Any, default: float) -> float:
    candidate = str(scalar(value, default)).strip().removesuffix("%")
    try:
        parsed = float(candidate)
    except ValueError:
        return default
    return parsed if math.isfinite(parsed) else default


def integer(value: Any, default: int) -> int:
    return round(number(value, float(default)))


def nozzle_material(value: Any) -> str:
    candidate = str(scalar(value, "undefine")).strip().lower()
    if candidate not in NOZZLE_MATERIALS:
        raise ValueError("unsupported nozzle material")
    return candidate


def filament_vendor(value: Any, source_brand: str) -> str:
    candidate = str(scalar(value, source_brand)).strip()
    if not candidate:
        return source_brand
    return {
        "snapmaker": "Snapmaker",
    }.get(candidate.casefold(), candidate)


def filament_color(value: Any) -> int:
    candidate = str(scalar(value, "")).strip().strip('"')
    if re.fullmatch(r"#[0-9A-Fa-f]{6}", candidate) is None:
        return NO_FILAMENT_COLOR
    return int(candidate[1:], 16)


def boolean(value: Any, default: bool = False) -> bool:
    candidate = str(scalar(value, "1" if default else "0")).strip().lower()
    return candidate in {"1", "true", "yes", "on"}


def build_plate_type(value: Any) -> str:
    candidate = str(scalar(value, "4")).strip().casefold()
    aliases = {
        "1": "cool",
        "cool plate": "cool",
        "2": "engineering",
        "engineering plate": "engineering",
        "3": "high_temp",
        "high temp plate": "high_temp",
        "high temperature plate": "high_temp",
        "smooth pei plate": "high_temp",
        "4": "textured_pei",
        "textured pei plate": "textured_pei",
        "5": "textured_cool",
        "textured cool plate": "textured_cool",
        "6": "graphic_effect",
        "epoxy resin plate": "graphic_effect",
        "graphic effect plate": "graphic_effect",
        "7": "super_tack",
        "supertack plate": "super_tack",
        "super tack plate": "super_tack",
    }
    if candidate not in aliases:
        raise ValueError("unsupported default build plate")
    return aliases[candidate]


def filename_format(value: Any) -> str:
    candidate = str(scalar(value, DEFAULT_GCODE_FILENAME_FORMAT))
    if not candidate.strip():
        candidate = DEFAULT_GCODE_FILENAME_FORMAT
    if (
        len(candidate.encode("utf-8")) > MAX_GCODE_FILENAME_FORMAT_BYTES
        or any(character in candidate for character in ("\0", "\r", "\n"))
    ):
        raise ValueError("unsafe filename format")
    return candidate


def thumbnail_definitions(value: Any, default_format: Any = "PNG") -> str:
    fallback_format = str(scalar(default_format, "PNG")).strip().upper() or "PNG"
    if fallback_format not in SUPPORTED_GCODE_THUMBNAIL_FORMATS:
        raise ValueError("unsupported G-code thumbnail format")
    candidates = [
        item.strip()
        for raw_value in values(value)
        for item in raw_value.split(",")
        if item.strip()
    ]
    if len(candidates) > MAX_GCODE_THUMBNAILS:
        raise ValueError("too many G-code thumbnails")
    normalized: list[str] = []
    for candidate in candidates:
        match = re.fullmatch(
            r"([0-9]{1,3})x([0-9]{1,3})(?:/([A-Za-z_]+))?",
            candidate,
        )
        if match is None:
            raise ValueError("invalid G-code thumbnail definition")
        width, height = int(match.group(1)), int(match.group(2))
        image_format = (match.group(3) or fallback_format).upper()
        if not (1 <= width <= 999 and 1 <= height <= 999):
            raise ValueError("unsafe G-code thumbnail dimensions")
        if image_format not in SUPPORTED_GCODE_THUMBNAIL_FORMATS:
            raise ValueError("unsupported G-code thumbnail format")
        normalized.append(f"{width}x{height}/{image_format}")
    return ",".join(normalized)


def small_area_flow_compensation_model(value: Any) -> str:
    if value is None:
        return DEFAULT_SMALL_AREA_FLOW_COMPENSATION_MODEL
    raw_values = value if isinstance(value, list) else [value]
    lines: list[str] = []
    for raw_value in raw_values:
        serialized = str(raw_value).replace("\\n", "\n")
        for candidate in re.split(r"[;\n]", serialized):
            normalized = candidate.strip().strip('"').strip()
            if normalized:
                lines.append(normalized)
    if not lines:
        return DEFAULT_SMALL_AREA_FLOW_COMPENSATION_MODEL
    if len(lines) > 256:
        raise ValueError("too many small-area flow-compensation points")
    previous_length = -1.0
    normalized_lines: list[str] = []
    for index, line in enumerate(lines):
        coordinates = [coordinate.strip() for coordinate in line.split(",")]
        if len(coordinates) != 2:
            raise ValueError("invalid small-area flow-compensation point")
        try:
            extrusion_length, factor = map(float, coordinates)
        except ValueError as error:
            raise ValueError("invalid small-area flow-compensation number") from error
        if (
            not math.isfinite(extrusion_length)
            or not math.isfinite(factor)
            or extrusion_length < 0
            or extrusion_length > 1_000_000
            or factor < 0
            or factor > 2
            or (index == 0 and extrusion_length != 0)
            or (index > 0 and extrusion_length <= previous_length)
        ):
            raise ValueError("unsafe small-area flow-compensation model")
        previous_length = extrusion_length
        normalized_lines.append(f"{coordinates[0]},{coordinates[1]}")
    if len(normalized_lines) < 2 or abs(float(normalized_lines[-1].split(",")[1]) - 1) > 1e-6:
        raise ValueError("small-area flow-compensation model must end at factor one")
    model = "\n".join(normalized_lines)
    if len(model.encode("utf-8")) > 16_384:
        raise ValueError("oversized small-area flow-compensation model")
    return model


def adaptive_pressure_advance_model(value: Any, enabled: bool) -> str:
    candidate = str(scalar(value, "0,0,0\n0,0,0")).replace("\\n", "\n").strip()
    if len(candidate.encode("utf-8")) > MAX_ADAPTIVE_PRESSURE_ADVANCE_MODEL_BYTES:
        raise ValueError("oversized adaptive pressure-advance model")
    if not enabled:
        return candidate

    lines = [line.strip() for line in candidate.splitlines() if line.strip()]
    if len(lines) not in range(2, 257):
        raise ValueError("invalid adaptive pressure-advance point count")
    flow_by_acceleration: dict[float, tuple[int, float]] = {}
    normalized_lines: list[str] = []
    for line in lines:
        coordinates = [coordinate.strip() for coordinate in line.split(",")]
        if len(coordinates) != 3:
            raise ValueError("invalid adaptive pressure-advance point")
        try:
            pressure_advance, flow, acceleration = map(float, coordinates)
        except ValueError as error:
            raise ValueError("invalid adaptive pressure-advance number") from error
        if (
            not all(math.isfinite(value) for value in (pressure_advance, flow, acceleration))
            or pressure_advance < 0
            or pressure_advance > 2
            or flow < 0.001
            or flow > 1_000
            or acceleration < 1
            or acceleration > 1_000_000
        ):
            raise ValueError("unsafe adaptive pressure-advance model")
        count, previous_flow = flow_by_acceleration.get(acceleration, (0, -1.0))
        if flow <= previous_flow:
            raise ValueError("adaptive pressure-advance flow values must increase")
        flow_by_acceleration[acceleration] = (count + 1, flow)
        normalized_lines.append(",".join(coordinates))
    if any(count < 2 for count, _ in flow_by_acceleration.values()):
        raise ValueError("adaptive pressure-advance acceleration needs two flow points")
    return "\n".join(normalized_lines)


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


def default_profile_names(value: Any) -> list[str]:
    """Normalize Orca's array and legacy semicolon-delimited preset references."""
    normalized = [
        name.strip()
        for item in values(value)
        for name in item.split(";")
        if name.strip()
    ]
    if len(normalized) > MAX_FILAMENT_SLOTS:
        raise ValueError("too many default profile references")
    if any(len(name) > 512 or len(name.encode("utf-8")) > 2_048 for name in normalized):
        raise ValueError("unsafe default profile reference")
    return normalized


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


def optional_polygon(value: Any, label: str) -> list[float]:
    """Preserve an optional machine-space Orca polygon with bounded coordinates."""
    if not values(value):
        return []
    xs, ys = point_values(value)
    flattened = [coordinate for point in zip(xs, ys) for coordinate in point]
    if len(flattened) < 6 or len(flattened) > 512 or len(flattened) % 2:
        raise ValueError(f"invalid {label} point count")
    if any(not math.isfinite(coordinate) or abs(coordinate) > 3_000 for coordinate in flattened):
        raise ValueError(f"unsafe {label} coordinate")
    signed_double_area = sum(
        flattened[index] * flattened[(index + 3) % len(flattened)] -
        flattened[(index + 2) % len(flattened)] * flattened[index + 1]
        for index in range(0, len(flattened), 2)
    )
    if abs(signed_double_area) < 2.0:
        raise ValueError(f"degenerate {label}")
    return flattened


def coordinate_pair(value: Any, default_x: float, default_y: float) -> tuple[float, float]:
    """Parse Orca's scalar, comma/x-delimited, or two-element point representation."""
    candidates = values(value)
    if not candidates:
        return default_x, default_y
    if len(candidates) > 2:
        raise ValueError("invalid coordinate pair")
    if len(candidates) == 2:
        return number(candidates[0], default_x), number(candidates[1], default_y)
    coordinates = [item.strip() for item in re.split(r"[,x]", candidates[0], maxsplit=1)]
    if len(coordinates) == 2:
        return number(coordinates[0], default_x), number(coordinates[1], default_y)
    parsed = number(coordinates[0], default_x)
    return parsed, parsed


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


def profile_entry_kind(path: Path, raw: dict[str, Any]) -> str | None:
    kind = raw.get("type")
    if kind in {"machine", "filament", "process"}:
        return str(kind)
    # Orca occasionally keeps a non-instantiable filament base without a type.
    # It is still an inheritance node, but never a selectable catalog record.
    if (
        kind is None
        and raw.get("instantiation") is not None
        and not boolean(raw.get("instantiation"))
        and path.parent.name == "filament"
    ):
        return "filament"
    return None


class Resolver:
    def __init__(self, profile_root: Path) -> None:
        self.profile_root = profile_root
        self.entries: list[tuple[Path, str, dict[str, Any]]] = []
        self.index: dict[tuple[str, str], list[int]] = defaultdict(list)
        self.cache: dict[int, dict[str, Any]] = {}
        for path in sorted(profile_root.rglob("*.json")):
            raw = json.loads(path.read_text(encoding="utf-8"))
            kind = profile_entry_kind(path, raw)
            name = raw.get("name")
            if kind is None or not name:
                continue
            if raw.get("type") is None:
                raw = raw | {"type": kind}
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
    head_wrap_detect_zone = optional_polygon(
        raw.get("head_wrap_detect_zone"),
        "head-wrap detection zone",
    )
    height = number(raw.get("printable_height"), 0)
    nozzle = number(raw.get("nozzle_diameter"), 0)
    nozzle_height = number(raw.get("nozzle_height"), 2.5)
    nozzle_volume = number(raw.get("nozzle_volume"), 0)
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
    bed_mesh_min_x, bed_mesh_min_y = coordinate_pair(raw.get("bed_mesh_min"), -99_999, -99_999)
    bed_mesh_max_x, bed_mesh_max_y = coordinate_pair(raw.get("bed_mesh_max"), 99_999, 99_999)
    bed_mesh_probe_distance_x, bed_mesh_probe_distance_y = coordinate_pair(
        raw.get("bed_mesh_probe_distance"), 50, 50
    )
    physical_extruder_count = len(values(raw.get("nozzle_diameter")))
    supports_multi_material = str(
        scalar(raw.get("single_extruder_multi_material"), "0")
    ).lower() in {"1", "true"}
    extruder_count = (
        MAX_FILAMENT_SLOTS
        if supports_multi_material and physical_extruder_count == 1
        else physical_extruder_count
    )
    flavor = str(scalar(raw.get("gcode_flavor"), "")).lower()
    printer_structure = str(scalar(raw.get("printer_structure"), "undefine")).strip().lower()
    best_object_position_x, best_object_position_y = coordinate_pair(
        raw.get("best_object_pos"),
        0.5,
        0.5,
    )
    if not (
        50 <= height <= 1_500 and
        0.1 <= nozzle <= 2.0 and
        0.1 <= nozzle_height <= 100 and
        1 <= physical_extruder_count <= MAX_FILAMENT_SLOTS
    ):
        raise ValueError("unsafe printer dimensions")
    if flavor not in SUPPORTED_GCODE_FLAVORS:
        raise ValueError(f"unsupported G-code flavor: {flavor}")
    if printer_structure not in SUPPORTED_PRINTER_STRUCTURES:
        raise ValueError(f"unsupported printer structure: {printer_structure}")
    if not (
        0 <= best_object_position_x <= 1 and
        0 <= best_object_position_y <= 1
    ):
        raise ValueError("unsafe best object position")

    def motion_pair(key: str, default: float) -> tuple[float, float]:
        parsed = number_values(raw.get(key), default)
        normal = parsed[0] if parsed and parsed[0] > 0 else default
        silent = parsed[1] if len(parsed) > 1 and parsed[1] > 0 else normal
        return normal, silent

    motion_defaults = (
        ("machine_max_speed_x", 300),
        ("machine_max_speed_y", 300),
        ("machine_max_speed_z", 15),
        ("machine_max_speed_e", 25),
        ("machine_max_acceleration_x", 3_000),
        ("machine_max_acceleration_y", 3_000),
        ("machine_max_acceleration_z", 200),
        ("machine_max_acceleration_e", 2_000),
        ("machine_max_acceleration_extruding", 3_000),
        ("machine_max_acceleration_retracting", 2_000),
        ("machine_max_acceleration_travel", 3_000),
        ("machine_max_jerk_x", 8),
        ("machine_max_jerk_y", 8),
        ("machine_max_jerk_z", 0.4),
        ("machine_max_jerk_e", 5),
    )
    motion_pairs = {
        key: motion_pair(key, default)
        for key, default in motion_defaults
    }
    minimum_extruding_rates = motion_pair("machine_min_extruding_rate", 0)
    minimum_travel_rates = motion_pair("machine_min_travel_rate", 0)

    def motion(key: str) -> float:
        return motion_pairs[key][0]

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
        "headWrapDetectZone": head_wrap_detect_zone,
        "maxPrintHeight": height,
        "nozzleDiameter": nozzle,
        "nozzleMaterial": nozzle_material(raw.get("nozzle_type")),
        "nozzleHrc": integer(raw.get("nozzle_hrc"), 0),
        "nozzleHeight": nozzle_height,
        "nozzleVolume": nozzle_volume,
        "minLayerHeight": min_layer_height,
        "maxLayerHeight": max_layer_height,
        "pelletModded": boolean(raw.get("pellet_modded_printer")),
        "singleExtruderMultiMaterial": supports_multi_material,
        "coolingTubeRetraction": number(raw.get("cooling_tube_retraction"), 91.5),
        "coolingTubeLength": number(raw.get("cooling_tube_length"), 5),
        "parkingPosRetraction": number(raw.get("parking_pos_retraction"), 92),
        "extraLoadingMove": number(raw.get("extra_loading_move"), -2),
        "enableFilamentRamming": boolean(raw.get("enable_filament_ramming"), True),
        "rammingLineWidthRatio": number(raw.get("ramming_line_width_ratio"), 2),
        "changePressureWhenWiping": boolean(
            raw.get("enable_change_pressure_when_wiping"), True
        ),
        "rammingPressureAdvance": number(raw.get("ramming_pressure_advance_value"), 0),
        "purgeInPrimeTower": boolean(raw.get("purge_in_prime_tower"), True),
        "highCurrentOnFilamentSwap": boolean(raw.get("high_current_on_filament_swap")),
        "extruderCount": extruder_count,
        "auxiliaryFan": boolean(raw.get("auxiliary_fan")),
        "fanSpeedupTime": number(raw.get("fan_speedup_time"), 0),
        "fanSpeedupOverhangs": boolean(raw.get("fan_speedup_overhangs"), True),
        "fanKickstart": number(raw.get("fan_kickstart"), 0),
        "supportsChamberTemperatureControl": boolean(
            raw.get("support_chamber_temp_control")
        ),
        "supportsAirFiltration": boolean(raw.get("support_air_filtration")),
        "scanFirstLayer": boolean(raw.get("scan_first_layer")),
        "bedMeshMinX": bed_mesh_min_x,
        "bedMeshMinY": bed_mesh_min_y,
        "bedMeshMaxX": bed_mesh_max_x,
        "bedMeshMaxY": bed_mesh_max_y,
        "bedMeshProbeDistanceX": bed_mesh_probe_distance_x,
        "bedMeshProbeDistanceY": bed_mesh_probe_distance_y,
        "adaptiveBedMeshMargin": number(raw.get("adaptive_bed_mesh_margin"), 0),
        "gcodeThumbnails": thumbnail_definitions(
            raw.get("thumbnails"),
            raw.get("thumbnails_format", "PNG"),
        ),
        "machineStartGcode": str(raw.get("machine_start_gcode", "")),
        "machineEndGcode": str(raw.get("machine_end_gcode", "")),
        "machinePauseGcode": str(raw.get("machine_pause_gcode", "")),
        "templateCustomGcode": str(raw.get("template_custom_gcode", "")),
        "timeLapseGcode": str(raw.get("time_lapse_gcode", "")),
        "beforeLayerChangeGcode": str(raw.get("before_layer_change_gcode", "")),
        "layerChangeGcode": str(raw.get("layer_change_gcode", "")),
        "changeFilamentGcode": first_non_blank(
            raw,
            ["change_filament_gcode", "toolchange_gcode"],
        ),
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
        "printerStructure": printer_structure,
        "bestObjectPositionX": best_object_position_x,
        "bestObjectPositionY": best_object_position_y,
        "maxSpeedX": motion("machine_max_speed_x"),
        "maxSpeedY": motion("machine_max_speed_y"),
        "maxSpeedZ": motion("machine_max_speed_z"),
        "maxSpeedE": motion("machine_max_speed_e"),
        "maxAccelerationX": motion("machine_max_acceleration_x"),
        "maxAccelerationY": motion("machine_max_acceleration_y"),
        "maxAccelerationZ": motion("machine_max_acceleration_z"),
        "maxAccelerationE": motion("machine_max_acceleration_e"),
        "maxAccelerationExtruding": motion("machine_max_acceleration_extruding"),
        "maxAccelerationRetracting": motion("machine_max_acceleration_retracting"),
        "maxAccelerationTravel": motion("machine_max_acceleration_travel"),
        "maxJerkX": motion("machine_max_jerk_x"),
        "maxJerkY": motion("machine_max_jerk_y"),
        "maxJerkZ": motion("machine_max_jerk_z"),
        "maxJerkE": motion("machine_max_jerk_e"),
        "silentMode": boolean(raw.get("silent_mode")),
        "silentMotionLimits": [motion_pairs[key][1] for key, _ in motion_defaults],
        "maxJunctionDeviation": number(raw.get("machine_max_junction_deviation"), 0),
        # Keep the binary contract stable even when every resolved source value
        # happens to be an integer. Android reads these four fields as floats.
        "minimumExtrudingRate": float(minimum_extruding_rates[0]),
        "minimumTravelRate": float(minimum_travel_rates[0]),
        "silentMinimumExtrudingRate": float(minimum_extruding_rates[1]),
        "silentMinimumTravelRate": float(minimum_travel_rates[1]),
        "resonanceAvoidance": boolean(raw.get("resonance_avoidance")),
        "minResonanceAvoidanceSpeed": number(raw.get("min_resonance_avoidance_speed"), 70),
        "maxResonanceAvoidanceSpeed": number(raw.get("max_resonance_avoidance_speed"), 120),
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
        "supportMultiBedTypes": boolean(raw.get("support_multi_bed_types")),
        "defaultBuildPlate": build_plate_type(raw.get("default_bed_type")),
        "defaultPrintProfile": next(iter(default_profile_names(raw.get("default_print_profile"))), ""),
        "defaultFilamentProfiles": default_profile_names(raw.get("default_filament_profile")),
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
        and 0 <= profile["nozzleHrc"] <= 500
        and 0 <= profile["nozzleVolume"] <= 1_000
        and 0.1 <= profile["extruderClearanceRadius"] <= 1_000
        and 0.1 <= profile["extruderClearanceHeightToRod"] <= 1_500
        and 0.1 <= profile["extruderClearanceHeightToLid"] <= 1_500
        and len(profile["defaultFilamentProfiles"]) <= MAX_FILAMENT_SLOTS
        and 0 <= profile["retractLength"] <= 100
        and 0 <= profile["retractSpeed"] <= 500
        and 0 <= profile["deretractSpeed"] <= 500
        and 0 <= profile["retractionMinimumTravel"] <= 1_000
        and 0 <= profile["wipeDistance"] <= 100
        and 0 <= profile["retractBeforeWipe"] <= 100
        and -100 <= profile["retractRestartExtra"] <= 100
        and len(profile["printingByObjectGcode"].encode("utf-8")) <= 262_144
        and len(profile["machinePauseGcode"].encode("utf-8")) <= 262_144
        and len(profile["templateCustomGcode"].encode("utf-8")) <= 262_144
        and len(profile["timeLapseGcode"].encode("utf-8")) <= 262_144
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
        and 0 <= profile["fanSpeedupTime"] <= 60
        and 0 <= profile["fanKickstart"] <= 60
        and 0.1 <= profile["rammingLineWidthRatio"] <= 20
        and 0 <= profile["rammingPressureAdvance"] <= 2
        and all(-100_000 <= profile[key] <= 100_000 for key in [
            "bedMeshMinX", "bedMeshMinY", "bedMeshMaxX", "bedMeshMaxY"
        ])
        and profile["bedMeshMinX"] <= profile["bedMeshMaxX"]
        and profile["bedMeshMinY"] <= profile["bedMeshMaxY"]
        and all(0 <= profile[key] <= 100_000 for key in [
            "bedMeshProbeDistanceX", "bedMeshProbeDistanceY", "adaptiveBedMeshMargin"
        ])
        and 0 <= profile["maxJunctionDeviation"] <= 10
        and all(
            0 <= profile[key] <= 2_000
            for key in [
                "minimumExtrudingRate",
                "minimumTravelRate",
                "silentMinimumExtrudingRate",
                "silentMinimumTravelRate",
            ]
        )
        and 0 <= profile["minResonanceAvoidanceSpeed"] <= profile["maxResonanceAvoidanceSpeed"] <= 2_000
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


def timelapse_type(value: Any) -> str:
    return {
        "0": "traditional",
        "1": "smooth",
    }.get(str(scalar(value, "0")).strip(), "traditional")


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


def wall_direction(value: Any) -> str:
    candidate = str(scalar(value, "auto")).strip().lower()
    return {
        "auto": "auto",
        "cw": "cw",
        "clockwise": "cw",
        "ccw": "ccw",
        "counter clockwise": "ccw",
        "counter-clockwise": "ccw",
        "counterclockwise": "ccw",
    }.get(candidate, "auto")


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


def extra_solid_infills(value: Any, default: str = "") -> str:
    candidate = "".join(str(scalar(value, default)).split())
    if not candidate:
        return ""
    if len(candidate) > 256:
        return default
    tokens = candidate.split(",")
    if not (1 <= len(tokens) <= 64):
        return default
    multiple = len(tokens) > 1
    normalized: list[str] = []
    for token in tokens:
        if not token or token.count("#") > 1:
            return default
        base_text, separator, count_text = token.partition("#")
        if not base_text.isdigit() or (separator and count_text and not count_text.isdigit()):
            return default
        base = int(base_text)
        count = int(count_text) if count_text else 1
        if not (1 <= base <= 1_000_000 and 1 <= count <= 10_000):
            return default
        if not multiple and count > base:
            return default
        normalized.append(str(base) if count == 1 else f"{base}#{count}")
    return ",".join(normalized)


def build_filament(brand: str, raw: dict[str, Any]) -> dict[str, Any]:
    name = str(raw["name"])
    vendor = filament_vendor(raw.get("filament_vendor"), brand)
    filament_type = str(scalar(raw.get("filament_type"), "")).strip()
    nozzle = integer(raw.get("nozzle_temperature"), 0)
    first_nozzle = integer(
        first_present(
            raw,
            ["nozzle_temperature_initial_layer", "first_layer_temperature"],
            nozzle,
        ),
        nozzle,
    )
    bed_value = first_present(
        raw,
        [
            "hot_plate_temp",
            "textured_plate_temp",
            "bed_temperature",
            "hot_plate_temp_initial_layer",
        ],
        0,
    )
    first_bed_value = first_present(
        raw,
        [
            "hot_plate_temp_initial_layer",
            "textured_plate_temp_initial_layer",
            "bed_temperature_initial_layer",
        ],
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
    diameter = number(raw.get("filament_diameter"), 1.75)
    pellet_flow_coefficient = number(
        raw.get("pellet_flow_coefficient"),
        4 / (math.pi * diameter * diameter),
    )
    adaptive_pa_enabled = boolean(raw.get("adaptive_pressure_advance"))
    profile = {
        "id": stable_id("filament", brand, name),
        "name": name,
        "brand": vendor,
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
        "diameter": diameter,
        "pelletFlowCoefficient": pellet_flow_coefficient,
        "density": number(raw.get("filament_density"), 1.24),
        "costPerKilogram": number(raw.get("filament_cost"), 0),
        "shrinkageXyPercent": number(raw.get("filament_shrink"), 100),
        "shrinkageZPercent": number(raw.get("filament_shrinkage_compensation_z"), 100),
        "soluble": boolean(raw.get("filament_soluble")),
        "supportMaterial": boolean(raw.get("filament_is_support")),
        "defaultColor": filament_color(raw.get("default_filament_colour")),
        "minimalPurgeOnWipeTower": number(raw.get("filament_minimal_purge_on_wipe_tower"), 15),
        "towerInterfacePreExtrusionDistance": number(
            raw.get("filament_tower_interface_pre_extrusion_dist"), 10
        ),
        "towerInterfacePreExtrusionLength": number(
            raw.get("filament_tower_interface_pre_extrusion_length"), 0
        ),
        "towerIroningArea": number(raw.get("filament_tower_ironing_area"), 4),
        "towerInterfacePurgeLength": number(
            raw.get("filament_tower_interface_purge_volume"), 20
        ),
        "towerInterfacePrintTemperature": integer(
            raw.get("filament_tower_interface_print_temp"), -1
        ),
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
        "ironingFanSpeed": integer(raw.get("ironing_fan_speed"), -1),
        "slowDownLayerTime": number(raw.get("slow_down_layer_time"), 8),
        "slowDownMinSpeed": number(raw.get("slow_down_min_speed"), 10),
        "closeFanFirstLayers": integer(raw.get("close_fan_the_first_x_layers"), 1),
        "fullFanSpeedLayer": integer(raw.get("full_fan_speed_layer"), 3),
        "pressureAdvanceEnabled": boolean(raw.get("enable_pressure_advance")),
        "pressureAdvance": number(raw.get("pressure_advance"), 0),
        "adaptivePressureAdvanceEnabled": adaptive_pa_enabled,
        "adaptivePressureAdvanceModel": adaptive_pressure_advance_model(
            raw.get("adaptive_pressure_advance_model"), adaptive_pa_enabled
        ),
        "adaptivePressureAdvanceOverhangs": boolean(
            raw.get("adaptive_pressure_advance_overhangs")
        ),
        "adaptivePressureAdvanceBridge": number(
            raw.get("adaptive_pressure_advance_bridges"), 0
        ),
        "requiredNozzleHrc": integer(raw.get("required_nozzle_HRC"), 0),
        "compatiblePrinters": values(raw.get("compatible_printers")),
        "compatiblePrints": values(raw.get("compatible_prints")),
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
        and 0.1 <= profile["maxVolumetricSpeed"] <= 300
        and 0.5 <= profile["diameter"] <= 4
        and 4 / (math.pi * 4 * 4)
        <= profile["pelletFlowCoefficient"]
        <= 4 / (math.pi * 0.5 * 0.5)
        and 0 <= profile["density"] <= 10
        and 0 <= profile["costPerKilogram"] <= 1_000_000
        and 10 <= profile["shrinkageXyPercent"] <= 200
        and 10 <= profile["shrinkageZPercent"] <= 200
        and NO_FILAMENT_COLOR <= profile["defaultColor"] <= 0xFFFFFF
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
        and 0 <= profile["towerInterfacePreExtrusionDistance"] <= 1_000
        and 0 <= profile["towerInterfacePreExtrusionLength"] <= 1_000
        and 0 <= profile["towerIroningArea"] <= 10_000
        and 0 <= profile["towerInterfacePurgeLength"] <= 1_000
        and -1 <= profile["towerInterfacePrintTemperature"] <= 500
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
        and -1 <= profile["ironingFanSpeed"] <= 100
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
        and 0 <= profile["pressureAdvance"] <= 10
        and 0 <= profile["requiredNozzleHrc"] <= 500
        and 0 <= profile["adaptivePressureAdvanceBridge"] <= 2
        and len(profile["adaptivePressureAdvanceModel"].encode("utf-8")) <=
            MAX_ADAPTIVE_PRESSURE_ADVANCE_MODEL_BYTES
        and (not profile["adaptivePressureAdvanceEnabled"] or profile["pressureAdvanceEnabled"])
    ):
        raise ValueError("unsafe filament limits")
    return profile


def build_process(
    brand: str,
    raw: dict[str, Any],
    printer_nozzles: dict[str, float],
    *,
    nozzle_override: float | None = None,
    compatible_override: list[str] | None = None,
) -> dict[str, Any]:
    name = str(raw["name"])
    compatible = (
        compatible_override
        if compatible_override is not None
        else values(raw.get("compatible_printers"))
    )
    nozzles = {printer_nozzles[item] for item in compatible if item in printer_nozzles}
    nozzle = nozzle_override if nozzle_override is not None else (
        nozzles.pop() if len(nozzles) == 1 else 0.4
    )
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
        raw.get("internal_bridge_speed", raw.get("ineternal_bridge_speed")), "150%"
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
    initial_layer_travel_acceleration, initial_layer_travel_acceleration_percent = float_or_percent(
        raw.get("initial_layer_travel_acceleration"), "100%"
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
        "supportFlowRatio": number(raw.get("support_flow_ratio"), 1),
        "supportInterfaceFlowRatio": number(raw.get("support_interface_flow_ratio"), 1),
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
        "firstLayerTravelAcceleration": initial_layer_travel_acceleration,
        "firstLayerTravelAccelerationPercent": initial_layer_travel_acceleration_percent,
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
        "extraSolidInfills": extra_solid_infills(raw.get("extra_solid_infills")),
        "smallAreaFlowCompensation": boolean(raw.get("small_area_infill_flow_compensation")),
        "smallAreaFlowCompensationModel": small_area_flow_compensation_model(
            raw.get("small_area_infill_flow_compensation_model")
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
        "wallDirection": wall_direction(
            raw.get("wall_direction", raw.get("wall_loop_direction"))
        ),
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
        "flushMultiplierOverrideEnabled": raw.get("flush_multiplier") not in (None, ""),
        "flushMultiplier": number(raw.get("flush_multiplier"), 0.3),
        "primeTowerPositionX": number(raw.get("wipe_tower_x"), 170),
        "primeTowerPositionY": number(raw.get("wipe_tower_y"), 140),
        "primeTowerBrimWidth": number(raw.get("prime_tower_brim_width"), 3),
        "primeTowerBrimChamfer": boolean(raw.get("prime_tower_brim_chamfer"), True),
        "primeTowerBrimChamferMaxWidth": number(
            raw.get("prime_tower_brim_chamfer_max_width"), 4
        ),
        "primeTowerFramework": boolean(raw.get("prime_tower_enable_framework")),
        "primeTowerSkipPoints": boolean(raw.get("prime_tower_skip_points"), True),
        "primeTowerFlatIroning": boolean(raw.get("prime_tower_flat_ironing")),
        "primeTowerInterfaceFeatures": boolean(raw.get("enable_tower_interface_features")),
        "primeTowerInterfaceCooldown": boolean(
            raw.get("enable_tower_interface_cooldown_during_tower")
        ),
        "primeTowerInfillGap": number(raw.get("prime_tower_infill_gap"), 150),
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
        "gcodeAddLineNumber": boolean(raw.get("gcode_add_line_number")),
        "gcodeLabelObjects": boolean(raw.get("gcode_label_objects"), True),
        "excludeObject": boolean(raw.get("exclude_object")),
        "gcodeComments": boolean(raw.get("gcode_comments")),
        "timelapseType": timelapse_type(raw.get("timelapse_type")),
        "initialLayerTravelSpeed": initial_layer_travel_speed,
        "initialLayerTravelSpeedPercent": initial_layer_travel_speed_percent,
        "slowDownLayers": integer(raw.get("slow_down_layers"), 0),
        "accelToDecelEnabled": boolean(raw.get("accel_to_decel_enable"), True),
        "accelToDecelFactor": number(raw.get("accel_to_decel_factor"), 50),
        "filenameFormat": filename_format(raw.get("filename_format")),
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
            0 <= profile[key] <= 2
            for key in ["supportFlowRatio", "supportInterfaceFlowRatio"]
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
            for key in [
                "firstLayerTravelAcceleration",
                "bridgeAcceleration",
                "sparseInfillAcceleration",
                "internalSolidInfillAcceleration",
            ]
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
        and 0 <= profile["flushMultiplier"] <= 10
        and 0 <= profile["primeTowerBrimWidth"] <= 100
        and 0 <= profile["primeTowerBrimChamferMaxWidth"] <= 100
        and 100 <= profile["primeTowerInfillGap"] <= 1_000
        and -500 <= profile["standbyTemperatureDelta"] <= 500
        and 0 <= profile["internalBridgeAngle"] <= 360
        and 0 <= profile["infillWallOverlap"] <= 100
        and 0 <= profile["topBottomInfillWallOverlap"] <= 100
        and 0 <= profile["infillCombinationMaxLayerHeight"] <= (
            1_000 if profile["infillCombinationMaxLayerHeightPercent"] else 10
        )
        and all(0 <= profile[key] <= 360 for key in ["infillDirection", "solidInfillDirection"])
        and len(profile["smallAreaFlowCompensationModel"].encode("utf-8")) <= 16_384
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
        and profile["timelapseType"] in {"traditional", "smooth"}
        and (profile["timelapseType"] != "smooth" or profile["printSequence"] == "by layer")
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


def build_process_variants(
    brand: str,
    raw: dict[str, Any],
    printer_nozzles: dict[str, float],
) -> list[dict[str, Any]]:
    compatible = values(raw.get("compatible_printers"))
    compatible_by_nozzle: dict[float, list[str]] = defaultdict(list)
    for printer_name in compatible:
        nozzle = printer_nozzles.get(printer_name)
        if nozzle is not None:
            compatible_by_nozzle[nozzle].append(printer_name)

    if len(compatible_by_nozzle) <= 1:
        return [build_process(brand, raw, printer_nozzles)]

    sorted_nozzles = sorted(compatible_by_nozzle)
    base_nozzle = next(
        (nozzle for nozzle in sorted_nozzles if math.isclose(nozzle, 0.4, abs_tol=0.0001)),
        sorted_nozzles[0],
    )
    profiles: list[dict[str, Any]] = []
    errors: list[ValueError] = []
    for nozzle in sorted_nozzles:
        try:
            profile = build_process(
                brand,
                raw,
                printer_nozzles,
                nozzle_override=nozzle,
                compatible_override=compatible_by_nozzle[nozzle],
            )
        except ValueError as error:
            errors.append(error)
            continue
        if nozzle != base_nozzle:
            profile["id"] = stable_id(
                "process",
                brand,
                f"{raw['name']} @ nozzle {nozzle:g}",
            )
        profiles.append(profile)
    if not profiles:
        raise errors[0]
    return profiles


def canonical_profiles(
    candidates: list[tuple[Path, dict[str, Any]]],
    profile_kind: str,
) -> list[dict[str, Any]]:
    grouped: dict[str, list[tuple[Path, dict[str, Any]]]] = defaultdict(list)
    for source, profile in candidates:
        grouped[profile["id"]].append((source, profile))

    canonical: list[dict[str, Any]] = []
    for profile_id, entries in grouped.items():
        if len(entries) == 1:
            canonical.append(entries[0][1])
            continue

        def priority(entry: tuple[Path, dict[str, Any]]) -> tuple[bool, bool]:
            source, profile = entry
            stem = source.stem.casefold()
            archival_copy = stem.endswith(" copy") or stem.endswith("_old")
            unscoped = not profile.get("compatiblePrinters", [])
            return archival_copy, unscoped

        best_priority = min(priority(entry) for entry in entries)
        preferred = [entry for entry in entries if priority(entry) == best_priority]
        baseline = preferred[0][1]
        if any(profile != baseline for _, profile in preferred[1:]):
            raise ValueError(f"conflicting {profile_kind} stable id: {profile_id}")
        canonical.append(baseline)
    return canonical


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
    if isinstance(value, list) and not value:
        return BINARY_EMPTY_LIST
    if isinstance(value, list) and all(isinstance(item, str) for item in value):
        return BINARY_STRING_LIST
    if isinstance(value, list) and all(isinstance(item, (int, float)) and not isinstance(item, bool) for item in value):
        return BINARY_FLOAT_LIST
    raise ValueError(f"unsupported binary catalog value: {type(value).__name__}")


def infer_binary_kind(records: list[dict[str, Any]], field: str) -> int:
    kinds = {binary_kind(record[field]) for record in records}
    empty_list = BINARY_EMPTY_LIST in kinds
    kinds.discard(BINARY_EMPTY_LIST)
    if empty_list and not kinds:
        return BINARY_STRING_LIST
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
            empty_list_match = actual_kind == BINARY_EMPTY_LIST and kind in {
                BINARY_STRING_LIST,
                BINARY_FLOAT_LIST,
            }
            if actual_kind != kind and not nullable_match and not empty_list_match and not (
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

    resolved_entries: list[tuple[Path, str, dict[str, Any]]] = []
    for entry_id, (source, brand, raw) in enumerate(resolver.entries):
        if not boolean(raw.get("instantiation")):
            continue
        try:
            resolved_entries.append((source, brand, resolver.resolve(entry_id)))
        except ValueError as error:
            rejected[f"inheritance: {error}"] += 1

    printer_candidates: list[tuple[Path, dict[str, Any]]] = []
    for source, brand, raw in resolved_entries:
        if raw.get("type") != "machine":
            continue
        try:
            printer_candidates.append((source, build_printer(brand, raw)))
        except ValueError as error:
            rejected[f"printer: {error}"] += 1
    printers = canonical_profiles(printer_candidates, "printer")
    printers.sort(key=lambda profile: (profile["brand"].casefold(), profile["name"].casefold()))
    printer_nozzles = {profile["name"]: profile["nozzleDiameter"] for profile in printers}

    filament_candidates: list[tuple[Path, dict[str, Any]]] = []
    process_candidates: list[tuple[Path, dict[str, Any]]] = []
    for source, brand, raw in resolved_entries:
        try:
            if raw.get("type") == "filament":
                filament_candidates.append((source, build_filament(brand, raw)))
            elif raw.get("type") == "process":
                process_candidates.extend(
                    (source, profile)
                    for profile in build_process_variants(brand, raw, printer_nozzles)
                )
        except ValueError as error:
            rejected[f"{raw.get('type')}: {error}"] += 1
    filaments = canonical_profiles(filament_candidates, "filament")
    processes = canonical_profiles(process_candidates, "process")
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
