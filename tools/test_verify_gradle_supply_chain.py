from __future__ import annotations

import tempfile
import textwrap
import unittest
from pathlib import Path
from unittest import mock

from tools import verify_gradle_supply_chain as supply_chain


SHA256 = "a" * 64


def metadata_xml(
    *,
    hosts: tuple[str, ...] = ("linux", "osx", "windows"),
    extra_configuration: str = "",
    duplicate_checksum: bool = False,
) -> str:
    artifacts = []
    for index, host in enumerate(hosts):
        second_checksum = (
            f'<sha256 value="{"b" * 64}"/>'
            if duplicate_checksum and index == 0
            else ""
        )
        artifacts.append(
            f"""
            <artifact name="aapt2-1.0-{host}.jar">
               <sha256 value="{SHA256}"/>
               {second_checksum}
            </artifact>
            """
        )
    return textwrap.dedent(
        f"""\
        <?xml version="1.0" encoding="UTF-8"?>
        <verification-metadata xmlns="https://schema.gradle.org/dependency-verification">
           <configuration>
              <verify-metadata>true</verify-metadata>
              <verify-signatures>false</verify-signatures>
              {extra_configuration}
           </configuration>
           <components>
              <component group="com.android.tools.build" name="aapt2" version="1.0">
                 {''.join(artifacts)}
              </component>
           </components>
        </verification-metadata>
        """
    )


class VerifyGradleSupplyChainTest(unittest.TestCase):
    def verify_xml(self, source: str) -> tuple[int, int]:
        with tempfile.TemporaryDirectory() as directory:
            metadata = Path(directory) / "verification-metadata.xml"
            metadata.write_text(source, encoding="utf-8")
            with mock.patch.object(supply_chain, "METADATA", metadata):
                return supply_chain.verify_metadata()

    def test_accepts_one_sha256_for_every_host_aapt2(self) -> None:
        self.assertEqual((1, 3), self.verify_xml(metadata_xml()))

    def test_rejects_nested_trusted_artifact_bypass(self) -> None:
        source = metadata_xml(
            extra_configuration=(
                '<trusted-artifacts><trust group="example"/></trusted-artifacts>'
            )
        )
        with self.assertRaisesRegex(supply_chain.VerificationError, "must not bypass"):
            self.verify_xml(source)

    def test_rejects_multiple_accepted_hashes_for_one_artifact(self) -> None:
        with self.assertRaisesRegex(supply_chain.VerificationError, "must have one SHA-256"):
            self.verify_xml(metadata_xml(duplicate_checksum=True))

    def test_rejects_missing_host_aapt2_checksum(self) -> None:
        with self.assertRaisesRegex(supply_chain.VerificationError, "AAPT2 checksums are missing"):
            self.verify_xml(metadata_xml(hosts=("linux", "osx")))

    def test_dynamic_version_detection(self) -> None:
        for version in ("1.+", "latest.release", "[1,2)", "2.0-SNAPSHOT"):
            with self.subTest(version=version):
                self.assertTrue(supply_chain.is_dynamic(version))
        self.assertFalse(supply_chain.is_dynamic("2.3.21"))

    def test_combines_dependency_locks_from_every_android_module(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app_lock = root / "app.lock"
            profile_lock = root / "profile.lock"
            app_lock.write_text("example:app:1.0=debugRuntimeClasspath\n", encoding="utf-8")
            profile_lock.write_text(
                "example:benchmark:2.0=nonMinifiedReleaseRuntimeClasspath\n",
                encoding="utf-8",
            )
            with mock.patch.object(
                supply_chain,
                "LOCKFILES",
                (app_lock, profile_lock),
            ):
                self.assertEqual(
                    2,
                    supply_chain.verify_lockfiles(
                        {"example:app:1.0", "example:benchmark:2.0"},
                    ),
                )


if __name__ == "__main__":
    unittest.main()
