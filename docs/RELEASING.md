# Releasing DuckySlicer

Tagged releases are built from source by GitHub Actions, signed without checking
keys into the repository, verified for Android 16 KB page compatibility, and
published with a CycloneDX SBOM of the resolved Android, Rust, and native
dependency graph and a build-provenance attestation. Every external GitHub Action
is pinned to an immutable commit. The publish job cannot run until the signed build
finishes, the matching release-candidate test APK passes the complete ARM64 Android
device suite, and the signed minified APK installs and cold-launches on that emulator.
Gradle plug-ins, module metadata, and library artifacts are resolved from a checked-in
lock and must match the reviewed SHA-256 verification metadata.

## One-time repository setup

Create an Android signing key outside the repository and add these encrypted
GitHub Actions secrets:

- `DUCKYSLICER_KEYSTORE_BASE64`: base64-encoded keystore bytes
- `DUCKYSLICER_STORE_PASSWORD`: keystore password
- `DUCKYSLICER_KEY_ALIAS`: signing alias
- `DUCKYSLICER_KEY_PASSWORD`: key password

Back up the keystore and passwords in an appropriate secret manager. Losing the
key prevents publishing a compatible update under the same Android identity.

## Release procedure

1. Ensure the Android workflow passes on the intended commit.
2. Review the source diff, dependency changes, license notices, and generated
   profile-catalog counts.
3. Create and push an annotated SemVer tag such as `v0.2.0` or `v0.2.0-rc.1`.
4. Wait for all three **Release APK** jobs: build, ARM64 device tests, and publish.
   A failed device test intentionally leaves no GitHub Release behind.
5. Verify that the GitHub release contains the ARM64 APK and CycloneDX JSON, and
   that the provenance attestation is visible.
6. Install the release APK on a supported ARM64 device and perform an offline
   import, slice, full-layer preview, export, and optional printer upload smoke
   test before announcing the release.

The workflow derives `versionName` from the tag and uses the GitHub run number as
the monotonically increasing Android `versionCode`.

## Local unsigned release check

```shell
cd android
./gradlew --dependency-verification=strict \
  :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
cd ..
python3 -m unittest tools.test_verify_apk tools.test_verify_gradle_supply_chain
python3 tools/verify_apk.py android/app/build/outputs/apk/release/app-release-unsigned.apk
python3 tools/verify_gradle_supply_chain.py
python3 tools/verify_workflows.py
```

Without the four signing environment variables Gradle intentionally produces an
unsigned local release. Never weaken the release workflow to accept an unsigned
artifact. The structural verifier rejects unexpected ABIs or native libraries,
compressed or misaligned native entries, ELF LOAD segments below 16 KB alignment,
unsafe or duplicate ZIP paths, and missing or legacy profile catalogs.
