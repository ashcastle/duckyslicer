import gzip
import json
from pathlib import Path
import tempfile
import unittest

from generate_orca_import_parents import generate


class ImportParentsTest(unittest.TestCase):
    def test_vendor_local_inheritance_and_deterministic_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "profiles"
            for brand, speed in (("A", "35"), ("B", "100")):
                folder = root / brand / "process"
                folder.mkdir(parents=True)
                (folder / "base.json").write_text(json.dumps({
                    "type": "process", "name": "common", "outer_wall_speed": speed,
                }))
                (folder / "child.json").write_text(json.dumps({
                    "type": "process", "name": brand + " standard", "inherits": "common",
                }))
            first, second = Path(directory) / "first.bin", Path(directory) / "second.bin"
            generate(root, first)
            generate(root, second)
            self.assertEqual(first.read_bytes(), second.read_bytes())
            records = [json.loads(line) for line in gzip.decompress(first.read_bytes()).splitlines()]
            children = {item["name"]: item for item in records if item["name"] != "common"}
            self.assertEqual("35", children["A standard"]["outer_wall_speed"])
            self.assertEqual("100", children["B standard"]["outer_wall_speed"])
            self.assertNotIn("inherits", children["A standard"])
            self.assertEqual(2, sum(item["name"] == "common" for item in records))


if __name__ == "__main__":
    unittest.main()
