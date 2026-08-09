# Releasing DuckySlicer

Tagged releases are built from source by GitHub Actions, signed without checking
keys into the repository, verified for Android 16 KB page compatibility, and
published with a CycloneDX SBOM of the resolved Android, Rust, and native
dependency graph, a reviewed license expression for every component, and a
build-provenance attestation. The unsigned APK is assembled twice with identical
version inputs, with the second build running clean and without the Gradle build
cache; publication stops unless both files are byte-for-byte identical. Every
release also carries a deterministic recursive source archive and detached source
manifest containing the root commit, runtime and engine submodule commits, native
download pins, and hashes of critical build inputs. Every external GitHub Action
is pinned to an immutable commit. The build job has no signing secrets and produces
an unsigned candidate. A separate protected job that does not check out or execute
project code signs the candidate, verifies the public certificate fingerprint, and
removes its temporary keystore before uploading the signed artifact. The publish job
cannot run until that exact signed APK passes the complete ARM64 Android device suite
and cold-launches on the emulator.
Gradle plug-ins, module metadata, and library artifacts are resolved from a checked-in
lock and must match the reviewed SHA-256 verification metadata.

## One-time repository setup

Create an Android signing key outside the repository. In GitHub, create a protected
environment named `release`, restrict it to release tags, and configure required
reviewers when the repository plan supports them. Store these encrypted environment
secrets there, not as build-job environment variables:

- `DUCKYSLICER_KEYSTORE_BASE64`: base64-encoded keystore bytes
- `DUCKYSLICER_STORE_PASSWORD`: keystore password
- `DUCKYSLICER_KEY_ALIAS`: signing alias
- `DUCKYSLICER_KEY_PASSWORD`: key password

Export the public signing certificate and calculate its SHA-256 fingerprint:

```shell
keytool -exportcert -keystore /secure/path/duckyslicer-release.jks \
  -alias <release-alias> | openssl dgst -sha256
```

Add the normalized 64-character result as the `release` environment variable
`DUCKYSLICER_SIGNING_CERT_SHA256`. This value is public identity metadata, not a
secret. The isolated signer refuses to publish an APK from any other key.

Back up the keystore and passwords in an appropriate secret manager. Losing the
key prevents publishing a compatible update under the same Android identity.

## Release procedure

1. Ensure the Android workflow passes on the intended commit.
2. Review the source diff, dependency changes, license notices, and generated
   profile-catalog counts.
3. Create and push an annotated SemVer tag such as `v0.2.0` or `v0.2.0-rc.1`.
4. Approve the protected `release` environment when prompted, then wait for all four
   **Release APK** stages: unsigned build, isolated sign, ARM64 device tests, and
   publish. A signing mismatch or failed device test intentionally leaves no GitHub
   Release behind.
5. Verify that the GitHub release contains the ARM64 APK, CycloneDX JSON, recursive
   `source.tar.gz`, detached `SOURCE-MANIFEST.json`, and `SHA256SUMS` file. Check the
   hashes, run the source-bundle verifier, confirm every SBOM component has one
   license expression, confirm the in-app third-party view contains the complete
   offline license bundle, and confirm the provenance attestation is visible.
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
python3 -m unittest tools.test_verify_apk tools.test_verify_gradle_supply_chain tools.test_verify_slice_storage
python3 tools/verify_apk.py android/app/build/outputs/apk/release/app-release-unsigned.apk
python3 tools/verify_gradle_supply_chain.py
python3 tools/verify_slice_storage.py
python3 tools/verify_runtime_resilience.py
python3 tools/verify_workflows.py
```

To reproduce the unsigned release locally, use the same `versionName` and positive
`versionCode` for both builds, copy the first APK outside `android/app/build`, then
clean and rebuild with `--no-build-cache`:

```shell
python3 tools/verify_reproducible_release.py \
  /path/to/first-app-release-unsigned.apk \
  android/app/build/outputs/apk/release/app-release-unsigned.apk
```

Generate and verify the same recursive corresponding-source artifacts used by CI:

```shell
python3 tools/generate_source_bundle.py 0.2.0 42 \
  /tmp/DuckySlicer-0.2.0-source.tar.gz \
  /tmp/DuckySlicer-0.2.0-SOURCE-MANIFEST.json
python3 tools/generate_source_bundle.py --verify \
  /tmp/DuckySlicer-0.2.0-source.tar.gz \
  /tmp/DuckySlicer-0.2.0-SOURCE-MANIFEST.json
```

The generator reads committed Git objects and requires every recursive submodule to
be initialized at its recorded gitlink. Local AI instruction files are intentionally
excluded because they are neither build inputs nor corresponding source.

Keep the four signing variables unset during the local build so Gradle produces an
unsigned candidate matching the build job. Never move the signing secrets back into
that job or publish its unsigned artifact. The structural verifier rejects unexpected
ABIs or native libraries, compressed or misaligned native entries, ELF LOAD segments
below 16 KB alignment, unsafe or duplicate ZIP paths, and missing or legacy profile
catalogs.
