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
is expected to include a CycloneDX SBOM and GitHub build-provenance attestation;
an APK without those matching release artifacts should not be treated as an
official DuckySlicer build.

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

Untrusted STL and G-code cross a Rust validation boundary before reaching preview
or transformation code. That boundary accepts regular files, applies the same
512 MiB STL limit as Android import, bounds individual text lines, rejects
non-finite or extreme coordinates, and writes transformed STL through a temporary
file before an atomic replacement. The host corpus and ARM64 device suite exercise
these rejection paths and verify that JNI remains usable after an invalid input.

Never include printer credentials, signing keys, personal models, or generated
G-code containing private paths or identifiers in a report.
