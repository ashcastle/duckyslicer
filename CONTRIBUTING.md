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
  and a JNI recovery assertion for new parser failure modes. Extend the deterministic
  ASCII/binary STL and G-code mutation corpus when fixing a malformed-input defect;
  do not replace its fixed seeds with flaky environmental randomness.
- Treat app-private JSON and LAN-printer responses as untrusted too. Preserve the
  last-known-good generation, never replace unreadable/future-schema data, keep
  response size and nesting bounded, and do not enable credential-bearing redirects.
  Stage and bind a replacement printer credential generation before committing
  metadata, and never retain a key across an endpoint change.
  Cleartext printer names must resolve entirely to local addresses at request time;
  pin the connection target and bypass system proxies before attaching an access key
  to prevent DNS rebinding or proxy forwarding.
- Bind every remote status, upload-progress, and command result to the printer profile
  that started it. A late result must never update another selected printer or expose
  pause, resume, or cancel controls for the wrong device; keep profile changes disabled
  while a remote operation is active. Remote operations, their busy state, and their
  exact request-scoped cancellation must survive Activity recreation, and an upload
  completed after the project or slicing inputs change must never become eligible for Start Print.
  Device-profile loading, selection, saving, deletion, and credential
  metadata must share that same retained owner so a recreated Activity cannot race or
  replace a newer durable profile list. Cancel a remote refresh, upload, or printer
  command by disconnecting only its request-bound connection.
  Final retained-owner clearance must stop that exact active connection.
  Stale cancellation must never stop a later refresh, upload, or printer command.
- Keep native slicer work off every Android main thread. New long-running operations must
  retain request-scoped cancellation, terminate only the isolated worker, and prove
  a clean follow-up operation on ARM64. Foreground-slice cancellation must carry its
  retained session request ID; an idle or stale slice owner must never cancel a later
  native operation.
- Project history, active slicing options, restoration, and debounced persistence must
  share the same Activity-retained owner. Rotation must preserve unsaved edits and undo
  history, while the durable project generation remains the process-death recovery path.
  Flush the latest dirty revision when the app enters the background or that owner is
  finally cleared. Before an archive import commits its replacement, cancel and join
  any older metadata write so it cannot later overwrite the imported generation.
- Model import, primitive creation, automatic lay, arrangement, split, and cut must run
  through that retained project owner. Bind each result to its starting history and
  options, reject duplicate work after rotation, and delete every unaccepted model file.
  Ordinary STL, 3MF, and OBJ import cancellation must interrupt the exact provider open
  and input stream as well as the matching isolated-worker request; final-owner cleanup
  must remove its staging data without deleting the user-selected source document.
- UI disposal must not issue a process-wide slicer cancellation. Only the retained
  owner of an active operation may cancel it. Long-running project edits must expose
  request-scoped cancellation, preserve the starting project on cancellation, and
  remove every generated model that was not accepted. Final retained-owner clearance
  must cancel that exact request; ordinary Activity recreation must not.
- User-selected G-code export must run through its retained owner, hold a reader lease,
  reject duplicate copies, and expose request-scoped cancellation that interrupts the
  exact provider open and copy streams. Rotation must retain that operation; final owner
  clearance must stop it. Cancellation or failure must delete the partial document.
- Project archive export must follow the same retained-owner lifecycle. Cancellation
  must interrupt the exact provider open and ZIP write, including each copied model,
  survive rotation, stop on final owner clearance, and delete the partial document.
- Project archive import must bind cancellation to the exact provider open, input
  stream, and ZIP read. An accepted cancellation must win before the atomic commit
  gate, preserve the current project, and remove all staged or installed import files.
  Rotation retains the import; final owner clearance stops it without deleting the
  user-selected source document.
- Every `CreateDocument` writer must accept only its returned `content://` URI, truncate
  the new target, and delete it after cancellation or failure so partial exports never
  look like valid G-code, project archives, or support details.
- Profile catalog loading, recent selections, and user-profile saves must share one
  Activity-retained owner. A saved profile may update the catalog after rotation, but
  its late completion may select that profile only in the project session revision that
  started the save. Track recent-selection persistence revisions and flush the newest
  dirty `Recent` list when the app enters the background or that owner is finally cleared.
- Profile bundle import and export must use that same retained owner and exact provider
  cancellation boundary. External profile documents must remain `content://`-only and
  bind one pending request to one import operation; consume the request only after that
  operation is terminal. Keep the additive, atomic, credential-free contract and
  `docs/PROFILE_BUNDLE_FORMAT.md` synchronized with every format change.
