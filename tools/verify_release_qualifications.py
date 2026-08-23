#!/usr/bin/env python3
"""Validate physical-device evidence required by local release preparation."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
from pathlib import Path

try:
    from tools.analyze_startup_benchmark import AnalysisError, analyze
    from tools.qualification_corpus import MANIFEST, REQUIRED_CASES, CorpusError, load_manifest, validate
    from tools.run_physical_qualification import (
        DeviceIdentity,
        RunnerError,
        physical_rejection,
        validate_dense_render,
        validate_resource_budget,
    )
    from tools.run_desktop_orca_qualification import (
        DesktopQualificationError,
        compatibility_sha256,
    )
    from tools.run_qualification_corpus import validate_report
except ModuleNotFoundError:  # Direct `python tools/verify_release_qualifications.py` execution.
    from analyze_startup_benchmark import AnalysisError, analyze
    from qualification_corpus import MANIFEST, REQUIRED_CASES, CorpusError, load_manifest, validate
    from run_physical_qualification import (
        DeviceIdentity,
        RunnerError,
        physical_rejection,
        validate_dense_render,
        validate_resource_budget,
    )
    from run_desktop_orca_qualification import DesktopQualificationError, compatibility_sha256
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


def validate_orca_conformance(
    document: dict[str, object],
    source_commit: str,
    physical_report_sha256: str,
) -> None:
    require_commit(document, source_commit)
    if document.get("schemaVersion") != 1 or document.get("source") != "desktop-orca":
        raise QualificationEvidenceError("Orca conformance report identity is invalid")
    manifest = load_manifest()
    validate(manifest)
    engine = manifest.get("engine")
    expected_revision = engine.get("revision") if isinstance(engine, dict) else None
    if document.get("engineRevision") != expected_revision:
        raise QualificationEvidenceError("Orca conformance report uses a different engine revision")
    expected_manifest = hashlib.sha256(MANIFEST.read_bytes()).hexdigest()
    if document.get("manifestSha256") != expected_manifest:
        raise QualificationEvidenceError("Orca conformance report uses a stale corpus manifest")
    if document.get("androidReportSha256") != physical_report_sha256:
        raise QualificationEvidenceError("Orca conformance report uses a different physical report")
    if document.get("desktopBuildMode") != "pinned-source-rebuilt":
        raise QualificationEvidenceError("Orca conformance report was not rebuilt from pinned source")
    if not isinstance(document.get("desktopBuildCacheHit"), bool):
        raise QualificationEvidenceError("Orca conformance report has no verified build-cache state")
    binary_digest = document.get("desktopBinarySha256")
    if not isinstance(binary_digest, str) or re.fullmatch(r"[0-9a-f]{64}", binary_digest) is None:
        raise QualificationEvidenceError("Orca conformance report has no desktop binary digest")
    compatibility_digest = document.get("desktopCompatibilitySha256")
    if (
        not isinstance(compatibility_digest, str)
        or re.fullmatch(r"[0-9a-f]{64}", compatibility_digest) is None
    ):
        raise QualificationEvidenceError("Orca conformance report has no compatibility-input digest")
    try:
        expected_compatibility = compatibility_sha256()
    except DesktopQualificationError as error:
        raise QualificationEvidenceError(str(error)) from error
    if compatibility_digest != expected_compatibility:
        raise QualificationEvidenceError("Orca conformance report uses stale compatibility inputs")
    if document.get("passed") is not True or document.get("failures") != []:
        raise QualificationEvidenceError("Desktop Orca comparison did not pass cleanly")
    cases = document.get("cases")
    if not isinstance(cases, list):
        raise QualificationEvidenceError("Orca conformance report has no corpus cases")
    identifiers: list[str] = []
    for case in cases:
        if not isinstance(case, dict) or not isinstance(case.get("id"), str):
            raise QualificationEvidenceError("Orca conformance report contains an invalid case")
        identifiers.append(str(case["id"]))
        if case.get("differences") != []:
            raise QualificationEvidenceError(
                f"Desktop Orca comparison differs for {case['id']}",
            )
        for field in ("layers", "emittedLayers", "extrusionMotions"):
            value = case.get(field)
            if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
                raise QualificationEvidenceError(
                    f"Orca conformance case {case['id']} has invalid {field}",
                )
        fingerprint = case.get("profileFingerprint")
        if not isinstance(fingerprint, str) or re.fullmatch(r"[0-9a-f]{64}", fingerprint) is None:
            raise QualificationEvidenceError(
                f"Orca conformance case {case['id']} has no profile fingerprint",
            )
    if len(identifiers) != len(set(identifiers)) or set(identifiers) != set(REQUIRED_CASES):
        raise QualificationEvidenceError("Orca conformance report does not contain every corpus case")


def verify_release_qualifications(
    physical_report: Path,
    startup_report: Path,
    orca_report: Path,
    source_commit: str,
) -> None:
    validate_physical(read_document(physical_report), source_commit)
    validate_startup(read_document(startup_report), source_commit)
    physical_digest = hashlib.sha256(physical_report.read_bytes()).hexdigest()
    validate_orca_conformance(read_document(orca_report), source_commit, physical_digest)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--physical-report", required=True, type=Path)
    parser.add_argument("--startup-report", required=True, type=Path)
    parser.add_argument("--orca-report", required=True, type=Path)
    parser.add_argument("--source-commit", required=True)
    args = parser.parse_args()
    try:
        verify_release_qualifications(
            args.physical_report,
            args.startup_report,
            args.orca_report,
            args.source_commit,
        )
    except (CorpusError, QualificationEvidenceError) as error:
        print(f"Release qualification failed: {error}")
        return 1
    print("Release physical rendering, slicing, startup, and Orca conformance evidence passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
