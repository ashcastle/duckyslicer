#!/usr/bin/env python3
"""Verify the security policy of DuckySlicer's merged APK manifest."""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from pathlib import Path


PACKAGE_NAME = "com.ashcastle.duckyslicer"
MIN_SDK = 26
TARGET_SDK = 36
DYNAMIC_RECEIVER_PERMISSION = f"{PACKAGE_NAME}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
EXPECTED_PERMISSIONS = frozenset(
    {
        "android.permission.INTERNET",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
        "android.permission.POST_NOTIFICATIONS",
        DYNAMIC_RECEIVER_PERMISSION,
    }
)
COMPONENT_TAGS = frozenset({"activity", "activity-alias", "service", "receiver", "provider"})
ELEMENT = re.compile(r"^(?P<indent>\s*)E: (?P<tag>[A-Za-z0-9_-]+)")
ATTRIBUTE = re.compile(r"^(?P<indent>\s*)A: (?P<name>.+?)=(?P<value>.*)$")
RESOURCE_ID = re.compile(r"\(0x[0-9A-Fa-f]+\)$")
RAW_VALUE = re.compile(r'\(Raw: "(?P<value>.*)"\)\s*$')
QUOTED_VALUE = re.compile(r'^"(?P<value>.*)"(?:\s|$)')
TYPED_PREFIX = re.compile(r"^\(type [^)]+\)")


class VerificationError(ValueError):
    """The merged Android manifest violates a release invariant."""


@dataclass
class ManifestNode:
    tag: str
    attributes: dict[str, str] = field(default_factory=dict)
    children: list[ManifestNode] = field(default_factory=list)

    def direct(self, tag: str) -> list[ManifestNode]:
        return [child for child in self.children if child.tag == tag]


def _attribute_name(source: str) -> str:
    name = RESOURCE_ID.sub("", source.strip())
    return name.rsplit(":", 1)[-1]


def _attribute_value(source: str) -> str:
    raw = RAW_VALUE.search(source)
    if raw is not None:
        return raw.group("value")
    quoted = QUOTED_VALUE.match(source.strip())
    if quoted is not None:
        return quoted.group("value")
    return TYPED_PREFIX.sub("", source.strip()).strip()


def parse_aapt_xmltree(source: str) -> ManifestNode:
    roots: list[ManifestNode] = []
    stack: list[tuple[int, ManifestNode]] = []
    for line in source.splitlines():
        element = ELEMENT.match(line)
        if element is not None:
            indentation = len(element.group("indent"))
            node = ManifestNode(element.group("tag"))
            while stack and stack[-1][0] >= indentation:
                stack.pop()
            if stack:
                stack[-1][1].children.append(node)
            else:
                roots.append(node)
            stack.append((indentation, node))
            continue
        attribute = ATTRIBUTE.match(line)
        if attribute is None:
            continue
        indentation = len(attribute.group("indent"))
        if not stack or stack[-1][0] >= indentation:
            raise VerificationError("aapt manifest attribute has no containing element")
        name = _attribute_name(attribute.group("name"))
        if name in stack[-1][1].attributes:
            raise VerificationError(f"aapt manifest repeats attribute {name}")
        stack[-1][1].attributes[name] = _attribute_value(attribute.group("value"))
    manifests = [root for root in roots if root.tag == "manifest"]
    if len(manifests) != 1:
        raise VerificationError(f"expected one merged manifest root, found {len(manifests)}")
    return manifests[0]


def _integer(node: ManifestNode, name: str) -> int:
    value = node.attributes.get(name)
    if value is None:
        raise VerificationError(f"{node.tag} is missing {name}")
    try:
        return int(value, 0)
    except ValueError as error:
        raise VerificationError(f"{node.tag} has invalid {name}: {value}") from error


def _boolean(node: ManifestNode, name: str, *, default: bool | None = None) -> bool:
    value = node.attributes.get(name)
    if value is None:
        if default is not None:
            return default
        raise VerificationError(f"{node.tag} is missing {name}")
    normalized = value.lower()
    if normalized in {"true", "false"}:
        return normalized == "true"
    try:
        return int(value, 0) != 0
    except ValueError as error:
        raise VerificationError(f"{node.tag} has invalid {name}: {value}") from error


def _component_signature(node: ManifestNode) -> tuple[str, str, bool, str | None]:
    name = node.attributes.get("name", "")
    if not name:
        raise VerificationError(f"merged {node.tag} has no name")
    return (
        node.tag,
        name,
        _boolean(node, "exported"),
        node.attributes.get("permission"),
    )


