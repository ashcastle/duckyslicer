# DuckySlicer project format

DuckySlicer saves portable projects with the `.duckyproject` extension and the MIME
type `application/vnd.duckyslicer.project+zip`. The format is a versioned ZIP archive
so a project can be inspected and recovered with standard tools.

## Schema 1

An archive contains exactly:

```text
manifest.json
models/000.stl
models/001.stl
...
```

`manifest.json` identifies the format as `com.ashcastle.duckyslicer.project`, declares
schema version `1`, and stores the selected object, resolved printer, filament, and
slicing settings, object transforms, support painting, display names, and model-entry
references. Objects that share one source model also share one model entry.

The archive intentionally does not contain G-code, remote-printer profiles, printer
addresses, access keys, support reports, or other app state. A project therefore
remains offline and portable without becoming a printer-credential backup.

## Import boundary

Project files are untrusted input. The importer accepts only the manifest and numbered
STL entries, rejects duplicate, directory, traversal, and unknown entries, and validates
all references before replacing the current project. Models are extracted to a private
staging directory, synced, inspected by the native STL boundary, and moved into private
storage before the project metadata is atomically committed. A failed import leaves the
current project unchanged and removes staged data.

The current bounds are:

- 256 objects and unique models
- 1 MiB manifest
- 512 MiB per model
- 1 GiB total uncompressed content
- 1,082,130,432 bytes for the compressed input stream

Unknown schema versions are rejected. A future schema must preserve these failure and
privacy properties or document a migration with equivalent bounds.
