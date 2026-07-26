#!/usr/bin/env python3
"""Verify ChatBar GitHub Release metadata, notes propagation, and APK asset."""

from __future__ import annotations

import argparse
import html
from html.parser import HTMLParser
import json
import os
import re
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET


ATOM_NAMESPACE = "http://www.w3.org/2005/Atom"
VERSION_PATTERN = re.compile(r"\d+(?:\.\d+){2}(?:[.-][0-9A-Za-z]+)?")
BULLET_PATTERN = re.compile(r"^\s*[-*]\s+(.+?)\s*$")


class _PlainTextParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.parts: list[str] = []

    def handle_data(self, data: str) -> None:
        self.parts.append(data)

    def text(self) -> str:
        return " ".join(self.parts)


def _normalize_version(raw: str) -> str:
    version = raw.strip()
    if version.lower().startswith("v"):
        version = version[1:]
    if not VERSION_PATTERN.fullmatch(version):
        raise ValueError(f"invalid version: {raw!r}")
    return version


def _run_gh(repo: str, tag: str) -> dict:
    env = os.environ.copy()
    env["GH_PAGER"] = "cat"
    result = subprocess.run(
        [
            "gh",
            "release",
            "view",
            tag,
            "--repo",
            repo,
            "--json",
            "tagName,name,url,isDraft,isPrerelease,body,assets",
        ],
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        env=env,
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "gh release view failed")
    return json.loads(result.stdout)


def _release_bullets(body: str) -> list[str]:
    if not body.strip():
        raise ValueError("Release body is empty")
    if not re.search(r"(?m)^##\s+更新内容\s*$", body):
        raise ValueError("Release body must contain exact heading: ## 更新内容")
    bullets = [
        match.group(1).strip()
        for line in body.splitlines()
        if (match := BULLET_PATTERN.match(line))
    ]
    if not bullets:
        raise ValueError("Release body has no user-facing Markdown bullets")
    return bullets


def _fetch_atom(repo: str) -> ET.Element:
    request = urllib.request.Request(
        f"https://github.com/{repo}/releases.atom",
        headers={
            "Accept": "application/atom+xml,application/xml;q=0.9",
            "User-Agent": "ChatBar-release-verifier",
        },
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return ET.fromstring(response.read())


def _atom_content(root: ET.Element, tag: str) -> str:
    for entry in root.findall(f"{{{ATOM_NAMESPACE}}}entry"):
        links = entry.findall(f"{{{ATOM_NAMESPACE}}}link")
        hrefs = [
            link.attrib.get("href", "")
            for link in links
            if link.attrib.get("rel", "alternate") == "alternate"
        ]
        if not any(href.rstrip("/").endswith(f"/releases/tag/{tag}") for href in hrefs):
            continue
        content = entry.findtext(f"{{{ATOM_NAMESPACE}}}content", default="")
        parser = _PlainTextParser()
        parser.feed(html.unescape(content))
        return parser.text()
    raise ValueError(f"Atom feed has no entry for {tag}")


def _plain(value: str) -> str:
    value = re.sub(r"\[([^\]]+)]\([^)]+\)", r"\1", value)
    value = re.sub(r"[`*_#]", "", value)
    return re.sub(r"\s+", "", html.unescape(value))


def verify(repo: str, raw_version: str) -> dict:
    version = _normalize_version(raw_version)
    tag = f"v{version}"
    release = _run_gh(repo, tag)

    if release.get("tagName") != tag:
        raise ValueError(f"tag mismatch: expected {tag}, got {release.get('tagName')!r}")
    if release.get("isDraft") or release.get("isPrerelease"):
        raise ValueError("Release is not a published stable release")

    body = str(release.get("body") or "")
    bullets = _release_bullets(body)
    asset_name = f"ChatBar-{version}.apk"
    assets = release.get("assets") or []
    if not any(asset.get("name") == asset_name for asset in assets):
        raise ValueError(f"missing APK asset: {asset_name}")

    atom_text = _plain(_atom_content(_fetch_atom(repo), tag))
    missing = [bullet for bullet in bullets if _plain(bullet) not in atom_text]
    if missing:
        raise ValueError(f"Atom note mismatch; missing bullets: {missing}")

    return {
        "version": version,
        "tag": tag,
        "url": release.get("url"),
        "apk": asset_name,
        "note_bullets": len(bullets),
        "api_body": "ok",
        "atom_body": "ok",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", required=True, help="OWNER/REPO")
    parser.add_argument("--version", required=True, help="Version with or without v prefix")
    args = parser.parse_args()
    try:
        result = verify(args.repo, args.version)
    except Exception as error:
        print(f"FAILED: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
