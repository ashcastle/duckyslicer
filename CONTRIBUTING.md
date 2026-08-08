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
- Treat imported STL and G-code as untrusted. Avoid unbounded line reads or unchecked
  coordinate arithmetic, preserve atomic outputs, and add both host corpus coverage
  and a JNI recovery assertion for new parser failure modes.
- Keep user-facing copy plain and non-technical. `Slice`, `G-code`, printer,
  filament, and process terminology may follow established slicer language.

## Set up the repository

```shell
git clone --recurse-submodules <repository-url>
cd duckyslicer/android
./gradlew --dependency-verification=strict assembleDebug
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
./gradlew --dependency-verification=strict \
  :app:testDebugUnitTest :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:lintDebug

cd ..
python3 -m unittest tools.test_verify_apk tools.test_verify_gradle_supply_chain
python3 tools/verify_apk.py android/app/build/outputs/apk/debug/app-debug.apk
python3 tools/verify_gradle_supply_chain.py
python3 tools/verify_workflows.py
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

Workflow changes must keep third-party Actions pinned to full commit hashes. A
tagged release must preserve the build → isolated sign → ARM64 device test →
publish dependency; publishing an APK before device tests finish is not an
accepted fallback.
The release build job must never receive signing secrets. Only the isolated `sign`
job may use them; it must not check out source or execute Gradle, repository scripts,
or other project code, and it must verify the pinned signing-certificate fingerprint.

## Updating Android dependencies

Gradle dependency changes must update both `android/app/gradle.lockfile` and
`android/gradle/verification-metadata.xml`. Generate trust data from an empty
Gradle user home so previously cached plug-in metadata cannot hide a missing
checksum, then review every new coordinate and checksum before committing it:

```shell
cd android
verification_home="$(mktemp -d)"
GRADLE_USER_HOME="$verification_home" ./gradlew --no-daemon \
  --write-locks --write-verification-metadata sha256 \
  :app:testDebugUnitTest :app:lintRelease :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:assembleRelease

cd ..
python3 tools/verify_gradle_supply_chain.py
git diff -- android/app/gradle.lockfile android/gradle/verification-metadata.xml
```

Do not add trusted-artifact or ignored-key bypasses. If a repository publishes a
new checksum for an existing coordinate, stop and verify the upstream artifact
instead of accepting both values automatically.

## Pull requests

Describe the user-visible outcome, important implementation trade-offs, and the
checks that passed. Include before-and-after screenshots for UI or preview work.
Do not include generated APKs in Git; CI publishes build artifacts.

Security issues must follow [SECURITY.md](SECURITY.md) instead of a public issue.
