#!/usr/bin/env python3
"""Extract a compact English->Chinese TSV from pocket_dict_5000 Dart data."""

from __future__ import annotations

import argparse
import re
from pathlib import Path


ENTRY = re.compile(
    r"^\s*'(?P<key>[a-z-]+)': CommonWordEntry\("
    r"word: '(?:\\'|[^'])*', pronunciation: '(?:\\'|[^'])*', "
    r"meaning: '(?P<meaning>(?:\\'|[^'])*)'\),$"
)
HAN = re.compile(r"[\u3400-\u9fff]")
POS_PREFIX = re.compile(
    r"^(?:(?:art|aux|conj|interj|num|prep|pron|adv|adj|ad|a|n|v|vi|vt)\.\s*)+",
    re.IGNORECASE,
)
LABEL = re.compile(r"^\[[^]]+]\s*")


def simplify(raw: str) -> str | None:
    text = raw.replace(r"\'", "'").replace(r"\\", "\\")
    candidates = re.split(r"[；;\n]", text)
    for candidate in candidates:
        value = LABEL.sub("", candidate.strip())
        value = POS_PREFIX.sub("", value).strip()
        value = re.split(r"[,，(（]", value, maxsplit=1)[0].strip(" .:：")
        value = re.sub(r"\s+", "", value)
        if HAN.search(value) and 1 <= len(value) <= 16:
            return value
    return None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    entries: dict[str, str] = {}
    for line in args.source.read_text(encoding="utf-8").splitlines():
        match = ENTRY.match(line)
        if not match:
            continue
        translation = simplify(match.group("meaning"))
        if translation:
            entries[match.group("key")] = translation

    args.output.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "# Generated from FirepadCN/pocket_dict_5000 (MIT), derived from ECDICT (MIT).",
        "# word<TAB>concise Simplified Chinese meaning",
    ]
    lines.extend(f"{word}\t{entries[word]}" for word in sorted(entries))
    args.output.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    print(f"wrote {len(entries)} entries to {args.output}")


if __name__ == "__main__":
    main()