- Live app settings and their debounced persistence must share one Activity-retained
  owner. Rotation must preserve the latest slider and toggle values before disk commit,
  and only the newest revision may report a persistence result.
  Flush the latest dirty settings when the app enters the background or that owner is
  finally cleared.
- Fixed support-event writers must serialize through the process-wide journal boundary;
  adding another retained owner must not turn diagnostics into competing read-modify-write
  snapshots or expose free-form failure details.
- Keep every external activity intent inside the reviewed Manifest allowlist. Explicit
  notification and test launches must match the activity's declared action and
  category so strict intent matching can remain enabled.
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
translation. The other supported upstream languages are generated at build time only from
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
not a complete functional gate. API 35+ ARM64 16 KB devices remain valid for normal
development checks. The local GitHub APK and Play AAB preparation commands raise that
requirement to an Android 16/API 36 ARM64 16 KB runtime and refuse to create a release
candidate without it.
Run `python3 tools/prepare_release_avd.py --create` to install or verify the pinned
local ARM64 16 KB system image and AVD. The command prints the headless emulator launch
and exact API 36 gate commands; hosted CI is not a substitute for this runtime check.
The host portion also regenerates the CycloneDX SBOM at
`android/app/build/outputs/duckyslicer-debug.cdx.json`
from the resolved Debug dependency inventory and verifies that its licenses match the
offline license index inside the exact APK. The generated SBOM and license inventory
are ignored build outputs; review them when dependencies change, but do not commit them.

Preview changes should be checked with outer walls, inner walls, sparse infill,
solid surfaces, support, bridges, multiple layer heights, and a dense model. The
default depth renderer and the low-power compatibility renderer must both remain
usable.
The Rust-to-Kotlin G-code preview boundary must remain a versioned, bounded primitive
payload written into an Android-owned native-order direct `ByteBuffer`; do not
reintroduce a JSON string, per-segment objects, or a full JNI `FloatArray` copy.
Payload format changes require Rust encoding, Kotlin validation, malformed-payload host
tests, and the ARM64 production parser test to change together. Reuse the bounded
direct staging pool; repeated layer-range loads must not accumulate one maximum-sized
native allocation per request.
Large-model inspection follows the same rule: keep its bounded vertices and source-facet
mapping in the versioned primitive `FloatArray` payload, and keep JSON decoding out of
the production import path.
Depth-tested toolpath geometry is packed by the bounded Rust JNI path, validated again
by Kotlin, copied once into native-order direct VBO staging memory, and uploaded only
when the layer range, role visibility, quality, or visual style changes. Keep the
managed packer as a safe fallback and require byte-exact ARM64 parity tests for both
instance and line layouts whenever either implementation changes.
Toolpaths use one 32-byte instance per retained segment and OpenGL ES 3 instanced draws;
do not expand every line into six duplicated CPU vertices. Bed triangles remain a small
separate VBO. Camera gestures must reuse the existing GPU buffers; do not return to
client-side vertex arrays or per-frame geometry uploads.
Automatic preview quality starts from the bounded performance tier before mesh
generation. Once the first coherent frame is visible, the GLES renderer measures a
bounded number of completed GPU frames and may promote one tier at a time to Balanced
and Detail. A slow candidate falls back to the last proven tier and remains there for
that workload; do not use RAM capacity as a proxy for GPU throughput or oscillate tiers
during inspection. Explicit user choices remain authoritative, and active gestures may
downgrade at most one tier until the view settles. The requested and gesture tiers may
retain at most two geometry sets (bed plus toolpath-instance VBOs). Prewarm the gesture
tier after the first visible frame instead of rebuilding or uploading geometry on
touch-down or touch-up.
The physical qualification runner is the release acceptance check for this policy. Its
dense workload enforces the Automatic first-frame and p95 budgets, bounded geometry
uploads, peak-PSS budget, and Android thermal severity. Emulator throttling and
software-renderer traces are useful for locating regressions but do not replace this
physical-device gate. The dense workload also repeats three times in one instrumentation
session so process restarts, continuing UI-PSS growth, and warmed-up slicing/Preview
regressions fail instead of being hidden by a fresh process.
Release startup evidence is collected separately with
`python3 tools/run_startup_qualification.py --serial <physical-serial>`. It runs cold
startup both without compilation and with the checked-in Baseline Profile, requires
TTID and TTFD samples, rejects large regressions, and refuses emulator results as
release evidence. Both local release preparers require this startup report and the
complete physical qualification report for the exact source commit. Regenerate the
app-owned profile with
`ANDROID_SERIAL=<api-36-serial> ./android/gradlew -p android :app:generateBaselineProfile`
when the initial workspace journey changes; the generated rules are first-party-only
and deterministically sorted before commit.
Reconstructable VBOs must be released when Android
reports that the UI is hidden or the process is in the background, then rebuilt lazily on
the first visible frame. Keep this policy pure and host-tested alongside the real ARM64
EGL renderer regression.
If the ES3 context, shaders, attributes, buffer upload, or first draw fails, the preview
must use the bounded compatibility fallback for the current session instead of
leaving a blank surface. Keep the user's renderer preference unchanged so an explicit
mode toggle or a later app session can retry the depth path.

