# Releasing DuckySlicer

The GitHub Release APK is built only on the maintainer's machine. GitHub Actions
never builds the GitHub Release APK. A GitHub Release contains exactly one public
asset: the signed ARM64 APK.

`tools/prepare_local_release.py` runs the complete local gate, assembles the unsigned
APK twice with identical version inputs, starts both builds clean with the Gradle
build cache disabled, and rejects any byte difference. It also verifies
the package identity, unsigned state, APK structure, and 16 KB alignment, then records
the SHA-256 and exact source commit in local release metadata. It also preserves a
reproducible `LOCAL-R8-MAPPING` file and `LOCAL-NATIVE-SYMBOLS` archive, with their
digests, for diagnosing that exact build. These local support files must not be
uploaded to the public GitHub Release.

The manually dispatched GitHub workflow only validates those pinned inputs, signs in
the protected `release` environment, and publishes. GitHub exposes drafts only to a
token with push access, so validation receives `contents: write` but contains only
read-only release commands, has no signing key, and never checks out repository code.
Signing has no repository checkout or Release permission; publishing has no signing
key. The tagged repository and its recursive submodule pins are the durable
corresponding source.

GitHub Actions does not run an Android emulator. The local preparation command runs
the functional suite on the Android 16/API 36 ARM64 16 KB
`DuckySlicer_16KB_API36` AVD. The preparer refuses API 35 even though the ordinary
development gate accepts it.
Pull-request CI remains independent static evidence and is never a release build.

## One-time repository setup

Create `DuckySlicer_16KB_API36` in Android Studio Device Manager from an Android 16,
API 36, Google APIs ARM64 system image configured for 16 KB pages. Boot it and verify
that `adb shell getconf PAGE_SIZE` reports `16384` before preparing either release
format. An API 35 16 KB AVD may remain installed for development regression testing.

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

Before starting, run
`python3 tools/audit_release_readiness.py --target github`. It reports repository
synchronization, exact-source GitHub CI, a representative physical ARM64 device,
and source-bound qualification evidence without changing repository, device, or
GitHub state. Play credentials are outside the GitHub APK release gate; audit the
dormant Play path separately with `--target play` only when Play publishing is an
explicit release goal. Resolve every `BLOCKED` line before preparing a candidate.

1. Switch to a clean `main`, initialize recursive submodules, and run the physical
   rendering/slicing and startup qualifications on an awake, unlocked representative
   ARM64 phone. Retain the physical corpus G-code, then compare all nine cases with a
   verified CLI from the pinned desktop slicing-engine source. The comparison tool configures
   and rebuilds that CLI when its source, compatibility inputs, toolchain, or binary digest
   changes; otherwise it reuses the exact verified build. All three reports are ignored
   local evidence and record the exact source commit; emulators are rejected for the
   physical measurements. Both physical runners refuse a dirty checkout, a non-`main`
   branch, or a source commit that changes while evidence is being collected.

   ```shell
   python3 tools/run_physical_qualification.py \
     --serial <physical-serial> \
     --retain-gcode build/qualification/physical-gcode
   python3 tools/run_startup_qualification.py --serial <physical-serial>
   python3 tools/run_desktop_orca_qualification.py \
     --android-report build/qualification/physical-report.json \
     --android-gcode build/qualification/physical-gcode/simple-part.gcode \
     --output build/qualification/desktop-orca-release
   ```

2. The preparation
   command fetches `origin/main` itself and refuses a stale or divergent checkout.
   Choose a SemVer and a positive Android `versionCode` greater than every previously
   released build, then prepare the candidate locally:

   ```shell
   python3 tools/prepare_local_release.py \
     --version 0.2.0-rc.1 \
     --version-code 5 \
     --physical-report build/qualification/physical-report.json \
     --startup-report build/qualification/startup-report.json \
     --orca-report build/qualification/desktop-orca-release/comparison-report.json
   ```

3. Review the three qualification reports, generated `LOCAL-RELEASE.json`, source diff,
   dependency changes, license
   notices, and profile catalog. Archive the named local R8 mapping and native symbols
   with the private release record, but upload only the recorded unsigned APK to the
   draft Release. Perform an offline import, slice, full-layer preview, and G-code
   export smoke test with the locally installed Debug APK.
4. Create and push an annotated `v<version>` tag at the exact `sourceCommit` recorded
   in the metadata. Create an unpublished draft GitHub Release for that tag containing
   concise user-visible Release notes and exactly the recorded unsigned APK; mark it as
   a prerelease when the SemVer has a prerelease suffix. Remove private model names,
   printer details, credentials, paths, and support logs from the notes.
5. Dispatch `sign-local-release.yml` from `main` with the recorded tag, asset name,
   SHA-256, versionCode, and source commit. The workflow rejects every other ref;
   approve the protected `release` environment.
6. The `validate` job checks the draft, tag commit, digest, package name, versionCode,
   versionName, unsigned state, Release-note digest, and 16 KB alignment. The isolated
   `sign` job signs those exact bytes. The `publish` job rechecks the draft, tag, and
   notes, replaces the unsigned asset, and publishes only the signed APK.
7. Verify that the GitHub Release contains exactly one asset and that its certificate
   fingerprint matches the pinned release key. Install it on a supported ARM64 device
   and repeat the offline smoke test before announcing the release. Release notes must
   describe user-visible changes; the publisher preserves them and appends the signed
   APK SHA-256, signing-certificate fingerprint, and source tag. This keeps the public
   Release APK-only without hiding the information needed to verify its one download.

