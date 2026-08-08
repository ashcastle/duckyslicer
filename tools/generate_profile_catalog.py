#!/usr/bin/env python3
"""Normalize the pinned OrcaSlicer profile tree into a compact Android catalog."""

from __future__ import annotations

import hashlib
import json
import math
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 10
SUPPORTED_GCODE_FLAVORS = {"marlin", "marlin2", "klipper"}
INFILL_PATTERNS = {
    "monotonic", "monotonicline", "rectilinear", "alignedrectilinear",
    "zigzag", "crosszag", "lockedzag", "line", "grid", "triangles",
    "tri-hexagon", "cubic", "adaptivecubic", "quartercubic", "supportcubic",
    "lightning", "honeycomb", "3dhoneycomb", "lateral-honeycomb",
    "lateral-lattice", "crosshatch", "tpmsd", "tpmsfk", "gyroid",
    "concentric", "hilbertcurve", "archimedeanchords", "octagramspiral",
}


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


def values(value: Any) -> list[str]:
    if value is None:
        return []
    if not isinstance(value, list):
        value = [value]
    return [str(item) for item in value if str(item).strip()]


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


def printable_size(area: Any) -> tuple[float, float]:
    points: list[tuple[float, float]] = []
    for item in values(area):
        try:
            x_text, y_text = item.lower().split("x", 1)
            points.append((float(x_text), float(y_text)))
        except ValueError:
            continue
    if len(points) < 3:
        raise ValueError("invalid printable area")
    width = max(point[0] for point in points) - min(point[0] for point in points)
    depth = max(point[1] for point in points) - min(point[1] for point in points)
    if not (50 <= width <= 1_500 and 50 <= depth <= 1_500):
        raise ValueError("unsafe printable area")
    return width, depth


