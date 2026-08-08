# Contributing to DuckySlicer

Thanks for helping build an offline-first Android slicer. Contributions should
keep the mobile workflow clear, preserve the inherited slicing behavior, and
avoid introducing an account or cloud requirement.

## Before opening a change

- Search existing issues and keep each pull request focused on one problem.
- Do not commit models, G-code, printer credentials, signing material, local AI
  instructions, or private design notes.
- Preserve attribution and license notices for inherited or third-party code.
- Prefer Rust for new native safety boundaries and parsers. Changes to the
  inherited C++ slicing engine should be narrowly scoped and covered by a
  regression test.
- Keep user-facing copy plain and non-technical. `Slice`, `G-code`, printer,
  filament, and process terminology may follow established slicer language.

## Set up the repository

```shell
git clone --recurse-submodules <repository-url>
cd duckyslicer/android
./gradlew assembleDebug
```

The first build reconstructs the pinned ARM64 slicer runtime from source and can
take a while. The complete prerequisites are listed in [README.md](README.md).

## Validate a change

Run the narrow checks that cover your change, then the full local gate before a
pull request:

```shell
cd rust/duckyslicer-jni
cargo fmt --check
cargo test --locked
cargo clippy --locked -- -D warnings

cd ../../android
./gradlew :app:testDebugUnitTest :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:lintDebug
```

With an ARM64 Android device or emulator connected:

```shell
cd android
./gradlew :app:connectedDebugAndroidTest
```

Preview changes should be checked with outer walls, inner walls, sparse infill,
solid surfaces, support, bridges, multiple layer heights, and a dense model. The
default depth renderer and the low-power compatibility renderer must both remain
usable.

## Pull requests

Describe the user-visible outcome, important implementation trade-offs, and the
checks that passed. Include before-and-after screenshots for UI or preview work.
Do not include generated APKs in Git; CI publishes build artifacts.

Security issues must follow [SECURITY.md](SECURITY.md) instead of a public issue.