## Play Console bundle handoff

Google Play receives an Android App Bundle, but the public GitHub Release remains
APK-only. `tools/prepare_local_play_bundle.py` runs the complete local Android 16/
API 36 ARM64 16 KB gate, builds the unsigned AAB and its universal delivery APK twice
from clean inputs with the Gradle build cache disabled, and rejects any byte
difference. The delivery APK is checked for package and version identity, unsigned
state, ARM64-only native libraries, and 16 KB alignment. It remains local and is
never uploaded. The AAB must contain its R8 mapping and full native debug symbols for
the owned Rust and inherited slicer libraries so Play can symbolize production
crashes without changing the delivered APK size.

GitHub never builds the Play AAB. The manually dispatched **Sign Local Play Bundle**
workflow only downloads a digest-pinned AAB from a private draft, validates its source
tag and native structure, signs it in the protected `play` environment, and removes
the private draft while retaining the source tag. It uses a separate Play upload key
instead of the GitHub APK release key. By default it stops at the
`duckyslicer-play-signed` Actions artifact. An explicitly approved
`publish_internal=true` dispatch can instead publish that exact signed digest to the
Play internal track through the Android Publisher Edits API. Production, staged
rollout, and promotion remain separate Console decisions.

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

For optional internal-track publishing, enable the Google Play Android Developer API
and grant a dedicated service account only the app-level release permission it needs
in Play Console. Configure GitHub-to-Google Workload Identity Federation so the trust
condition accepts only `ashcastle/duckyslicer`, `refs/heads/main`, and the protected
`play` environment. Add these public environment variables:

- `DUCKYSLICER_GOOGLE_WORKLOAD_IDENTITY_PROVIDER`: full provider resource name
- `DUCKYSLICER_GOOGLE_PLAY_SERVICE_ACCOUNT`: dedicated service-account email

Do not add a service-account JSON key, OAuth client secret, or refresh token. The
publisher receives a 15-minute token for only the `androidpublisher` scope, while the
signer remains the only job that can access the upload key.

The Publishing API can update only an existing Play app with at least one binary
already uploaded. Complete the first upload and required legal declarations in Play
Console before enabling this automation. Later AABs use a resumable upload session;
the workflow rejects a session URL outside Google's Android Publisher origin.

For each Play candidate:

1. From a clean, synchronized `main`, choose a SemVer and a positive, previously
   unused `versionCode` greater than every version already uploaded to Play and no
   greater than `2100000000`. Prepare the candidate locally:

   ```shell
   python3 tools/prepare_local_play_bundle.py \
     --version 0.2.0-rc.1 \
     --version-code 5 \
     --physical-report build/qualification/physical-report.json \
     --startup-report build/qualification/startup-report.json \
     --orca-report build/qualification/desktop-orca-release/comparison-report.json
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
   `unsignedSha256`; also provide reviewed English `releaseNotes` and the explicit
   publication choice. Leave
   `publishInternal=false` for a signing-only handoff, or set it to `true` to request
   the internal track. Approve the protected `play` environment. No job checks out or
   executes repository code.
4. For an internal publication, the keyless publisher rechecks the signed checksum,
   opens an edit, rejects a `versionCode` no greater than Play's current maximum,
   uploads only the exact AAB, updates only `internal`, validates the edit, and commits
   with `ERROR_IF_IN_REVIEW` so an existing review is never cancelled. A failed run
   deletes its uncommitted edit. Retain the workflow URL with the release record.
   Retain the 90-day `duckyslicer-play-receipt-<versionCode>` artifact, which binds
   the source commit, transport tag, signed AAB SHA-256, version codes, edit, track,
   and workflow URL. The cleanup job removes the private draft but keeps the durable
   source tag.
5. Treat the unsigned universal delivery APK as a packaging and 16 KB inspection
   artifact; Android cannot install it until it is signed. Install the Play-signed
   build from the internal test track on representative physical devices before any
   promotion. Production selection, staged rollout, review submission, and rollback
   remain explicit Console actions.

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

The reviewed English and Korean listing copy, declarations, icon, feature graphic,
and five phone screenshots live under `distribution/google-play/`. Run
`python3 tools/verify_store_listing.py` before opening Play Console. The verifier
enforces the current text limits, image formats and dimensions, localized alt text,
no-data declarations, foreground-service contract, and public test-model capture
provenance. The screenshots are
actual 1080 × 1920 app screens from the local Android 15 ARM64 16 KB emulator; do not
replace them with private models or identifying printer information. Recheck the
current official [listing field limits](https://support.google.com/googleplay/android-developer/answer/9859152?hl=en)
and [preview asset requirements](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en)
before each submission because Console policy can change independently of the source.

The candidate declares one `dataSync` foreground service for user-initiated,
on-device slicing. In **Policy > App content > Foreground service permissions**, use
the reviewed functionality and interruption text from `console-declarations.json`
under **Local processing: Other**. Record and externally host a short demonstration
that follows every `demoVideo.captureSteps` item, then paste that video URL into Play
Console. The repository intentionally stores neither the submission URL nor account
information. Re-record the video whenever the user-visible start, progress, cancel,
or completion flow changes. Recheck Google's current
[foreground-service declaration requirements](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en)
before each submission.

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
