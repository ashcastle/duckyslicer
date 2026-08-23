#!/usr/bin/env python3
"""Authoritative component and notice policy for the Android native runtime."""

from __future__ import annotations

import fnmatch
import hashlib
import tarfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath


class NativeLicenseError(ValueError):
    """The native runtime cannot be mapped to reviewed license material."""


@dataclass(frozen=True)
class NativePolicy:
    key: str
    name: str
    license_expression: str
    notice_patterns: tuple[str, ...]


GIT_COMPONENTS = (
    NativePolicy("ANDROID_SLICER_RUNTIME", "Android slicer runtime", "AGPL-3.0-only", ("build/native-slicer/source/LICENSE",)),
    NativePolicy("SLICER_ENGINE", "OrcaSlicer", "AGPL-3.0-only", ("build/native-slicer/source/app/src/main/cpp/orcaslicer/LICENSE.txt",)),
    NativePolicy("EIGEN", "Eigen", "MPL-2.0", ("build/native-slicer/dependency-sources/eigen/COPYING.*",)),
    NativePolicy("CEREAL", "cereal", "BSD-3-Clause", ("build/native-slicer/dependency-sources/cereal/LICENSE",)),
    NativePolicy("NLOHMANN_JSON", "nlohmann-json", "MIT", ("build/native-slicer/dependency-sources/json/LICENSE.MIT",)),
    NativePolicy("ZLIB", "zlib", "Zlib", ("build/native-slicer/dependency-sources/zlib/LICENSE",)),
    NativePolicy("EXPAT", "Expat", "MIT", ("build/native-slicer/dependency-sources/expat/COPYING",)),
    NativePolicy("CLIPPER2", "Clipper2", "BSL-1.0", ("build/native-slicer/dependency-sources/clipper2/LICENSE",)),
    NativePolicy("ONETBB", "oneTBB", "Apache-2.0", ("build/native-slicer/dependency-sources/onetbb/LICENSE.txt",)),
    NativePolicy("BOOST_ANDROID", "Boost-for-Android", "BSL-1.0", ("build/native-slicer/dependency-sources/boost-android/LICENSE",)),
    NativePolicy(
        "OCCT",
        "Open CASCADE Technology",
        "LGPL-2.1-only WITH OCCT-exception-1.0",
        (
            "build/native-slicer/dependency-sources/occt/LICENSE_LGPL_21.txt",
            "build/native-slicer/dependency-sources/occt/OCCT_LGPL_EXCEPTION.txt",
        ),
    ),
    NativePolicy(
        "NLOPT",
        "NLopt",
        "LGPL-2.1-or-later",
        (
            "build/native-slicer/dependency-sources/nlopt/COPYING",
            "build/native-slicer/dependency-sources/nlopt/COPYRIGHT",
        ),
    ),
    NativePolicy("LIBJPEG_TURBO", "libjpeg-turbo", "BSD-3-Clause AND Zlib AND IJG", ("build/native-slicer/dependency-sources/libjpeg-turbo/LICENSE.md",)),
)

ARCHIVE_COMPONENTS = (
    (NativePolicy("BOOST_ARCHIVE", "Boost", "BSL-1.0", ("build/native-slicer/dependency-sources/boost-android/boost_1_84_0/LICENSE_1_0.txt",)), "1.84.0"),
    (
        NativePolicy(
            "LIBNOISE",
            "libnoise",
            "LGPL-2.1-or-later",
            (
                "build/native-slicer/source/app/src/main/cpp/extern/libnoise/share/doc/libnoise/README.md",
                "build/native-slicer/source/app/src/main/cpp/extern/libnoise/share/doc/libnoise/noise.h",
                "build/native-slicer/source/app/src/main/cpp/extern/libnoise/share/doc/libnoise/COPYING.LGPL",
            ),
        ),
        "1.0",
    ),
    (NativePolicy("CGAL", "CGAL", "GPL-3.0-or-later OR LGPL-3.0-or-later", ("LICENSE*",)), "5.6"),
    (NativePolicy("GMP", "GMP", "GPL-2.0-or-later OR LGPL-3.0-or-later", ("COPYING*",)), "6.3.0"),
    (NativePolicy("MPFR", "MPFR", "LGPL-3.0-or-later", ("COPYING*",)), "4.2.1"),
)

ARCHIVE_NOTICE_PATHS = {
    "CGAL": "build/native-slicer/dependency-sources/CGAL-5.6.tar.xz",
    "GMP": "build/native-slicer/dependency-sources/gmp-6.3.0.tar.xz",
    "MPFR": "build/native-slicer/dependency-sources/mpfr-4.2.1.tar.xz",
}

