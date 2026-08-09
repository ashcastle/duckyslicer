<p align="center">
  <img src="docs/assets/duckyslicer-mark.svg" alt="DuckySlicer duck mark" width="128">
</p>

<h1 align="center">DuckySlicer</h1>

<p align="center">
  <strong>Slice on your Android device. Connect to a printer only when you choose.</strong>
</p>

<p align="center">
  <img alt="Android 8+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&amp;logoColor=white">
  <img alt="ARM64" src="https://img.shields.io/badge/APK-arm64--v8a-F6C945">
  <img alt="Offline first" src="https://img.shields.io/badge/workflow-offline--first-202124">
  <img alt="Rust" src="https://img.shields.io/badge/native%20boundary-Rust-DEA584?logo=rust&amp;logoColor=black">
  <img alt="License AGPL-3.0" src="https://img.shields.io/badge/license-AGPL--3.0-blue">
</p>

DuckySlicer is an Android-first 3D-print slicer. It imports STL models, slices them
on-device, previews real G-code layers, and exports G-code without an account,
cloud service, or printer connection. Optional direct connections can send a
finished G-code file to OctoPrint or Klipper/Moonraker.

> DuckySlicer is a pre-release project under active production hardening. If you
> want to follow
> its progress, a ⭐ on the repository is always appreciated.

## What is working

- Fully offline STL import, on-device slicing, preview, and G-code export
- A full-screen print bed that keeps the model and G-code at their real millimetre scale
- One-finger orbit and two-finger pan/zoom for the whole scene
- Tap an object to select it, then drag it directly across the bed
- Import, select, duplicate, arrange, and slice multiple objects in one project, with undo and redo
- Automatic app-private project recovery that keeps imported models, selection, placement, and effective printer, filament, and slicing settings across process restarts
- Last-known-good recovery for project metadata, saved profiles, and printer connections;
  unreadable generations are preserved instead of being replaced by an empty autosave
- Full-layer preview by default, with a two-handle slider for choosing a visible layer range
- Depth-tested extrusion beads with dark side faces, role-colored outlines, adjustable height shading, and tap-to-isolate controls for outer walls, inner walls, infill, solid surfaces, support, bridges, and bed adhesion
- Orca role preservation from generated G-code through the Rust parser, with geometry regression tests that require distinct exterior, cavity-facing, and structural inner-wall paths
- Layer-aware preview detail that preserves complete representative perimeter loops instead of punching gaps through walls
- Working move, rotate, scale, center, reset, duplicate, arrange, and remove controls
- A pinned Orca-derived catalog with 785 printer variants, 3,306 filament presets, and 2,140 slicing processes, streamed from a compact validated binary asset
- Orca process fidelity for independent outer/inner walls, sparse infill, internal solid, top surface, and support speeds and line widths, plus feature accelerations
- Orca bridge, gap-fill, first-layer solid, shell-thickness, feature-flow, and support-interface settings preserved through profiles, projects, and on-device G-code
- Orca sparse, top, bottom, and internal-solid patterns preserved independently—including crosshatch—along with seam, ironing, overhang-unit, and support-pattern semantics
- Legacy and current Orca wall/infill order, infill-to-wall bonding, combined infill, bridge density/thickness, and feature-relative speed and acceleration units preserved end to end
- Orca dimensional compensation, vertical-shell policy, gap-fill targeting, infill direction/anchoring, and unsupported-bridge limits preserved in bundled and saved profiles
- Orca wall-crossing avoidance, infill retraction, small-perimeter tuning, seam/wipe behavior, wall direction, and toolpath resolution preserved end to end
- Orca partial-top-surface thresholds, outer/inner bridge directions and filtering, bridge reinforcement, overhang reversal, counterbore bridging, and alternating extra walls preserved end to end
- Orca Arachne wall transitions, width distribution, minimum feature/wall rules, and independently rendered outer/inner wall roles preserved end to end
- Orca top surface, bottom surface, and internal-solid toolpaths kept as distinct
  preview roles instead of being collapsed into one generic solid fill
- Bounded primitive `FloatArray` preview transfer across JNI, avoiding large G-code
  JSON strings and per-segment JSON objects before GPU rendering
- Orca skirt height/speed, draft shield, brim topology/gap, and raft geometry preserved in bundled, project, and saved profiles
- Searchable printer, filament, and slicing selectors with collapsible brand and personal-profile groups
- Printer compatibility filtering, validated profile inheritance, and unsafe-entry rejection during the build
- Editable settings and schema-versioned named user profiles saved entirely in app-private storage
- Bottom navigation on phones and a space-saving vertical rail on tablets
- English defaults with Korean device-language localization
- A distinct DuckySlicer identity built around the duck mark, yellow accents, and charcoal surfaces
- Optional OctoPrint and Klipper/Moonraker status, upload, start, pause, resume, and cancel controls
- Printer access keys encrypted with Android Keystore; unencrypted connections limited to local addresses
- Bounded, depth-checked printer responses, disabled HTTP redirects, constrained credentials,
  G-code sizes, and returned paths, with connections closed on every failure
- No account, cloud dependency, analytics SDK, or Bambu network plug-in
- Fail-closed STL and G-code handling with bounded text lines, finite coordinate checks, atomic transformed-model writes, and recoverable Rust-failure containment at the JNI boundary
- A non-exported, restartable Android worker process for the inherited Orca C++ runtime, so a native signal ends the slice instead of the app
- Cancelable on-device slicing on a dedicated worker thread; cancellation terminates
  only the isolated Orca process and the next slice starts in a fresh worker