def _filter_signature(
    node: ManifestNode,
) -> tuple[tuple[str, ...], tuple[str, ...], tuple[tuple[tuple[str, str], ...], ...]]:
    actions = tuple(sorted(child.attributes.get("name", "") for child in node.direct("action")))
    categories = tuple(
        sorted(child.attributes.get("name", "") for child in node.direct("category"))
    )
    data = tuple(
        sorted(
            tuple(sorted(child.attributes.items()))
            for child in node.direct("data")
        )
    )
    if "" in actions or "" in categories:
        raise VerificationError("merged intent filter contains an unnamed action or category")
    return actions, categories, data


def _expected_main_filters() -> set[
    tuple[tuple[str, ...], tuple[str, ...], tuple[tuple[tuple[str, str], ...], ...]]
]:
    return {
        (
            ("android.intent.action.MAIN",),
            ("android.intent.category.LAUNCHER",),
            (),
        ),
        (
            ("android.intent.action.VIEW",),
            ("android.intent.category.DEFAULT",),
            (
                (("mimeType", "application/vnd.duckyslicer.project+zip"),),
                (("scheme", "content"),),
            ),
        ),
        (
            ("android.intent.action.VIEW",),
            ("android.intent.category.DEFAULT",),
            tuple(
                sorted(
                    (
                        (("host", "*"),),
                        (("mimeType", "application/octet-stream"),),
                        (("mimeType", "application/x-zip-compressed"),),
                        (("mimeType", "application/zip"),),
                        (("pathPattern", ".*.duckyproject"),),
                        (("scheme", "content"),),
                    )
                )
            ),
        ),
    }


def _expected_components(variant: str) -> set[tuple[str, str, bool, str | None]]:
    common = {
        ("activity", f"{PACKAGE_NAME}.MainActivity", True, None),
        ("service", f"{PACKAGE_NAME}.SlicerProcessService", False, None),
        ("provider", "androidx.startup.InitializationProvider", False, None),
        (
            "receiver",
            "androidx.profileinstaller.ProfileInstallReceiver",
            True,
            "android.permission.DUMP",
        ),
    }
    if variant == "debug":
        common.update(
            {
                (
                    "activity",
                    f"{PACKAGE_NAME}.ProcessRecoveryHarnessActivity",
                    True,
                    "android.permission.DUMP",
                ),
                ("activity", f"{PACKAGE_NAME}.AccessibilityHarnessActivity", False, None),
                ("activity", "androidx.compose.ui.tooling.PreviewActivity", True, None),
                ("activity", "androidx.activity.ComponentActivity", True, None),
                ("provider", "androidx.core.content.FileProvider", False, None),
            }
        )
    return common


