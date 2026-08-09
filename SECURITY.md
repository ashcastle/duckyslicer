# DuckySlicer security policy

DuckySlicer is an offline-first Android slicer with optional direct printer
connections. Security reports are welcome,
especially for malformed model or G-code files, unsafe native-memory behavior,
unexpected file access, printer authentication, network request handling, and APK
supply-chain issues.

## Supported versions

Only the latest source on the default branch is currently supported. There is no
production release channel yet.

Tagged pre-release APKs are built by the repository release workflow. Each release
is expected to include a CycloneDX SBOM, recursive source archive, detached source
manifest, checksum manifest, and GitHub build-provenance attestation; an APK without
those matching release artifacts should not be treated as an official DuckySlicer
build.

The source build has no access to the Android signing key. Signing occurs in a
protected environment job without a repository checkout, and the resulting APK
must match the pinned public certificate fingerprint before the same artifact is
tested and published. The temporary keystore is removed before artifact upload.

## Reporting a vulnerability

Do not publish exploit details, private data, or a proof-of-concept in a public
issue.

1. Check the repository's **Security** page for a private vulnerability-reporting
   form.
2. If private reporting is not available, open a minimal issue that asks the
   maintainer for a private contact channel. Include no sensitive details in that
   issue.
3. In the private report, include the affected commit or APK version, Android
   version and device, impact, reproduction steps, and the smallest safe test file
   needed to reproduce the problem.

There is no guaranteed response-time SLA before the first stable release. The
maintainer will
confirm scope and coordinate disclosure before publishing a fix when possible.

## Scope

Reports about DuckySlicer's Android UI, Rust boundary, build configuration, bundled
native runtime, and local file handling are in scope. Vulnerabilities that exist
only in a third-party dependency should also be reported to that dependency's
maintainer.

Printer access keys are encrypted with a non-exportable Android Keystore key and
are not written to the device-profile JSON file. HTTPS is accepted for any valid
host; unencrypted HTTP profiles are restricted to loopback, link-local, private,
carrier-grade NAT, and `.local` addresses. Remote printing always requires a
separate user action after upload, with confirmation enabled by default.

OctoPrint and Moonraker responses have a one-MiB byte ceiling and a fixed nesting
limit. Credential-bearing requests do not follow redirects. Credentials, uploaded
G-code, returned paths, and status labels are bounded, and every request disconnects
on success or failure.

App-private project, profile, and printer metadata is bounded and depth-checked
before JSON parsing. Each store maintains a synced last-known-good generation,
repairs a damaged primary from that generation, and refuses to overwrite the files
when neither copy is readable or when a newer schema is encountered. The UI blocks
project autosave and reports that the original files were left unchanged.

Long-running Orca work never runs on the isolated service's main thread. Each slice
has an unpredictable request identifier and only its matching cancellation request
may terminate the worker. Cancellation or UI disposal kills only the private
`:slicer` process; the application remains alive and a later request starts a fresh
worker. ARM64 tests exercise service responsiveness, cancellation, PID replacement,
and a successful recovery slice.

Untrusted STL and G-code cross a Rust validation boundary before reaching preview
or transformation code. That boundary accepts regular files, applies the same
512 MiB STL limit as Android import, bounds individual text lines, rejects
non-finite or extreme coordinates, and writes transformed STL through a temporary
file before an atomic replacement. The host corpus and ARM64 device suite exercise
these rejection paths and verify that JNI remains usable after an invalid input.
Every exported Rust JNI operation also contains any unwind-capable Rust panic
before it can cross the FFI boundary and converts it to a generic failure response.
This containment does not recover allocation failure, native signals, undefined
behavior, or faults inside the inherited C++ runtime; reports that reach any of
those process-level failure modes remain in scope.

The inherited C++ slicer runtime loads only in a non-exported `:slicer` service. A
native signal or abort terminates that worker and the active slice; the application
process remains alive and the next request starts a clean worker. Binder requests
accept only bounded settings and canonical files inside app-private storage, and
successful G-code is synchronized before being atomically retained under a unique
name. A retained slice may contain at most 1 GiB of G-code, retained outputs share a
1 GiB budget, and slicing starts only with at least 512 MiB of free app-storage space.
The worker also terminates itself if its periodic guard observes an active native
output over the limit or free space below the 64 MiB emergency threshold. Old
outputs are removed oldest first, while cross-process shared reader leases prevent
cleanup from deleting G-code
being previewed, exported, or uploaded. Stale native output and interrupted temporary
files are recovered on the next worker start. This is crash/address-space isolation,
not a permission sandbox: both processes run under the same Android UID and share the
app's private storage.

The ARM64 device corpus also passes open shells, reversed and duplicate facets,
degenerate attachments, intersecting closed shells, and fully degenerate input
through the production boundary. Repairable geometry must emit finite G-code;
irreparable geometry must fail cleanly; a known-good model must slice afterward.

Never include printer credentials, signing keys, personal models, or generated
G-code containing private paths or identifiers in a report.
