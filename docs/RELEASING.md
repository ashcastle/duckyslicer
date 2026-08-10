# Releasing DuckySlicer

The GitHub Release APK is built only on the maintainer's machine. GitHub Actions
never builds the GitHub Release APK. A GitHub Release contains exactly one public
asset: the signed ARM64 APK.

`tools/prepare_local_release.py` runs the complete local gate, assembles the unsigned
APK twice with identical version inputs, starts both builds clean with the Gradle
build cache disabled, and rejects any byte difference. It also verifies
the package identity, unsigned state, APK structure, and 16 KB alignment, then records
the SHA-256 and exact source commit in local release metadata.

The manually dispatched GitHub workflow only validates those pinned inputs, signs in
the protected `release` environment, and publishes. GitHub exposes drafts only to a
token with push access, so validation receives `contents: write` but contains only
read-only release commands, has no signing key, and never checks out repository code.
Signing has no repository checkout or Release permission; publishing has no signing
key. The tagged repository and its recursive submodule pins are the durable
corresponding source.

GitHub Actions does not run an Android emulator. The local preparation command runs
the functional suite on the Android 15 ARM64 16 KB `DuckySlicer_16KB_API35` AVD.
Pull-request CI remains independent static evidence and is never a release build.

## One-time repository setup

Create an Android signing key outside the repository. In GitHub, create a protected
environment named `release` and configure required reviewers when the repository
plan supports them. Because the signing workflow is manually dispatched from `main`,
allow only the protected `main` branch in the environment deployment rules. Store
these encrypted environment secrets there, not as build-job environment variables:

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

1. Switch to a clean `main` and initialize recursive submodules. The preparation
   command fetches `origin/main` itself and refuses a stale or divergent checkout.
2. Choose a SemVer and a positive Android `versionCode` greater than every previously
   released build, then prepare the candidate locally:

   ```shell
   python3 tools/prepare_local_release.py \
     --version 0.2.0-rc.1 \
     --version-code 5
   ```

3. Review the generated `LOCAL-RELEASE.json`, source diff, dependency changes, license
   notices, and profile catalog. Perform an offline import, slice, full-layer preview,
   and G-code export smoke test with the locally installed Debug APK.
4. Create and push an annotated `v<version>` tag at the exact `sourceCommit` recorded
   in the metadata. Create an unpublished draft GitHub Release for that tag containing
   exactly the recorded unsigned APK; mark it as a prerelease when the SemVer has a
   prerelease suffix.
5. Dispatch `sign-local-release.yml` from `main` with the recorded tag, asset name,
   SHA-256, versionCode, and source commit. The workflow rejects every other ref;
   approve the protected `release` environment.
6. The `validate` job checks the draft, tag commit, digest, package name, versionCode,
   versionName, unsigned state, and 16 KB alignment. The isolated `sign` job signs
   those exact bytes. The `publish` job rechecks the draft and tag, replaces the
   unsigned asset, and publishes only the signed APK.
7. Verify that the GitHub Release contains exactly one asset and that its certificate
   fingerprint matches the pinned release key. Install it on a supported ARM64 device
   and repeat the offline smoke test before announcing the release.

## Play Console bundle handoff

Google Play receives an Android App Bundle, but the public GitHub Release remains
APK-only. `tools/prepare_local_play_bundle.py` runs the complete local ARM64 16 KB
gate, builds the unsigned AAB and its universal delivery APK twice from clean inputs
with the Gradle build cache disabled, and rejects any byte difference. The delivery
APK is checked for package and version identity, unsigned state, ARM64-only native
libraries, and 16 KB alignment. It remains local and is never uploaded.

GitHub never builds the Play AAB. The manually dispatched **Sign Local Play Bundle**
workflow only downloads a digest-pinned AAB from a private draft, validates its source
tag and native structure, signs it in the protected `play` environment, and removes
the private draft while retaining the source tag. It uses a separate Play upload key
instead of the GitHub APK release key and never uploads to Play Console. The operator
downloads the `duckyslicer-play-signed` Actions artifact, checks its SHA-256 file, and
uploads the AAB manually.

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

