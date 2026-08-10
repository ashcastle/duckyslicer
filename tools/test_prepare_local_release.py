from __future__ import annotations

import unittest

from tools.prepare_local_release import (
    MAX_VERSION_CODE,
    PACKAGE_NAME,
    ReleaseIdentity,
    ReleasePreparationError,
    gradle_release_command,
    mismatched_submodules,
    parse_badging,
    signing_variables,
    validate_release_inputs,
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
        )
        self.assertEqual(
            {
                "schemaVersion": 1,
                "project": "DuckySlicer",
                "packageName": PACKAGE_NAME,
                "versionName": "1.2.3",
                "versionCode": 42,
                "sourceCommit": "a" * 40,
                "unsignedAsset": "DuckySlicer-1.2.3-arm64-unsigned.apk",
                "unsignedSha256": "b" * 64,
            },
            identity.document(),
        )


if __name__ == "__main__":
    unittest.main()
