#!/usr/bin/env python3
"""Compare Android corpus output with the pinned desktop Orca engine."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import shutil
import subprocess
import sys
from collections.abc import Mapping, Sequence
from pathlib import Path

try:
    from tools.qualification_corpus import CORPUS_ROOT, MANIFEST, load_manifest, validate
except ModuleNotFoundError:  # Direct `python tools/run_desktop_orca_qualification.py` execution.
    from qualification_corpus import CORPUS_ROOT, MANIFEST, load_manifest, validate


ROOT = Path(__file__).resolve().parent.parent
ORCA_ROOT = ROOT / "third_party/android-slicer-runtime/app/src/main/cpp/orcaslicer"
DEFAULT_BINARY = ORCA_ROOT / "build/qualification-desktop/src/Snapmaker_Orca"
DEFAULT_ANDROID_GCODE = ROOT / "build/qualification/android-gcode/simple-part.gcode"
DEFAULT_ANDROID_REPORT = ROOT / "build/qualification/android-report.json"
DEFAULT_OUTPUT = ROOT / "build/qualification/desktop-orca"
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


def analyze_gcode(path: Path, fingerprint_keys: Sequence[str]) -> dict[str, object]:
    role_motions = {role: 0 for role in ROLE_NAMES}
    profile_values: dict[str, str] = {}
    active_role = "other"
    extrusion_motions = 0
    layers = 0
    min_x = math.inf
    max_x = -math.inf
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
                if line.startswith(("G1 ", "G2 ", "G3 ")) and axis_value(line, "E") is not None:
                    extrusion_motions += 1
                    role_motions[active_role] += 1
                    x = axis_value(line, "X")
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
        "roleMotions": role_motions,
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
    objects: list[dict[str, object]] = []
    for index, relative in enumerate(model_paths):
        objects.append(
            {
                "path": str((CORPUS_ROOT / relative).resolve()),
                "count": 1,
                "filaments": [1],
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
    return differences


def run(
    binary: Path,
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
    if not binary.is_file():
        raise DesktopQualificationError(f"Pinned desktop Orca binary is missing: {binary}")
    config = parse_config_block(android_gcode)
    center = bed_center(config)
    try:
        android_report = json.loads(android_report_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise DesktopQualificationError(f"Could not read Android report: {error}") from error
    if android_report.get("engineRevision") != expected_revision:
        raise DesktopQualificationError("Android report uses a different Orca revision")
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
    machine = profiles / "machine.json"
    write_profile(machine, "machine", config)

    reports: list[dict[str, object]] = []
    failures: list[str] = []
    fingerprint_keys = [str(value) for value in manifest["profileFingerprintKeys"]]
    for case in selected:
        identifier = str(case["id"])
        case_dir = output / identifier
        case_dir.mkdir()
        process = case_dir / "process.json"
        case_config = dict(config)
        case_config["enable_support"] = "1" if case.get("supportEnabled", False) else "0"
        if case.get("supportEnabled", False):
            case_config["support_threshold_angle"] = "45"
            case_config["support_type"] = "normal(auto)"
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

    report = {
        "schemaVersion": 1,
        "source": "desktop-orca",
        "engineRevision": expected_revision,
        "manifestSha256": hashlib.sha256(MANIFEST.read_bytes()).hexdigest(),
        "androidReport": str(android_report_path),
        "androidConfigGcode": str(android_gcode),
        "cases": reports,
        "passed": not failures,
        "failures": failures,
    }
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
    parser.add_argument("--android-gcode", type=Path, default=DEFAULT_ANDROID_GCODE)
    parser.add_argument("--android-report", type=Path, default=DEFAULT_ANDROID_REPORT)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--case", action="append", dest="cases", help="compare one case; repeatable")
    args = parser.parse_args(argv)
    try:
        run(
            args.binary.resolve(),
            args.android_gcode.resolve(),
            args.android_report.resolve(),
            args.output.resolve(),
            set(args.cases) if args.cases else None,
        )
    except DesktopQualificationError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
