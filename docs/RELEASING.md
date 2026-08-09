# Releasing DuckySlicer

Tagged releases are built from source by GitHub Actions, signed without checking
keys into the repository, and verified for Android 16 KB page compatibility. A
GitHub Release contains exactly one public asset: the signed ARM64 APK. Its build-
provenance attestation is bound to that APK alone.

The workflow still generates a CycloneDX SBOM, reviewed dependency inventory,
deterministic recursive source archive, detached source manifest, and checksum
manifest as build evidence. Those files are verified inside the workflow and kept
as short-lived Actions artifacts; they are not GitHub Release downloads. The tagged
repository and its recursive submodule pins remain the durable corresponding source.

The unsigned APK is assembled twice with identical version inputs, with the second
build running clean and without the Gradle build cache; publication stops unless
both files are byte-for-byte identical. Every external GitHub Action is pinned to an
immutable commit. The build job has no signing secrets. A separate protected job
that does not check out or execute project code signs the candidate, verifies the
public certificate fingerprint, and removes its temporary keystore before artifact
upload.

GitHub Actions does not run an Android emulator. Before a tag is created, the full
functional suite must pass on the local Android 15 ARM64 16 KB
`DuckySlicer_16KB_API35` AVD. Hosted CI supplies independent build, host-test, lint,
packaging, and static 16 KB evidence; it is not the functional device gate.
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

1. On `DuckySlicer_16KB_API35`, confirm `getconf PAGE_SIZE` is `16384`, run the
   complete connected instrumentation suite against the intended commit, then
   perform an offline import, slice, preview, and G-code export smoke test.
2. Ensure the Android workflow passes on the intended commit.
3. Review the source diff, dependency changes, license notices, and generated
   profile-catalog counts.
4. Create and push an annotated SemVer tag such as `v0.2.0` or `v0.2.0-rc.1`.
5. Approve the protected `release` environment when prompted, then wait for the
   unsigned build, isolated sign, and publish stages. A reproducibility, signing,
   source-verification, or packaging failure intentionally leaves no GitHub Release.
6. Verify that the GitHub Release contains exactly one asset, the ARM64 APK. Confirm
   its pinned signing-certificate fingerprint, structural verifier result, and
   build-provenance attestation. Review the SBOM, source archive, detached source
   manifest, and checksum results in the workflow evidence.
7. Install the release APK on a supported ARM64 device and perform an offline
   import, slice, full-layer preview, export, and optional printer upload smoke
   test before announcing the release.

The workflow derives `versionName` from the tag and uses the GitHub run number as
the monotonically increasing Android `versionCode`.

## Play Console bundle handoff

Google Play receives an Android App Bundle, but the public GitHub Release remains
APK-only. The manually dispatched **Play Bundle** workflow builds an unsigned AAB
from the selected commit and signs it in a separate `play` environment. It uses a
separate Play upload key instead of the GitHub APK release key and never uploads to
Play Console. The operator downloads the `duckyslicer-play-signed` Actions artifact,
checks its SHA-256 file, and uploads the AAB manually after completing the local
16 KB AVD release gate.

Create an RSA 2048-bit-or-stronger upload key outside the repository, register its
public certificate in Play Console, and protect the `play` environment with required
reviewers. Configure only these environment secrets:

- `DUCKYSLICER_PLAY_KEYSTORE_BASE64`
- `DUCKYSLICER_PLAY_STORE_PASSWORD`
- `DUCKYSLICER_PLAY_KEY_ALIAS`
- `DUCKYSLICER_PLAY_KEY_PASSWORD`

Add the normalized 64-character upload-certificate fingerprint as the public
`DUCKYSLICER_PLAY_CERT_SHA256` environment variable. Keep the upload key separate
from the Play-managed app signing key and the GitHub APK signing key.

When dispatching the workflow, provide a SemVer `version_name` and a positive,
previously unused `version_code` that is greater than every version already uploaded
to Play and no greater than `2100000000`. The workflow stops after producing the
signed Actions artifact; Play track selection, release notes, review, and rollout
remain explicit Console actions.

## Local unsigned release check

```shell
cd android
./gradlew --dependency-verification=strict \
  :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
cd ..
python3 -m unittest discover -s tools -p 'test_*.py'
python3 tools/verify_apk.py android/app/build/outputs/apk/release/app-release-unsigned.apk
python3 tools/verify_gradle_supply_chain.py
python3 tools/verify_slice_storage.py
python3 tools/verify_preview_boundary.py
python3 tools/verify_runtime_resilience.py
python3 tools/verify_data_practices.py
python3 tools/verify_release_contract.py
python3 tools/verify_play_bundle_workflow.py
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
