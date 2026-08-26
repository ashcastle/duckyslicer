#!/usr/bin/env python3
"""Verify the checked-in Google Play listing copy and graphics."""

from __future__ import annotations

import binascii
import hashlib
import json
import re
import struct
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path, PurePosixPath


ROOT = Path(__file__).resolve().parent.parent
STORE_ROOT = ROOT / "distribution/google-play"
EXPECTED_LOCALES = ("en-US", "ko-KR")
EXPECTED_PACKAGE = "com.ashcastle.duckyslicer"
EXPECTED_PRIVACY_URL = (
    "https://github.com/ashcastle/duckyslicer/blob/main/PRIVACY.md"
)
EXPECTED_SUPPORT_URL = (
    "https://github.com/ashcastle/duckyslicer/issues/new?template=support_question.yml"
)
EXPECTED_SOURCE_MODEL = "tests/data/test_stl/ASCII/20mmbox-LF.stl"
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
PNG_METADATA_CHUNKS = {b"eXIf", b"iTXt", b"tEXt", b"tIME", b"zTXt"}
PNG_CRITICAL_CHUNKS = {b"IHDR", b"PLTE", b"IDAT", b"IEND"}
MARKETING_PATTERNS = (
    re.compile(r"(?i)(?:^|\W)(?:best|free|million downloads|new|top|#1)(?:$|\W)"),
    re.compile(r"(?i)\b(?:download|install|try) now\b"),
    re.compile(r"(?:최고|무료|1위|지금\s*(?:다운로드|설치|사용))"),
    re.compile(r"[★☆]"),
)
TECHNICAL_COPY = (
    "16 KB",
    "API 35",
    "ARM64",
    "C++",
    "JNI",
    "Rust",
    "memory leak",
    "메모리 누수",
)
CORE_COPY = {
    "en-US": ("offline", "STL", "OBJ", "3MF", "G-code", "No account", "open-source"),
    "ko-KR": ("오프라인", "STL", "OBJ", "3MF", "G-code", "계정", "오픈소스"),
}
EXPECTED_FOREGROUND_SERVICE = {
    "type": "dataSync",
    "consoleUseCase": "Local processing: Other",
    "functionality": (
        "A user taps Slice to convert selected 3D models and profiles into G-code on the "
        "device. The foreground service keeps that explicitly requested slice running when "
        "the app is no longer visible."
    ),
    "deferredImpact": (
        "Deferring the work makes the user wait after tapping Slice and delays the G-code "
        "they explicitly requested."
    ),
    "interruptedImpact": (
        "Interrupting the work discards the in-progress slice because slicing cannot resume "
        "from a checkpoint; the user must start the slice again."
    ),
    "userInitiated": True,
    "userPerceptible": True,
    "userStoppable": True,
    "runsOnlyWhileNecessary": True,
    "demoVideo": {
        "externalUrlRequiredAtSubmission": True,
        "repositoryStoresUrl": False,
        "captureSteps": [
            "Use a public or synthetic model that keeps slicing active long enough to "
            "demonstrate the service.",
            "Tap Slice in DuckySlicer and show the in-app progress state.",
            "Leave DuckySlicer while slicing continues, then open the notification shade.",
            "Show the slicing progress notification and its Cancel action.",
            "Tap Cancel and show that the active slice stops.",
            "Start the slice again, leave the app, return, and show the completed preview.",
        ],
    },
}
ANDROID_NAMESPACE = "{http://schemas.android.com/apk/res/android}"


class StoreListingError(ValueError):
    """The listing no longer satisfies the reviewed Play contract."""


@dataclass(frozen=True)
class PngInfo:
    width: int
    height: int
    bit_depth: int
    color_type: int
    chunks: frozenset[bytes]


def _exact_keys(value: object, expected: set[str], name: str) -> dict[str, object]:
    if not isinstance(value, dict):
        raise StoreListingError(f"{name} must be a JSON object")
    actual = set(value)
    if actual != expected:
        raise StoreListingError(
            f"{name} keys changed: expected {sorted(expected)}, found {sorted(actual)}"
        )
    return value


