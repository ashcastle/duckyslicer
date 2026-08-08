# DuckySlicer security policy

DuckySlicer is an offline-first Android slicer with optional direct printer
connections. Security reports are welcome,
especially for malformed model or G-code files, unsafe native-memory behavior,
unexpected file access, printer authentication, network request handling, and APK
supply-chain issues.

## Supported versions

Only the latest source on the default branch is currently supported. There is no
production release channel yet.

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

Never include printer credentials, signing keys, personal models, or generated
G-code containing private paths or identifiers in a report.
