from __future__ import annotations

import unittest

from tools.generate_offline_licenses import (
    BundleError,
    discover_vendored_directories,
    render_bundle,
    verify_vendored_policy,
)
from tools.native_license_policy import expected_vendored_directories


class GenerateOfflineLicensesTest(unittest.TestCase):
    def test_discovers_every_vendored_dependency_from_ninja_output(self) -> None:
        source = "target.o: #deps 17, deps mtime 1 (VALID)\n" + "\n".join(
            f"    /tmp/orcaslicer/deps_src/{name}/source.h"
            for name in sorted(expected_vendored_directories())
        )
        self.assertEqual(expected_vendored_directories(), discover_vendored_directories(source))
        verify_vendored_policy(source)

    def test_rejects_unreviewed_vendored_dependency(self) -> None:
        source = "target.o: #deps 18, deps mtime 1 (VALID)\n" + "\n".join(
            [
                *(f"    /tmp/orcaslicer/deps_src/{name}/source.h" for name in expected_vendored_directories()),
                "    /tmp/orcaslicer/deps_src/new-library/source.h",
            ]
        )
        with self.assertRaisesRegex(BundleError, "new-library"):
            verify_vendored_policy(source)

    def test_ignores_stale_ninja_dependency_records(self) -> None:
        source = "target.o: #deps 1, deps mtime 1 (STALE)\n" \
            "    /tmp/orcaslicer/deps_src/removed-library/source.h\n"
        self.assertEqual(frozenset(), discover_vendored_directories(source))

    def test_bundle_is_deterministic_and_deduplicates_documents(self) -> None:
        records = [
            ("pkg:test/b@1", "MIT", (("b/LICENSE", "same text\r\n"),)),
            ("pkg:test/a@1", "MIT", (("a/LICENSE", "same text\n"),)),
        ]
        first = render_bundle("Summary", records)
        second = render_bundle("Summary", list(reversed(records)))
        self.assertEqual(first, second)
        self.assertEqual(1, first.count("same text"))
        self.assertLess(first.index("pkg:test/a@1"), first.index("pkg:test/b@1"))

    def test_rejects_component_without_documents(self) -> None:
        with self.assertRaisesRegex(BundleError, "no reviewed license material"):
            render_bundle("Summary", [("pkg:test/a@1", "MIT", ())])


if __name__ == "__main__":
    unittest.main()
