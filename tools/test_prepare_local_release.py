from __future__ import annotations

import os
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from tools.prepare_local_release import (
    EXPECTED_NATIVE_SYMBOL_ENTRIES,
    MAX_VERSION_CODE,
    PACKAGE_NAME,
    ReleaseIdentity,
    ReleasePreparationError,
    gradle_release_command,
    mismatched_submodules,
    parse_badging,
    prepare_release,
    signing_variables,
    validate_release_inputs,
    verify_release_diagnostics,
)


class PrepareLocalReleaseTest(unittest.TestCase):
    def test_preserves_clean_submodule_status_prefix(self) -> None:
        clean = " 0123456789abcdef third_party/runtime (heads/main)\n"
        self.assertEqual([], mismatched_submodules(clean))

        invalid = (
            "+123456789abcdef0 third_party/a (heads/main)\n"
            "-abcdef0123456789 third_party/b\n"
        )
        self.assertEqual(invalid.splitlines(), mismatched_submodules(invalid))

    def test_accepts_semver_and_bounded_version_code(self) -> None:
        validate_release_inputs("1.2.3", 1)
        validate_release_inputs("1.2.3-rc.4", MAX_VERSION_CODE)

    def test_rejects_tag_prefix_build_metadata_and_invalid_code(self) -> None:
        for version in ("v1.2.3", "01.2.3", "1.2", "1.2.3+local"):
            with self.subTest(version=version):
                with self.assertRaisesRegex(ReleasePreparationError, "SemVer"):
                    validate_release_inputs(version, 1)
        for version_code in (0, MAX_VERSION_CODE + 1):
            with self.subTest(version_code=version_code):
                with self.assertRaisesRegex(ReleasePreparationError, "version code"):
                    validate_release_inputs("1.2.3", version_code)

    def test_detects_every_local_or_hosted_signing_input(self) -> None:
        environment = {
            "DUCKYSLICER_KEYSTORE_FILE": "/secret/release.jks",
            "DUCKYSLICER_KEYSTORE_BASE64": "encoded",
            "DUCKYSLICER_STORE_PASSWORD": "password",
            "DUCKYSLICER_KEY_ALIAS": "release",
            "DUCKYSLICER_KEY_PASSWORD": "password",
        }
        self.assertEqual(sorted(environment), signing_variables(environment))

    def test_builds_same_identity_twice_and_cleans_only_second_build(self) -> None:
        first = gradle_release_command("2.0.0-rc.1", 42, rebuild=False)
        second = gradle_release_command("2.0.0-rc.1", 42, rebuild=True)
        for command in (first, second):
            self.assertIn("--dependency-verification=strict", command)
            self.assertIn("-Pduckyslicer.versionName=2.0.0-rc.1", command)
            self.assertIn("-Pduckyslicer.versionCode=42", command)
            self.assertIn(":app:assembleRelease", command)
        self.assertIn(":app:lintRelease", first)
        self.assertIn(":app:clean", first)
        self.assertIn("--no-build-cache", first)
        self.assertIn("--no-build-cache", second)
        self.assertIn(":app:clean", second)

    def test_parses_exact_android_package_identity(self) -> None:
        source = (
            f"package: name='{PACKAGE_NAME}' versionCode='42' "
            "versionName='1.2.3-rc.1' platformBuildVersionName=''\n"
        )
        self.assertEqual((PACKAGE_NAME, 42, "1.2.3-rc.1"), parse_badging(source))
        with self.assertRaisesRegex(ReleasePreparationError, "package identity"):
            parse_badging("application-label:'DuckySlicer'")

    def test_metadata_pins_commit_artifact_and_digest(self) -> None:
        identity = ReleaseIdentity(
            version_name="1.2.3",
            version_code=42,
            source_commit="a" * 40,
            unsigned_asset="DuckySlicer-1.2.3-arm64-unsigned.apk",
            unsigned_sha256="b" * 64,
            local_r8_mapping="DuckySlicer-1.2.3-LOCAL-R8-MAPPING.txt",
            local_r8_mapping_sha256="c" * 64,
            local_native_symbols="DuckySlicer-1.2.3-LOCAL-NATIVE-SYMBOLS.zip",
            local_native_symbols_sha256="d" * 64,
        )
        self.assertEqual(
            {
                "schemaVersion": 2,
                "project": "DuckySlicer",
                "packageName": PACKAGE_NAME,
                "versionName": "1.2.3",
                "versionCode": 42,
                "sourceCommit": "a" * 40,
                "unsignedAsset": "DuckySlicer-1.2.3-arm64-unsigned.apk",
                "unsignedSha256": "b" * 64,
                "localR8Mapping": "DuckySlicer-1.2.3-LOCAL-R8-MAPPING.txt",
                "localR8MappingSha256": "c" * 64,
                "localNativeSymbols": "DuckySlicer-1.2.3-LOCAL-NATIVE-SYMBOLS.zip",
                "localNativeSymbolsSha256": "d" * 64,
            },
            identity.document(),
        )

    def test_release_diagnostics_require_r8_and_all_native_symbols(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            mapping = root / "mapping.txt"
            symbols = root / "symbols.zip"
            mapping.write_bytes(b"# compiler: R8\n# compiler_version: test\n")
            with zipfile.ZipFile(symbols, "w") as archive:
                for name in EXPECTED_NATIVE_SYMBOL_ENTRIES:
                    archive.writestr(name, b"\x7fELF" + bytes(60))
            verify_release_diagnostics(mapping, symbols)

            mapping.write_text("not a mapping\n", encoding="utf-8")
            with self.assertRaisesRegex(ReleasePreparationError, "R8 mapping"):
                verify_release_diagnostics(mapping, symbols)

            mapping.write_bytes(b"# compiler: R8\n")
            with zipfile.ZipFile(symbols, "w") as archive:
                archive.writestr(
                    next(iter(EXPECTED_NATIVE_SYMBOL_ENTRIES)),
                    b"\x7fELF" + bytes(60),
                )
            with self.assertRaisesRegex(ReleasePreparationError, "allowlist"):
                verify_release_diagnostics(mapping, symbols)

    def test_failed_metadata_commit_removes_partial_outputs(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release_apk = root / "app-release-unsigned.apk"
            release_mapping = root / "mapping.txt"
            release_symbols = root / "native-debug-symbols.zip"
            output = root / "output"
            release_apk.write_bytes(b"reproducible release")
            release_mapping.write_bytes(b"# compiler: R8\n")
            with zipfile.ZipFile(release_symbols, "w") as archive:
                for name in EXPECTED_NATIVE_SYMBOL_ENTRIES:
                    archive.writestr(name, b"\x7fELF" + bytes(60))
            with (
                patch.dict(os.environ, {}, clear=True),
                patch("tools.prepare_local_release.RELEASE_APK", release_apk),
                patch("tools.prepare_local_release.RELEASE_MAPPING", release_mapping),
                patch(
                    "tools.prepare_local_release.RELEASE_NATIVE_SYMBOLS",
                    release_symbols,
                ),
                patch(
                    "tools.prepare_local_release.verify_checkout",
                    return_value="a" * 40,
                ),
                patch(
                    "tools.prepare_local_release.android_build_tools",
                    return_value=root,
                ),
                patch("tools.prepare_local_release.run") as run_mock,
                patch("tools.prepare_local_release.verify_unsigned_apk"),
                patch(
                    "tools.prepare_local_release.write_metadata",
                    side_effect=OSError("disk full"),
                ),
            ):
                with self.assertRaisesRegex(OSError, "disk full"):
                    prepare_release("1.2.3", 42, output)
            gate_command = run_mock.call_args_list[0].args[0]
            self.assertTrue(gate_command[1].endswith("tools/run_local_gate.py"))
            self.assertEqual("--require-api-36", gate_command[-1])
            self.assertEqual([], list(output.iterdir()))


if __name__ == "__main__":
    unittest.main()