def _read_json(path: Path) -> dict[str, object]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise StoreListingError(f"Could not read {path.name}: {error}") from error
    if not isinstance(value, dict):
        raise StoreListingError(f"{path.name} must contain a JSON object")
    return value


def _read_clean_text(path: Path) -> str:
    try:
        raw = path.read_bytes()
        source = raw.decode("utf-8")
    except (OSError, UnicodeError) as error:
        raise StoreListingError(f"Could not read {path}: {error}") from error
    if not raw or raw.startswith(b"\xef\xbb\xbf") or b"\x00" in raw or "\r" in source:
        raise StoreListingError(f"{path} must be plain UTF-8 with LF line endings")
    if source.rstrip("\n") + "\n" != source:
        raise StoreListingError(f"{path} must end with exactly one newline")
    if any(line != line.rstrip() for line in source.splitlines()):
        raise StoreListingError(f"{path} contains trailing whitespace")
    return source[:-1]


def _verify_copy(locale: str, listing: Path) -> None:
    fields = {
        "title": (_read_clean_text(listing / "title.txt"), 30),
        "short description": (
            _read_clean_text(listing / "short-description.txt"),
            80,
        ),
        "full description": (
            _read_clean_text(listing / "full-description.txt"),
            4_000,
        ),
    }
    title = fields["title"][0]
    short = fields["short description"][0]
    full = fields["full description"][0]
    if "\n" in title or "\n" in short:
        raise StoreListingError(f"{locale} title and short description must be one line")
    if title != "DuckySlicer":
        raise StoreListingError(f"{locale} store title changed from the project identity")
    if len(full) < 300:
        raise StoreListingError(f"{locale} full description is too short to explain the app")
    combined = "\n".join(value for value, _ in fields.values())
    for field_name, (value, limit) in fields.items():
        if not value or len(value) > limit:
            raise StoreListingError(
                f"{locale} {field_name} has {len(value)} characters; limit is {limit}"
            )
    for pattern in MARKETING_PATTERNS:
        if match := pattern.search(combined):
            raise StoreListingError(
                f"{locale} listing contains disallowed promotional copy: {match.group(0)!r}"
            )
    for marker in TECHNICAL_COPY:
        if marker.lower() in combined.lower():
            raise StoreListingError(
                f"{locale} user-facing listing contains implementation wording: {marker}"
            )
    if "OrcaSlicer" in combined:
        raise StoreListingError(
            f"{locale} store copy must describe DuckySlicer; attribution belongs in legal notices"
        )
    missing = [marker for marker in CORE_COPY[locale] if marker not in full]
    if missing:
        raise StoreListingError(f"{locale} full description omits core behavior: {missing}")


def _asset_path(root: Path, value: object, name: str) -> Path:
    if not isinstance(value, str) or not value:
        raise StoreListingError(f"{name} path must be a non-empty string")
    relative = PurePosixPath(value)
    if relative.is_absolute() or ".." in relative.parts or relative.as_posix() != value:
        raise StoreListingError(f"{name} path is unsafe: {value}")
    candidate = root.joinpath(*relative.parts)
    if candidate.is_symlink() or not candidate.is_file():
        raise StoreListingError(f"{name} is missing or is a symlink: {value}")
    resolved_root = root.resolve()
    if resolved_root not in candidate.resolve().parents:
        raise StoreListingError(f"{name} escapes the listing directory: {value}")
    return candidate


