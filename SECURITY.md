# DuckySlicer security policy

DuckySlicer is an offline-first Android slicer with optional direct printer
connections. Security reports are welcome,
especially for malformed model or G-code files, unsafe native-memory behavior,
unexpected file access, printer authentication, network request handling, and APK
supply-chain issues.

## Supported versions

Only the latest source on the default branch is currently supported. There is no
production release channel yet.

The Release APK is built twice on the maintainer's local machine and must be
byte-for-byte reproducible. The local process records its SHA-256, package name,
versionCode, versionName, and tag commit. GitHub validates those exact values but
never builds the GitHub Release APK. The GitHub Release exposes exactly one
downloadable asset: the signed ARM64 APK. Published Release notes contain the signed
APK SHA-256, signing-certificate SHA-256, and exact source tag. Empty notes and a
maintainer-supplied integrity block are rejected; the keyless publisher appends the
verified block immediately before publication. The reviewed note body is digest-pinned
across validation and publication. The tagged repository plus its recursive submodule
pins are the durable corresponding source.

Validation has no signing key and never checks out repository code. GitHub requires
push access to see draft releases, so its token is Release-capable, but policy checks
limit the inline validator to read-only release commands. Signing occurs in a
protected environment job without a repository checkout, repository execution, or
Release permission. It signs only the digest-checked artifact, verifies the pinned
public certificate fingerprint, and removes its temporary keystore. A separate
keyless publisher rechecks the tag and draft state before exposing the signed APK.
GitHub-hosted emulators are not part of the release pipeline; functional qualification
runs on the local ARM64 16 KB AVD.

Play bundles use a separate upload key in a separate protected `play` environment.
The AAB and universal delivery APK are built twice on the maintainer's local machine
and must be byte-for-byte reproducible. GitHub never builds the Play AAB. The manual
Play workflow validates only the digest-pinned local AAB from a private draft, does
not check out or execute repository code in validation, signing, or cleanup, pins the
upload-certificate fingerprint, and stops at a signed Actions artifact. Validation
and cleanup can inspect or remove the private draft but receive no signing material;
the signer can read only the validated Actions artifact. The workflow has no Play
Console credentials and cannot select a track or start a rollout.

Release and Play preparation inspect the final merged APK manifest produced after
dependency manifest merging. The gate requires API 36 targeting, the exact permission
and component allowlists, disabled backup and release debugging, isolated slicer service
attributes, and content-only external project imports. A new transitive component or
permission fails the build until it is reviewed explicitly.

The same Gradle build used by pull-request verification is traced by CodeQL for
Java and Kotlin using the extended security query suite. Analysis runs for trusted
repository pull requests and every push to `main`; untrusted fork pull requests do
not receive a write-capable security token and are scanned after merge. This is
static analysis only and does not replace the local ARM64 16 KB functional gate.

The local gate generates a CycloneDX SBOM from the exact Debug APK, resolved Gradle
runtime graph, locked Cargo graph, and pinned native components. Generation fails if
any component lacks reviewed license policy or if the SBOM licenses differ from the
offline license index packaged in that APK. CI retains its independently generated
Debug SBOM as a review artifact; it is not an additional public Release asset.

## Reporting a vulnerability

Do not publish exploit details, private data, or a proof-of-concept in a public
issue.

1. Check the repository's
   [Security Advisories page](https://github.com/ashcastle/duckyslicer/security/advisories)
   and use **Report a vulnerability** when that private option is available.
2. If private reporting is not available, use the public
   [support-question form](https://github.com/ashcastle/duckyslicer/issues/new?template=support_question.yml)
   only to ask the maintainer for a private contact channel. Include no sensitive
   details in that issue.
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
Credential updates are staged under a new generation before profile metadata is
committed. A saved key is never carried to a changed connection type or address
unless the user supplies it again, and obsolete generations are removed only after
the durable metadata backup has caught up.
Before a cleartext request receives any access key, every current DNS answer must
still be local and the connection URL is pinned to one validated address. This keeps
a saved `.local` name from redirecting credentials through DNS rebinding. Cleartext
printer requests also bypass system proxies. HTTPS keeps its original hostname so the
platform certificate verifier remains authoritative.

OctoPrint and Moonraker responses have a one-MiB byte ceiling and a fixed nesting
limit. Credential-bearing requests do not follow redirects. Credentials, uploaded
G-code, returned paths, and status labels are bounded, and every request disconnects
on success or failure.

App-private project, profile, and printer metadata is bounded and depth-checked
before JSON parsing. Each store maintains a synced last-known-good generation,
repairs a damaged primary from that generation, and refuses to overwrite the files
when neither copy is readable or when a newer schema is encountered. The UI blocks
project autosave and reports that the original files were left unchanged.
The exported launcher activity enforces its declared intent filters. External project
opening accepts only the reviewed `content://` project types, while notification
launches use the matching launcher action and category.

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
Before Orca exports, the worker applies a 1 GiB `RLIMIT_FSIZE` soft limit; a write that
reaches it fails or terminates only the isolated worker, and the next request restores
the production limit. The inherited compatibility preview cache reads at most 256 KiB
instead of duplicating the complete output in memory. As a secondary defence, the worker
terminates itself if its periodic guard observes an active native output over the limit
or free space below the 64 MiB emergency threshold. Old
outputs are removed oldest first, while cross-process shared reader leases prevent
cleanup from deleting G-code
being previewed, exported, or uploaded. Stale native output and interrupted temporary
files are recovered on the next worker start. This is crash/address-space isolation,
not a permission sandbox: both processes run under the same Android UID and share the
app's private storage.
The persistent project-model directory is an explicit monitored transient root because
Orca writes beside its transformed input; completed output is moved into bounded slice
storage before it is returned, and stale adjacent output is removed on worker recovery.

The ARM64 suite additionally forces a small native file-size limit, verifies that disk
growth stops at that boundary, and requires a normal recovery slice. Its mesh corpus
also passes open shells, reversed and duplicate facets,
degenerate attachments, intersecting closed shells, and fully degenerate input
through the production boundary. Repairable geometry must emit finite G-code;
irreparable geometry must fail cleanly; a known-good model must slice afterward.

Never include printer credentials, signing keys, personal models, or generated
G-code containing private paths or identifiers in a report.
