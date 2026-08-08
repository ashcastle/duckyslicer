# Releasing DuckySlicer

Tagged releases are built from source by GitHub Actions, signed without checking
keys into the repository, verified for Android 16 KB page compatibility, and
published with a CycloneDX SBOM of the resolved Android, Rust, and native
dependency graph and a build-provenance attestation.

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
3. Create and push an annotated SemVer tag such as `v0.2.0`.
4. Wait for the **Release APK** workflow to finish.
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
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

Without the four signing environment variables Gradle intentionally produces an
unsigned local release. Never weaken the release workflow to accept an unsigned
artifact.