# These directories were observed in the completed Ninja dependency graph for
# libprusaslicer-jni.so. A new vendored input must be reviewed here or packaging
# fails instead of silently omitting it from the SBOM and offline notices.
VENDORED_COMPONENTS = (
    NativePolicy("Shiny", "Shiny profiler", "MIT", ("build/native-slicer/source/app/src/main/cpp/orcaslicer/deps_src/Shiny/ShinyTools.h",)),
    NativePolicy("admesh", "ADMesh", "GPL-2.0-or-later", ("build/native-slicer/source/app/src/main/cpp/orcaslicer/deps_src/admesh/shared.cpp",)),
    NativePolicy("agg", "Anti-Grain Geometry", "BSD-3-Clause OR LicenseRef-AGG", ("build/native-slicer/source/app/src/main/cpp/orcaslicer/deps_src/agg/copying",)),
    NativePolicy("ankerl", "ankerl unordered_dense", "MIT", ("build/native-slicer/source/app/src/main/cpp/orcaslicer/deps_src/ankerl/unordered_dense.h",)),
    NativePolicy("clipper", "Clipper 6.4.2", "BSL-1.0", ("build/native-slicer/source/app/src/main/cpp/orcaslicer/deps_src/clipper/clipper.cpp",)),
    NativePolicy("fast_float", "fast_float 2.0.0", "MIT", ("build/native-slicer/source/app/src/main/cpp/orcaslicer/deps_src/fast_float/fast_float.h",)),
    NativePolicy("glu-libtess", "SGI GLU libtess", "SGI-B-2.0", ("build/native-slicer/source/app/src/main/cpp/orcaslicer/deps_src/glu-libtess/src/tess.c",)),
    NativePolicy("imgui", "Dear ImGui", "MIT", ("build/native-slicer/source/app/src/main/cpp/orcaslicer/deps_src/imgui/LICENSE.txt",)),
    NativePolicy("libigl", "libigl", "MPL-2.0", ("build/native-slicer/dependency-sources/eigen/COPYING.MPL2",)),
    NativePolicy("libnest2d", "libnest2d", "LGPL-3.0-only", ("build/native-slicer/source/app/src/main/cpp/orcaslicer/deps_src/libnest2d/LICENSE.txt",)),
    NativePolicy("mcut", "MCUT", "GPL-3.0-only", ("build/native-slicer/source/app/src/main/cpp/orcaslicer/deps_src/mcut/LICENSE.txt", "build/native-slicer/source/app/src/main/cpp/orcaslicer/deps_src/mcut/LICENSE.GPL.txt")),
    NativePolicy("miniz", "miniz", "MIT", ("build/native-slicer/source/app/src/main/cpp/orcaslicer/deps_src/miniz/LICENSE",)),
    NativePolicy("nanosvg", "NanoSVG", "Zlib", ("build/native-slicer/source/app/src/main/cpp/orcaslicer/deps_src/nanosvg/nanosvg.h", "build/native-slicer/source/app/src/main/cpp/orcaslicer/deps_src/nanosvg/nanosvgrast.h")),
    NativePolicy("qhull", "Qhull", "LicenseRef-Qhull", ("build/native-slicer/source/app/src/main/cpp/orcaslicer/deps_src/qhull/COPYING.txt",)),
    NativePolicy("qoi", "QOI", "MIT", ("build/native-slicer/source/app/src/main/cpp/orcaslicer/deps_src/qoi/qoi.h",)),
    NativePolicy("semver", "semver.c", "MIT", ("build/native-slicer/source/app/src/main/cpp/orcaslicer/deps_src/semver/semver.c",)),
    NativePolicy("spline", "tk spline", "GPL-2.0-or-later", ("build/native-slicer/source/app/src/main/cpp/orcaslicer/deps_src/spline/spline.h",)),
)


