from __future__ import annotations

import os
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from tools.prepare_local_play_bundle import (
    EXPECTED_NATIVE_ENTRIES,
    R8_MAPPING_ENTRY,
    REQUIRED_DEBUG_SYMBOL_ENTRIES,
    PlayBundleIdentity,
    gradle_play_command,
    play_transport_tag,
    prepare_play_bundle,
    signing_variables,
    verify_unsigned_bundle,
)
from tools.prepare_local_release import PACKAGE_NAME, ReleasePreparationError


def write_bundle(
    path: Path,
    *,
    extra_entries: dict[str, bytes] | None = None,
    omitted_entries: frozenset[str] = frozenset(),
) -> None:
    entries = {
        "BundleConfig.pb": b"config",
        "base/manifest/AndroidManifest.xml": b"manifest",
        R8_MAPPING_ENTRY: b"# compiler: R8\n",
        **{name: b"elf" for name in EXPECTED_NATIVE_ENTRIES},
        **{
            name: b"\x7fELF" + bytes(60)
            for name in REQUIRED_DEBUG_SYMBOL_ENTRIES
        },
        **(extra_entries or {}),
    }
    with zipfile.ZipFile(path, "w") as archive:
        for name, data in entries.items():
            if name not in omitted_entries:
                archive.writestr(name, data)


class PrepareLocalPlayBundleTest(unittest.TestCase):
    def test_builds_two_clean_cache_disabled_bundle_candidates(self) -> None:
        first = gradle_play_command("1.2.3-rc.1", 42, include_lint=True)
        second = gradle_play_command("1.2.3-rc.1", 42, include_lint=False)
        for command in (first, second):
            self.assertIn("--dependency-verification=strict", command)
            self.assertIn("--no-build-cache", command)
            self.assertIn(":app:clean", command)
            self.assertIn(":app:bundleRelease", command)
            self.assertIn(":app:packageReleaseUniversalApk", command)
            self.assertIn("-Pduckyslicer.versionName=1.2.3-rc.1", command)
            self.assertIn("-Pduckyslicer.versionCode=42", command)
        self.assertIn(":app:lintRelease", first)
        self.assertNotIn(":app:lintRelease", second)

    def test_refuses_every_release_or_play_signing_input(self) -> None:
        environment = {
            "DUCKYSLICER_KEYSTORE_FILE": "/secret/release.jks",
            "DUCKYSLICER_KEYSTORE_BASE64": "release",
            "DUCKYSLICER_STORE_PASSWORD": "release",
            "DUCKYSLICER_KEY_ALIAS": "release",
            "DUCKYSLICER_KEY_PASSWORD": "release",
            "DUCKYSLICER_PLAY_KEYSTORE_FILE": "/secret/play.jks",
            "DUCKYSLICER_PLAY_KEYSTORE_BASE64": "play",
            "DUCKYSLICER_PLAY_STORE_PASSWORD": "play",
            "DUCKYSLICER_PLAY_KEY_ALIAS": "play",
            "DUCKYSLICER_PLAY_KEY_PASSWORD": "play",
        }
        self.assertEqual(sorted(environment), signing_variables(environment))

    def test_accepts_only_unsigned_arm64_bundle_structure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            bundle = Path(directory) / "candidate.aab"
            write_bundle(bundle)
            verify_unsigned_bundle(bundle)

            signed = Path(directory) / "signed.aab"
            write_bundle(signed, extra_entries={"META-INF/UPLOAD.RSA": b"signature"})
            with self.assertRaisesRegex(ReleasePreparationError, "remain unsigned"):
                verify_unsigned_bundle(signed)

            wrong_abi = Path(directory) / "wrong-abi.aab"
            write_bundle(
                wrong_abi,
                extra_entries={"base/lib/x86_64/libextra.so": b"elf"},
            )
            with self.assertRaisesRegex(ReleasePreparationError, "ARM64 allowlist"):
                verify_unsigned_bundle(wrong_abi)

    def test_rejects_missing_or_invalid_play_diagnostics(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            missing = root / "missing.aab"
            write_bundle(missing, omitted_entries=frozenset({R8_MAPPING_ENTRY}))
            with self.assertRaisesRegex(ReleasePreparationError, "diagnostics"):
                verify_unsigned_bundle(missing)

            invalid = root / "invalid.aab"
            symbol = next(iter(REQUIRED_DEBUG_SYMBOL_ENTRIES))
            write_bundle(invalid, extra_entries={symbol: b"not an ELF"})
            with self.assertRaisesRegex(ReleasePreparationError, "debug symbols"):
                verify_unsigned_bundle(invalid)

    def test_metadata_pins_source_bundle_delivery_and_transport(self) -> None:
        identity = PlayBundleIdentity(
            version_name="1.2.3",
            version_code=42,
            source_commit="a" * 40,
            transport_tag=play_transport_tag("1.2.3", 42),
            unsigned_asset="DuckySlicer-1.2.3-play-unsigned.aab",
            unsigned_sha256="b" * 64,
            delivery_apk="DuckySlicer-1.2.3-play-universal-unsigned.apk",
            delivery_apk_sha256="c" * 64,
        )
        self.assertEqual(
            {
                "schemaVersion": 1,
                "project": "DuckySlicer",
                "packageName": PACKAGE_NAME,
                "versionName": "1.2.3",
                "versionCode": 42,
                "sourceCommit": "a" * 40,
                "transportTag": "play-v1.2.3-42",
                "unsignedAsset": "DuckySlicer-1.2.3-play-unsigned.aab",
                "unsignedSha256": "b" * 64,
                "deliveryApk": "DuckySlicer-1.2.3-play-universal-unsigned.apk",
                "deliveryApkSha256": "c" * 64,
            },
            identity.document(),
        )

    def test_failed_metadata_commit_removes_partial_outputs(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bundle = root / "app-release.aab"
            delivery = root / "app-release-universal-unsigned.apk"
            output = root / "output"
            bundle.write_bytes(b"reproducible bundle")
            delivery.write_bytes(b"reproducible delivery")
            with (
                patch.dict(os.environ, {}, clear=True),
                patch("tools.prepare_local_play_bundle.RELEASE_AAB", bundle),
                patch("tools.prepare_local_play_bundle.DELIVERY_APK", delivery),
                patch(
                    "tools.prepare_local_play_bundle.verify_checkout",
                    return_value="a" * 40,
                ),
                patch(
                    "tools.prepare_local_play_bundle.android_build_tools",
                    return_value=root,
                ),
                patch("tools.prepare_local_play_bundle.run") as run_mock,
                patch("tools.prepare_local_play_bundle.verify_play_candidate"),
                patch(
                    "tools.prepare_local_play_bundle.write_metadata",
                    side_effect=OSError("disk full"),
                ),
            ):
                with self.assertRaisesRegex(OSError, "disk full"):
                    prepare_play_bundle("1.2.3", 42, output)
            gate_command = run_mock.call_args_list[0].args[0]
            self.assertTrue(gate_command[1].endswith("tools/run_local_gate.py"))
            self.assertEqual("--require-api-36", gate_command[-1])
            self.assertEqual([], list(output.iterdir()))


if __name__ == "__main__":
    unittest.main()