def build_printer(brand: str, raw: dict[str, Any]) -> dict[str, Any]:
    name = str(raw["name"])
    width, depth = printable_size(raw.get("printable_area"))
    height = number(raw.get("printable_height"), 0)
    nozzle = number(raw.get("nozzle_diameter"), 0)
    flavor = str(scalar(raw.get("gcode_flavor"), "")).lower()
    if not (50 <= height <= 1_500 and 0.1 <= nozzle <= 2.0):
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
        "maxPrintHeight": height,
        "nozzleDiameter": nozzle,
        "machineStartGcode": str(raw.get("machine_start_gcode", "")),
        "machineEndGcode": str(raw.get("machine_end_gcode", "")),
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
    if not filament_type or not (150 <= nozzle <= 400 and 0 <= bed <= 160):
        raise ValueError("unsafe filament temperatures")
    profile = {
        "id": stable_id("filament", brand, name),
        "name": name,
        "brand": brand,
        "nativeName": filament_type,
        "nozzleTemp": nozzle,
        "firstLayerNozzleTemp": first_nozzle,
        "bedTemp": bed,
        "firstLayerBedTemp": first_bed,
        "flowRatio": number(raw.get("filament_flow_ratio"), 1.0),
        "maxVolumetricSpeed": number(raw.get("filament_max_volumetric_speed"), 12),
        "retractLength": number(raw.get("retraction_length"), 0.8),
        "retractSpeed": number(raw.get("retraction_speed"), 45),
        "fanMinSpeed": integer(raw.get("fan_min_speed"), 30),
        "fanMaxSpeed": integer(raw.get("fan_max_speed"), 100),
        "overhangFanSpeed": integer(raw.get("overhang_fan_speed"), 100),
        "slowDownLayerTime": number(raw.get("slow_down_layer_time"), 8),
        "slowDownMinSpeed": number(raw.get("slow_down_min_speed"), 10),
        "closeFanFirstLayers": integer(raw.get("close_fan_the_first_x_layers"), 1),
        "fullFanSpeedLayer": integer(raw.get("full_fan_speed_layer"), 3),
        "pressureAdvanceEnabled": boolean(raw.get("enable_pressure_advance")),
        "pressureAdvance": number(raw.get("pressure_advance"), 0),
        "compatiblePrinters": values(raw.get("compatible_printers")),
    }
    if not (
        0.5 <= profile["flowRatio"] <= 1.5
        and 0.1 <= profile["maxVolumetricSpeed"] <= 100
        and all(0 <= profile[key] <= 100 for key in ["fanMinSpeed", "fanMaxSpeed", "overhangFanSpeed"])
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
    support_type = str(raw.get("support_type", "normal")).lower()
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
    max_travel_detour_distance, max_travel_detour_distance_percent = float_or_percent(
        raw.get("max_travel_detour_distance"), 0
    )
    small_perimeter_speed, small_perimeter_speed_percent = float_or_percent(
        raw.get("small_perimeter_speed"), "50%"
    )
    seam_gap, seam_gap_percent = float_or_percent(raw.get("seam_gap"), "10%")
    wipe_speed, wipe_speed_percent = float_or_percent(raw.get("wipe_speed"), "80%")
    legacy_wall_order = str(scalar(raw.get("wall_infill_order"), ""))
    resolved_wall_order = raw.get("wall_sequence", legacy_wall_order)
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
        "brimWidth": 0 if raw.get("brim_type") == "no_brim" else number(raw.get("brim_width"), 0),
        "topSolidLayers": integer(raw.get("top_shell_layers"), 5),
        "bottomSolidLayers": integer(raw.get("bottom_shell_layers"), 4),
        "topShellThickness": number(raw.get("top_shell_thickness"), 0),
        "bottomShellThickness": number(raw.get("bottom_shell_thickness"), 0),
        "fillPattern": infill_pattern(raw.get("sparse_infill_pattern"), "gyroid"),
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
        "alignInfillDirectionToModel": boolean(raw.get("align_infill_direction_to_model")),
        "minimumSparseInfillArea": number(raw.get("minimum_sparse_infill_area"), 15),
        "infillAnchor": infill_anchor,
        "infillAnchorPercent": infill_anchor_percent,
        "infillAnchorMax": infill_anchor_max,
        "infillAnchorMaxPercent": infill_anchor_max_percent,
        "gapFillTarget": enum_value(
            raw.get("gap_fill_target"), {"everywhere", "topbottom", "nowhere"}, "nowhere"
        ),
        "filterOutGapFill": number(raw.get("filter_out_gap_fill"), 0),
        "reduceCrossingWall": boolean(raw.get("reduce_crossing_wall")),
        "maxTravelDetourDistance": max_travel_detour_distance,
        "maxTravelDetourDistancePercent": max_travel_detour_distance_percent,
        "reduceInfillRetraction": boolean(raw.get("reduce_infill_retraction")),
        "travelSpeed": number(raw.get("travel_speed"), 300),
        "firstLayerSpeed": first_layer_speed,
        "supportType": "tree" if "tree" in support_type else "normal",
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
        "supportStyle": enum_value(
            raw.get("support_style"),
            {"default", "grid", "snug", "organic", "tree_slim", "tree_strong", "tree_hybrid"},
            "default",
        ),
        "skirtLoops": integer(raw.get("skirt_loops"), 0),
        "skirtDistance": number(raw.get("skirt_distance"), 6),
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
        "seamPosition": enum_value(
            raw.get("seam_position"), {"nearest", "aligned", "aligned_back", "back", "random"}, "aligned"
        ),
        "staggeredInnerSeams": boolean(raw.get("staggered_inner_seams")),
        "seamGap": seam_gap,
        "seamGapPercent": seam_gap_percent,
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
        "ironingSpeed": number(raw.get("ironing_speed"), 20),
        "wallGenerator": wall_generator(raw.get("wall_generator")),
        "wallSequence": wall_sequence(resolved_wall_order),
        "wallDirection": enum_value(raw.get("wall_direction"), {"auto", "ccw", "cw"}, "auto"),
        "detectThinWalls": boolean(raw.get("detect_thin_wall")),
        "detectOverhangWalls": boolean(raw.get("detect_overhang_wall"), True),
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
        "compatiblePrinters": compatible,
    }
    if not (
        0 <= profile["fillDensity"] <= 1
        and 1 <= profile["printSpeed"] <= 2_000
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
        and 10 <= profile["bridgeDensity"] <= 100
        and 10 <= profile["internalBridgeDensity"] <= 100
        and 0 <= profile["bridgeAngle"] <= 360
        and 0 <= profile["internalBridgeAngle"] <= 360
        and 0 <= profile["infillWallOverlap"] <= 100
        and 0 <= profile["topBottomInfillWallOverlap"] <= 100
        and 0 <= profile["infillCombinationMaxLayerHeight"] <= (
            1_000 if profile["infillCombinationMaxLayerHeightPercent"] else 10
        )
        and all(0 <= profile[key] <= 360 for key in ["infillDirection", "solidInfillDirection"])
        and 0 <= profile["minimumSparseInfillArea"] <= 1_000_000
        and 0 <= profile["filterOutGapFill"] <= 1_000_000
        and 0 <= profile["maxTravelDetourDistance"] <= 1_000
        and 0 <= profile["smallPerimeterSpeed"] <= (
            1_000 if profile["smallPerimeterSpeedPercent"] else 2_000
        )
        and 0 <= profile["smallPerimeterThreshold"] <= 1_000_000
        and 0.001 <= profile["resolution"] <= 100
        and 0 <= profile["seamGap"] <= 1_000
        and 0 <= profile["minWidthTopSurface"] <= 1_500
        and 0 <= profile["overhangReverseThreshold"] <= 2_000
        and 0 <= profile["wipeSpeed"] <= (
            1_000 if profile["wipeSpeedPercent"] else 2_000
        )
        and all(
            0 <= profile[key] <= 1_000
            for key in ["infillAnchor", "infillAnchorMax"]
        )
        and all(abs(profile[key]) <= 2 for key in ["xyHoleCompensation", "xyContourCompensation"])
        and 0 <= profile["elephantFootCompensation"] <= 2
        and 1 <= profile["elephantFootCompensationLayers"] <= 100
        and 0 <= profile["maxBridgeLength"] <= 1_000_000
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
        and 0 <= profile["supportInterfaceTopLayers"] <= 20
        and -1 <= profile["supportInterfaceBottomLayers"] <= 20
        and 0 <= profile["supportInterfaceSpacing"] <= 20
        and 0 <= profile["supportBottomInterfaceSpacing"] <= 20
        and 0 <= profile["supportTopZDistance"] <= 20
        and 0 <= profile["supportBottomZDistance"] <= 20
        and 0 <= profile["supportObjectXYDistance"] <= 20
        and profile["fillPattern"] in INFILL_PATTERNS
        and profile["topSurfacePattern"] in INFILL_PATTERNS
        and profile["bottomSurfacePattern"] in INFILL_PATTERNS
        and profile["internalSolidInfillPattern"] in INFILL_PATTERNS
        and profile["ironingPattern"] in INFILL_PATTERNS
        and 0 <= profile["ironingFlow"] <= 100
        and 0 <= profile["ironingSpacing"] <= 1
        and 1 <= profile["ironingSpeed"] <= 2_000
    ):
        raise ValueError("unsafe process limits")
    return profile


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
    temporary = output.with_suffix(output.suffix + ".tmp")
    temporary.write_text(json.dumps(catalog, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    temporary.replace(output)
    print(
        f"Generated {output}: {len(printers)} printers, {len(filaments)} filaments, "
        f"{len(processes)} processes, {sum(rejected.values())} rejected"
    )


if __name__ == "__main__":
    main()
