<p align="center">
  <img src="docs/assets/duckyslicer-mark.svg" alt="DuckySlicer duck mark" width="128">
</p>

<h1 align="center">DuckySlicer</h1>

<p align="center">
  <strong>Slice on Android, on-device and offline.</strong>
</p>

<p align="center">
  <img alt="Android 8+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&amp;logoColor=white">
  <img alt="ARM64" src="https://img.shields.io/badge/APK-arm64--v8a-F6C945">
  <img alt="Offline first" src="https://img.shields.io/badge/workflow-offline--first-202124">
  <img alt="License AGPL-3.0" src="https://img.shields.io/badge/license-AGPL--3.0-blue">
</p>

DuckySlicer imports STL models, slices them on the device, previews real G-code
layers, and exports G-code without an account or cloud service. Optional direct
connections support OctoPrint and Klipper/Moonraker.

> DuckySlicer is an alpha project under active development. If you want to follow
> the project, a ⭐ on the repository helps others find it.

## Highlights

- Offline STL import, multi-object projects, automatic arrangement, support painting,
  on-device slicing, full-layer preview, and G-code export
- Real-size print beds with original printable-area polygons and machine origins
- One-finger orbit, two-finger pan/zoom, and direct object selection and movement
- Searchable Orca-derived printer, filament, and slicing profile catalogs, grouped by brand
- A persistent **Recent** group for the last-used printer, filament, and slicing profiles
- Editable named profiles with Orca-style **Quality**, **Strength**, **Speed**, **Support**,
  and **Others** sections
- Role-colored toolpaths, height shading, two-handle layer ranges, and visibility controls
- Phone bottom navigation and a space-saving tablet navigation rail
- English defaults with Korean device-language localization
- ARM64 native libraries compatible with Android's 16 KB page-size requirement

## App layout

| Area | Purpose |
| --- | --- |
| **Slice** | Select and edit profiles, arrange objects, paint support, and slice |
| **Preview** | Inspect all layers or narrow the visible range |
| **Device** | Send G-code and control an OctoPrint or Klipper printer |
| **Project** | Manage objects and local project state |
| **Settings** | Adjust preview, display, connection, and app behavior |

## Install

Download the signed ARM64 APK from [GitHub Releases](https://github.com/ashcastle/duckyslicer/releases).
Android may ask you to allow installation from the browser or file manager used to
open the APK.

## Build

Requirements: JDK 17, Android SDK 36, Android NDK 28.2.13676358, Rust 1.91.1,
`cargo-ndk`, Python 3.11+, CMake, Ninja, Git, Curl, Tar, and Make.

```shell
git submodule update --init --recursive
cd android
./gradlew --dependency-verification=strict assembleDebug
```

The first build compiles the pinned headless slicing runtime and can take a while.
The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

Run the on-device suite with:

```shell
cd android
./gradlew connectedDebugAndroidTest
```

## Engineering notes

The inherited slicing engine remains C++ and runs in a private, restartable Android
worker process. New DuckySlicer native boundaries prefer Rust. Preview data crosses JNI
as a bounded `FloatArray`; direct-memory meshes and scene-stable OpenGL VBO uploads let
Automatic preview detail avoid retransmitting geometry during camera gestures.

Generated G-code is retained within bounded app-private storage and protected by
cross-process reader leases during preview, export, and printer upload. Native output is
hard-limited with `RLIMIT_FSIZE`. Release CI rebuilds pinned sources, verifies 16 KB ELF
alignment, signs in an isolated job, and gates publication on ARM64 device tests.

## License

DuckySlicer is distributed under the [GNU Affero General Public License v3](LICENSE.txt).
Source lineage and binary provenance are recorded in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Every APK includes offline third-party
notices, and releases include checksums, an SBOM, and the corresponding source archive.

See [CONTRIBUTING.md](CONTRIBUTING.md), [SECURITY.md](SECURITY.md), and
[docs/RELEASING.md](docs/RELEASING.md) for development, reporting, and release details.
