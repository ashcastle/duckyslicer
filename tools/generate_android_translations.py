#!/usr/bin/env python3
"""Generate Android string overlays from exact matches in OrcaSlicer's PO catalogs."""

from __future__ import annotations

import ast
import html
import os
import sys
import tempfile
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path
from typing import Final


ROOT = Path(__file__).resolve().parent.parent
DEFAULT_STRINGS = ROOT / "android/app/src/main/res/values/strings.xml"
ORCA_I18N = ROOT / "localization/i18n"
MIN_TRANSLATIONS_PER_LOCALE = 100

# The app intentionally follows the inherited Orca language set. English is the
# unqualified Android resource and Korean remains a complete hand-maintained overlay.
ORCA_LOCALE_TO_ANDROID: Final[dict[str, tuple[str, str]]] = {
    "ca": ("ca", "ca"),
    "cs": ("cs", "cs"),
    "de": ("de", "de"),
    "es": ("es", "es"),
    "fr": ("fr", "fr"),
    "hu": ("hu", "hu"),
    "it": ("it", "it"),
    "ja": ("ja", "ja"),
    "lt": ("lt", "lt"),
    "nl": ("nl", "nl"),
    "pl": ("pl", "pl"),
    "pt_BR": ("pt-BR", "pt-rBR"),
    "ru": ("ru", "ru"),
    "sv": ("sv", "sv"),
    "th": ("th", "th"),
    "tr": ("tr", "tr"),
    "uk": ("uk", "uk"),
    "vi": ("vi", "vi"),
    "zh_CN": ("zh-CN", "zh-rCN"),
    "zh_TW": ("zh-TW", "zh-rTW"),
}
EXPECTED_TRANSLATION_COUNTS: Final[dict[str, int]] = {
    "ca": 381,
    "cs": 380,
    "de": 381,
    "es": 386,
    "fr": 381,
    "hu": 387,
    "it": 391,
    "ja": 352,
    "lt": 389,
    "nl": 270,
    "pl": 363,
    "pt_BR": 386,
    "ru": 400,
    "sv": 239,
    "th": 401,
    "tr": 388,
    "uk": 366,
    "vi": 372,
    "zh_CN": 399,
    "zh_TW": 399,
}
SUPPORTED_ORCA_LOCALES: Final[frozenset[str]] = frozenset(
    {"en", "ko", *ORCA_LOCALE_TO_ANDROID}
)
SUPPORTED_LANGUAGE_TAGS: Final[tuple[str, ...]] = (
    "en",
    "ko",
    *(language_tag for language_tag, _ in ORCA_LOCALE_TO_ANDROID.values()),
)
SUPPORTED_RESOURCE_CONFIGS: Final[tuple[str, ...]] = (
    "en",
    "ko",
    *(qualifier for _, qualifier in ORCA_LOCALE_TO_ANDROID.values()),
)


class TranslationGenerationError(ValueError):
    """The pinned translation inputs are missing, ambiguous, or unsafe."""


@dataclass
class _PoEntry:
    flags: set[str] = field(default_factory=set)
    obsolete: bool = False
    context: str | None = None
    msgid: str | None = None
    plural: str | None = None
    msgstr: str | None = None
    field_name: str | None = None


def _quoted(value: str, source: Path, line_number: int) -> str:
    try:
        parsed = ast.literal_eval(value)
    except (SyntaxError, ValueError) as error:
        raise TranslationGenerationError(
            f"invalid PO string at {source}:{line_number}"
        ) from error
    if not isinstance(parsed, str):
        raise TranslationGenerationError(f"non-string PO value at {source}:{line_number}")
    return parsed


def parse_po_catalog(source: Path) -> dict[str, str]:
    """Read unambiguous, translated, singular, context-free PO entries."""
    translations: dict[str, str] = {}
    ambiguous: set[str] = set()
    entry = _PoEntry()

    def finish() -> None:
        nonlocal entry
        if (
            entry.msgid
            and entry.msgstr
            and entry.context is None
            and entry.plural is None
            and "fuzzy" not in entry.flags
            and not entry.obsolete
        ):
            previous = translations.get(entry.msgid)
            if previous is not None and previous != entry.msgstr:
                ambiguous.add(entry.msgid)
            elif entry.msgid not in ambiguous:
                translations[entry.msgid] = entry.msgstr
        entry = _PoEntry()

    for line_number, raw_line in enumerate(
        source.read_text(encoding="utf-8").splitlines(),
        start=1,
    ):
        line = raw_line.strip()
        if not line:
            finish()
            continue
        if line.startswith("#~"):
            entry.obsolete = True
            continue
        if line.startswith("#,"):
            entry.flags.update(flag.strip() for flag in line[2:].split(","))
            continue
        if line.startswith("#"):
            continue

        fields = (
            ("msgctxt ", "context"),
            ("msgid_plural ", "plural"),
            ("msgid ", "msgid"),
            ("msgstr ", "msgstr"),
        )
        matched = False
        for prefix, field_name in fields:
            if not line.startswith(prefix):
                continue
            value = _quoted(line[len(prefix) :], source, line_number)
            setattr(entry, field_name, value)
            entry.field_name = field_name
            matched = True
            break
        if matched:
            continue
        if line.startswith("msgstr["):
            entry.plural = entry.plural or "plural"
            entry.field_name = "_ignored"
            continue
        if line.startswith('"') and entry.field_name == "_ignored":
            continue
        if line.startswith('"') and entry.field_name is not None:
            value = _quoted(line, source, line_number)
            current = getattr(entry, entry.field_name)
            setattr(entry, entry.field_name, (current or "") + value)
            continue
        raise TranslationGenerationError(f"unsupported PO syntax at {source}:{line_number}")
    finish()
    for msgid in ambiguous:
        translations.pop(msgid, None)
    return translations


