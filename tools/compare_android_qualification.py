#!/usr/bin/env python3
"""Compare 4 KB and 16 KB Android qualification outputs after strict normalization."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from collections.abc import Mapping, Sequence
from pathlib import Path

try:
    from tools.qualification_corpus import CorpusError, load_manifest, validate
    from tools.run_qualification_corpus import RunnerError, validate_report
except ModuleNotFoundError:  # Direct `python tools/compare_android_qualification.py` execution.
    from qualification_corpus import CorpusError, load_manifest, validate
    from run_qualification_corpus import RunnerError, validate_report


VOLATILE_CASE_FIELDS = frozenset(
    {
        "gcodeBytes",
        "gcodeSha256",
        "previewParseElapsedMs",
        "sliceElapsedMs",
    }
)
GENERATED_AT = re.compile(r"on \d{4}-\d{2}-\d{2} at \d{2}:\d{2}:\d{2}")
STAGED_MODEL = re.compile(r"slicer-input-(\d+)-(\d+)-\d+\.stl")


class QualificationComparisonError(RuntimeError):
    """Two qualification runs do not prove equivalent page-size behavior."""


def load_report(path: Path, manifest: dict[str, object]) -> dict[str, object]:
    try:
        payload = path.read_text(encoding="utf-8")
    except OSError as error:
        raise QualificationComparisonError(f"Could not read qualification report {path}: {error}") from error
    try:
        return validate_report(payload, manifest)
    except RunnerError as error:
        raise QualificationComparisonError(f"Invalid qualification report {path}: {error}") from error


def report_target(report: Mapping[str, object], label: str) -> dict[str, object]:
    target = report.get("target")
    if not isinstance(target, dict):
        raise QualificationComparisonError(f"{label} report does not identify its Android target")
    api = target.get("apiLevel")
    abi = target.get("abi")
    page_size = target.get("pageSizeBytes")
    if not isinstance(api, int) or api < 26 or abi != "arm64-v8a":
        raise QualificationComparisonError(f"{label} report has invalid API or ABI metadata")
    if not isinstance(page_size, int) or page_size < 4_096 or page_size & (page_size - 1):
        raise QualificationComparisonError(f"{label} report has invalid page-size metadata")
    return target


def stable_case(case: Mapping[str, object]) -> dict[str, object]:
    return {key: value for key, value in case.items() if key not in VOLATILE_CASE_FIELDS}


def differing_keys(left: Mapping[str, object], right: Mapping[str, object]) -> list[str]:
    return sorted(key for key in left.keys() | right.keys() if left.get(key) != right.get(key))


def normalized_gcode(path: Path) -> bytes:
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as error:
        raise QualificationComparisonError(f"Could not read retained G-code {path}: {error}") from error
    text = GENERATED_AT.sub("on <timestamp>", text)
    text = STAGED_MODEL.sub(r"slicer-input-\1-\2-<nonce>.stl", text)
    return (text.rstrip("\r\n") + "\n").encode("utf-8")


def compare(
    baseline_report_path: Path,
    candidate_report_path: Path,
    baseline_gcode: Path,
    candidate_gcode: Path,
) -> dict[str, object]:
    manifest = load_manifest()
    validate(manifest)
    baseline = load_report(baseline_report_path, manifest)
    candidate = load_report(candidate_report_path, manifest)
    if baseline.get("physicalMeasurementRequested") is True:
        raise QualificationComparisonError("Baseline report is a physical performance measurement")
    if candidate.get("physicalMeasurementRequested") is True:
        raise QualificationComparisonError("Candidate report is a physical performance measurement")
    baseline_target = report_target(baseline, "Baseline")
    candidate_target = report_target(candidate, "Candidate")
    page_sizes = {
        int(baseline_target["pageSizeBytes"]),
        int(candidate_target["pageSizeBytes"]),
    }
    if page_sizes != {4_096, 16_384}:
        raise QualificationComparisonError(
            "Comparison requires one 4 KB and one 16 KB Android qualification report"
        )

    ignored_top_level = {"cases", "target", "physicalMeasurementRequested"}
    baseline_stable = {
        key: value for key, value in baseline.items() if key not in ignored_top_level
    }
    candidate_stable = {
        key: value for key, value in candidate.items() if key not in ignored_top_level
    }
    top_differences = differing_keys(baseline_stable, candidate_stable)
    if top_differences:
        raise QualificationComparisonError(
            "Qualification metadata differs: " + ", ".join(top_differences)
        )

    baseline_cases = {
        case["id"]: case for case in baseline["cases"] if isinstance(case, dict)
    }
    candidate_cases = {
        case["id"]: case for case in candidate["cases"] if isinstance(case, dict)
    }
    results: list[dict[str, object]] = []
    for identifier in sorted(baseline_cases):
        baseline_case = stable_case(baseline_cases[identifier])
        candidate_case = stable_case(candidate_cases[identifier])
        case_differences = differing_keys(baseline_case, candidate_case)
        if case_differences:
            raise QualificationComparisonError(
                f"Qualification case {identifier} differs: " + ", ".join(case_differences)
            )
        baseline_payload = normalized_gcode(baseline_gcode / f"{identifier}.gcode")
        candidate_payload = normalized_gcode(candidate_gcode / f"{identifier}.gcode")
        if baseline_payload != candidate_payload:
            raise QualificationComparisonError(
                f"Qualification case {identifier} has different normalized G-code"
            )
        results.append(
            {
                "id": identifier,
                "normalizedGcodeSha256": hashlib.sha256(candidate_payload).hexdigest(),
            }
        )
    return {
        "schemaVersion": 1,
        "engineRevision": candidate["engineRevision"],
        "manifestSha256": candidate["manifestSha256"],
        "baselineTarget": baseline_target,
        "candidateTarget": candidate_target,
        "cases": results,
    }


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-report", required=True, type=Path)
    parser.add_argument("--candidate-report", required=True, type=Path)
    parser.add_argument("--baseline-gcode", required=True, type=Path)
    parser.add_argument("--candidate-gcode", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    try:
        result = compare(
            args.baseline_report,
            args.candidate_report,
            args.baseline_gcode,
            args.candidate_gcode,
        )
        if args.output is not None:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(
                json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
        print(
            f"Android qualification outputs match across 4 KB and 16 KB pages: "
            f"{len(result['cases'])} cases"
        )
    except (CorpusError, QualificationComparisonError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
