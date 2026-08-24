#!/usr/bin/env python3
"""Build compact runtime assets from NovelAI's public compressed tokenizer definitions.

Sources used by the app:
  https://novelai.net/tokenizer/compressed/t5_tokenizer.def?v=2&static=true
  https://novelai.net/tokenizer/compressed/qwen35_tokenizer.def?v=2&static=true

The input files are raw-deflate JSON. Output files are gzip-compressed binary tables
that avoid parsing a multi-megabyte JSON document on an Android device.
"""

from __future__ import annotations

import argparse
import gzip
import json
import struct
import zlib
from pathlib import Path


def load_definition(path: Path) -> dict:
    compressed = path.read_bytes()
    for window_bits in (-zlib.MAX_WBITS, zlib.MAX_WBITS):
        try:
            return json.loads(zlib.decompress(compressed, window_bits))
        except zlib.error:
            continue
    raise ValueError(f"Unsupported tokenizer compression: {path}")


def write_bytes(stream, value: bytes) -> None:
    stream.write(struct.pack(">I", len(value)))
    stream.write(value)


def write_text(stream, value: str) -> None:
    write_bytes(stream, value.encode("utf-8"))


def byte_unicode_symbols() -> dict[str, int]:
    direct = list(range(ord("!"), ord("~") + 1))
    direct += list(range(0xA1, 0xAC + 1))
    direct += list(range(0xAE, 0xFF + 1))
    symbols = direct[:]
    extra = 0
    for byte in range(256):
        if byte not in direct:
            direct.append(byte)
            symbols.append(256 + extra)
            extra += 1
    return {chr(codepoint): byte for byte, codepoint in zip(direct, symbols)}


def extract_merges(config: dict) -> list[tuple[str, str]]:
    result: list[tuple[str, str]] = []
    for merge in config["merges"]:
        if isinstance(merge, str):
            left, right = merge.split(" ", 1)
        else:
            left, right = merge
        result.append((left, right))
    return result


def extract_special_tokens(config: dict) -> list[str]:
    raw = config.get("specialTokens", config.get("special_tokens", []))
    if isinstance(raw, dict):
        values = raw.keys()
    else:
        values = raw
    result: list[str] = []
    for value in values:
        if isinstance(value, str):
            token = value
        else:
            token = value.get("content", value.get("token", ""))
        if token and token not in result:
            result.append(token)
    return result


def build_t5(source: Path, destination: Path) -> None:
    definition = load_definition(source)
    config = definition.get("config", definition)
    model = config.get("model", config)
    vocab = model["vocab"]
    unk_id = int(model.get("unk_id", model.get("unkId", 2)))
    eos_id = next(
        (index for index, item in enumerate(vocab) if item[0] == "</s>"),
        1,
    )
    unknown_score = min(float(item[1]) for item in vocab) - 10.0
    nodes = [{"children": {}, "token": -1, "score": float("-inf")}]
    for token_id, (piece, score) in enumerate(vocab):
        node_index = 0
        for char in piece:
            unit = ord(char)
            child = nodes[node_index]["children"].get(unit)
            if child is None:
                child = len(nodes)
                nodes[node_index]["children"][unit] = child
                nodes.append({"children": {}, "token": -1, "score": float("-inf")})
            node_index = child
        nodes[node_index]["token"] = token_id
        nodes[node_index]["score"] = float(score)

    edges: list[tuple[int, int]] = []
    node_rows: list[tuple[int, float, int, int]] = []
    for node in nodes:
        first_edge = len(edges)
        children = sorted(node["children"].items())
        edges.extend(children)
        node_rows.append((node["token"], node["score"], first_edge, len(children)))

    destination.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(destination, "wb", compresslevel=9) as stream:
        stream.write(b"NT51")
        stream.write(struct.pack(">IIIIfI", 3, unk_id, eos_id, len(nodes), unknown_score, len(edges)))
        for token_id, score, first_edge, child_count in node_rows:
            stream.write(struct.pack(">ifII", token_id, score, first_edge, child_count))
        for unit, child in edges:
            stream.write(struct.pack(">II", unit, child))
    print(
        f"T5: vocab={len(vocab)} nodes={len(nodes)} edges={len(edges)} "
        f"output={destination.stat().st_size} bytes"
    )


def build_qwen(source: Path, destination: Path) -> None:
    definition = load_definition(source)
    config = definition.get("config", {})
    split_regex = config.get("splitRegex", config.get("split_regex"))
    if not split_regex:
        raise ValueError("Qwen definition has no splitRegex")
    merges = extract_merges(definition)
    special_tokens = extract_special_tokens(definition)

    vocab = {token: int(token_id) for token, token_id in definition["vocab"].items()}
    byte_symbols = byte_unicode_symbols()
    initial_symbol_ids = {token: vocab[token] for token in byte_symbols}
    unicode_for_byte = {byte: token for token, byte in byte_symbols.items()}
    encoded_merges: list[tuple[int, int, int]] = []
    for rank, (left, right) in enumerate(merges):
        if left not in vocab or right not in vocab or left + right not in vocab:
            raise ValueError(f"Merge {rank} references unknown vocab symbol: {left!r} + {right!r}")
        combined = left + right
        encoded_merges.append((vocab[left], vocab[right], vocab[combined]))

    destination.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(destination, "wb", compresslevel=9) as stream:
        stream.write(b"NQ51")
        stream.write(struct.pack(">I", 1))
        write_text(stream, split_regex)
        stream.write(struct.pack(">I", len(special_tokens)))
        for token in special_tokens:
            write_text(stream, token)
        stream.write(struct.pack(">" + "I" * 256, *(initial_symbol_ids[unicode_for_byte[byte]] for byte in range(256))))
        stream.write(struct.pack(">I", len(encoded_merges)))
        for left, right, result in encoded_merges:
            stream.write(struct.pack(">III", left, right, result))
    print(
        f"Qwen: vocab={len(vocab)} merges={len(merges)} "
        f"special={len(special_tokens)} regex={split_regex!r} "
        f"output={destination.stat().st_size} bytes"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--t5", type=Path, required=True)
    parser.add_argument("--qwen", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    # Avoid .gz: Android packaging transparently expands it and strips the suffix.
    build_t5(args.t5, args.output / "nai_t5_v2.binz")
    build_qwen(args.qwen, args.output / "nai_qwen35_v2.binz")


if __name__ == "__main__":
    main()
