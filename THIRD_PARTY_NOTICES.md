# Third-party notices

## Source-built Android slicer runtime

DuckySlicer's Android ARM64 slicer runtime is built from pinned source during
the Android build. No precompiled slicer engine is stored in this repository.
The complete input lock is
[`native/slicer-runtime/versions.env`](native/slicer-runtime/versions.env), and
the reviewed Android adaptation is
[`native/slicer-runtime/runtime.patch`](native/slicer-runtime/runtime.patch).

- Android runtime lineage:
  [`taylormadearmy/u1-slicer-for-android`](https://github.com/taylormadearmy/u1-slicer-for-android)
  at `6f64367361c4bd56bacc97a991874ce1f4b837b4` — GNU AGPL v3.0
- OrcaSlicer engine:
  [`taylormadearmy/OrcaSlicer`](https://github.com/taylormadearmy/OrcaSlicer)
  at `2c8a5385bc53cbc16211b4dd36ef9963ee185f4a` — GNU AGPL v3.0
- Toolchain: Android NDK `28.2.13676358`, API 26, `arm64-v8a`

Native binary hashes belong to each release artifact rather than this source
notice. The release `SHA256SUMS`, CycloneDX SBOM, and provenance attestation bind
the APK and its source-built runtime to that release; the revisions above are the
authoritative corresponding-source inputs.

The APK also contains a compact, normalized profile catalog generated from the
same pinned OrcaSlicer source tree. The generator resolves profile inheritance,
keeps source brand and compatibility metadata, and rejects values outside
DuckySlicer's supported Android runtime bounds. The generated catalog is a
derived part of the OrcaSlicer work and is distributed under the same AGPL terms.

AndroidX, Jetpack Compose, Kotlin, Kotlin Coroutines, Kotlin Serialization,
JetBrains annotations, JSpecify, and Guava's `listenablefuture` compatibility
artifact are distributed under the Apache License 2.0. Exact resolved Maven and
Cargo versions and their reviewed license expressions are recorded in each
release's CycloneDX SBOM; an unreviewed group or license identifier fails the build.

The runtime is linked with the following pinned libraries. Exact commit and
archive checksums are kept in `versions.env`; the corresponding license texts
are packaged in the APK's offline third-party license bundle.

| Component | License |
| --- | --- |
| Eigen | MPL-2.0 and component-specific compatible licenses |
| cereal | BSD-3-Clause |
| nlohmann/json | MIT |
| zlib | zlib License |
| Expat | MIT |
| Clipper2 | Boost Software License 1.0 |
| oneTBB | Apache-2.0 |
| Boost / Boost-for-Android | Boost Software License 1.0 |
| Open CASCADE Technology | LGPL-2.1 with OCCT exception |
| NLopt compiled library | LGPL-2.1-or-later |
| libjpeg-turbo | IJG, BSD-3-Clause, and zlib licenses |
| CGAL 5.6 headers | GPL-3.0-or-later, LGPL-3.0-or-later, and component-specific licenses |
| GMP 6.3.0 | GPL-2.0-or-later or LGPL-3.0-or-later |
| MPFR 4.2.1 | LGPL-3.0-or-later |

The pinned engine source also vendors code that appears in the completed native
build dependency graph: Shiny, ADMesh, Anti-Grain Geometry, ankerl
`unordered_dense`, Clipper 6.4.2, fast_float, SGI GLU libtess, Dear ImGui,
libigl, libnest2d, MCUT, miniz, NanoSVG, Qhull, QOI, semver.c, and tk spline.
Each is represented separately in the CycloneDX SBOM and mapped to its exact
license or attribution source in the offline bundle. A newly observed vendored
source directory fails packaging until its license policy is reviewed.

This software is based in part on the work of the Independent JPEG Group.

`libc++_shared.so` is staged from the same pinned NDK at build time. The build
script validates that the produced ELF is AArch64, that every load segment is
16 KB aligned, and that it has no unexpected dynamic-library dependency.
