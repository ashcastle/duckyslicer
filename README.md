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
- Full-layer preview by default, with a two-handle slider for choosing a visible layer range
- Depth-tested extrusion beads with dark side faces, role-colored outlines, and adjustable height shading for outer walls, inner walls, infill, solid surfaces, support, bridges, and bed adhesion
- Orca role preservation from generated G-code through the Rust parser, with regression tests that require distinct outer- and inner-wall paths
- Layer-aware preview detail that preserves complete representative perimeter loops instead of punching gaps through walls
- Working move, rotate, scale, center, reset, duplicate, arrange, and remove controls
- A pinned Orca-derived catalog with 785 printer variants, 3,306 filament presets, and 2,140 slicing processes
- Orca process fidelity for independent outer/inner walls, sparse infill, internal solid, top surface, and support speeds and line widths, plus feature accelerations
- Orca bridge, gap-fill, first-layer solid, shell-thickness, feature-flow, and support-interface settings preserved through profiles, projects, and on-device G-code
- Orca sparse, top, bottom, and internal-solid patterns preserved independently—including crosshatch—along with seam, ironing, overhang-unit, and support-pattern semantics
- Legacy and current Orca wall/infill order, infill-to-wall bonding, combined infill, bridge density/thickness, and feature-relative speed and acceleration units preserved end to end
- Orca dimensional compensation, vertical-shell policy, gap-fill targeting, infill direction/anchoring, and unsupported-bridge limits preserved in bundled and saved profiles
- Orca wall-crossing avoidance, infill retraction, small-perimeter tuning, seam/wipe behavior, wall direction, and toolpath resolution preserved end to end
- Orca partial-top-surface thresholds, outer/inner bridge directions and filtering, bridge reinforcement, overhang reversal, counterbore bridging, and alternating extra walls preserved end to end
- Searchable printer, filament, and slicing selectors with collapsible brand and personal-profile groups
- Printer compatibility filtering, validated profile inheritance, and unsafe-entry rejection during the build
- Editable settings and schema-versioned named user profiles saved entirely in app-private storage
- Bottom navigation on phones and a space-saving vertical rail on tablets
- English defaults with Korean device-language localization
- A distinct DuckySlicer identity built around the duck mark, yellow accents, and charcoal surfaces
- Optional OctoPrint and Klipper/Moonraker status, upload, start, pause, resume, and cancel controls
- Printer access keys encrypted with Android Keystore; unencrypted connections limited to local addresses
- No account, cloud dependency, analytics SDK, or Bambu network plug-in
- 16 KB page-size-compatible ARM64 native libraries for current Android devices

The device test suite uses a repository geometry fixture and verifies Rust mesh
inspection, native slicing, non-empty G-code, all generated layers, extrusion paths
with real Z coordinates, project recovery through native reinspection, and semantic
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
        ├── Rust JNI: input validation, streaming STL transforms,
        │             G-code role/range parsing, preview data
        │
        └── Native slicer runtime: model loading, slicing,
                                   G-code generation
```

New DuckySlicer-owned native code should prefer Rust. Inherited slicing algorithms
remain C++ while they are isolated behind a narrow boundary. The inherited runtime,
engine, and every native dependency are pinned to reviewed revisions; the APK build
rebuilds the runtime from source and verifies ARM64 identity, 16 KB ELF alignment,
and its dynamic-library allowlist before packaging it.

## Build the APK

Requirements:

- JDK 17
- Android SDK 36
- Android NDK 28.2.13676358
- Rust with the `aarch64-linux-android` target
- `cargo-ndk`
- Python 3.11 or newer for profile and SBOM generation
- CMake, Ninja, Git, Curl, Tar, and Make

```shell
git submodule update --init --recursive
cd android
./gradlew assembleDebug
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

DuckySlicer is distributed under the [GNU Affero General Public License v3](LICENSE).
Inherited source lineage, binary provenance, and corresponding source locations are
recorded in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

See [CONTRIBUTING.md](CONTRIBUTING.md) for development and validation rules,
[SECURITY.md](SECURITY.md) for private vulnerability reporting, and
[docs/RELEASING.md](docs/RELEASING.md) for the signed release process.