def verify_manifest(root: ManifestNode, variant: str) -> None:
    if variant not in {"debug", "release"}:
        raise VerificationError(f"unsupported manifest variant: {variant}")
    if root.attributes.get("package") != PACKAGE_NAME:
        raise VerificationError(f"unexpected package: {root.attributes.get('package')}")
    if _integer(root, "compileSdkVersion") != TARGET_SDK:
        raise VerificationError("merged manifest must be compiled with Android API 36")

    uses_sdk = root.direct("uses-sdk")
    if len(uses_sdk) != 1:
        raise VerificationError(f"expected one uses-sdk element, found {len(uses_sdk)}")
    if _integer(uses_sdk[0], "minSdkVersion") != MIN_SDK:
        raise VerificationError("merged manifest minSdkVersion must remain 26")
    if _integer(uses_sdk[0], "targetSdkVersion") != TARGET_SDK:
        raise VerificationError("merged manifest targetSdkVersion must remain 36")

    permission_nodes = root.direct("uses-permission")
    permissions = {node.attributes.get("name", "") for node in permission_nodes}
    if permissions != EXPECTED_PERMISSIONS or len(permission_nodes) != len(EXPECTED_PERMISSIONS):
        raise VerificationError(
            "merged manifest permission allowlist changed: "
            f"expected={sorted(EXPECTED_PERMISSIONS)}, found={sorted(permissions)}"
        )
    declarations = root.direct("permission")
    if (
        len(declarations) != 1
        or declarations[0].attributes.get("name") != DYNAMIC_RECEIVER_PERMISSION
    ):
        raise VerificationError("dynamic receiver signature permission declaration changed")
    if _integer(declarations[0], "protectionLevel") != 2:
        raise VerificationError("dynamic receiver permission must remain signature-protected")

    applications = root.direct("application")
    if len(applications) != 1:
        raise VerificationError(f"expected one application element, found {len(applications)}")
    application = applications[0]
    if application.attributes.get("name") != f"{PACKAGE_NAME}.DuckySlicerApplication":
        raise VerificationError("merged manifest application class changed")
    if _boolean(application, "allowBackup"):
        raise VerificationError("application backup must remain disabled")
    if _boolean(application, "extractNativeLibs"):
        raise VerificationError("native libraries must remain directly loadable without extraction")
    if not _boolean(application, "usesCleartextTraffic"):
        raise VerificationError("optional local HTTP printer connections require cleartext opt-in")
    if _boolean(application, "testOnly", default=False):
        raise VerificationError("installable artifacts must not be test-only")
    debuggable = _boolean(application, "debuggable", default=False)
    if debuggable != (variant == "debug"):
        raise VerificationError(f"{variant} artifact has an invalid debuggable state")
    for attribute in ("fullBackupContent", "dataExtractionRules", "localeConfig"):
        if not application.attributes.get(attribute):
            raise VerificationError(f"merged application is missing {attribute}")

    component_nodes = [node for node in application.children if node.tag in COMPONENT_TAGS]
    components = {_component_signature(node) for node in component_nodes}
    expected_components = _expected_components(variant)
    if components != expected_components or len(component_nodes) != len(expected_components):
        raise VerificationError(
            "merged component allowlist changed: "
            f"expected={sorted(map(repr, expected_components))}, "
            f"found={sorted(map(repr, components))}"
        )
    services = [
        node
        for node in application.direct("service")
        if node.attributes.get("name") == f"{PACKAGE_NAME}.SlicerProcessService"
    ]
    service = services[0]
    if service.attributes.get("process") != ":slicer" or _integer(
        service, "foregroundServiceType"
    ) != 1:
        raise VerificationError("slicer service isolation or foreground type changed")

    main_activities = [
        node
        for node in application.direct("activity")
        if node.attributes.get("name") == f"{PACKAGE_NAME}.MainActivity"
    ]
    main_filter_nodes = main_activities[0].direct("intent-filter")
    main_filters = {_filter_signature(node) for node in main_filter_nodes}
    if main_filters != _expected_main_filters() or len(main_filter_nodes) != len(main_filters):
        raise VerificationError("MainActivity external intent allowlist changed")


def verify_aapt_output(source: str, variant: str) -> None:
    verify_manifest(parse_aapt_xmltree(source), variant)


def resolve_aapt(environment: Mapping[str, str]) -> Path:
    sdk = environment.get("ANDROID_SDK_ROOT") or environment.get("ANDROID_HOME")
    if not sdk:
        raise VerificationError("ANDROID_SDK_ROOT or ANDROID_HOME is required")
    executable = "aapt.exe" if os.name == "nt" else "aapt"
    aapt = Path(sdk) / "build-tools/36.0.0" / executable
    if not aapt.is_file():
        raise VerificationError(f"Android build-tools 36.0.0 aapt is unavailable: {aapt}")
    return aapt


def inspect_apk(apk: Path, variant: str, aapt: Path) -> None:
    if not apk.is_file() or apk.stat().st_size <= 0:
        raise VerificationError(f"APK is unavailable: {apk}")
    try:
        result = subprocess.run(
            [str(aapt), "dump", "xmltree", str(apk), "AndroidManifest.xml"],
            check=False,
            capture_output=True,
            text=True,
            timeout=30,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise VerificationError(f"could not inspect merged APK manifest: {error}") from error
    if result.returncode != 0:
        detail = (result.stdout + result.stderr).strip()
        raise VerificationError(f"aapt manifest inspection failed: {detail}")
    verify_aapt_output(result.stdout, variant)


def main(arguments: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--variant", choices=("debug", "release"), required=True)
    parser.add_argument("apk", type=Path)
    options = parser.parse_args(arguments)
    try:
        inspect_apk(options.apk.resolve(), options.variant, resolve_aapt(os.environ))
    except VerificationError as error:
        print(f"Merged manifest verification failed: {error}", file=sys.stderr)
        return 1
    print(
        f"Verified {options.variant} merged manifest: API {MIN_SDK}-{TARGET_SDK}, "
        f"{len(EXPECTED_PERMISSIONS)} permissions, exact components and content-only imports"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