def _png_info(path: Path, maximum_bytes: int) -> PngInfo:
    size = path.stat().st_size
    if size not in range(1, maximum_bytes + 1):
        raise StoreListingError(
            f"{path.name} is {size} bytes; maximum is {maximum_bytes}"
        )
    raw = path.read_bytes()
    if not raw.startswith(PNG_SIGNATURE):
        raise StoreListingError(f"{path.name} is not a PNG")
    offset = len(PNG_SIGNATURE)
    chunks: set[bytes] = set()
    ihdr: bytes | None = None
    saw_iend = False
    while offset < len(raw):
        if offset + 12 > len(raw):
            raise StoreListingError(f"{path.name} has a truncated PNG chunk")
        length = struct.unpack_from(">I", raw, offset)[0]
        chunk_type = raw[offset + 4 : offset + 8]
        payload_start = offset + 8
        payload_end = payload_start + length
        crc_end = payload_end + 4
        if crc_end > len(raw):
            raise StoreListingError(f"{path.name} has an invalid PNG chunk length")
        expected_crc = struct.unpack_from(">I", raw, payload_end)[0]
        actual_crc = binascii.crc32(chunk_type + raw[payload_start:payload_end]) & 0xFFFFFFFF
        if actual_crc != expected_crc:
            raise StoreListingError(f"{path.name} has an invalid {chunk_type!r} checksum")
        if chunk_type[:1].isupper() and chunk_type not in PNG_CRITICAL_CHUNKS:
            raise StoreListingError(
                f"{path.name} contains unsupported critical chunk {chunk_type!r}"
            )
        if not chunks and chunk_type != b"IHDR":
            raise StoreListingError(f"{path.name} does not begin with IHDR")
        chunks.add(chunk_type)
        if chunk_type == b"IHDR":
            if ihdr is not None or length != 13:
                raise StoreListingError(f"{path.name} has an invalid IHDR")
            ihdr = raw[payload_start:payload_end]
        if chunk_type == b"IEND":
            if length != 0 or crc_end != len(raw):
                raise StoreListingError(f"{path.name} has data after IEND")
            saw_iend = True
            break
        offset = crc_end
    if ihdr is None or not saw_iend or b"IDAT" not in chunks:
        raise StoreListingError(f"{path.name} is an incomplete PNG")
    metadata = sorted(chunk.decode("ascii") for chunk in chunks & PNG_METADATA_CHUNKS)
    if metadata:
        raise StoreListingError(f"{path.name} contains removable metadata: {metadata}")
    width, height, bit_depth, color_type, compression, filtering, interlace = struct.unpack(
        ">IIBBBBB", ihdr
    )
    if compression != 0 or filtering != 0 or interlace not in (0, 1):
        raise StoreListingError(f"{path.name} uses unsupported PNG encoding")
    return PngInfo(width, height, bit_depth, color_type, frozenset(chunks))


def _verify_png(
    path: Path,
    *,
    dimensions: tuple[int, int],
    color_type: int,
    maximum_bytes: int,
) -> None:
    info = _png_info(path, maximum_bytes)
    if (info.width, info.height) != dimensions:
        raise StoreListingError(
            f"{path.name} is {info.width}x{info.height}; expected {dimensions[0]}x{dimensions[1]}"
        )
    if info.bit_depth != 8 or info.color_type != color_type:
        color = "32-bit RGBA" if color_type == 6 else "24-bit RGB without alpha"
        raise StoreListingError(f"{path.name} must be {color}")


