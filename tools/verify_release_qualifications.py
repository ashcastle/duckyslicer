#!/usr/bin/env python3
"""Validate physical-device evidence required by local release preparation."""

from __future__ import annotations

import argparse
import json
import math
import re
from pathlib import Path

try:
    from tools.analyze_startup_benchmark import AnalysisError, analyze
    from tools.qualification_corpus import REQUIRED_CASES, CorpusError, load_manifest, validate
    from tools.run_physical_qualification import (
        DeviceIdentity,
        RunnerError,
        physical_rejection,
        validate_dense_render,
        validate_resource_budget,
    )
    from tools.run_qualification_corpus import validate_report
except ModuleNotFoundError:  # Direct `python tools/verify_release_qualifications.py` execution.
    from analyze_startup_benchmark import AnalysisError, analyze
    from qualification_corpus import REQUIRED_CASES, CorpusError, load_manifest, validate
    from run_physical_qualification import (
        DeviceIdentity,
        RunnerError,
        physical_rejection,
        validate_dense_render,
        validate_resource_budget,
    )
    from run_qualification_corpus import validate_report


class QualificationEvidenceError(ValueError):
    """A release qualification report is missing, stale, or invalid."""


def read_document(path: Path) -> dict[str, object]:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise QualificationEvidenceError(f"Could not read qualification report {path}: {error}") from error
    if not isinstance(document, dict):
        raise QualificationEvidenceError(f"Qualification report must be a JSON object: {path}")
    return document


def device_identity(value: object) -> DeviceIdentity:
    if not isinstance(value, dict):
        raise QualificationEvidenceError("Qualification report has no physical device identity")
    try:
        identity = DeviceIdentity(
            serial=str(value["serial"]),
            manufacturer=str(value["manufacturer"]),
            model=str(value["model"]),
            api=int(value["api"]),
            abi=str(value["abi"]),
            page_size_bytes=int(value["page_size_bytes"]),
            hardware=str(value["hardware"]),
            kernel_qemu=str(value["kernel_qemu"]),
            boot_qemu=str(value["boot_qemu"]),
            build_fingerprint=str(value["build_fingerprint"]),
            memory_total_kb=int(value["memory_total_kb"]),
        )
    except (KeyError, TypeError, ValueError) as error:
        raise QualificationEvidenceError("Qualification report has an invalid device identity") from error
    rejection = physical_rejection(identity)
    if rejection:
        raise QualificationEvidenceError("Release evidence requires a physical device: " + rejection)
    return identity


def require_commit(document: dict[str, object], source_commit: str) -> None:
    if not re.fullmatch(r"[0-9a-f]{40}", source_commit):
        raise QualificationEvidenceError("Expected source commit must be a full lowercase Git SHA")
    if document.get("sourceCommit") != source_commit:
        raise QualificationEvidenceError("Qualification report was generated for a different source commit")


def validate_physical(document: dict[str, object], source_commit: str) -> None:
    require_commit(document, source_commit)
    if document.get("source") != "physical-android":
        raise QualificationEvidenceError("Rendering and slicing evidence was not collected physically")
    identity = device_identity(document.get("device"))
    manifest = load_manifest()
    validate(manifest)
    try:
        report = validate_report(json.dumps(document), manifest, set(REQUIRED_CASES))
    except (CorpusError, RunnerError) as error:
        raise QualificationEvidenceError(str(error)) from error
    cases = report.get("cases")
    if not isinstance(cases, list):
        raise QualificationEvidenceError("Physical qualification has no corpus cases")
    for case in cases:
        if not isinstance(case, dict) or not isinstance(case.get("id"), str):
            raise QualificationEvidenceError("Physical qualification contains an invalid case")
        identifier = str(case["id"])
        host = case.get("host")
        try:
            validate_resource_budget(host, identity, identifier)
            if identifier == "dense-preview":
                validate_dense_render(case, required_soak_cycles=3)
        except RunnerError as error:
            raise QualificationEvidenceError(str(error)) from error


def validate_startup(document: dict[str, object], source_commit: str) -> None:
    require_commit(document, source_commit)
    if document.get("schemaVersion") != 1:
        raise QualificationEvidenceError("Startup qualification report schema is invalid")
    device_identity(document.get("device"))
    digest = document.get("benchmarkSha256")
    if not isinstance(digest, str) or re.fullmatch(r"[0-9a-f]{64}", digest) is None:
        raise QualificationEvidenceError("Startup qualification has no benchmark digest")
    benchmark = document.get("benchmark")
    if not isinstance(benchmark, dict):
        raise QualificationEvidenceError("Startup qualification has no benchmark payload")
    try:
        actual_ratios = analyze(benchmark)
    except AnalysisError as error:
        raise QualificationEvidenceError(str(error)) from error
    recorded_ratios = document.get("profileRatios")
    if not isinstance(recorded_ratios, dict) or set(recorded_ratios) != set(actual_ratios):
        raise QualificationEvidenceError("Startup qualification ratios are incomplete")
    for name, actual in actual_ratios.items():
        recorded = recorded_ratios.get(name)
        if (
            not isinstance(recorded, (int, float))
            or isinstance(recorded, bool)
            or not math.isclose(float(recorded), actual, rel_tol=1e-9, abs_tol=1e-9)
        ):
            raise QualificationEvidenceError("Startup qualification ratios do not match its benchmark")


def verify_release_qualifications(
    physical_report: Path,
    startup_report: Path,
    source_commit: str,
) -> None:
    validate_physical(read_document(physical_report), source_commit)
    validate_startup(read_document(startup_report), source_commit)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--physical-report", required=True, type=Path)
    parser.add_argument("--startup-report", required=True, type=Path)
    parser.add_argument("--source-commit", required=True)
    args = parser.parse_args()
    try:
        verify_release_qualifications(
            args.physical_report,
            args.startup_report,
            args.source_commit,
        )
    except (CorpusError, QualificationEvidenceError) as error:
        print(f"Release qualification failed: {error}")
        return 1
    print("Release physical rendering, slicing, and startup evidence passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
