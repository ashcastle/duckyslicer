from __future__ import annotations

import json
import subprocess
import tarfile
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from tools.generate_source_bundle import (
    BUILD_INPUTS,
    create_source_bundle,
    verify_source_bundle,
)


class GenerateSourceBundleTest(unittest.TestCase):
    @staticmethod
    def git(repository: Path, *arguments: str) -> str:
        return subprocess.check_output(
            ["git", "-C", str(repository), *arguments], text=True
        ).strip()

    def initialize(self, path: Path, name: str) -> None:
        path.mkdir(parents=True)
        self.git(path, "init", "--quiet")
        self.git(path, "config", "user.name", "DuckySlicer Test")
        self.git(path, "config", "user.email", "test@duckyslicer.invalid")
        self.git(path, "remote", "add", "origin", f"https://example.invalid/{name}.git")

    def commit_all(self, path: Path, message: str) -> None:
        self.git(path, "add", "--all")
        self.git(path, "commit", "--quiet", "-m", message)

    def fixture(self, temporary: Path) -> Path:
        engine = temporary / "engine"
        self.initialize(engine, "engine")
        (engine / "engine.cpp").write_text("int slice();\n", encoding="utf-8")
        (engine / "AGENTS.md").write_text("private agent instruction\n", encoding="utf-8")
        self.commit_all(engine, "engine")

        runtime = temporary / "runtime"
        self.initialize(runtime, "runtime")
        (runtime / "runtime.cpp").write_text("int bridge();\n", encoding="utf-8")
        subprocess.check_call(
            [
                "git",
                "-c",
                "protocol.file.allow=always",
                "-C",
                str(runtime),
                "submodule",
                "add",
                "--quiet",
                str(engine),
                "app/src/main/cpp/orcaslicer",
            ]
        )
        self.commit_all(runtime, "runtime")

        root = temporary / "root"
        self.initialize(root, "duckyslicer")
        for relative in BUILD_INPUTS:
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            if relative == "native/slicer-runtime/versions.env":
                target.write_text(
                    f"ANDROID_SLICER_RUNTIME_COMMIT={self.git(runtime, 'rev-parse', 'HEAD')}\n"
                    f"SLICER_ENGINE_COMMIT={self.git(engine, 'rev-parse', 'HEAD')}\n",
                    encoding="utf-8",
                )
            else:
                target.write_bytes(f"fixture:{relative}\n".encode())
        subprocess.check_call(
            [
                "git",
                "-c",
                "protocol.file.allow=always",
                "-C",
                str(root),
                "submodule",
                "add",
                "--quiet",
                str(runtime),
                "third_party/android-slicer-runtime",
            ]
        )
        self.commit_all(root, "root")
        subprocess.check_call(
            [
                "git",
                "-c",
                "protocol.file.allow=always",
                "-C",
                str(root),
                "submodule",
                "update",
                "--init",
                "--recursive",
                "--quiet",
            ]
        )
        return root

    def test_bundle_is_deterministic_recursive_and_excludes_agent_instructions(self) -> None:
        with TemporaryDirectory() as directory:
            temporary = Path(directory)
            root = self.fixture(temporary)
            first = temporary / "first.tar.gz"
            second = temporary / "second.tar.gz"
            first_manifest = temporary / "first.json"
            second_manifest = temporary / "second.json"
            create_source_bundle(root, "1.2.3", 42, first, first_manifest)
            create_source_bundle(root, "1.2.3", 42, second, second_manifest)

            self.assertEqual(first.read_bytes(), second.read_bytes())
            verified = verify_source_bundle(first, first_manifest)
            self.assertEqual(3, len(verified["source"]["repositories"]))
            with tarfile.open(first, "r:gz") as archive:
                names = set(archive.getnames())
            self.assertIn(
                "DuckySlicer-1.2.3-source/third_party/android-slicer-runtime/"
                "app/src/main/cpp/orcaslicer/engine.cpp",
                names,
            )
            self.assertFalse(any(name.endswith("/AGENTS.md") for name in names))

    def test_verifier_rejects_changed_detached_manifest(self) -> None:
        with TemporaryDirectory() as directory:
            temporary = Path(directory)
            root = self.fixture(temporary)
            archive = temporary / "source.tar.gz"
            manifest = temporary / "manifest.json"
            create_source_bundle(root, "1.0.0", 7, archive, manifest)
            changed = json.loads(manifest.read_text(encoding="utf-8"))
            changed["source"]["rootCommit"] = "0" * 40
            manifest.write_text(json.dumps(changed), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "internal and detached"):
                verify_source_bundle(archive, manifest)


if __name__ == "__main__":
    unittest.main()
