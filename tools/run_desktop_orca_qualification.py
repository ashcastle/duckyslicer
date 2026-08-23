#!/usr/bin/env python3
"""Compare Android corpus output with the pinned desktop Orca engine."""

from __future__ import annotations

import argparse
import contextlib
import hashlib
import json
import math
import platform
import shutil
import subprocess
import sys
from collections.abc import Iterator, Mapping, Sequence
from pathlib import Path

try:
    from tools.qualification_corpus import CORPUS_ROOT, MANIFEST, load_manifest, validate
    from tools.run_physical_qualification import RunnerError, qualification_source_commit
except ModuleNotFoundError:  # Direct `python tools/run_desktop_orca_qualification.py` execution.
    from qualification_corpus import CORPUS_ROOT, MANIFEST, load_manifest, validate
    from run_physical_qualification import RunnerError, qualification_source_commit


ROOT = Path(__file__).resolve().parent.parent
ORCA_ROOT = ROOT / "third_party/android-slicer-runtime/app/src/main/cpp/orcaslicer"
DEFAULT_BUILD_DIR = ORCA_ROOT / "build/qualification-desktop"
DEFAULT_BINARY = DEFAULT_BUILD_DIR / "src/Snapmaker_Orca"
DEFAULT_ANDROID_GCODE = ROOT / "build/qualification/android-gcode/simple-part.gcode"
DEFAULT_ANDROID_REPORT = ROOT / "build/qualification/android-report.json"
DEFAULT_OUTPUT = ROOT / "build/qualification/desktop-orca"
COMPAT_ROOT = ROOT / "qualification/desktop-orca-compat"
COMPAT_FILES = (COMPAT_ROOT / "modern-clang-enum.patch",)
DESKTOP_BUILD_MODE = "pinned-source-rebuilt"
ROLE_NAMES = (
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
)


class DesktopQualificationError(RuntimeError):
    """The desktop comparison could not produce trustworthy evidence."""