For each Play candidate:

1. From a clean, synchronized `main`, choose a SemVer and a positive, previously
   unused `versionCode` greater than every version already uploaded to Play and no
   greater than `2100000000`. Prepare the candidate locally:

   ```shell
   python3 tools/prepare_local_play_bundle.py \
     --version 0.2.0-rc.1 \
     --version-code 5
   ```

2. Review
   `build/local-play/0.2.0-rc.1-5/DuckySlicer-0.2.0-rc.1-LOCAL-PLAY.json`. Create and push the exact
   `transportTag` at its `sourceCommit`, then create an unpublished draft containing
   exactly its `unsignedAsset`:

   ```shell
   git tag -a play-v0.2.0-rc.1-5 <sourceCommit> \
     -m "DuckySlicer Play 0.2.0-rc.1 source"
   git push origin play-v0.2.0-rc.1-5
   gh release create play-v0.2.0-rc.1-5 \
     build/local-play/0.2.0-rc.1-5/DuckySlicer-0.2.0-rc.1-play-unsigned.aab \
     --draft --verify-tag \
     --title "Private Play transport 0.2.0-rc.1 (5)" \
     --notes "Temporary unsigned Play signing input."
   ```

3. Dispatch `play-bundle.yml` from `main` with the six exact metadata values:
   `versionName`, `versionCode`, `sourceCommit`, `transportTag`, `unsignedAsset`, and
   `unsignedSha256`. Approve the protected `play` environment. The validator and
   signer each recheck the unsigned digest; neither checks out or executes repository
   code.
4. Download `duckyslicer-play-signed`, verify its `.sha256` file, and retain the
   workflow run URL with the release record. The cleanup job removes the private
   draft even when signing fails after validation, but deliberately keeps the tag as
   durable corresponding-source identity.
5. Install the locally verified universal delivery APK for the final offline smoke
   test. Upload only the signed AAB to Play Console. Track selection, release notes,
   review, staged rollout, and rollback remain explicit Console actions.

Before each Play upload, verify the public privacy-policy URL is reachable:
`https://github.com/ashcastle/duckyslicer/blob/main/PRIVACY.md`. The same bilingual
policy must open offline from **Settings > Data & privacy > Privacy policy** in the
candidate app. For the current official build, the Data safety answers are **No** for
both collection and sharing: app data remains on-device, and optional exports or
printer transfers go directly to a destination chosen by the user rather than to the
DuckySlicer project. Re-evaluate those answers and update the policy before adding any
account, telemetry, crash-reporting, advertising, hosted service, new permission, or
new data destination. The policy, Data safety form, store listing, and actual release
behavior must agree.

Use the current Google Play
[Data safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)
and [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en)
when completing that review; do not rely on a previous Console submission after app
behavior or Play definitions change.

## Local release output

The default output directory is `build/local-release/<version>/`. It contains the
unsigned APK and a small `LOCAL-RELEASE.json` with `versionName`, `versionCode`,
`sourceCommit`, `unsignedAsset`, and `unsignedSha256`. Both files are ignored by Git.

The command refreshes `origin/main`, then refuses a dirty checkout, a branch other
than `main`, a mismatch with `origin/main`, unpinned recursive submodules, existing
output files, or any local or hosted signing variable. It never reads a keystore and
never uploads or publishes.
The unsigned draft asset is temporary; the public Release must never expose it.

The Play preparation output is `build/local-play/<version>-<versionCode>/`. It
contains the reproducible unsigned AAB, the reproducible universal unsigned delivery
APK, and `DuckySlicer-<version>-LOCAL-PLAY.json`. The metadata pins the package,
version, source commit,
transport tag, filenames, and both SHA-256 values. These files are ignored by Git.
The Play command applies the same clean-checkout and signing-variable refusal rules
as the APK command and never creates a tag, draft, workflow run, or Console release.

To obtain the complete corresponding source, clone the tagged repository with
`--recurse-submodules`. Local AI instruction files are not build inputs and remain
excluded from source-generation tooling and version control.