- Distinct, synchronized G-code artifacts for successful slices instead of one shared output file being overwritten
- Native G-code writes hard-limited to 1 GiB with `RLIMIT_FSIZE` inside the isolated worker, with only a
  256 KiB compatibility preview cache instead of a second full in-memory copy
- Retained G-code limited to 1 GiB per slice and 1 GiB in total, with a 512 MiB
  free-space reserve, secondary active-generation emergency monitoring, and
  cross-process reader leases protecting preview, export, and printer upload from cleanup
- 16 KB page-size-compatible ARM64 native libraries for current Android devices
- Immutable GitHub Action pins and checksum-verified, version-locked Gradle artifacts;
  releases publish only after the full ARM64 device suite passes
- Byte-for-byte reproducible unsigned release builds and a deterministic recursive
  source archive with exact repository, submodule, patch, and dependency pins

The device test suite uses repository-generated geometry fixtures and verifies Rust mesh
inspection, malformed-input recovery, repair of open, reversed, duplicate, degenerate,
and intersecting facets, native slicing, non-empty G-code, all generated layers, extrusion paths
with real Z coordinates, hollow-solid cavity preservation, project recovery through native reinspection, and semantic
G-code contracts for real Creality/Marlin, Prusa/Marlin 2, and Anycubic/Klipper
profiles. Local
simulated OctoPrint and Moonraker endpoints also verify
authentication, status parsing, uploads, explicit print start, and encrypted key storage.

## Mobile workflow

| Area | Purpose |
| --- | --- |
| **Slice** | Choose built-in or saved printer, filament, and slicing profiles; edit, save, and slice |
| **Preview** | Inspect all layers or narrow the visible range with two slider handles |
| **Device** | Save OctoPrint or Klipper connections, check status, send G-code, and control an active print |
| **Project** | Select any object in the current multi-object project and see local G-code state |
| **Settings** | Preview load, visual contrast, screen behavior, connection timeout, print confirmation, and language behavior |
| **Top-left menu** | Add a model, arrange the project, or export completed G-code |

## Architecture

```text
Jetpack Compose mobile UI
        │
        ├── Direct printer client: OctoPrint and Moonraker HTTP APIs,
        │                          Android Keystore credentials
        │
        ├── Rust JNI in the app process: input validation, streaming STL transforms,
        │                               G-code role/range parsing, preview data
        │
        └── Private Binder worker process (:slicer)
                    └── Native Orca runtime: model loading, slicing,
                                             G-code generation
```

New DuckySlicer-owned native code should prefer Rust. Inherited slicing algorithms
remain C++ while they are isolated behind a narrow boundary. The inherited runtime,
engine, and every native dependency are pinned to reviewed revisions; the APK build
rebuilds the runtime from source and verifies ARM64 identity, 16 KB ELF alignment,
and its dynamic-library allowlist before packaging it. Tagged releases repeat those
checks on the complete APK and are published only after release-candidate device tests.
The Gradle wrapper distribution, Maven metadata, plug-ins, and library artifacts are
also locked to reviewed versions and SHA-256 checksums.
Release builds produce an unsigned candidate without access to signing secrets. A
separate protected job—with no source checkout or project build execution—signs it,
checks the public certificate fingerprint, and hands that exact APK to device tests
and publication. Before signing, CI performs a clean second assembly without the
Gradle build cache and requires both unsigned APKs to have identical bytes. Each
release also publishes a deterministic recursive source archive and detached source
manifest; unlike GitHub's automatic source ZIP, it contains the pinned runtime and
engine submodules and records every externally fetched native input.

## Build the APK

Requirements:

- JDK 17
- Android SDK 36
- Android NDK 28.2.13676358
- Rust 1.91.1 with the `aarch64-linux-android` target
- `cargo-ndk`
- Python 3.11 or newer for profile and SBOM generation
- CMake, Ninja, Git, Curl, Tar, and Make

```shell
git submodule update --init --recursive
cd android
./gradlew --dependency-verification=strict assembleDebug
```

The first build compiles the pinned headless slicer engine and its native dependencies
and can take a while. Later builds reuse `build/native-slicer`; set
`DUCKYSLICER_NATIVE_JOBS` to choose a conservative parallel job count for your machine.
The reviewed output is stripped to roughly APK-ready size before Gradle stages it.

The debug APK is written to:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## Run the device slicing tests

The test APK includes a small public-domain-style geometry fixture from the repository,
so the complete on-device suite runs with one command:

```shell
cd android
./gradlew connectedDebugAndroidTest
```

Pass `-Pandroid.testInstrumentationRunnerArguments.modelName=<asset-name>` to select
another fixture bundled into the test APK.

## Languages

The current app ships English and Korean resources. Future translations will stay within
the inherited language set and reuse established slicing terms.

## License and provenance

DuckySlicer is distributed under the [GNU Affero General Public License v3](LICENSE.txt).
Inherited source lineage, binary provenance, and corresponding source locations are
recorded in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Every APK includes
an offline, component-indexed copy of all resolved Android, Rust, native, and
vendored license texts under **Settings → About → Third-party notices**. Release
SBOM generation fails if a component has no reviewed license expression. Official
releases include the APK, CycloneDX SBOM, recursive `source.tar.gz`, detached source
manifest, and one checksum file covering all four artifacts.

See [CONTRIBUTING.md](CONTRIBUTING.md) for development and validation rules,
[SECURITY.md](SECURITY.md) for private vulnerability reporting, and
[docs/RELEASING.md](docs/RELEASING.md) for the signed release process.
