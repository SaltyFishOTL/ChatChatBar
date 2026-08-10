#!/usr/bin/env python3
"""Compile nai-codex Markdown sections into ChatBar runtime assets.

Each level-three Markdown section remains one intact reference block. Runtime
retrieval uses only Chinese text extracted from that original block; the block
itself is sent unchanged to the final prompt-design model.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path


SCHEMA_VERSION = 1
SOURCE_VERSION = "nai-codex-v3.0"

SECTION_FILES = {
    "25-composition.md": "COMPOSITION",
    "40-positions.md": "R18",
    "45-r18-codex.md": "R18",
    "50-clothing.md": "WARDROBE",
    "55-wardrobe.md": "WARDROBE",
}

SPECIAL_EXPANSIONS = {
    "bikini pulled aside": ["side-tie_bikini", "untied_bikini"],
    "festive ribbon": ["ribbon", "christmas"],
}

def stable_id(kind: str, title: str, category: str, prompt: str) -> str:
    material = "\n".join((kind, title, category, prompt)).encode("utf-8")
    digest = hashlib.sha1(material).hexdigest()[:12]
    return f"{kind.lower()}-{digest}"


def chinese_search_text(text: str) -> str:
    """Keep Chinese prose for retrieval and exclude every English prompt tag."""
    return " ".join(re.findall(r"[\u3400-\u9fff]+", text))


def entry(kind: str, title: str, category: str, prompt: str, source: str) -> dict:
    return {
        "id": stable_id(kind, title, category, prompt),
        "kind": kind,
        "title": title.strip(),
        "category": category.strip(),
        "prompt": prompt.strip(),
        "searchText": chinese_search_text(prompt),
        "source": source,
    }


def parse_h3_sections(path: Path, kind: str) -> list[dict]:
    text = path.read_text(encoding="utf-8-sig")
    headings = list(re.finditer(r"(?m)^(#{1,3})(?!#)\s+(.+?)\s*$", text))
    results: list[dict] = []
    current_h2 = ""
    for index, heading in enumerate(headings):
        level = len(heading.group(1))
        if level == 2:
            current_h2 = heading.group(2).strip()
            continue
        if level != 3:
            continue
        title = heading.group(2).strip()
        end = headings[index + 1].start() if index + 1 < len(headings) else len(text)
        block = text[heading.start():end].strip()
        category_match = re.search(r"(?m)^分类：\s*(.*?)\s*$", block)
        category = category_match.group(1).strip() if category_match else current_h2
        if block:
            results.append(entry(kind, title, category, block, path.name))
    return results


def clean_cell(value: str) -> str:
    return value.strip().strip("`").strip()


def split_aliases(value: str) -> list[str]:
    aliases = []
    for part in re.split(r"\s+/\s+", value):
        cleaned = re.sub(r"（.*?）", "", part).strip()
        if cleaned:
            aliases.append(cleaned)
    return aliases


def parse_rewrite_rules(path: Path) -> list[dict]:
    text = path.read_text(encoding="utf-8-sig")
    rules: list[dict] = []
    seen: set[tuple[tuple[str, ...], tuple[str, ...], str]] = set()
    for raw_line in text.splitlines():
        if not raw_line.startswith("|"):
            continue
        cells = [clean_cell(cell) for cell in raw_line.strip().strip("|").split("|")]
        if len(cells) != 2:
            continue
        source, target = cells
        if source in {"法典/常见写法", "---"} or target in {"danbooru 标准", "---"}:
            continue
        aliases = split_aliases(source)
        if not aliases:
            continue
        if source in SPECIAL_EXPANSIONS:
            replacements = SPECIAL_EXPANSIONS[source]
            mode = "EXPAND"
        elif target.startswith("（"):
            continue
        elif " / " in target:
            replacements = [part.strip() for part in target.split(" / ") if part.strip()]
            mode = "AMBIGUOUS"
        else:
            replacements = [target]
            mode = "REPLACE"
        key = (tuple(aliases), tuple(replacements), mode)
        if key in seen:
            continue
        seen.add(key)
        rules.append({"aliases": aliases, "replacements": replacements, "mode": mode})
    for alias, replacements in SPECIAL_EXPANSIONS.items():
        key = ((alias,), tuple(replacements), "EXPAND")
        if key not in seen:
            rules.append({"aliases": [alias], "replacements": replacements, "mode": "EXPAND"})
    return rules


def compile_catalog(source_root: Path) -> dict:
    references = source_root / "references"
    if not references.is_dir():
        raise SystemExit(f"references directory not found: {references}")
    entries: list[dict] = []
    for name, kind in SECTION_FILES.items():
        entries.extend(parse_h3_sections(references / name, kind))
    unique_entries = []
    seen_ids = set()
    for item in entries:
        if not item["prompt"] or item["id"] in seen_ids:
            continue
        seen_ids.add(item["id"])
        unique_entries.append(item)
    entries = unique_entries
    return {
        "schemaVersion": SCHEMA_VERSION,
        "sourceVersion": SOURCE_VERSION,
        "entries": entries,
        "rewriteRules": parse_rewrite_rules(references / "03-danbooru-mapping.md"),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path, help="Path to nai-codex directory")
    parser.add_argument("output", type=Path, help="Generated ChatBar JSON asset")
    args = parser.parse_args()
    catalog = compile_catalog(args.source)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(catalog, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(
        f"wrote {len(catalog['entries'])} entries and "
        f"{len(catalog['rewriteRules'])} rewrite rules to {args.output}"
    )


if __name__ == "__main__":
    main()
