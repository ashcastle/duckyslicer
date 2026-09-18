# Native slicer runtime

DuckySlicer does not link a prebuilt slicer engine. The Android ARM64 runtime is
built from pinned third-party source during the Gradle build, and every input is
locked in a single file:

- `native/slicer-runtime/versions.env` — repository URLs, commits, archives, and
  SHA-256 digests for the runtime, the engine, and all native dependencies.
- `native/slicer-runtime/build.sh` — the authoritative, ordered build procedure.
- `third_party/android-slicer-runtime` — git submodule at the exact runtime commit
  recorded in `versions.env`.
- `tools/verify_native_safety.py` — policy check that the pins and the JNI failure
  containment stay intact.

## Lineage and licensing

| Component | Source | License |
| --- | --- | --- |
| Android runtime / JNI bridge | `taylormadearmy/u1-slicer-for-android` | AGPL-3.0 |
| Slicer engine | `taylormadearmy/OrcaSlicer` | AGPL-3.0 |

Both are community forks of AGPL projects (PrusaSlicer / SoftFever OrcaSlicer).
Attribution and the exact revisions are recorded in `THIRD_PARTY_NOTICES.md`.
Generated profile catalogs are derived from the pinned engine tree and stay under
the same AGPL terms.

## Patch stack

Changes never edit the upstream forks in place. They live as reviewable patches
applied to a scratch worktree, with two distinct layers:

1. **Runtime bridge patches** — applied to the runtime fork worktree, in order,
   starting with `runtime.patch` (the Android adaptation). `build.sh` lists the
   full order; the current stack is `runtime.patch`, `project-3mf.patch`,
   `profile-options.patch`, … through `auto-line-width.patch` (27 patches total).
2. **Engine patches** — the nine `engine-*.patch` files, applied to the nested
   `app/src/main/cpp/orcaslicer` engine subtree (`engine-profile-options.patch`
   through `engine-auto-width-segmentation.patch`).

Each patch is guarded by `git apply --check` before `git apply`; a patch that no
longer applies fails the build with a specific diagnostic instead of producing a
silently different binary. Because the worktree is reused across builds, the
script first reverses every patch that is already present to normalize the tree,
then re-applies the whole stack forward.

`native/slicer-runtime/overlay/` holds a few files that are copied wholesale
instead of patched (for example the `sapil_*` sources and two stub headers used by
the Android build).

### Adding or changing a patch

1. Make the change in a scratch checkout of the runtime/engine at the pinned
   commit.
2. Export a focused patch into `native/slicer-runtime/` and register it in
   `build.sh` in the correct position (engine patches go in the `engine_patches`
   array).
3. Keep the existing guard/`die` pair so an unreviewed tree cannot build.
4. Rebuild and re-run the qualification corpus plus the desktop comparison before
   merging. Do not move a pin or reorder patches without that evidence.

## Reproducibility risk

The runtime and engine commits point at single-maintainer community forks. If
those repositories are deleted, made private, or force-pushed, the pinned commits
can become unreachable and a clean build stops being reproducible.

Mitigations already in place:

- `tools/generate_source_bundle.py` can produce a recursive source archive and
  a manifest recording runtime and engine gitlinks and external inputs. Do not
  assume a source archive is embedded in every APK or uploaded with every release.
- The APK-only release records the source tag, APK SHA-256, and signing certificate
  SHA-256 in its release notes. Local diagnostic outputs are retained separately.
- Native dependency sources and outputs are cached by their locked inputs, so an
  unchanged pin does not re-fetch on every run.

Recommended follow-up for maintainers: mirror the two fork repositories (or their
pinned trees) to an organization-owned location and record that mirror in
`THIRD_PARTY_NOTICES.md`, so the exact build inputs have an independent backup.

## Build cost

The debug runtime `libprusaslicer-jni.so` is large (hundreds of megabytes) and the
first cold build is slow. CI bounds this with a content-addressed `ccache` and a
native dependency cache keyed on `versions.env` and `build.sh`. Local incremental
Kotlin builds reuse the cached native output and do not re-run the native build
unless a native input changes.
