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
- Treat app-private JSON and LAN-printer responses as untrusted too. Preserve the
  last-known-good generation, never replace unreadable/future-schema data, keep
  response size and nesting bounded, and do not enable credential-bearing redirects.
  Cleartext printer names must resolve entirely to local addresses at request time;
  pin the connection target and bypass system proxies before attaching an access key
  to prevent DNS rebinding or proxy forwarding.
- Keep Orca work off every Android main thread. New long-running operations must
  retain request-scoped cancellation, terminate only the isolated worker, and prove
  a clean follow-up operation on ARM64.
- Preserve the ARM64 imperfect-mesh corpus. Common repairable defects must still
  produce finite G-code, irreparable geometry must fail without terminating the app,
  and a valid model must slice immediately after every corpus entry.
- Keep `NativeLibrary` construction inside the non-exported `:slicer` service. The
  inherited C++ runtime must never load into the application process; changes to this
  boundary require the worker-termination ARM64 regression to remain green.
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
The checked-in `rust-toolchain.toml` pins the compiler, formatter, linter, and Android
target used by local and CI builds; do not replace it with an unversioned `stable`.

English is the default Android resource and Korean is the complete hand-maintained
translation. The other supported Orca languages are generated at build time only from
exact, non-fuzzy matches in the pinned PO catalogs; unmatched mobile copy falls back to
English. Do not edit generated resources or add languages outside that catalog set.
Run `python3 tools/verify_localization.py` after changing app copy or translations.

## Validate a change

Run the narrow checks that cover your change, then the full local gate before a
pull request:

```shell
python3 tools/run_local_gate.py
```

The command runs the Rust, Android host, policy, APK, connected-device, and
UI-process recovery checks in fail-fast order. It automatically selects the only
online API 35+ ARM64 device using 16,384-byte pages. If more than one eligible
device is connected, choose one explicitly:

```shell
python3 tools/run_local_gate.py --serial <adb-serial>
```

`python3 tools/run_local_gate.py --host-only` is useful while iterating, but it is
not the authoritative full gate. The local ARM64 16 KB AVD is the authoritative
functional gate.

Preview changes should be checked with outer walls, inner walls, sparse infill,
solid surfaces, support, bridges, multiple layer heights, and a dense model. The
default depth renderer and the low-power compatibility renderer must both remain
usable.
The Rust-to-Kotlin G-code preview boundary must remain a versioned, bounded primitive
`FloatArray`; do not reintroduce a JSON string or per-segment JSON objects. Payload
format changes require Rust encoding, Kotlin validation, malformed-payload host tests,
and the ARM64 production parser test to change together.
Depth-tested preview geometry must be built directly in native-order direct memory and
uploaded through an OpenGL VBO only when the layer range, role visibility, quality, or
visual style changes. Camera gestures must reuse the existing GPU buffer; do not return
to client-side vertex arrays or per-frame geometry uploads.
Automatic preview quality must resolve to a concrete tier before mesh generation.
Low-RAM or 192 MiB-and-smaller app heaps use the bounded performance tier, explicit
user choices remain authoritative, and active gestures may downgrade at most one tier
until the view settles. The requested and gesture tiers may retain at most two VBOs;
prewarm the gesture tier after the first visible frame instead of rebuilding or uploading
geometry on touch-down or touch-up. Keep this policy pure and host-tested alongside the
real ARM64 EGL renderer regression.

The mobile slicing-profile editor keeps the Orca mental model in five horizontally
scrollable sections: Quality, Strength, Speed, Support, and Others. Keep profile
selection above those settings, preserve that order, localize every title, and place
new process controls in the narrowest matching section instead of restoring one long form.

Generated G-code changes must retain the per-output and total-byte limits, free-space
reserve, stale-output recovery, and reader lease around every preview, export, and
printer-upload stream. The isolated native writer's `RLIMIT_FSIZE` ceiling and bounded
compatibility preview cache are part of that contract; do not replace either with a
periodic-only check or a full-file read. Run the host storage regressions and both ARM64
hard-limit recovery and cross-process lease regressions when changing retention or file access.
Orca writes beside its transformed input, so the persistent project-model directory must
remain an explicit monitored transient root. The ARM64 persistent-project regression must
finish with G-code in bounded slice storage and no `output.gcode` beside the model.

Workflow changes must keep third-party Actions pinned to full commit hashes. The
GitHub Release APK must be built locally with
`python3 tools/prepare_local_release.py`; tag-triggered or manually dispatched hosted
APK builds are not allowed. The local command runs the complete ARM64 16 KB AVD gate,
builds the unsigned release twice, and rejects any byte difference.
The signing workflow must preserve validate → isolated sign → publish ordering. Only
the `sign` job may receive signing secrets; it must not check out source, execute
Gradle or repository scripts, or write the Release. Only `publish` may write the
Release. The GitHub Release must contain only the signed ARM64 APK. The tag and
recursive submodule pins remain the corresponding-source identity.
Play AABs follow the same local-only rule. Build them with
`python3 tools/prepare_local_play_bundle.py`; the local command runs the full gate,
builds both Play artifacts twice, checks the universal delivery APK at 16 KB, and
rejects byte differences. GitHub never builds the Play AAB. Its manual workflow may
only validate the private draft, sign the exact digest in the protected `play`
environment, retain the signed AAB plus checksum as an Actions artifact, and remove
the draft without deleting its source tag. It must use a separate Play upload key and
never uploads to Play Console.
Both local release paths inspect the final merged APK manifest, not only the source
manifest. API levels, permissions, backup/debug state, application components, and
content-URI import filters are fail-closed allowlists. Any dependency that changes the
merged manifest requires explicit security review and a policy update.

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