def read_android_strings(source: Path) -> list[tuple[str, str]]:
    try:
        root = ET.parse(source).getroot()
    except (OSError, ET.ParseError) as error:
        raise TranslationGenerationError(f"invalid Android strings resource: {source}") from error
    if root.tag != "resources":
        raise TranslationGenerationError(f"unexpected Android resource root: {root.tag}")
    strings: list[tuple[str, str]] = []
    names: set[str] = set()
    for element in root.findall("string"):
        name = element.attrib.get("name", "")
        if not name or name in names:
            raise TranslationGenerationError(f"invalid or duplicate Android string name: {name}")
        names.add(name)
        strings.append((name, "".join(element.itertext())))
    if not strings:
        raise TranslationGenerationError("Android strings resource is empty")
    return strings


def _safe_translation(source: str, translation: str) -> bool:
    if not translation or translation == source or translation != translation.strip():
        return False
    if "%" in source or "%" in translation or len(translation) > 4_096:
        return False
    return not any(ord(character) < 32 and character not in "\n\t" for character in translation)


def _android_text(value: str) -> str:
    escaped = (
        value.replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace('"', '\\"')
        .replace("\r\n", "\\n")
        .replace("\r", "\\n")
        .replace("\n", "\\n")
        .replace("\t", "\\t")
    )
    if escaped.startswith(("@", "?")):
        escaped = "\\" + escaped
    return html.escape(escaped, quote=False)


def translation_resource(strings: list[tuple[str, str]], catalog: dict[str, str]) -> tuple[str, int]:
    rows: list[str] = []
    for name, source in strings:
        translation = catalog.get(source)
        if translation is None or not _safe_translation(source, translation):
            continue
        rows.append(f'    <string name="{name}">{_android_text(translation)}</string>')
    document = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!-- Generated from exact, non-fuzzy OrcaSlicer PO matches. Do not edit. -->\n"
        "<resources>\n"
        + "\n".join(rows)
        + "\n</resources>\n"
    )
    return document, len(rows)


def generate_translation_resources(
    default_strings: Path,
    orca_i18n: Path,
    output_root: Path,
) -> dict[str, int]:
    strings = read_android_strings(default_strings)
    output_root = output_root.resolve()
    filesystem_root = Path(output_root.anchor)
    if output_root.name != "res" or output_root.parent == filesystem_root:
        raise TranslationGenerationError(
            f"translation output must be a narrow generated res directory: {output_root}"
        )
    if output_root.exists() and (not output_root.is_dir() or output_root.is_symlink()):
        raise TranslationGenerationError(f"unsafe translation output: {output_root}")
    output_root.parent.mkdir(parents=True, exist_ok=True)
    counts: dict[str, int] = {}
    with tempfile.TemporaryDirectory(
        dir=output_root.parent,
        prefix=f".{output_root.name}-",
    ) as temporary:
        staging = Path(temporary) / "resources"
        for orca_locale, (_, qualifier) in ORCA_LOCALE_TO_ANDROID.items():
            source = orca_i18n / orca_locale / f"OrcaSlicer_{orca_locale}.po"
            if not source.is_file():
                raise TranslationGenerationError(f"missing Orca translation catalog: {source}")
            document, count = translation_resource(strings, parse_po_catalog(source))
            if count < MIN_TRANSLATIONS_PER_LOCALE:
                raise TranslationGenerationError(
                    f"too few exact translations for {orca_locale}: {count}"
                )
            expected_count = EXPECTED_TRANSLATION_COUNTS[orca_locale]
            if count != expected_count:
                raise TranslationGenerationError(
                    f"exact translation review required for {orca_locale}: "
                    f"expected {expected_count}, found {count}"
                )
            destination = staging / f"values-{qualifier}" / "strings.xml"
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(document, encoding="utf-8", newline="\n")
            counts[orca_locale] = count
        previous = Path(temporary) / "previous"
        if output_root.exists():
            os.replace(output_root, previous)
        try:
            os.replace(staging, output_root)
        except OSError:
            if previous.exists() and not output_root.exists():
                os.replace(previous, output_root)
            raise
    return counts


def main(arguments: list[str]) -> int:
    if len(arguments) != 4:
        raise SystemExit(
            f"usage: {arguments[0]} DEFAULT_STRINGS ORCA_I18N_ROOT OUTPUT_RES_ROOT"
        )
    try:
        counts = generate_translation_resources(
            Path(arguments[1]),
            Path(arguments[2]),
            Path(arguments[3]),
        )
    except (OSError, TranslationGenerationError) as error:
        print(f"Android translation generation failed: {error}", file=sys.stderr)
        return 1
    total = sum(counts.values())
    print(
        f"Generated {len(counts)} Orca Android locales with {total} exact translations "
        f"({min(counts.values())}-{max(counts.values())} per locale)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