def parse_versions(root: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in (root / "native/slicer-runtime/versions.env").read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            values[key] = value
    return values


def native_components(root: Path) -> list[dict[str, object]]:
    values = parse_versions(root)
    components: list[dict[str, object]] = []
    for policy in GIT_COMPONENTS:
        revision = values.get(f"{policy.key}_COMMIT")
        if not revision:
            raise NativeLicenseError(f"missing native revision: {policy.key}_COMMIT")
        repository = values.get(f"{policy.key}_REPOSITORY")
        components.append(
            {
                "type": "library",
                "name": policy.name,
                "version": revision,
                "bom-ref": f"native:{policy.key.lower()}@{revision}",
                "licenses": [{"expression": policy.license_expression}],
                "externalReferences": ([{"type": "vcs", "url": repository}] if repository else []),
            }
        )
    for policy, version in ARCHIVE_COMPONENTS:
        components.append(
            {
                "type": "library",
                "name": policy.name,
                "version": version,
                "bom-ref": f"native:{policy.key.lower()}@{version}",
                "licenses": [{"expression": policy.license_expression}],
                "hashes": [{"alg": "SHA-256", "content": values[f"{policy.key}_SHA256"]}],
                "externalReferences": [{"type": "distribution", "url": values[f"{policy.key}_URL"]}],
            }
        )
    engine_revision = values["SLICER_ENGINE_COMMIT"]
    for policy in VENDORED_COMPONENTS:
        components.append(
            {
                "type": "library",
                "name": policy.name,
                "version": engine_revision,
                "bom-ref": f"native:orca-vendored-{policy.key.lower()}@{engine_revision}",
                "licenses": [{"expression": policy.license_expression}],
            }
        )
    ndk_version = values["ANDROID_NDK_VERSION"]
    components.append(
        {
            "type": "library",
            "name": "LLVM libc++ Android runtime",
            "version": ndk_version,
            "bom-ref": f"native:android-ndk-libcxx@{ndk_version}",
            "licenses": [{"expression": "Apache-2.0 WITH LLVM-exception"}],
        }
    )
    return components


def _resolve_patterns(root: Path, patterns: tuple[str, ...]) -> tuple[Path, ...]:
    resolved: list[Path] = []
    for pattern in patterns:
        matches = sorted(root.glob(pattern))
        if not matches:
            raise NativeLicenseError(f"native license source is missing: {pattern}")
        resolved.extend(path for path in matches if path.is_file())
    if not resolved:
        raise NativeLicenseError(f"native license policy resolved no files: {patterns}")
    return tuple(dict.fromkeys(resolved))


def _resolve_archive_notices(
    root: Path,
    policy: NativePolicy,
    expected_sha256: str,
) -> tuple[Path, ...]:
    relative_archive = ARCHIVE_NOTICE_PATHS[policy.key]
    archive_path = root / relative_archive
    try:
        digest = hashlib.sha256(archive_path.read_bytes()).hexdigest()
    except OSError as error:
        raise NativeLicenseError(
            f"native license archive is missing: {relative_archive}"
        ) from error
    if digest != expected_sha256:
        raise NativeLicenseError(
            f"native license archive checksum mismatch: {relative_archive}"
        )

    matched_patterns: set[str] = set()
    documents: dict[str, bytes] = {}
    try:
        with tarfile.open(archive_path, mode="r:xz") as archive:
            for member in archive.getmembers():
                member_path = PurePosixPath(member.name)
                if member_path.is_absolute() or ".." in member_path.parts:
                    raise NativeLicenseError(
                        f"unsafe native license archive member: {member.name}"
                    )
                if not member.isfile() or len(member_path.parts) != 2:
                    continue
                name = member_path.name
                patterns = tuple(
                    pattern
                    for pattern in policy.notice_patterns
                    if fnmatch.fnmatchcase(name, pattern)
                )
                if not patterns:
                    continue
                source = archive.extractfile(member)
                if source is None:
                    raise NativeLicenseError(
                        f"cannot read native license archive member: {member.name}"
                    )
                content = source.read()
                if not content:
                    raise NativeLicenseError(
                        f"empty native license archive member: {member.name}"
                    )
                if name in documents and documents[name] != content:
                    raise NativeLicenseError(
                        f"duplicate native license archive member: {name}"
                    )
                documents[name] = content
                matched_patterns.update(patterns)
    except (OSError, tarfile.TarError) as error:
        raise NativeLicenseError(
            f"cannot inspect native license archive: {relative_archive}"
        ) from error

    missing = sorted(set(policy.notice_patterns) - matched_patterns)
    if missing:
        raise NativeLicenseError(
            f"native license archive has no reviewed notices: {policy.key} {missing}"
        )

    output_root = root / "build/native-slicer/archive-license-sources" / policy.key.lower()
    output_root.mkdir(parents=True, exist_ok=True)
    resolved: list[Path] = []
    for name, content in sorted(documents.items()):
        output = output_root / name
        output.write_bytes(content)
        resolved.append(output)
    return tuple(resolved)


def native_notice_sources(root: Path, ndk_root: Path) -> dict[str, tuple[Path, ...]]:
    values = parse_versions(root)
    notices: dict[str, tuple[Path, ...]] = {}
    for policy in GIT_COMPONENTS:
        revision = values[f"{policy.key}_COMMIT"]
        notices[f"native:{policy.key.lower()}@{revision}"] = _resolve_patterns(root, policy.notice_patterns)
    for policy, version in ARCHIVE_COMPONENTS:
        if policy.key in ARCHIVE_NOTICE_PATHS:
            sources = _resolve_archive_notices(
                root,
                policy,
                values[f"{policy.key}_SHA256"],
            )
        else:
            sources = _resolve_patterns(root, policy.notice_patterns)
        notices[f"native:{policy.key.lower()}@{version}"] = sources
    engine_revision = values["SLICER_ENGINE_COMMIT"]
    for policy in VENDORED_COMPONENTS:
        notices[f"native:orca-vendored-{policy.key.lower()}@{engine_revision}"] = _resolve_patterns(root, policy.notice_patterns)
    ndk_notices = sorted(ndk_root.glob("toolchains/llvm/prebuilt/*/NOTICE"))
    if len(ndk_notices) != 1 or not ndk_notices[0].is_file():
        raise NativeLicenseError(f"expected one LLVM toolchain NOTICE under {ndk_root}")
    notices[f"native:android-ndk-libcxx@{values['ANDROID_NDK_VERSION']}"] = (ndk_notices[0],)
    expected = {str(component["bom-ref"]) for component in native_components(root)}
    if set(notices) != expected:
        raise NativeLicenseError("native notice policy does not exactly match native SBOM components")
    return notices


def expected_vendored_directories() -> frozenset[str]:
    return frozenset(policy.key for policy in VENDORED_COMPONENTS)