def captured(command: Sequence[str], *, cwd: Path, timeout: int = 1_800) -> str:
    try:
        result = subprocess.run(
            list(command),
            cwd=cwd,
            check=False,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise DesktopQualificationError(f"Could not run {' '.join(command)}: {error}") from error
    output = (result.stdout + result.stderr).strip()
    if result.returncode != 0:
        raise DesktopQualificationError(
            f"Command failed ({result.returncode}): {' '.join(command)}\n{output[-8_000:]}"
        )
    return output


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def compatibility_sha256() -> str:
    digest = hashlib.sha256()
    for path in COMPAT_FILES:
        if not path.is_file():
            raise DesktopQualificationError(f"Desktop compatibility input is missing: {path}")
        digest.update(path.relative_to(ROOT).as_posix().encode())
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def desktop_configure_command(build_dir: Path) -> tuple[str, ...]:
    if sys.platform != "darwin" or platform.machine() != "arm64":
        raise DesktopQualificationError(
            "The reproducible desktop Orca qualification build currently requires macOS ARM64",
        )
    dependencies = ORCA_ROOT / "deps/build/arm64/OrcaSlicer_dep/usr/local"
    if not dependencies.is_dir():
        raise DesktopQualificationError(
            "Pinned desktop Orca dependencies are missing; build the arm64 dependency bundle first",
        )
    compatibility_sha256()
    return (
        "cmake",
        "-S",
        str(ORCA_ROOT),
        "-B",
        str(build_dir),
        "-G",
        "Ninja",
        "-DCMAKE_BUILD_TYPE=Release",
        "-DSLIC3R_GUI=ON",
        "-DORCA_TOOLS=OFF",
        "-DSLIC3R_STATIC=ON",
        "-DSLIC3R_SENTRY=OFF",
        "-DCMAKE_POLICY_VERSION_MINIMUM=3.5",
        "-DCMAKE_OSX_ARCHITECTURES=arm64",
        "-DCMAKE_OSX_DEPLOYMENT_TARGET=12.0",
        f"-DCMAKE_PREFIX_PATH={dependencies}",
        "-DCMAKE_CXX_FLAGS=",
    )


@contextlib.contextmanager
def modern_clang_compatibility() -> Iterator[None]:
    patch = COMPAT_ROOT / "modern-clang-enum.patch"
    targets = (
        "src/slic3r/GUI/wxMediaCtrl2.h",
        "src/libslic3r/Support/TreeSupport3D.cpp",
        "src/slic3r/GUI/PartPlate.cpp",
    )
    clean = subprocess.run(
        ("git", "diff", "--quiet", "--", *targets),
        cwd=ORCA_ROOT,
        check=False,
    )
    if clean.returncode != 0:
        raise DesktopQualificationError(
            "Pinned desktop source has local changes in a compatibility target",
        )
    apply_command = (
        "git",
        "apply",
        "--unidiff-zero",
        "--whitespace=nowarn",
        str(patch),
    )
    captured(
        ("git", "apply", "--unidiff-zero", "--check", str(patch)),
        cwd=ORCA_ROOT,
        timeout=20,
    )
    captured(apply_command, cwd=ORCA_ROOT, timeout=20)
    try:
        yield
    finally:
        captured(
            ("git", "apply", "--unidiff-zero", "--check", "--reverse", str(patch)),
            cwd=ORCA_ROOT,
            timeout=20,
        )
        captured(
            ("git", "apply", "--unidiff-zero", "--reverse", str(patch)),
            cwd=ORCA_ROOT,
            timeout=20,
        )
        restored = subprocess.run(
            ("git", "diff", "--quiet", "--", *targets),
            cwd=ORCA_ROOT,
            check=False,
        )
        if restored.returncode != 0:
            raise DesktopQualificationError("Pinned desktop source was not restored after build")


def desktop_build_identity(build_dir: Path) -> dict[str, object]:
    configure = desktop_configure_command(build_dir)
    return {
        "schemaVersion": 1,
        "sourceRevision": captured(("git", "rev-parse", "HEAD"), cwd=ORCA_ROOT, timeout=20),
        "compatibilitySha256": compatibility_sha256(),
        "configureCommand": list(configure),
        "cmakeVersion": captured(("cmake", "--version"), cwd=ROOT, timeout=20),
        "compilerVersion": captured(("c++", "--version"), cwd=ROOT, timeout=20),
    }


def build_pinned_desktop_cli(build_dir: Path, binary: Path) -> tuple[str, str, bool]:
    identity = desktop_build_identity(build_dir)
    stamp = build_dir / ".ducky-qualification-build.json"
    if stamp.is_file() and binary.is_file():
        try:
            recorded = json.loads(stamp.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            recorded = None
        binary_digest = file_sha256(binary)
        if isinstance(recorded, dict) and recorded == {
            **identity,
            "binarySha256": binary_digest,
        }:
            return binary_digest, str(identity["compatibilitySha256"]), True

    captured(tuple(identity["configureCommand"]), cwd=ROOT)
    command = (
        "cmake",
        "--build",
        str(build_dir),
        "--config",
        "Release",
        "--target",
        "Snapmaker_Orca",
        "--parallel",
        "8",
    )
    with modern_clang_compatibility():
        captured(command, cwd=ROOT)
    if not binary.is_file():
        raise DesktopQualificationError(f"Pinned desktop Orca binary is missing after build: {binary}")
    binary_digest = file_sha256(binary)
    stamp.write_text(
        json.dumps({**identity, "binarySha256": binary_digest}, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return binary_digest, str(identity["compatibilitySha256"]), False


def parse_config_block(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    inside = False
    try:
        with path.open(encoding="utf-8", errors="strict") as source:
            for raw in source:
                line = raw.rstrip("\r\n")
                if line == "; CONFIG_BLOCK_START":
                    inside = True
                    continue
                if line == "; CONFIG_BLOCK_END":
                    if not inside:
                        raise DesktopQualificationError(f"Unexpected config block end in {path}")
                    return values
                if inside and line.startswith("; ") and " = " in line:
                    key, value = line[2:].split(" = ", 1)
                    if key:
                        values[key] = value
    except OSError as error:
        raise DesktopQualificationError(f"Could not read Android G-code {path}: {error}") from error
    raise DesktopQualificationError(f"Complete Orca config block is missing from {path}")


def role_name(label: str) -> str:
    normalized = label.strip().lower()
    if "outer wall" in normalized or "external perimeter" in normalized:
        return "outerWall"
    if "inner wall" in normalized or "perimeter" in normalized:
        return "innerWall"
    if "bridge" in normalized or "overhang" in normalized:
        return "bridge"
    if "support" in normalized:
        return "support"
    if any(value in normalized for value in ("skirt", "brim", "raft")):
        return "adhesion"
    if "top surface" in normalized:
        return "topSurface"
    if "bottom surface" in normalized:
        return "bottomSurface"
    if "solid" in normalized:
        return "internalSolid"
    if "infill" in normalized:
        return "sparseInfill"
    return "other"


def axis_value(line: str, axis: str) -> float | None:
    for token in line.split():
        if token.startswith(axis) and len(token) > 1:
            try:
                value = float(token[1:])
            except ValueError:
                continue
            return value if math.isfinite(value) else None
    return None


def tool_index(line: str) -> int | None:
    command = line.partition(";")[0].strip()
    if not command.startswith("T"):
        return None
    try:
        value = int(command[1:])
    except ValueError:
        return None
    return value if 0 <= value <= 15 else None


def analyze_gcode(path: Path, fingerprint_keys: Sequence[str]) -> dict[str, object]:
    role_motions = {role: 0 for role in ROLE_NAMES}
    role_layers: dict[str, set[int]] = {role: set() for role in ROLE_NAMES}
    role_extrusion_mm = {role: 0.0 for role in ROLE_NAMES}
    role_tools: dict[str, set[int]] = {role: set() for role in ROLE_NAMES}
    role_tool_extrusion_mm: dict[str, dict[int, float]] = {role: {} for role in ROLE_NAMES}
    profile_values: dict[str, str] = {}
    support_geometry = hashlib.sha256()
    active_role = "other"
    active_tool = 0
    tool_changes = 0
    relative_extrusion = False
    absolute_extruders = {0: 0.0}
    emitted_layer = -1
    extrusion_motions = 0
    layers = 0
    min_x = math.inf
    max_x = -math.inf
    current_x = 0.0
    current_y = 0.0
    current_z = 0.0
    digest = hashlib.sha256()
    try:
        with path.open("rb") as raw:
            for chunk in iter(lambda: raw.read(1024 * 1024), b""):
                digest.update(chunk)
        with path.open(encoding="utf-8", errors="strict") as source:
            for raw in source:
                line = raw.rstrip("\r\n")
                if line.startswith("; total layer number:"):
                    layers = int(line.rsplit(":", 1)[1].strip())
                if line.startswith(";LAYER_CHANGE"):
                    emitted_layer += 1
                if line.startswith(";") and " = " in line:
                    key, separator, value = line.removeprefix(";").lstrip().partition(" = ")
                    if separator and key in fingerprint_keys:
                        profile_values[key] = value.strip()
                label = None
                if line.startswith(";TYPE:"):
                    label = line.removeprefix(";TYPE:")
                elif line.startswith("; FEATURE:"):
                    label = line.removeprefix("; FEATURE:")
                if label is not None:
                    active_role = role_name(label)
                selected_tool = tool_index(line)
                if selected_tool is not None:
                    if selected_tool != active_tool:
                        tool_changes += 1
                    active_tool = selected_tool
                    absolute_extruders.setdefault(active_tool, 0.0)
                elif line.startswith("M82"):
                    relative_extrusion = False
                elif line.startswith("M83"):
                    relative_extrusion = True
                elif line.startswith("G92"):
                    reset = axis_value(line, "E")
                    if reset is not None:
                        absolute_extruders[active_tool] = reset
                if line.startswith(("G1 ", "G2 ", "G3 ")):
                    x = axis_value(line, "X")
                    y = axis_value(line, "Y")
                    z = axis_value(line, "Z")
                    if x is not None:
                        current_x = x
                    if y is not None:
                        current_y = y
                    if z is not None:
                        current_z = z
                    encoded_extrusion = axis_value(line, "E")
                    if encoded_extrusion is None:
                        continue
                    absolute_extruder = absolute_extruders[active_tool]
                    extrusion_delta = (
                        encoded_extrusion
                        if relative_extrusion
                        else encoded_extrusion - absolute_extruder
                    )
                    if not relative_extrusion:
                        absolute_extruders[active_tool] = encoded_extrusion
                    spatial_motion = any(axis_value(line, axis) is not None for axis in "XYZIJ")
                    if extrusion_delta <= 1e-7 or not spatial_motion or emitted_layer < 0:
                        continue
                    extrusion_motions += 1
                    role_motions[active_role] += 1
                    role_layers[active_role].add(emitted_layer)
                    role_extrusion_mm[active_role] += extrusion_delta
                    role_tools[active_role].add(active_tool)
                    role_tool_extrusion_mm[active_role][active_tool] = (
                        role_tool_extrusion_mm[active_role].get(active_tool, 0.0) + extrusion_delta
                    )
                    if active_role == "support":
                        signature = (
                            f"{emitted_layer}|{active_tool}|{current_x:.4f}|{current_y:.4f}|"
                            f"{current_z:.4f}|{extrusion_delta:.7f}\n"
                        )
                        support_geometry.update(signature.encode())
                    if x is not None:
                        min_x = min(min_x, x)
                        max_x = max(max_x, x)
    except (OSError, UnicodeError, ValueError) as error:
        raise DesktopQualificationError(f"Could not analyze {path}: {error}") from error
    if layers <= 0:
        raise DesktopQualificationError(f"No layer count found in {path}")
    missing = sorted(set(fingerprint_keys) - profile_values.keys())
    if missing:
        raise DesktopQualificationError(f"Desktop G-code omits profile keys: {', '.join(missing)}")
    fingerprint = "\n".join(f"{key}={profile_values[key]}" for key in sorted(fingerprint_keys))
    return {
        "layers": layers,
        "gcodeBytes": path.stat().st_size,
        "gcodeSha256": digest.hexdigest(),
        "extrusionMotions": extrusion_motions,
        "extrusionXSpanMm": max_x - min_x if math.isfinite(min_x) and math.isfinite(max_x) else 0.0,
        "emittedLayers": emitted_layer + 1,
        "roleMotions": role_motions,
        "roleLayers": {role: len(values) for role, values in role_layers.items()},
        "roleFirstLayers": {
            role: min(values) if values else -1 for role, values in role_layers.items()
        },
        "roleLastLayers": {
            role: max(values) if values else -1 for role, values in role_layers.items()
        },
        "roleExtrusionMm": role_extrusion_mm,
        "usedTools": sorted(set().union(*role_tools.values())),
        "toolChanges": tool_changes,
        "roleTools": {role: sorted(values) for role, values in role_tools.items()},
        "roleToolExtrusionMm": {
            role: {str(tool): value for tool, value in sorted(values.items())}
            for role, values in role_tool_extrusion_mm.items()
        },
        "supportGeometryFingerprint": support_geometry.hexdigest(),
        "profileFingerprint": hashlib.sha256(fingerprint.encode()).hexdigest(),
        "profileValues": dict(sorted(profile_values.items())),
    }


def write_profile(path: Path, profile_type: str, config: Mapping[str, str]) -> None:
    document = dict(config)
    document.update(
        {
            "name": f"Ducky qualification {profile_type}",
            "type": profile_type,
            "from": "system",
            "version": "2.3.3.0",
        }
    )
    if profile_type == "filament":
        document["filament_id"] = "ducky-qualification-generic-pla"
    elif profile_type == "process":
        # The CLI rejects a system process unless it explicitly names the
        # system machine preset loaded alongside it.
        document["compatible_printers"] = ["Ducky qualification machine"]
        # Android's embedded core accepts relative E without a layer hook. The
        # desktop CLI validates that preset more strictly; the reset has no
        # geometry or profile-fingerprint effect and prevents E precision loss.
        if document.get("use_relative_e_distances") == "1" and not document.get(
            "layer_change_gcode"
        ):
            document["layer_change_gcode"] = "G92 E0"
    path.write_text(json.dumps(document, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def bed_center(config: Mapping[str, str]) -> tuple[float, float]:
    points: list[tuple[float, float]] = []
    for encoded in config.get("bed_shape", "").split(","):
        coordinates = encoded.split("x", 1)
        if len(coordinates) != 2:
            continue
        try:
            points.append((float(coordinates[0]), float(coordinates[1])))
        except ValueError:
            continue
    if len(points) < 3:
        raise DesktopQualificationError("Android G-code has no usable bed_shape")
    xs = [point[0] for point in points]
    ys = [point[1] for point in points]
    return (min(xs) + max(xs)) / 2.0, (min(ys) + max(ys)) / 2.0


def write_assembly(
    path: Path,
    case: Mapping[str, object],
    center: tuple[float, float],
) -> None:
    model_paths = [str(value) for value in case["models"]]
    offsets = [float(value) for value in case.get("offsetsXmm", [0.0] * len(model_paths))]
    project_slots = [int(value) for value in case.get("modelFilamentSlots", [0] * len(model_paths))]
    feature_routing = case.get("featureFilaments")
    routes_default_volume_by_feature = isinstance(feature_routing, Mapping) and (
        int(feature_routing.get("wallFilament", 1)) != 1
        or int(feature_routing.get("solidInfillFilament", 1))
        != int(feature_routing.get("wallFilament", 1))
        or (
            bool(feature_routing.get("infillOverrideEnabled", False))
            and int(feature_routing.get("sparseInfillFilament", 1))
            != int(feature_routing.get("wallFilament", 1))
        )
    )
    objects: list[dict[str, object]] = []
    for index, relative in enumerate(model_paths):
        project_slot = project_slots[index]
        native_slot = 0 if project_slot == 0 and routes_default_volume_by_feature else project_slot + 1
        objects.append(
            {
                "path": str((CORPUS_ROOT / relative).resolve()),
                "count": 1,
                "filaments": [native_slot],
                "assemble_index": [0],
                # Android's transform pipeline converts the project-relative
                # offset into bed space before the pinned core sees the STL.
                "pos_x": [center[0] + offsets[index]],
                "pos_y": [center[1]],
                "pos_z": [0.0],
            }
        )
    document = {
        "plates": [
            {
                # A named plate asks the GUI-enabled macOS CLI to rasterize a
                # label without a wxApp. The name has no slicing semantics.
                "plate_name": "",
                "need_arrange": False,
                "objects": objects,
            }
        ]
    }
    path.write_text(json.dumps(document, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def compare_case(
    desktop: Mapping[str, object],
    android: Mapping[str, object],
    required_roles: Sequence[str],
) -> list[str]:
    differences: list[str] = []
    # The Android core's object-layer counter includes support scheduling
    # entries; Preview and the desktop CLI both consume emitted G-code layers.
    android_layers = android.get("previewLayerCount", android["layers"])
    if desktop["layers"] != android_layers:
        differences.append(f"G-code layers: desktop={desktop['layers']} android={android_layers}")
    if desktop["emittedLayers"] != android.get("emittedLayers"):
        differences.append(
            f"emitted layers: desktop={desktop['emittedLayers']} "
            f"android={android.get('emittedLayers')}"
        )
    if desktop["profileFingerprint"] != android["profileFingerprint"]:
        differences.append("effective profile fingerprint differs")
    desktop_span = float(desktop["extrusionXSpanMm"])
    android_span = float(android["extrusionXSpanMm"])
    if abs(desktop_span - android_span) > 0.05:
        differences.append(f"extrusion X span: desktop={desktop_span:.3f} android={android_span:.3f}")
    desktop_motions = int(desktop["extrusionMotions"])
    android_motions = int(android["extrusionMotions"])
    tolerance = max(10, math.ceil(android_motions * 0.01))
    if abs(desktop_motions - android_motions) > tolerance:
        differences.append(
            f"extrusion motions: desktop={desktop_motions} android={android_motions} tolerance={tolerance}"
        )
    desktop_roles = desktop["roleMotions"]
    android_roles = android["roleMotions"]
    assert isinstance(desktop_roles, Mapping)
    assert isinstance(android_roles, Mapping)
    for role in required_roles:
        if int(desktop_roles.get(role, 0)) <= 0:
            differences.append(f"required role missing: {role}")
    for role in ROLE_NAMES:
        desktop_count = int(desktop_roles.get(role, 0))
        android_count = int(android_roles.get(role, 0))
        role_tolerance = max(2, math.ceil(android_count * 0.01))
        if abs(desktop_count - android_count) > role_tolerance:
            differences.append(
                f"{role} motions: desktop={desktop_count} android={android_count} "
                f"tolerance={role_tolerance}"
            )
    for field in ("roleLayers", "roleFirstLayers", "roleLastLayers"):
        desktop_values = desktop[field]
        android_values = android[field]
        assert isinstance(desktop_values, Mapping)
        assert isinstance(android_values, Mapping)
        for role in ROLE_NAMES:
            if int(desktop_values.get(role, -1)) != int(android_values.get(role, -1)):
                differences.append(
                    f"{field} {role}: desktop={desktop_values.get(role)} "
                    f"android={android_values.get(role)}"
                )
    desktop_extrusion = desktop["roleExtrusionMm"]
    android_extrusion = android["roleExtrusionMm"]
    assert isinstance(desktop_extrusion, Mapping)
    assert isinstance(android_extrusion, Mapping)
    for role in ROLE_NAMES:
        desktop_mm = float(desktop_extrusion.get(role, 0.0))
        android_mm = float(android_extrusion.get(role, 0.0))
        extrusion_tolerance = max(0.02, abs(android_mm) * 0.001)
        if abs(desktop_mm - android_mm) > extrusion_tolerance:
            differences.append(
                f"{role} positive extrusion: desktop={desktop_mm:.4f} "
                f"android={android_mm:.4f} tolerance={extrusion_tolerance:.4f}"
            )
    for field in ("usedTools", "roleTools"):
        if desktop.get(field) != android.get(field):
            differences.append(f"{field}: desktop={desktop.get(field)} android={android.get(field)}")
    if desktop.get("toolChanges") != android.get("toolChanges"):
        differences.append(
            f"tool changes: desktop={desktop.get('toolChanges')} android={android.get('toolChanges')}"
        )
    desktop_tool_extrusion = desktop.get("roleToolExtrusionMm", {})
    android_tool_extrusion = android.get("roleToolExtrusionMm", {})
    assert isinstance(desktop_tool_extrusion, Mapping)
    assert isinstance(android_tool_extrusion, Mapping)
    for role in ROLE_NAMES:
        desktop_values = desktop_tool_extrusion.get(role, {})
        android_values = android_tool_extrusion.get(role, {})
        assert isinstance(desktop_values, Mapping)
        assert isinstance(android_values, Mapping)
        if set(desktop_values) != set(android_values):
            differences.append(
                f"{role} extrusion tools: desktop={sorted(desktop_values)} "
                f"android={sorted(android_values)}"
            )
            continue
        for tool in desktop_values:
            desktop_mm = float(desktop_values[tool])
            android_mm = float(android_values[tool])
            tolerance = max(0.02, abs(android_mm) * 0.001)
            if abs(desktop_mm - android_mm) > tolerance:
                differences.append(
                    f"{role} T{tool} extrusion: desktop={desktop_mm:.4f} "
                    f"android={android_mm:.4f} tolerance={tolerance:.4f}"
                )
    return differences


def run(
    binary: Path,
    build_dir: Path,
    android_gcode: Path,
    android_report_path: Path,
    output: Path,
    case_ids: set[str] | None = None,
) -> dict[str, object]:
    manifest = load_manifest()
    validate(manifest)
    expected_revision = str(manifest["engine"]["revision"])
    actual_revision = captured(("git", "rev-parse", "HEAD"), cwd=ORCA_ROOT, timeout=20)
    if actual_revision != expected_revision:
        raise DesktopQualificationError(
            f"Desktop source revision differs: expected={expected_revision} actual={actual_revision}"
        )
    try:
        android_report = json.loads(android_report_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise DesktopQualificationError(f"Could not read Android report: {error}") from error
    if android_report.get("engineRevision") != expected_revision:
        raise DesktopQualificationError("Android report uses a different Orca revision")
    release_source_commit: str | None = None
    if android_report.get("source") == "physical-android":
        release_source_commit = str(android_report.get("sourceCommit", ""))
        qualification_source_commit(release_source_commit)
    desktop_binary_sha256, compatibility_digest, build_cache_hit = build_pinned_desktop_cli(
        build_dir,
        binary,
    )
    base_config = parse_config_block(android_gcode)
    android_cases = {
        case["id"]: case for case in android_report.get("cases", []) if isinstance(case, dict)
    }
    selected = [
        case for case in manifest["cases"]
        if case_ids is None or case["id"] in case_ids
    ]
    selected_ids = {case["id"] for case in selected}
    if case_ids is not None and selected_ids != case_ids:
        raise DesktopQualificationError("Unknown corpus case: " + ", ".join(sorted(case_ids - selected_ids)))
    missing_android = sorted(selected_ids - android_cases.keys())
    if missing_android:
        raise DesktopQualificationError("Android report lacks cases: " + ", ".join(missing_android))

    if output.exists():
        shutil.rmtree(output)
    profiles = output / "profiles"
    profiles.mkdir(parents=True)
    reports: list[dict[str, object]] = []
    failures: list[str] = []
    fingerprint_keys = [str(value) for value in manifest["profileFingerprintKeys"]]
    for case in selected:
        identifier = str(case["id"])
        case_dir = output / identifier
        case_dir.mkdir()
        case_gcode = android_gcode.parent / f"{identifier}.gcode"
        case_config = parse_config_block(case_gcode) if case_gcode.is_file() else dict(base_config)
        center = bed_center(case_config)
        machine = profiles / f"machine-{identifier}.json"
        write_profile(machine, "machine", case_config)
        process = case_dir / "process.json"
        write_profile(process, "process", case_config)
        assembly = case_dir / "assembly.json"
        write_assembly(assembly, case, center)
        command = (
            str(binary),
            "--load-settings",
            f"{machine};{process}",
            "--load-assemble-list",
            str(assembly),
            "--outputdir",
            str(case_dir),
            "--no-check",
            "--slice",
            "1",
        )
        print(f"[desktop-orca] slicing {identifier}")
        log = captured(command, cwd=ORCA_ROOT)
        (case_dir / "desktop-orca.log").write_text(log + "\n", encoding="utf-8")
        gcodes = sorted(case_dir.glob("*.gcode"))
        if len(gcodes) != 1:
            raise DesktopQualificationError(
                f"Expected one desktop G-code for {identifier}, found {len(gcodes)}"
            )
        metrics = analyze_gcode(gcodes[0], fingerprint_keys)
        metrics["id"] = identifier
        expected = case["expected"]
        differences = compare_case(metrics, android_cases[identifier], expected["requiredRoles"])
        metrics["differences"] = differences
        reports.append(metrics)
        failures.extend(f"{identifier}: {difference}" for difference in differences)

    reports_by_id = {str(report["id"]): report for report in reports}
    for case in selected:
        expected = case.get("expected", {})
        if not isinstance(expected, Mapping):
            continue
        baseline_id = expected.get("supportGeometryDifferentFrom")
        identifier = str(case["id"])
        if baseline_id not in reports_by_id:
            continue
        if (
            reports_by_id[identifier].get("supportGeometryFingerprint")
            == reports_by_id[str(baseline_id)].get("supportGeometryFingerprint")
        ):
            failures.append(
                f"{identifier}: desktop support geometry does not differ from {baseline_id}"
            )

    report = {
        "schemaVersion": 1,
        "source": "desktop-orca",
        "sourceCommit": android_report.get("sourceCommit"),
        "engineRevision": expected_revision,
        "desktopBuildMode": DESKTOP_BUILD_MODE,
        "desktopBuildCacheHit": build_cache_hit,
        "desktopBinarySha256": desktop_binary_sha256,
        "desktopCompatibilitySha256": compatibility_digest,
        "manifestSha256": hashlib.sha256(MANIFEST.read_bytes()).hexdigest(),
        "androidReport": str(android_report_path),
        "androidReportSha256": file_sha256(android_report_path),
        "androidConfigGcode": str(android_gcode),
        "cases": reports,
        "passed": not failures,
        "failures": failures,
    }
    if release_source_commit is not None:
        qualification_source_commit(release_source_commit)
    report_path = output / "comparison-report.json"
    report_path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    if failures:
        raise DesktopQualificationError(
            "Desktop Orca comparison found material differences:\n- " + "\n- ".join(failures)
        )
    print(f"[desktop-orca] passed {len(reports)} cases; report: {report_path}")
    return report


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, default=DEFAULT_BINARY)
    parser.add_argument("--build-dir", type=Path, default=DEFAULT_BUILD_DIR)
    parser.add_argument("--android-gcode", type=Path, default=DEFAULT_ANDROID_GCODE)
    parser.add_argument("--android-report", type=Path, default=DEFAULT_ANDROID_REPORT)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--case", action="append", dest="cases", help="compare one case; repeatable")
    args = parser.parse_args(argv)
    try:
        run(
            args.binary.resolve(),
            args.build_dir.resolve(),
            args.android_gcode.resolve(),
            args.android_report.resolve(),
            args.output.resolve(),
            set(args.cases) if args.cases else None,
        )
    except (DesktopQualificationError, RunnerError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