The mobile slicing-profile editor keeps the familiar desktop-slicer model in five horizontally
scrollable sections: Quality, Strength, Speed, Support, and Others. Keep profile
selection above those settings, preserve that order, localize every title, and place
new process controls in the narrowest matching section instead of restoring one long form.

Generated G-code changes must retain the per-output and total-byte limits, free-space
reserve, stale-output recovery, and reader lease around every preview, export, and
printer-upload stream. The isolated native writer's `RLIMIT_FSIZE` ceiling and bounded
compatibility preview cache are part of that contract; do not replace either with a
periodic-only check or a full-file read. Run the host storage regressions and both ARM64
hard-limit recovery and cross-process lease regressions when changing retention or file access.
The native slicer writes beside its transformed input, so the persistent project-model directory must
remain an explicit monitored transient root. The ARM64 persistent-project regression must
finish with G-code in bounded slice storage and no `output.gcode` beside the model.

Workflow changes must keep third-party Actions pinned to full commit hashes. The
GitHub Release APK must be built locally with
`python3 tools/prepare_local_release.py`; tag-triggered or manually dispatched hosted
APK builds are not allowed. The local command runs the complete Android 16/API 36
ARM64 16 KB gate,
builds the unsigned release twice, and rejects any byte difference. The two builds
must also produce identical local R8 mapping and native debug symbol files; retain
them with the private release record and never upload them as public Release assets.
The signing workflow must preserve validate → isolated sign → publish ordering. Only
the `sign` job may receive signing secrets; it must not check out source, execute
Gradle or repository scripts, or write the Release. Only `publish` may write the
Release. The GitHub Release must contain only the signed ARM64 APK. The tag and
recursive submodule pins remain the corresponding-source identity.
Draft Release notes must describe user-visible changes without private data. The
publisher preserves those notes and publishes the APK SHA-256 and signing-certificate
fingerprint in the Release notes; do not add separate checksum or SBOM assets.
Play AABs follow the same local-only rule. Build them with
`python3 tools/prepare_local_play_bundle.py`; the local command runs the full gate,
requires the same API 36 runtime, builds both Play artifacts twice, checks the
universal delivery APK at 16 KB, and
rejects byte differences. The AAB must embed its R8 mapping and full native debug
symbols for the owned Rust and inherited slicer libraries. GitHub never builds the
Play AAB. Its manual workflow may
only validate the private draft, sign the exact digest in the protected `play`
environment, retain the signed AAB plus checksum as an Actions artifact, and remove
the draft without deleting its source tag. It must use a separate Play upload key and
never uploads to Play Console.
Both local release paths inspect the final merged APK manifest, not only the source
manifest. API levels, permissions, backup/debug state, application components, and
content-URI import filters are fail-closed allowlists. Any dependency that changes the
merged manifest requires explicit security review and a policy update.

## Updating Android dependencies

Gradle dependency changes must update `android/app/gradle.lockfile`,
`android/baselineprofile/gradle.lockfile`, and
`android/gradle/verification-metadata.xml`. Generate trust data from an empty
Gradle user home so previously cached plug-in metadata cannot hide a missing
checksum, then review every new coordinate and checksum before committing it:

```shell
cd android
verification_home="$(mktemp -d)"
GRADLE_USER_HOME="$verification_home" ./gradlew --no-daemon \
  --write-locks --write-verification-metadata sha256 \
  :baselineprofile:compileNonMinifiedReleaseKotlin \
  :app:testDebugUnitTest :app:lintRelease :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:assembleRelease

cd ..
python3 tools/verify_gradle_supply_chain.py
git diff -- android/app/gradle.lockfile \
  android/baselineprofile/gradle.lockfile \
  android/gradle/verification-metadata.xml
```

Do not add trusted-artifact or ignored-key bypasses. If a repository publishes a
new checksum for an existing coordinate, stop and verify the upstream artifact
instead of accepting both values automatically.

## Pull requests

Describe the user-visible outcome, important implementation trade-offs, and the
checks that passed. Include before-and-after screenshots for UI or preview work.
Do not include generated APKs in Git; CI publishes build artifacts.

Security issues must follow [SECURITY.md](SECURITY.md) instead of a public issue.
