<p align="center">
  <img src="docs/assets/duckyslicer-mark.svg" alt="DuckySlicer duck mark" width="128">
</p>

<h1 align="center">DuckySlicer</h1>

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

DuckySlicer is an Android-first 3D-print slicer. It imports STL models, slices them
on-device, previews real G-code layers, and exports G-code without an account,
cloud service, or network permission.

> DuckySlicer is an early MVP, not yet a production release. If you want to follow
> its progress, a ⭐ on the repository is always appreciated.

## What is working

- Fully offline STL import, on-device slicing, preview, and G-code export
- A full-screen print bed that keeps the model and G-code at their real millimetre scale
- One-finger orbit and two-finger pan/zoom for the whole scene
- Full-layer preview by default, with a two-handle slider for choosing a visible layer range
- Outlined role colors plus adjustable height shading for walls, infill, solid surfaces, support, bridges, and bed adhesion
- Adaptive preview detail that stays lighter while the camera is moving and refines after release
- Working move, rotate, scale, center, reset, and remove controls for imported models
- Snapmaker U1 profiles for 0.2, 0.4, 0.6, and 0.8 mm nozzles
- Eight built-in material profiles and twelve matching slicing profiles
- Searchable printer and filament selectors with collapsible brand and personal-profile groups
- Editable settings and named user profiles saved entirely in app-private storage
- Bottom navigation on phones and a space-saving vertical rail on tablets
- English defaults with Korean device-language localization
- A distinct DuckySlicer identity built around the duck mark, yellow accents, and charcoal surfaces
- No Bambu network plug-in and no `INTERNET` permission in the MVP
- 16 KB page-size-compatible ARM64 native libraries for current Android devices

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
        ├── Rust JNI: input validation, streaming STL transforms,
        │             G-code role/range parsing, preview data
        │
        └── Native slicer runtime: model loading, slicing,
                                   G-code generation
```

New DuckySlicer-owned native code should prefer Rust. Inherited slicing algorithms
remain C++ while they are isolated behind a narrow boundary. Rebuilding the current
native runtime reproducibly from the included source baseline remains follow-up work.

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
cd android
./gradlew assembleDebug assembleDebugAndroidTest

MODEL_FILE="/absolute/path/to/model.stl"

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb push "$MODEL_FILE" /data/local/tmp/model-under-test.stl
adb shell run-as com.ashcastle.duckyslicer \
  cp /data/local/tmp/model-under-test.stl files/model-under-test.stl
adb shell am instrument -w -r \
  -e modelName model-under-test.stl \
  com.ashcastle.duckyslicer.test/androidx.test.runner.AndroidJUnitRunner
```

## Languages

The MVP ships English and Korean resources. Future translations will stay within
the inherited language set and reuse established slicing terms.

## License and provenance

DuckySlicer is distributed under the [GNU Affero General Public License v3](LICENSE).
Inherited source lineage, binary provenance, and corresponding source locations are
recorded in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
