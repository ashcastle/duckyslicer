from __future__ import annotations

import unittest
from pathlib import Path
from unittest import mock

from tools.run_physical_qualification import RunnerError
from tools.run_startup_qualification import changed_benchmark


class RunStartupQualificationTest(unittest.TestCase):
    def test_requires_exactly_one_new_benchmark_result(self) -> None:
        first = Path("first.json")
        second = Path("second.json")
        with mock.patch(
            "tools.run_startup_qualification.benchmark_files",
            return_value={first: 2},
        ):
            self.assertEqual(first, changed_benchmark({first: 1}))
        with mock.patch(
            "tools.run_startup_qualification.benchmark_files",
            return_value={first: 2, second: 2},
        ):
            with self.assertRaisesRegex(RunnerError, "expected one"):
                changed_benchmark({})


if __name__ == "__main__":
    unittest.main()
