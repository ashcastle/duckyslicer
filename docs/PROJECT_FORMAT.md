# DuckySlicer project format

DuckySlicer saves portable projects with the `.duckyproject` extension and the MIME
type `application/vnd.duckyslicer.project+zip`. The format is a versioned ZIP archive
so a project can be inspected and recovered with standard tools.

## Schema 76

An archive contains exactly:

```text
manifest.json
models/000.stl
models/001.stl
...
```

`manifest.json` identifies the format as `com.ashcastle.duckyslicer.project`, declares
schema version `76`, and stores the selected plate plus a bounded `plates` list. Each plate
owns its stable identity, optional display name, selected object, objects, and resolved
printer, filament, and slicing settings. It also owns up to 256 ordered print pauses, 256 ordered whole-layer
filament changes, and 64 bounded custom G-code events at exact print heights. Each object owns
a stable, bounded `volumes` list. The object owns its
transform (including independent X, Y, and Z scale), variable layer-height configuration
using either automatic adaptive quality or bounded manual ranges,
height-range process modifiers, and object-specific process overrides. It also owns up to 256
validated manual Brim-ear points
in model-local coordinates and millimetre radius units. Each volume owns its stable identity,
display name, model-entry reference, filament assignment, support and seam painting, and
multi-color painting. Imported Orca/BBS projects additionally preserve the exact recursive
facet annotations for support, seam, and multi-color painting instead of flattening a partially
painted triangle into a whole-face edit. A volume also records its Orca role (model part, negative volume,
parameter modifier, support blocker, or support enforcer) and bounded per-volume Orca overrides.
Objects or volumes that share one source model also share one model entry. Resolved filament
profiles preserve the diameter used to calculate E-axis extrusion plus the material density and
price per kilogram used for weight and cost statistics, plus soluble and dedicated-support
material semantics used by multi-material tool ordering and purging. Per-filament minimum
wipe-tower purge volume and auxiliary cooling speed are retained alongside the printer's
auxiliary-fan capability. Prime-tower X/Y placement, brim chamfer policy, and maximum chamfer
width, purge-volume matrix, and optional flush multiplier are also retained, so portable projects
preserve tool-change geometry, cooling output, adhesion geometry, and the user's bed layout.

Schema 1 through 75 projects remain readable. Projects without plate names retain their
localized numbered labels, and schemas before multi-plate support migrate deterministically
to one plate.
Their single object-level model, filament, and paint fields migrate deterministically to
one stable volume; older uniform-scale transforms, missing object-specific settings, and
missing Brim points receive safe defaults. Current projects may contain up to 16 plates and
up to 64 volumes per object, and retain plate-local objects and settings through Prepare, slicing,
autosave, and portable project round trips.

The archive intentionally does not contain G-code, remote-printer profiles, printer
addresses, access keys, support reports, or other app state. A project therefore
remains offline and portable without becoming a printer-credential backup.

On Android, a saved project can be opened from DuckySlicer's Project tab or by tapping
it in Files. External opening accepts only a granted `content://` URI with the project
MIME type, or a `.duckyproject` name reported as a ZIP-compatible type. Web, `file://`,
and unrelated binary URIs are not accepted. Opening into a non-empty workspace always
requires confirmation before the current project is replaced.

When an Android document provider grants persistent write access, DuckySlicer remembers
that document in private app state. **Save** then updates the same document, while **Save project as**
opens the system document picker for a new destination. The remembered link is a bounded
`content://` URI and display name, never a resolved local path. It is not written into the portable archive.
Starting a new project clears the current link but keeps up to five granted documents in a private recent list.
DuckySlicer removes entries whose Android permission is revoked and returns the permission of an entry evicted
from that bounded list. Removing an item from Recent likewise returns its permission without deleting the
document itself; the currently linked document cannot be removed from Recent until another project is active.

## Import boundary

Project files are untrusted input. The importer accepts only the manifest and numbered
STL entries, rejects duplicate, directory, traversal, and unknown entries, and validates
all references before replacing the current project. Models are extracted to a private
staging directory, synced, inspected by the native STL boundary, and moved into private
storage before the project metadata is atomically committed. A failed import leaves the
current project unchanged and removes staged data.

Import and export run in an Activity-retained operation so rotation does not interrupt
the transfer. An external-open request is bound to that exact import operation, so an
Activity recreation never opens the same request twice. If Android terminates the process,
the URI is restored without an in-memory operation claim; a non-empty durable workspace
returns to replacement confirmation before retrying. On startup, the importer removes only
abandoned private staging directories with the exact generated UUID form.

The current bounds are:

- 16 plates
- 256 objects, volumes, and unique models across the project
- 8 MiB manifest
- 512 MiB per model
- 1 GiB total uncompressed content
- 1,082,130,432 bytes for the compressed input stream

Unknown schema versions are rejected. A future schema must preserve these failure and
privacy properties or document a migration with equivalent bounds.