def _verify_foreground_service_sources(repository_root: Path) -> None:
    manifest_path = repository_root / "android/app/src/main/AndroidManifest.xml"
    try:
        manifest = ET.fromstring(manifest_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, ET.ParseError) as error:
        raise StoreListingError(f"Could not inspect Android manifest: {error}") from error

    permissions = {
        permission.get(f"{ANDROID_NAMESPACE}name")
        for permission in manifest.findall("uses-permission")
    }
    required_permissions = {
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
        "android.permission.POST_NOTIFICATIONS",
    }
    if not required_permissions.issubset(permissions):
        raise StoreListingError("Android manifest no longer declares the dataSync permissions")

    services = manifest.findall("application/service")
    declared_foreground_services = [
        service
        for service in services
        if service.get(f"{ANDROID_NAMESPACE}foregroundServiceType") is not None
    ]
    slicer_services = [
        service
        for service in services
        if service.get(f"{ANDROID_NAMESPACE}name") == ".SlicerProcessService"
    ]
    if len(slicer_services) != 1:
        raise StoreListingError("Android manifest must declare one SlicerProcessService")
    if declared_foreground_services != slicer_services:
        raise StoreListingError(
            "Every Android foreground service must have a reviewed Play declaration"
        )
    service = slicer_services[0]
    if (
        service.get(f"{ANDROID_NAMESPACE}foregroundServiceType") != "dataSync"
        or service.get(f"{ANDROID_NAMESPACE}exported") != "false"
    ):
        raise StoreListingError(
            "SlicerProcessService must remain a private dataSync foreground service"
        )

    source_paths = {
        "slicer service": repository_root
        / "android/app/src/main/java/com/ashcastle/duckyslicer/SlicerProcessService.kt",
        "slice composition": repository_root
        / "android/app/src/main/java/com/ashcastle/duckyslicer/MainActivity.kt",
        "slice controls": repository_root
        / "android/app/src/main/java/com/ashcastle/duckyslicer/PlateSliceBatchEffect.kt",
        "slice UI": repository_root
        / "android/app/src/main/java/com/ashcastle/duckyslicer/WorkspaceScreen.kt",
    }
    sources: dict[str, str] = {}
    for name, path in source_paths.items():
        try:
            sources[name] = path.read_text(encoding="utf-8")
        except (OSError, UnicodeError) as error:
            raise StoreListingError(f"Could not inspect {name}: {error}") from error

    service_markers = (
        "context.startForegroundService(SlicerProcessService.startSliceIntent(context, requestId))",
        "ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC",
        "ACTION_CANCEL_SLICE",
        "cancelIntent",
        ".addAction(",
        "getString(R.string.slice_notification_progress",
        "getString(R.string.cancel)",
        "stopForeground(STOP_FOREGROUND_REMOVE)",
        "stopSelf()",
        "return START_NOT_STICKY",
    )
    missing_service = [
        marker for marker in service_markers if marker not in sources["slicer service"]
    ]
    if missing_service:
        raise StoreListingError(
            f"Foreground slicing implementation no longer supports its declaration: {missing_service}"
        )
    control_markers = (
        "fun beginSelected()",
        "fun beginAll()",
        "fun request(all: Boolean)",
        "startSelected = { request(false) }",
        "startAll = { request(true) }",
        "operationModel.cancel()",
    )
    missing_controls = [
        marker for marker in control_markers if marker not in sources["slice controls"]
    ]
    composition_markers = (
        "val sliceStartControls = rememberSliceStartControls(",
        "if (allPlates) sliceStartControls.startAll() else sliceStartControls.startSelected()",
        "onCancelSlice = sliceStartControls.cancel",
    )
    missing_composition = [
        marker
        for marker in composition_markers
        if marker not in sources["slice composition"]
    ]
    ui_markers = (
        "onSlice = { onSlice(false) }",
        "onSliceAll = { onSlice(true) }",
        "onClick = onSlice",
        "onClick = onSliceAll",
        "onClick = onCancelSlice",
    )
    missing_ui = [marker for marker in ui_markers if marker not in sources["slice UI"]]
    if missing_controls or missing_composition or missing_ui:
        raise StoreListingError(
            "Foreground slicing is no longer demonstrably user initiated: "
            f"{missing_controls + missing_composition + missing_ui}"
        )


