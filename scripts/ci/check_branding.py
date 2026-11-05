#!/usr/bin/env python3
"""Validate Vinz brand assets and scan text for caller-supplied restricted names."""
from __future__ import annotations

import argparse
import hashlib
import re
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TEXT_SUFFIXES = {
    ".kt", ".java", ".kts", ".xml", ".json", ".properties", ".txt", ".html", ".css",
    ".js", ".svg", ".md", ".yml", ".yaml", ".py", ".sh", ".gradle", ".toml", ".cfg",
}
SKIP_DIRS = {".git", ".gradle", ".kotlin", "build", "node_modules", "__pycache__", ".venv"}
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
LOGO_SHA256 = "a87c595bbf5d25527c4c8265cf35aee492b4eb7845907b309440804895ccc940"
NOTIFICATION_SHA256 = "303226706680392135d9a50d09fe47d593c5aafc9523c48500e53ee28b303d12"
LOGO_FILES = (
    "design/branding/vinz-logo.png",
    "app/src/main/res/drawable-nodpi/vinz_logo.png",
    "app/src/main/assets/remote/icon.png",
    "docs/branding/icon-preview.png",
)
NOTIFICATION_FILE = "app/src/main/res/drawable-nodpi/vinz_notification_mark.png"
RESOURCE_REFERENCES = {
    "app/src/main/res/drawable/ic_launcher_foreground.xml": "@drawable/vinz_logo",
    "app/src/main/res/drawable/ic_launcher_monochrome.xml": "@drawable/vinz_notification_mark",
    "app/src/main/res/drawable/ic_notification.xml": "@drawable/vinz_notification_mark",
    "app/src/main/res/drawable/tv_banner.xml": "@drawable/vinz_logo",
    "app/src/main/assets/remote/index.html": "icon.png",
}


def png_info(data: bytes) -> tuple[int, int, int] | None:
    if len(data) < 26 or data[:8] != PNG_SIGNATURE or data[12:16] != b"IHDR":
        return None
    width, height = struct.unpack(">II", data[16:24])
    return width, height, data[25]


def verify_brand_assets(root: Path = ROOT) -> list[str]:
    problems: list[str] = []
    for rel in LOGO_FILES:
        path = root / rel
        if not path.is_file():
            problems.append(f"missing Vinz logo asset: {rel}")
            continue
        data = path.read_bytes()
        if hashlib.sha256(data).hexdigest() != LOGO_SHA256:
            problems.append(f"Vinz logo asset differs from the approved master: {rel}")
        if png_info(data) != (1254, 1254, 2):
            problems.append(f"Vinz logo asset is not the expected 1254px RGB PNG: {rel}")

    notification = root / NOTIFICATION_FILE
    if not notification.is_file():
        problems.append(f"missing notification mark: {NOTIFICATION_FILE}")
    else:
        data = notification.read_bytes()
        if hashlib.sha256(data).hexdigest() != NOTIFICATION_SHA256:
            problems.append(f"notification mark differs from the approved logo-derived mask: {NOTIFICATION_FILE}")
        if png_info(data) != (512, 512, 6):
            problems.append(f"notification mark must be a 512px RGBA PNG: {NOTIFICATION_FILE}")

    for rel, expected in RESOURCE_REFERENCES.items():
        path = root / rel
        if not path.is_file():
            problems.append(f"missing Vinz brand reference file: {rel}")
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            problems.append(f"unreadable Vinz brand reference file: {rel}")
            continue
        if expected not in text:
            problems.append(f"Vinz brand reference missing {expected}: {rel}")
        if rel.endswith("index.html") and "icon.svg" in text:
            problems.append(f"obsolete remote logo reference remains: {rel}")

    if (root / "app/src/main/assets/remote/icon.svg").exists():
        problems.append("obsolete remote vector logo remains: app/src/main/assets/remote/icon.svg")
    return problems


def scan(
    root: Path = ROOT,
    forbidden_texts: tuple[str, ...] | list[str] = (),
    forbidden_urls: tuple[str, ...] | list[str] = (),
) -> list[str]:
    problems: list[str] = []
    patterns = [re.compile(re.escape(item), re.IGNORECASE) for item in forbidden_texts if item]
    urls = [item.casefold() for item in forbidden_urls if item]
    for path in root.rglob("*"):
        if not path.is_file() or any(part in SKIP_DIRS for part in path.parts):
            continue
        rel = path.relative_to(root).as_posix()
        if rel == "README.md" or path.suffix.lower() not in TEXT_SUFFIXES:
            continue
        if any(pattern.search(rel) for pattern in patterns):
            problems.append(f"restricted name in path: {rel}")
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        if any(pattern.search(text) for pattern in patterns):
            problems.append(f"restricted name outside README: {rel}")
        folded = text.casefold()
        if any(url in folded for url in urls):
            problems.append(f"restricted URL outside README: {rel}")
    return problems


def self_test() -> None:
    from tempfile import TemporaryDirectory

    with TemporaryDirectory(prefix="vinz-brand-guard-") as td:
        root = Path(td)
        (root / "app").mkdir()
        (root / "app/source.kt").write_text("OtherBrand", encoding="utf-8")
        assert scan(root, ["OtherBrand"]), "restricted text outside README must fail"
        (root / "app/source.kt").write_text("Vinz", encoding="utf-8")
        (root / "README.md").write_text("OtherBrand https://example.invalid/source", encoding="utf-8")
        assert not scan(root, ["OtherBrand"], ["example.invalid/source"]), "README is the attribution exception"
        (root / "app/source.kt").write_text("https://example.invalid/source", encoding="utf-8")
        assert scan(root, forbidden_urls=["example.invalid/source"]), "restricted URL outside README must fail"
        assert verify_brand_assets(root), "missing brand assets must fail closed"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--forbid-text", action="append", default=[])
    parser.add_argument("--forbid-url", action="append", default=[])
    args = parser.parse_args()
    if args.self_test:
        self_test()
        print("Vinz branding guard self-test: PASS")
        return 0
    problems = scan(forbidden_texts=args.forbid_text, forbidden_urls=args.forbid_url)
    problems.extend(verify_brand_assets())
    if problems:
        print("Vinz branding guard: FAIL", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1
    print("Vinz branding guard: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
