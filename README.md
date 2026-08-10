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

DuckySlicer is an offline Android slicer for importing models, preparing print
jobs, previewing layers, and exporting G-code directly on your device.

> DuckySlicer is an alpha project under active development. If you want to follow
> the project, a ⭐ on the repository helps others find it.

## Features

- On-device slicing and G-code preview
- Printer, filament, and slicing profiles
- Multi-object projects, automatic arrangement, and support painting
- Touch-first phone and tablet interface
- Optional OctoPrint and Klipper/Moonraker connections
- 22 app languages, with English as the default

## Install

Download the signed ARM64 APK from [GitHub Releases](https://github.com/ashcastle/duckyslicer/releases).
Android may ask you to allow installation from the browser or file manager used to
open the APK.

## Build

Requirements: JDK 17, Android SDK 36, Android NDK 28.2, Rust, `cargo-ndk`,
CMake, Ninja, and Git.

```shell
git submodule update --init --recursive
cd android
./gradlew --dependency-verification=strict assembleDebug
```

The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

## License

DuckySlicer is distributed under the [GNU Affero General Public License v3](LICENSE.txt).
See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for third-party notices.