def _verify_declarations(
    store_root: Path,
    repository_root: Path,
    privacy_policy: str,
) -> None:
    declarations = _exact_keys(
        _read_json(store_root / "console-declarations.json"),
        {
            "schemaVersion",
            "packageName",
            "defaultLocale",
            "listingLocales",
            "privacyPolicyUrl",
            "supportUrl",
            "appAccess",
            "monetization",
            "dataSafety",
            "foregroundServices",
        },
        "console declarations",
    )
    expected_scalars = {
        "schemaVersion": 2,
        "packageName": EXPECTED_PACKAGE,
        "defaultLocale": "en-US",
        "listingLocales": list(EXPECTED_LOCALES),
        "privacyPolicyUrl": EXPECTED_PRIVACY_URL,
        "supportUrl": EXPECTED_SUPPORT_URL,
    }
    for name, expected in expected_scalars.items():
        if declarations[name] != expected:
            raise StoreListingError(
                f"console declaration {name} changed: expected {expected!r}"
            )
    nested = {
        "appAccess": ("loginRequired", False),
        "monetization": ("containsAds", False),
    }
    for name, (key, expected) in nested.items():
        value = _exact_keys(declarations[name], {key}, name)
        if value[key] is not expected:
            raise StoreListingError(f"console declaration {name}.{key} must be {expected}")
    safety = _exact_keys(
        declarations["dataSafety"],
        {"collectsData", "sharesData"},
        "dataSafety",
    )
    if safety != {"collectsData": False, "sharesData": False}:
        raise StoreListingError("Data safety changed and requires a new shipping-build review")
    foreground_services = declarations["foregroundServices"]
    if foreground_services != [EXPECTED_FOREGROUND_SERVICE]:
        raise StoreListingError(
            "Foreground service declaration changed and requires a new implementation review"
        )
    _verify_foreground_service_sources(repository_root)
    for marker in (
        EXPECTED_PACKAGE,
        "does not collect, sell, or share",
        "no developer-operated account, advertising, analytics",
        "Retention and deletion",
        "수집·판매·공유하지 않습니다",
    ):
        if marker not in privacy_policy:
            raise StoreListingError(f"privacy policy no longer supports store declarations: {marker}")


