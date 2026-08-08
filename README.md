# DuckySlicer

DuckySlicer is an Android-first, on-device 3D-print slicer derived from
[OrcaSlicer](https://github.com/OrcaSlicer/OrcaSlicer). It is designed to import a model,
slice it locally, preview real G-code layers, and export the result without an account,
cloud service, or network connection.

> DuckySlicer is in early MVP development. It is not yet a production release.

## MVP direction

- Android APK with an adaptive phone and tablet interface
- Fully offline STL import, slicing, layer preview, and G-code export
- Full-screen print-bed workspace with one-finger orbit and two-finger pan/zoom
- Bottom navigation for Slice, Preview, Device, Project, and Settings
- Yellow and charcoal visual system with minimal brand decoration
- English as the default language and device-language localization
- No Bambu network plug-in in the offline MVP

The first device build has been validated on an ARM64 Android device with a real STL file.
The current native bootstrap produces G-code on-device; the renderer and the OrcaSlicer 2.4.2
Android runtime migration remain active work.

## Architecture

```text
Jetpack Compose mobile UI
        │
        ├── Rust: input validation, bounded data conversion, preview parsing
        │
        └── OrcaSlicer C++ core: model loading, slicing, G-code generation
```

New DuckySlicer-owned native code should prefer Rust. The inherited slicing algorithms remain
C++ while they are isolated behind a narrow boundary.

## Build the Android APK

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

The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

## Languages

The MVP currently ships English and Korean resources. Future translations will stay within
the language set already supported by OrcaSlicer and will reuse its established slicing terms.

## Source lineage and license

DuckySlicer is based on OrcaSlicer and retains its open-source lineage through Bambu Studio,
PrusaSlicer, and Slic3r. The project is distributed under the GNU Affero General Public License
version 3. See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

OrcaSlicer is a trademark and project of its respective maintainers. DuckySlicer is an
independent fork and is not an official OrcaSlicer release.
