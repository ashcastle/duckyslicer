# DuckySlicer

<p align="center">
  <strong>Slice on your Android device. Keep the whole workflow offline.</strong>
</p>

<p align="center">
  <img alt="Android 8+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&amp;logoColor=white">
  <img alt="ARM64" src="https://img.shields.io/badge/APK-arm64--v8a-F6C945">
  <img alt="Offline" src="https://img.shields.io/badge/network-not%20required-202124">
  <img alt="Rust" src="https://img.shields.io/badge/native%20boundary-Rust-DEA584?logo=rust&amp;logoColor=black">
  <img alt="License AGPL-3.0" src="https://img.shields.io/badge/license-AGPL--3.0-blue">
</p>

DuckySlicer is an Android-first 3D-print slicer derived from
[OrcaSlicer](https://github.com/OrcaSlicer/OrcaSlicer). It imports STL models,
slices them on-device, previews real G-code layers, and exports G-code without an
account, cloud service, or network permission.

> DuckySlicer is an early MVP, not yet a production release. If you want to follow
> its progress, a ⭐ on the repository is always appreciated.

## What is working

- Fully offline STL import, on-device slicing, preview, and G-code export
- A full-screen print bed that keeps the model and G-code at their real millimetre scale
- One-finger orbit and two-finger pan/zoom for the whole scene
- Full-layer preview by default, with a two-handle slider for choosing a visible layer range
- Curated Snapmaker U1 printer, filament, and slicing defaults from OrcaSlicer
- Editable settings and named user profiles saved entirely in app-private storage
- Bottom navigation on phones and a space-saving vertical rail on tablets
- English defaults with Korean device-language localization
- Yellow and charcoal mobile UI with minimal in-app branding
- No Bambu network plug-in and no `INTERNET` permission in the MVP

The physical-device test uses a real 82 MB STL and verifies Rust mesh inspection,
native slicing, non-empty G-code, all generated layers, and extrusion paths with
real Z coordinates.

## Mobile workflow

| Area | Purpose |
| --- | --- |
| **Slice** | Choose built-in or saved printer, filament, and slicing profiles; edit, save, and slice |
| **Preview** | Inspect all layers or narrow the visible range with two slider handles |
| **Device** | Reserved for a future optional device workflow; unavailable in the offline MVP |
| **Project** | See the active model and local G-code state |
| **Settings** | App preferences, including device-language behavior |
| **Top-left menu** | Import a model or export completed G-code |

## Architecture

```text
Jetpack Compose mobile UI
        │
        ├── Rust JNI: input validation, bounded mesh conversion,
        │             G-code range parsing, preview data
        │
        └── Native slicer runtime: model loading, slicing,
                                   G-code generation
```

New DuckySlicer-owned native code should prefer Rust. Inherited slicing algorithms
remain C++ while they are isolated behind a narrow boundary. The bundled MVP runtime
currently reports Snapmaker Orca 2.3.3; rebuilding that runtime from this repository's
OrcaSlicer 2.4.2 source baseline remains follow-up work.

## Build the APK

Requirements:

- JDK 17
- Android SDK 36
- Android NDK 28.2.13676358
- Rust with the `aarch64-linux-android` target
- `cargo-ndk`

```shell
cd android
./gradlew assembleDebug
```

The debug APK is written to:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## Run the physical-device slicing test

The instrumentation fixture is intentionally not stored in Git. Build and install
both APKs, then copy an STL into the debuggable app's `filesDir` before running the
test:

```shell
MODEL_FILE="/absolute/path/to/model.stl"

adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb push "$MODEL_FILE" /data/local/tmp/model-under-test.stl
adb shell run-as com.ashcastle.duckyslicer \
  cp /data/local/tmp/model-under-test.stl files/model-under-test.stl
adb shell am instrument -w -r \
  -e modelName model-under-test.stl \
  com.ashcastle.duckyslicer.test/androidx.test.runner.AndroidJUnitRunner
```

## Languages

The MVP ships English and Korean resources. Future translations will stay within
the language set already supported by OrcaSlicer and reuse its established slicing
terms.

## Source lineage and license

DuckySlicer is based on OrcaSlicer and retains its open-source lineage through
Bambu Studio, PrusaSlicer, and Slic3r. The project is distributed under the
[GNU Affero General Public License v3](LICENSE). Binary provenance and corresponding
source locations are recorded in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

OrcaSlicer is a trademark and project of its respective maintainers. DuckySlicer
is an independent fork and is not an official OrcaSlicer release.