def _verify_assets(store_root: Path, repository_root: Path) -> None:
    manifest = _exact_keys(
        _read_json(store_root / "assets.json"),
        {"schemaVersion", "capture", "appIcon", "featureGraphic", "phoneScreenshots"},
        "asset manifest",
    )
    if manifest["schemaVersion"] != 1:
        raise StoreListingError("Unsupported store asset manifest version")
    capture = _exact_keys(
        manifest["capture"],
        {"device", "resolution", "sourceModel", "containsPrivateData"},
        "capture provenance",
    )
    if capture != {
        "device": "Android 15 ARM64 16 KB emulator",
        "resolution": "1080x1920",
        "sourceModel": EXPECTED_SOURCE_MODEL,
        "containsPrivateData": False,
    }:
        raise StoreListingError("Store screenshots must retain reviewed public capture provenance")
    source_model = repository_root / EXPECTED_SOURCE_MODEL
    if source_model.is_symlink() or not source_model.is_file():
        raise StoreListingError("Public screenshot source model is missing")

    icon = _asset_path(store_root, manifest["appIcon"], "app icon")
    feature = _asset_path(store_root, manifest["featureGraphic"], "feature graphic")
    _verify_png(icon, dimensions=(512, 512), color_type=6, maximum_bytes=1_048_576)
    _verify_png(feature, dimensions=(1024, 500), color_type=2, maximum_bytes=15_000_000)

    icon_source = _asset_path(store_root, "graphics/app-icon.svg", "app icon source")
    icon_svg = _read_clean_text(icon_source)
    for marker in ('viewBox="0 0 512 512"', "#F6C945", "#202124"):
        if marker not in icon_svg:
            raise StoreListingError(f"app icon source omits brand marker: {marker}")
    if re.search(r"<text(?:\s|>)", icon_svg, re.IGNORECASE):
        raise StoreListingError("App icon must remain language-neutral")

    feature_source = _asset_path(
        store_root,
        "graphics/feature-graphic.svg",
        "feature graphic source",
    )
    source = _read_clean_text(feature_source)
    for marker in ('viewBox="0 0 1024 500"', "#F6C945", "#202124"):
        if marker not in source:
            raise StoreListingError(f"feature graphic source omits brand marker: {marker}")
    if re.search(r"<text(?:\s|>)", source, re.IGNORECASE):
        raise StoreListingError("Feature graphic must remain language-neutral")

    screenshots = manifest["phoneScreenshots"]
    if not isinstance(screenshots, list) or len(screenshots) not in range(4, 9):
        raise StoreListingError("Store listing needs 4 to 8 phone screenshots")
    expected_images = {icon, feature}
    seen: set[Path] = set()
    seen_content: set[str] = set()
    for index, raw in enumerate(screenshots, start=1):
        entry = _exact_keys(raw, {"path", "altText"}, f"screenshot {index}")
        screenshot = _asset_path(store_root, entry["path"], f"screenshot {index}")
        if screenshot in seen:
            raise StoreListingError(f"screenshot {index} repeats an earlier image")
        seen.add(screenshot)
        content_hash = hashlib.sha256(screenshot.read_bytes()).hexdigest()
        if content_hash in seen_content:
            raise StoreListingError(
                f"screenshot {index} duplicates the content of an earlier image"
            )
        seen_content.add(content_hash)
        expected_images.add(screenshot)
        _verify_png(
            screenshot,
            dimensions=(1080, 1920),
            color_type=2,
            maximum_bytes=8_000_000,
        )
        if screenshot.stat().st_size < 50_000:
            raise StoreListingError(f"{screenshot.name} looks like a placeholder")
        alt_text = _exact_keys(entry["altText"], set(EXPECTED_LOCALES), f"screenshot {index} alt text")
        for locale in EXPECTED_LOCALES:
            value = alt_text[locale]
            if not isinstance(value, str) or len(value.strip()) not in range(10, 141):
                raise StoreListingError(
                    f"screenshot {index} {locale} alt text must be 10 to 140 characters"
                )

    image_files = {
        path
        for path in (store_root / "graphics").rglob("*")
        if path.is_file() and path.suffix.lower() in {".jpeg", ".jpg", ".png", ".webp"}
    }
    unlisted = sorted(str(path.relative_to(store_root)) for path in image_files - expected_images)
    if unlisted:
        raise StoreListingError(f"Unreviewed store images are present: {unlisted}")


def verify_store_listing(
    store_root: Path = STORE_ROOT,
    repository_root: Path = ROOT,
    privacy_policy: str | None = None,
) -> None:
    listing_root = store_root / "listings"
    try:
        actual_locales = tuple(sorted(path.name for path in listing_root.iterdir() if path.is_dir()))
    except OSError as error:
        raise StoreListingError(f"Could not inspect store listings: {error}") from error
    if actual_locales != tuple(sorted(EXPECTED_LOCALES)):
        raise StoreListingError(
            f"Store listing locales changed: expected {EXPECTED_LOCALES}, found {actual_locales}"
        )
    for locale in EXPECTED_LOCALES:
        listing = listing_root / locale
        actual_files = {path.name for path in listing.iterdir() if path.is_file()}
        expected_files = {"title.txt", "short-description.txt", "full-description.txt"}
        if actual_files != expected_files:
            raise StoreListingError(
                f"{locale} listing files changed: expected {sorted(expected_files)}, "
                f"found {sorted(actual_files)}"
            )
        _verify_copy(locale, listing)
    policy = privacy_policy
    if policy is None:
        policy = (repository_root / "PRIVACY.md").read_text(encoding="utf-8")
    _verify_declarations(store_root, repository_root, policy)
    _verify_assets(store_root, repository_root)


def main() -> None:
    try:
        verify_store_listing()
    except (OSError, StoreListingError) as error:
        raise SystemExit(f"Google Play listing verification failed: {error}") from error
    print(
        "Verified bilingual Play copy, no-data and foreground-service declarations, "
        "public capture provenance, and publication-ready graphics"
    )


if __name__ == "__main__":
    main()
