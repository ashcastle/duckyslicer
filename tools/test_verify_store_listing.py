from __future__ import annotations

import binascii
import json
import random
import struct
import tempfile
import unittest
import zlib
from pathlib import Path

from tools.verify_store_listing import StoreListingError, verify_store_listing


PRIVACY = """
com.ashcastle.duckyslicer
The official app has no developer-operated account, advertising, analytics.
The project does not collect, sell, or share app data.
Retention and deletion
수집·판매·공유하지 않습니다
"""


def _chunk(kind: bytes, payload: bytes) -> bytes:
    checksum = binascii.crc32(kind + payload) & 0xFFFFFFFF
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", checksum)


def write_png(
    path: Path,
    width: int,
    height: int,
    *,
    color_type: int,
    metadata: bool = False,
    detail_seed: int | None = None,
) -> None:
    channels = 4 if color_type == 6 else 3
    row = b"\x00" + bytes((246, 201, 69, 255)[:channels]) * width
    rows: list[bytes] = []
    if detail_seed is not None:
        generator = random.Random(detail_seed)
        detailed_rows = min(height, 24)
        rows.extend(
            b"\x00" + generator.randbytes(width * channels)
            for _ in range(detailed_rows)
        )
        rows.extend([row] * (height - detailed_rows))
    else:
        rows = [row] * height
    payload = zlib.compress(b"".join(rows), level=9)
    ihdr = struct.pack(">IIBBBBB", width, height, 8, color_type, 0, 0, 0)
    chunks = [_chunk(b"IHDR", ihdr)]
    if metadata:
        chunks.append(_chunk(b"tEXt", b"private-path\x00example"))
    chunks.extend((_chunk(b"IDAT", payload), _chunk(b"IEND", b"")))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + b"".join(chunks))


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def create_fixture(root: Path) -> Path:
    store = root / "distribution/google-play"
    full = {
        "en-US": (
            "Prepare prints offline with STL, OBJ, and 3MF models. Export G-code. "
            "No account is required. DuckySlicer is an open-source project. " * 3
        ),
        "ko-KR": (
            "오프라인으로 STL, OBJ, 3MF 모델을 준비하고 G-code로 내보냅니다. "
            "계정 없이 사용하는 오픈소스 프로젝트입니다. " * 5
        ),
    }
    full = {locale: value.strip() for locale, value in full.items()}
    short = {
        "en-US": "Slice 3D models on Android, even when offline.",
        "ko-KR": "안드로이드에서 3D 모델을 오프라인으로 슬라이스하세요.",
    }
    for locale in ("en-US", "ko-KR"):
        listing = store / "listings" / locale
        listing.mkdir(parents=True)
        (listing / "title.txt").write_text("DuckySlicer\n", encoding="utf-8")
        (listing / "short-description.txt").write_text(short[locale] + "\n", encoding="utf-8")
        (listing / "full-description.txt").write_text(full[locale] + "\n", encoding="utf-8")
    declarations = {
        "schemaVersion": 2,
        "packageName": "com.ashcastle.duckyslicer",
        "defaultLocale": "en-US",
        "listingLocales": ["en-US", "ko-KR"],
        "privacyPolicyUrl": "https://github.com/ashcastle/duckyslicer/blob/main/PRIVACY.md",
        "supportUrl": "https://github.com/ashcastle/duckyslicer/issues/new?template=support_question.yml",
        "appAccess": {"loginRequired": False},
        "monetization": {"containsAds": False},
        "dataSafety": {"collectsData": False, "sharesData": False},
        "foregroundServices": [
            {
                "type": "dataSync",
                "consoleUseCase": "Local processing: Other",
                "functionality": (
                    "A user taps Slice to convert selected 3D models and profiles into G-code "
                    "on the device. The foreground service keeps that explicitly requested "
                    "slice running when the app is no longer visible."
                ),
                "deferredImpact": (
                    "Deferring the work makes the user wait after tapping Slice and delays "
                    "the G-code they explicitly requested."
                ),
                "interruptedImpact": (
                    "Interrupting the work discards the in-progress slice because slicing "
                    "cannot resume from a checkpoint; the user must start the slice again."
                ),
                "userInitiated": True,
                "userPerceptible": True,
                "userStoppable": True,
                "runsOnlyWhileNecessary": True,
                "demoVideo": {
                    "externalUrlRequiredAtSubmission": True,
                    "repositoryStoresUrl": False,
                    "captureSteps": [
                        "Use a public or synthetic model that keeps slicing active long "
                        "enough to demonstrate the service.",
                        "Tap Slice in DuckySlicer and show the in-app progress state.",
                        "Leave DuckySlicer while slicing continues, then open the notification shade.",
                        "Show the slicing progress notification and its Cancel action.",
                        "Tap Cancel and show that the active slice stops.",
                        "Start the slice again, leave the app, return, and show the completed preview.",
                    ],
                },
            }
        ],
    }
    write_json(store / "console-declarations.json", declarations)
    screenshots = []
    for index in range(1, 5):
        relative = f"graphics/phone-screenshots/{index:02d}.png"
        screenshot = store / relative
        write_png(screenshot, 1080, 1920, color_type=2, detail_seed=index)
        screenshots.append(
            {
                "path": relative,
                "altText": {
                    "en-US": f"DuckySlicer application screen number {index}",
                    "ko-KR": f"DuckySlicer 앱 화면 {index}번 설명입니다",
                },
            }
        )
    write_png(store / "graphics/app-icon.png", 512, 512, color_type=6)
    write_png(store / "graphics/feature-graphic.png", 1024, 500, color_type=2)
    (store / "graphics/app-icon.svg").write_text(
        '<svg viewBox="0 0 512 512"><title>Icon</title>'
        '<circle fill="#F6C945"/><path fill="#202124"/></svg>\n',
        encoding="utf-8",
    )
    (store / "graphics/feature-graphic.svg").write_text(
        '<svg viewBox="0 0 1024 500"><title>Feature</title>'
        '<path fill="#F6C945" stroke="#202124"/></svg>\n',
        encoding="utf-8",
    )
    write_json(
        store / "assets.json",
        {
            "schemaVersion": 1,
            "capture": {
                "device": "Android 15 ARM64 16 KB emulator",
                "resolution": "1080x1920",
                "sourceModel": "tests/data/test_stl/ASCII/20mmbox-LF.stl",
                "containsPrivateData": False,
            },
            "appIcon": "graphics/app-icon.png",
            "featureGraphic": "graphics/feature-graphic.png",
            "phoneScreenshots": screenshots,
        },
    )
    source = root / "tests/data/test_stl/ASCII/20mmbox-LF.stl"
    source.parent.mkdir(parents=True)
    source.write_bytes(b"solid public\nendsolid public\n")
    manifest = root / "android/app/src/main/AndroidManifest.xml"
    manifest.parent.mkdir(parents=True)
    manifest.write_text(
        """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <application>
        <service android:name=".SlicerProcessService" android:exported="false"
            android:foregroundServiceType="dataSync" />
    </application>
</manifest>
""",
        encoding="utf-8",
    )
    kotlin_root = root / "android/app/src/main/java/com/ashcastle/duckyslicer"
    kotlin_root.mkdir(parents=True)
    (kotlin_root / "SlicerProcessService.kt").write_text(
        """context.startForegroundService(SlicerProcessService.startSliceIntent(context, requestId))
ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
ACTION_CANCEL_SLICE
cancelIntent
.addAction(
getString(R.string.slice_notification_progress
getString(R.string.cancel)
stopForeground(STOP_FOREGROUND_REMOVE)
stopSelf()
return START_NOT_STICKY
""",
        encoding="utf-8",
    )
    (kotlin_root / "MainActivity.kt").write_text(
        "val sliceStartControls = rememberSliceStartControls(\n"
        "if (allPlates) sliceStartControls.startAll() else sliceStartControls.startSelected()\n"
        "onCancelSlice = sliceStartControls.cancel\n",
        encoding="utf-8",
    )
    (kotlin_root / "PlateSliceBatchEffect.kt").write_text(
        "fun beginSelected() {}\nfun beginAll() {}\nfun request(all: Boolean) {}\n"
        "startSelected = { request(false) }\nstartAll = { request(true) }\n"
        "operationModel.cancel()\n",
        encoding="utf-8",
    )
    (kotlin_root / "WorkspaceScreen.kt").write_text(
        "onSlice = { gcodePreviewImportModel.clearDocument(); onSlice(false) }\n"
        "onSliceAll = { gcodePreviewImportModel.clearDocument(); onSlice(true) }\n"
        "onClick = onSlice\nonClick = onSliceAll\nonClick = onCancelSlice\n",
        encoding="utf-8",
    )
    return store


class VerifyStoreListingTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.store = create_fixture(self.root)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def verify(self) -> None:
        verify_store_listing(self.store, self.root, PRIVACY)

    def test_accepts_reviewed_listing(self) -> None:
        self.verify()

    def test_rejects_overlong_short_description(self) -> None:
        path = self.store / "listings/en-US/short-description.txt"
        path.write_text("x" * 81 + "\n", encoding="utf-8")
        with self.assertRaisesRegex(StoreListingError, "limit is 80"):
            self.verify()

    def test_rejects_promotional_claim(self) -> None:
        path = self.store / "listings/en-US/full-description.txt"
        path.write_text(path.read_text(encoding="utf-8").replace("Prepare", "Best") , encoding="utf-8")
        with self.assertRaisesRegex(StoreListingError, "promotional copy"):
            self.verify()

    def test_rejects_technical_store_copy(self) -> None:
        path = self.store / "listings/en-US/full-description.txt"
        path.write_text(path.read_text(encoding="utf-8").replace("prints", "ARM64 prints", 1), encoding="utf-8")
        with self.assertRaisesRegex(StoreListingError, "implementation wording"):
            self.verify()

    def test_rejects_alpha_channel_in_screenshot(self) -> None:
        path = self.store / "graphics/phone-screenshots/01.png"
        write_png(path, 1080, 1920, color_type=6)
        with self.assertRaisesRegex(StoreListingError, "24-bit RGB"):
            self.verify()

    def test_rejects_wrong_screenshot_dimensions(self) -> None:
        path = self.store / "graphics/phone-screenshots/01.png"
        write_png(path, 1080, 2400, color_type=2)
        with self.assertRaisesRegex(StoreListingError, "1080x1920"):
            self.verify()

    def test_rejects_image_metadata(self) -> None:
        path = self.store / "graphics/feature-graphic.png"
        write_png(path, 1024, 500, color_type=2, metadata=True)
        with self.assertRaisesRegex(StoreListingError, "removable metadata"):
            self.verify()

    def test_rejects_unreviewed_data_collection_change(self) -> None:
        path = self.store / "console-declarations.json"
        source = json.loads(path.read_text(encoding="utf-8"))
        source["dataSafety"]["collectsData"] = True
        write_json(path, source)
        with self.assertRaisesRegex(StoreListingError, "Data safety changed"):
            self.verify()

    def test_rejects_missing_foreground_service_declaration(self) -> None:
        path = self.store / "console-declarations.json"
        source = json.loads(path.read_text(encoding="utf-8"))
        del source["foregroundServices"]
        write_json(path, source)
        with self.assertRaisesRegex(StoreListingError, "keys changed"):
            self.verify()

    def test_rejects_changed_foreground_service_type(self) -> None:
        path = self.store / "console-declarations.json"
        source = json.loads(path.read_text(encoding="utf-8"))
        source["foregroundServices"][0]["type"] = "specialUse"
        write_json(path, source)
        with self.assertRaisesRegex(StoreListingError, "implementation review"):
            self.verify()

    def test_rejects_non_stoppable_foreground_service_claim(self) -> None:
        path = self.store / "console-declarations.json"
        source = json.loads(path.read_text(encoding="utf-8"))
        source["foregroundServices"][0]["userStoppable"] = False
        write_json(path, source)
        with self.assertRaisesRegex(StoreListingError, "implementation review"):
            self.verify()

    def test_rejects_missing_demo_capture_step(self) -> None:
        path = self.store / "console-declarations.json"
        source = json.loads(path.read_text(encoding="utf-8"))
        source["foregroundServices"][0]["demoVideo"]["captureSteps"].pop()
        write_json(path, source)
        with self.assertRaisesRegex(StoreListingError, "implementation review"):
            self.verify()

    def test_rejects_manifest_foreground_service_drift(self) -> None:
        path = self.root / "android/app/src/main/AndroidManifest.xml"
        path.write_text(
            path.read_text(encoding="utf-8").replace("dataSync", "specialUse"),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(StoreListingError, "private dataSync"):
            self.verify()

    def test_rejects_an_undeclared_additional_foreground_service(self) -> None:
        path = self.root / "android/app/src/main/AndroidManifest.xml"
        path.write_text(
            path.read_text(encoding="utf-8").replace(
                "</application>",
                '<service android:name=".OtherService" android:exported="false" '
                'android:foregroundServiceType="specialUse" />\n    </application>',
            ),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(StoreListingError, "reviewed Play declaration"):
            self.verify()

    def test_rejects_missing_notification_cancel_action(self) -> None:
        path = self.root / (
            "android/app/src/main/java/com/ashcastle/duckyslicer/SlicerProcessService.kt"
        )
        path.write_text(
            path.read_text(encoding="utf-8").replace("getString(R.string.cancel)", ""),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(StoreListingError, "supports its declaration"):
            self.verify()

    def test_rejects_slice_ui_without_user_cancel_action(self) -> None:
        path = self.root / (
            "android/app/src/main/java/com/ashcastle/duckyslicer/WorkspaceScreen.kt"
        )
        path.write_text(
            path.read_text(encoding="utf-8").replace("onClick = onCancelSlice", ""),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(StoreListingError, "demonstrably user initiated"):
            self.verify()

    def test_rejects_missing_localized_alt_text(self) -> None:
        path = self.store / "assets.json"
        source = json.loads(path.read_text(encoding="utf-8"))
        del source["phoneScreenshots"][0]["altText"]["ko-KR"]
        write_json(path, source)
        with self.assertRaisesRegex(StoreListingError, "alt text"):
            self.verify()

    def test_rejects_missing_core_offline_behavior(self) -> None:
        path = self.store / "listings/en-US/full-description.txt"
        path.write_text(
            path.read_text(encoding="utf-8").replace("offline", "without a network"),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(StoreListingError, "omits core behavior"):
            self.verify()

    def test_rejects_unsafe_asset_path(self) -> None:
        path = self.store / "assets.json"
        source = json.loads(path.read_text(encoding="utf-8"))
        source["phoneScreenshots"][0]["path"] = "../outside.png"
        write_json(path, source)
        with self.assertRaisesRegex(StoreListingError, "path is unsafe"):
            self.verify()

    def test_rejects_repeated_screenshot_path(self) -> None:
        path = self.store / "assets.json"
        source = json.loads(path.read_text(encoding="utf-8"))
        source["phoneScreenshots"][1]["path"] = source["phoneScreenshots"][0]["path"]
        write_json(path, source)
        with self.assertRaisesRegex(StoreListingError, "repeats an earlier image"):
            self.verify()

    def test_rejects_duplicate_screenshot_content(self) -> None:
        first = self.store / "graphics/phone-screenshots/01.png"
        second = self.store / "graphics/phone-screenshots/02.png"
        second.write_bytes(first.read_bytes())
        with self.assertRaisesRegex(StoreListingError, "duplicates the content"):
            self.verify()

    def test_rejects_placeholder_screenshot(self) -> None:
        path = self.store / "graphics/phone-screenshots/01.png"
        write_png(path, 1080, 1920, color_type=2)
        with self.assertRaisesRegex(StoreListingError, "looks like a placeholder"):
            self.verify()

    def test_rejects_unlisted_store_image(self) -> None:
        write_png(
            self.store / "graphics/phone-screenshots/unused.png",
            1080,
            1920,
            color_type=2,
            detail_seed=99,
        )
        with self.assertRaisesRegex(StoreListingError, "Unreviewed store images"):
            self.verify()

    def test_rejects_rgb_app_icon(self) -> None:
        write_png(self.store / "graphics/app-icon.png", 512, 512, color_type=2)
        with self.assertRaisesRegex(StoreListingError, "32-bit RGBA"):
            self.verify()

    def test_rejects_private_capture_provenance(self) -> None:
        path = self.store / "assets.json"
        source = json.loads(path.read_text(encoding="utf-8"))
        source["capture"]["containsPrivateData"] = True
        write_json(path, source)
        with self.assertRaisesRegex(StoreListingError, "public capture provenance"):
            self.verify()

    def test_rejects_missing_vector_source(self) -> None:
        (self.store / "graphics/app-icon.svg").unlink()
        with self.assertRaisesRegex(StoreListingError, "app icon source is missing"):
            self.verify()

    def test_rejects_text_in_feature_graphic(self) -> None:
        path = self.store / "graphics/feature-graphic.svg"
        path.write_text(
            path.read_text(encoding="utf-8").replace("</svg>", "<text>DuckySlicer</text></svg>"),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(StoreListingError, "language-neutral"):
            self.verify()


if __name__ == "__main__":
    unittest.main()
