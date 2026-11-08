#!/usr/bin/env python3
"""Validate Vinz brand assets and scan text for caller-supplied restricted names."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TEXT_SUFFIXES = {".kt", ".java", ".kts", ".xml", ".json", ".properties", ".txt", ".html", ".css", ".js", ".svg", ".md", ".yml", ".yaml", ".py", ".sh", ".gradle", ".toml", ".cfg"}
SKIP_DIRS = {".git", ".gradle", ".kotlin", "build", "node_modules", "__pycache__", ".venv"}
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
MANIFEST_FILE = "design/branding/asset-manifest.json"
LOGO_FILES = (
    "design/branding/vinz-logo.png",
    "app/src/main/res/drawable-nodpi/vinz_logo.png",
    "app/src/main/assets/remote/icon.png",
    "docs/branding/icon-preview.png",
)
NOTIFICATION_FILE = "app/src/main/res/drawable-nodpi/vinz_notification_mark.png"
LAUNCHER_FILES = {
    "app/src/main/res/mipmap-mdpi/ic_launcher.png": 48,
    "app/src/main/res/mipmap-hdpi/ic_launcher.png": 72,
    "app/src/main/res/mipmap-xhdpi/ic_launcher.png": 96,
    "app/src/main/res/mipmap-xxhdpi/ic_launcher.png": 144,
    "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png": 192,
}
FAVICON_FILES = {
    "app/src/main/assets/remote/favicon-16.png": 16,
    "app/src/main/assets/remote/favicon-32.png": 32,
    "app/src/main/assets/remote/favicon-48.png": 48,
    "app/src/main/assets/remote/icon-192.png": 192,
    "app/src/main/assets/remote/icon-512.png": 512,
    "app/src/main/assets/remote/apple-touch-icon.png": 180,
}
ICO_FILE = "app/src/main/assets/remote/favicon.ico"
EXPECTED_ASSETS = {
    **{path: (1024, "logo") for path in LOGO_FILES},
    NOTIFICATION_FILE: (512, "notification"),
    **{path: (size, "launcher") for path, size in LAUNCHER_FILES.items()},
    **{path: (size, "touch" if "apple-touch" in path else "favicon") for path, size in FAVICON_FILES.items()},
    ICO_FILE: (None, "favicon"),
}
RESOURCE_REFERENCES = {
    "app/src/main/res/drawable/ic_launcher_foreground.xml": "@drawable/vinz_logo",
    "app/src/main/res/drawable/ic_launcher_monochrome.xml": "@drawable/vinz_notification_mark",
    "app/src/main/res/drawable/ic_notification.xml": "@drawable/vinz_notification_mark",
    "app/src/main/res/drawable/tv_banner.xml": "@drawable/vinz_logo",
    "app/src/main/assets/remote/index.html": "icon.png",
}


def _safe_rel(value: object) -> str | None:
    if not isinstance(value, str) or not value or "\\" in value:
        return None
    path = Path(value)
    if path.is_absolute() or ".." in path.parts or path.as_posix() != value:
        return None
    return value


def png_rgba(data: bytes) -> tuple[int, int, bytes] | None:
    """Return decoded 8-bit non-interlaced RGBA scanlines, without image libraries."""
    if len(data) < 33 or data[:8] != PNG_SIGNATURE:
        return None
    pos, width, height, bit_depth, color_type, interlace = 8, None, None, None, None, None
    payload = bytearray()
    while pos + 12 <= len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        kind = data[pos + 4:pos + 8]
        end = pos + 12 + length
        if end > len(data):
            return None
        chunk = data[pos + 8:pos + 8 + length]
        if kind == b"IHDR":
            if length != 13 or width is not None:
                return None
            width, height, bit_depth, color_type, compression, filtering, interlace = struct.unpack(">IIBBBBB", chunk)
            if not width or not height or (bit_depth, color_type, compression, filtering, interlace) != (8, 6, 0, 0, 0):
                return None
        elif kind == b"IDAT":
            payload.extend(chunk)
        elif kind == b"IEND":
            break
        pos = end
    if width is None or not payload:
        return None
    try:
        raw = zlib.decompress(payload)
    except zlib.error:
        return None
    stride, expected = width * 4, height * (width * 4 + 1)
    if len(raw) != expected:
        return None
    rows, previous, cursor = [], bytearray(stride), 0
    for _ in range(height):
        filter_type, encoded = raw[cursor], raw[cursor + 1:cursor + 1 + stride]
        cursor += stride + 1
        row = bytearray(stride)
        for index, value in enumerate(encoded):
            left = row[index - 4] if index >= 4 else 0
            up = previous[index]
            up_left = previous[index - 4] if index >= 4 else 0
            if filter_type == 0: result = value
            elif filter_type == 1: result = (value + left) & 255
            elif filter_type == 2: result = (value + up) & 255
            elif filter_type == 3: result = (value + ((left + up) // 2)) & 255
            elif filter_type == 4:
                p = left + up - up_left
                pa, pb, pc = abs(p - left), abs(p - up), abs(p - up_left)
                result = (value + (left if pa <= pb and pa <= pc else up if pb <= pc else up_left)) & 255
            else: return None
            row[index] = result
        rows.append(row)
        previous = row
    return width, height, b"".join(rows)


def png_problems(
    rel: str,
    data: bytes,
    size: int,
    *,
    white_only: bool = False,
    decoded: tuple[int, int, bytes] | None = None,
) -> list[str]:
    """Reject malformed, square-backed, or unpadded transparent PNG assets.

    ``decoded`` lets the caller reuse an identical logo's pure-Python decode.
    """
    decoded = png_rgba(data) if decoded is None else decoded
    if decoded is None:
        return [f"brand PNG must be a valid non-interlaced 8-bit RGBA PNG: {rel}"]
    width, height, pixels = decoded
    if (width, height) != (size, size):
        return [f"brand PNG must be {size}x{size}: {rel}"]
    alpha = pixels[3::4]
    if not any(value == 0 for value in alpha) or not any(value for value in alpha):
        return [f"brand PNG must contain transparent background and nonzero foreground: {rel}"]
    edge_offsets = range(width)
    edge_pixels = [i for x in edge_offsets for i in (x, (height - 1) * width + x)]
    edge_pixels += [y * width for y in range(1, height - 1)] + [y * width + width - 1 for y in range(1, height - 1)]
    if any(alpha[index] != 0 for index in edge_pixels):
        return [f"brand PNG edges must be fully transparent: {rel}"]
    if white_only and any(pixels[index:index + 3] != b"\xff\xff\xff" for index in range(0, len(pixels), 4) if pixels[index + 3]):
        return [f"notification mark must use white RGB for every nonzero pixel: {rel}"]
    return []


def ico_problems(rel: str, data: bytes) -> list[str]:
    if len(data) < 6 or struct.unpack("<HHH", data[:6])[:2] != (0, 1):
        return [f"favicon ICO has an invalid header: {rel}"]
    count = struct.unpack("<H", data[4:6])[0]
    if count != 7 or len(data) < 6 + count * 16:
        return [f"favicon ICO must contain exactly seven frames: {rel}"]
    dimensions = set()
    for index in range(count):
        entry = data[6 + index * 16:22 + index * 16]
        width, height, length, offset = entry[0] or 256, entry[1] or 256, struct.unpack("<I", entry[8:12])[0], struct.unpack("<I", entry[12:16])[0]
        if not length or offset + length > len(data):
            return [f"favicon ICO contains a corrupt frame: {rel}"]
        frame = data[offset:offset + length]
        decoded = png_rgba(frame)
        if decoded is None:
            return [f"favicon ICO frame must contain a valid embedded RGBA PNG: {rel}"]
        actual_width, actual_height, _ = decoded
        if (actual_width, actual_height) != (width, height):
            return [f"favicon ICO frame dimensions do not match its embedded PNG: {rel}"]
        frame_problems = png_problems(f"{rel} frame {width}x{height}", frame, width, decoded=decoded)
        if frame_problems:
            return frame_problems
        dimensions.add((width, height))
    expected = {(n, n) for n in (16, 24, 32, 48, 64, 128, 256)}
    return [] if dimensions == expected else [f"favicon ICO frame dimensions are incorrect: {rel}"]


def load_manifest(root: Path) -> tuple[dict[str, dict[str, object]] | None, list[str]]:
    path, problems = root / MANIFEST_FILE, []
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return None, [f"missing or invalid branding manifest: {MANIFEST_FILE}"]
    if not isinstance(manifest, dict) or manifest.get("schema") != 1 or not isinstance(manifest.get("master"), dict) or not isinstance(manifest.get("assets"), list):
        return None, ["branding manifest has an invalid schema"]
    entries = [manifest["master"], *manifest["assets"]]
    by_path: dict[str, dict[str, object]] = {}
    for entry in entries:
        rel = _safe_rel(entry.get("path") if isinstance(entry, dict) else None)
        if not rel or rel == MANIFEST_FILE or rel in by_path or not isinstance(entry.get("sha256"), str) or not re.fullmatch(r"[0-9a-f]{64}", entry["sha256"]):
            problems.append("branding manifest contains an unsafe or invalid asset entry")
            continue
        by_path[rel] = entry
    if set(by_path) != set(EXPECTED_ASSETS):
        problems.append("branding manifest must cover the exact expected asset set")
    master = manifest["master"]
    if master.get("path") != LOGO_FILES[0] or master.get("kind") not in (None, "logo"):
        problems.append("branding manifest master must be the canonical logo")
    return by_path, problems


def verify_brand_assets(root: Path = ROOT) -> list[str]:
    manifest, problems = load_manifest(root)
    if manifest is None:
        return problems
    master_hash = manifest.get(LOGO_FILES[0], {}).get("sha256")
    # The four 1024px logo copies are byte-identical. Decode each digest once.
    decoded_pngs: dict[str, tuple[int, int, bytes] | None] = {}
    for rel, (size, kind) in EXPECTED_ASSETS.items():
        path = root / rel
        entry = manifest.get(rel)
        if not path.is_file():
            problems.append(f"missing Vinz brand asset: {rel}")
            continue
        if not isinstance(entry, dict):
            continue
        is_master = rel == LOGO_FILES[0]
        if ((not is_master and entry.get("kind") != kind) or
                (not is_master and size is not None and (entry.get("width"), entry.get("height")) != (size, size))):
            problems.append(f"branding manifest metadata is incorrect: {rel}")
        data = path.read_bytes()
        digest = hashlib.sha256(data).hexdigest()
        if is_master and digest != master_hash:
            problems.append("branding manifest master hash does not match canonical logo")
        elif digest != entry.get("sha256"):
            problems.append(f"branding manifest hash mismatch: {rel}")
        if rel in LOGO_FILES and digest != master_hash:
            problems.append(f"Vinz logo copy differs from the approved master: {rel}")
        if rel.endswith(".png"):
            if digest not in decoded_pngs:
                decoded_pngs[digest] = png_rgba(data)
            problems.extend(png_problems(
                rel, data, size, white_only=kind == "notification", decoded=decoded_pngs[digest],
            ))
        elif rel == ICO_FILE:
            problems.extend(ico_problems(rel, data))
    for rel, expected in RESOURCE_REFERENCES.items():
        path = root / rel
        if not path.is_file(): problems.append(f"missing Vinz brand reference file: {rel}")
        else:
            try: text = path.read_text(encoding="utf-8")
            except (UnicodeDecodeError, OSError): problems.append(f"unreadable Vinz brand reference file: {rel}"); continue
            if expected not in text: problems.append(f"Vinz brand reference missing {expected}: {rel}")
            if rel.endswith("index.html") and "icon.svg" in text: problems.append(f"obsolete remote logo reference remains: {rel}")
    if (root / "app/src/main/assets/remote/icon.svg").exists(): problems.append("obsolete remote vector logo remains: app/src/main/assets/remote/icon.svg")
    return problems


def scan(root: Path = ROOT, forbidden_texts: tuple[str, ...] | list[str] = (), forbidden_urls: tuple[str, ...] | list[str] = ()) -> list[str]:
    problems, patterns, urls = [], [re.compile(re.escape(item), re.IGNORECASE) for item in forbidden_texts if item], [item.casefold() for item in forbidden_urls if item]
    for path in root.rglob("*"):
        if not path.is_file() or any(part in SKIP_DIRS for part in path.parts): continue
        rel = path.relative_to(root).as_posix()
        if rel == "README.md" or path.suffix.lower() not in TEXT_SUFFIXES: continue
        if any(pattern.search(rel) for pattern in patterns): problems.append(f"restricted name in path: {rel}")
        try: text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError): continue
        if any(pattern.search(text) for pattern in patterns): problems.append(f"restricted name outside README: {rel}")
        if any(url in text.casefold() for url in urls): problems.append(f"restricted URL outside README: {rel}")
    return problems


def self_test() -> None:
    from tempfile import TemporaryDirectory
    with TemporaryDirectory(prefix="vinz-brand-guard-") as td:
        root = Path(td); (root / "app").mkdir(); (root / "app/source.kt").write_text("OtherBrand")
        assert scan(root, ["OtherBrand"])
        (root / "app/source.kt").write_text("Vinz"); (root / "README.md").write_text("OtherBrand https://example.invalid/source")
        assert not scan(root, ["OtherBrand"], ["example.invalid/source"])
        (root / "app/source.kt").write_text("https://example.invalid/source")
        assert scan(root, forbidden_urls=["example.invalid/source"])
        assert verify_brand_assets(root)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument("--self-test", action="store_true"); parser.add_argument("--forbid-text", action="append", default=[]); parser.add_argument("--forbid-url", action="append", default=[]); args = parser.parse_args()
    if args.self_test: self_test(); print("Vinz branding guard self-test: PASS"); return 0
    problems = scan(forbidden_texts=args.forbid_text, forbidden_urls=args.forbid_url); problems.extend(verify_brand_assets())
    if problems:
        print("Vinz branding guard: FAIL", file=sys.stderr); [print(f"  - {problem}", file=sys.stderr) for problem in problems]; return 1
    print("Vinz branding guard: PASS"); return 0

if __name__ == "__main__": raise SystemExit(main())
