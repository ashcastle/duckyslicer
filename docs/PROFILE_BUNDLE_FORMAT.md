# DuckySlicer profile bundle format

DuckySlicer exports portable user profiles with the `.duckyprofiles` extension and
the MIME type `application/vnd.duckyslicer.profiles+json`. The document is UTF-8 JSON
and is intended for offline migration between DuckySlicer installations.

## Bundle version 1

The top-level object contains exactly four fields:

```json
{
  "type": "com.ashcastle.duckyslicer.user-profiles",
  "bundleVersion": 1,
  "profileSchemaVersion": 113,
  "profiles": {
    "printers": [],
    "filaments": [],
    "slicing": []
  }
}
```

`profiles` contains exactly the three arrays shown above. Each record uses the same
canonical fields as DuckySlicer's versioned app-private user-profile store. Printer
records carry bed geometry and origin, nozzle, pellet, and extruder capabilities, machine
G-code, and motion limits. Filament records carry material, temperature, flow,
cooling, retraction, and printer-compatibility settings. Slicing records carry the
Quality, Strength, Speed, Support, and Others process settings exposed by the app.
The serializers and validators in `ProfileStore.kt` are the authoritative field
definitions.

Exports currently write profile schema 113. Bundle version 1 accepts supported profile
schemas 1 through 113 and applies the same safe defaults used for an older private
profile store. A future bundle envelope or profile schema is rejected until an
explicit migration is implemented.

## Import behavior

Profile bundles are untrusted input. DuckySlicer requires the exact envelope and
array names, a valid record in every array position, a unique ID within each profile
kind, and every numeric, text, geometry, and compatibility value to pass the normal
profile validators. Unknown per-profile fields are discarded by canonical
re-encoding; unknown envelope fields are rejected.

Import is additive and atomic:

- exact profile duplicates are skipped;
- profile names are trimmed and compared case-insensitively;
- different profiles with the same name are preserved under the next available
  suffix, such as `Name (2)`, and the import result reports how many names changed;
- importing that same bundle again recognizes an earlier conflict-adjusted copy and
  skips it instead of creating another suffix;
- an incoming profile whose ID belongs to different content receives a new user ID;
- if an imported printer ID changes, references from imported filament and slicing
  profiles are changed to that same printer ID;
- built-in status is never imported;
- validation, ID allocation, and the complete merge finish before one durable write;
- malformed, incompatible, canceled, or unreadable input leaves saved profiles
  unchanged.

The current bounds are 24 MiB per bundle and 4,096 combined saved printer, filament,
and slicing profiles. Provider reads and writes use bounded chunks and can be stopped
without deleting the user-selected source. A failed or stopped export deletes its
partial destination document.

## Privacy boundary

A bundle contains only user-created printer, filament, and slicing settings. It does not contain projects,
model geometry, G-code, recent-selection history, application settings, support details,
remote printer addresses, OctoPrint or Moonraker access keys, or other credentials.
The JSON is neither signed nor encrypted; inspect a file before importing it if its
origin is not trusted.

On Android, a bundle can be selected from DuckySlicer's menu, opened from Files, or
sent from another app's Share sheet with the custom MIME type. External opening accepts
only one granted `content://` URI with the custom MIME type,
or a `.duckyprofiles` name reported as `application/json` or
`application/octet-stream`. Web, `file://`, unrelated JSON, and unrelated binary
documents are not accepted. Rotation retains the exact import; if the app process is
terminated first, the additive request can be retried and duplicate detection keeps
the committed library stable.
