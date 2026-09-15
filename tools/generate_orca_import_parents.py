"""Package pinned preset source records for offline inheritance resolution."""
import gzip
import json
from pathlib import Path
import sys
from generate_profile_catalog import Resolver


def generate(root: Path, output: Path) -> None:
    records = set()
    resolver = Resolver(root)
    rejected = 0
    for entry_id in range(len(resolver.entries)):
        try:
            record = dict(resolver.resolve(entry_id))
        except ValueError:
            rejected += 1
            continue
        # Resolve vendor-local common names before packaging, using the same
        # source resolver as the built-in catalog, never a global-name guess.
        record.pop("inherits", None)
        records.add(json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":")))
    output.parent.mkdir(parents=True, exist_ok=True)
    # Deterministic bytes; differing records with the same name stay ambiguous.
    output.write_bytes(gzip.compress(("\n".join(sorted(records)) + "\n").encode(), mtime=0))
    print(f"Packaged {len(records)} resolved source presets; {rejected} unresolved")


if __name__ == "__main__":
    generate(Path(sys.argv[1]), Path(sys.argv[2]))
