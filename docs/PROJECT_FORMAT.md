# DuckySlicer project format

DuckySlicer saves portable projects with the `.duckyproject` extension and the MIME
type `application/vnd.duckyslicer.project+zip`. The format is a versioned ZIP archive
so a project can be inspected and recovered with standard tools.

## Schema 3

An archive contains exactly:

```text
manifest.json
models/000.stl
models/001.stl
...
```

`manifest.json` identifies the format as `com.ashcastle.duckyslicer.project`, declares
schema version `3`, and stores the selected object, resolved printer, filament, and
slicing settings, object transforms, support and seam painting, variable layer-height
ranges, display names, and model-entry references. Objects that share one source model
also share one model entry. Schema 1 and 2 projects remain readable and default missing
object-specific settings safely.

The archive intentionally does not contain G-code, remote-printer profiles, printer
addresses, access keys, support reports, or other app state. A project therefore
remains offline and portable without becoming a printer-credential backup.

On Android, a saved project can be opened from DuckySlicer's Project tab or by tapping
it in Files. External opening accepts only a granted `content://` URI with the project
MIME type, or a `.duckyproject` name reported as a ZIP-compatible type. Web, `file://`,
and unrelated binary URIs are not accepted. Opening into a non-empty workspace always
requires confirmation before the current project is replaced.

## Import boundary

Project files are untrusted input. The importer accepts only the manifest and numbered
STL entries, rejects duplicate, directory, traversal, and unknown entries, and validates
all references before replacing the current project. Models are extracted to a private
staging directory, synced, inspected by the native STL boundary, and moved into private
storage before the project metadata is atomically committed. A failed import leaves the
current project unchanged and removes staged data.

Import and export run in an Activity-retained operation so rotation does not interrupt
the transfer. If Android terminates the process during extraction, the next app start
removes only abandoned private staging directories with the exact generated UUID form.

The current bounds are:

- 256 objects and unique models
- 1 MiB manifest
- 512 MiB per model
- 1 GiB total uncompressed content
- 1,082,130,432 bytes for the compressed input stream

Unknown schema versions are rejected. A future schema must preserve these failure and
privacy properties or document a migration with equivalent bounds.
