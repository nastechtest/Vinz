#!/usr/bin/env python3
"""Fail-closed Vinz brand guard for text and visual assets.

Raster/vector/media assets and Android drawable/mipmap XML are SHA-256 allowlisted. New,
removed, or edited visuals fail until reviewed. Callers may supply source names and URLs to
reject; README.md is the sole attribution exception.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST_REL = ".github/vinz-visual-lock.json"
VISUAL_SUFFIXES = {
    ".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".avif", ".tif", ".tiff",
    ".svg", ".ico", ".icns", ".mp4", ".mov", ".webm", ".m4v",
}
TEXT_SUFFIXES = {
    ".kt", ".java", ".kts", ".xml", ".json", ".properties", ".txt", ".html", ".css",
    ".js", ".svg", ".md", ".yml", ".yaml", ".py", ".sh", ".gradle", ".toml", ".cfg",
}
SKIP_DIRS = {".git", ".gradle", ".kotlin", "build", "node_modules", "__pycache__", ".venv"}


def is_visual(path: Path) -> bool:
    if path.suffix.lower() in VISUAL_SUFFIXES:
        return True
    return path.suffix.lower() == ".xml" and any(
        part.startswith(("drawable", "mipmap")) for part in path.parts
    )


def discover_visuals(root: Path = ROOT) -> dict[str, str]:
    actual: dict[str, str] = {}
    for path in root.rglob("*"):
        if not path.is_file() or any(part in SKIP_DIRS for part in path.parts):
            continue
        if is_visual(path):
            actual[path.relative_to(root).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    return actual


def scan_text_policy(
    root: Path = ROOT,
    forbidden_texts: tuple[str, ...] | list[str] = (),
    forbidden_urls: tuple[str, ...] | list[str] = (),
) -> list[str]:
    """Reject caller-supplied source names/links everywhere except the attribution README."""
    problems: list[str] = []
    text_patterns = [re.compile(re.escape(value), re.IGNORECASE) for value in forbidden_texts if value]
    url_values = [value.casefold() for value in forbidden_urls if value]
    for path in root.rglob("*"):
        if not path.is_file() or any(part in SKIP_DIRS for part in path.parts):
            continue
        rel = path.relative_to(root).as_posix()
        if rel == "README.md":
            continue
        if path.suffix.lower() not in TEXT_SUFFIXES:
            continue
        if any(pattern.search(rel) for pattern in text_patterns):
            problems.append(f"forbidden source name in path: {rel}")
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        if any(pattern.search(text) for pattern in text_patterns):
            problems.append(f"forbidden source name outside README: {rel}")
        folded = text.casefold()
        if any(url in folded for url in url_values):
            problems.append(f"forbidden source URL outside README: {rel}")
    return problems


def verify(
    root: Path = ROOT,
    forbidden_texts: tuple[str, ...] | list[str] = (),
    forbidden_urls: tuple[str, ...] | list[str] = (),
) -> list[str]:
    manifest = root / MANIFEST_REL
    if not manifest.is_file():
        return [f"missing visual asset lock: {MANIFEST_REL}"]
    try:
        expected = json.loads(manifest.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as exc:
        return [f"cannot read visual asset lock: {exc}"]
    if not isinstance(expected, dict) or any(
        not isinstance(key, str) or not isinstance(value, str) for key, value in expected.items()
    ):
        return ["visual asset lock must be a JSON object of path-to-SHA-256 strings"]

    actual = discover_visuals(root)
    problems = scan_text_policy(root, forbidden_texts, forbidden_urls)
    for rel in sorted(set(expected) - set(actual)):
        problems.append(f"approved visual asset missing: {rel}")
    for rel in sorted(set(actual) - set(expected)):
        problems.append(f"unreviewed visual asset added: {rel}")
    for rel in sorted(set(actual) & set(expected)):
        if actual[rel] != expected[rel]:
            problems.append(f"visual asset changed; inspect and explicitly approve: {rel}")
    return problems


def self_test() -> None:
    with tempfile.TemporaryDirectory(prefix="vinz-brand-guard-") as td:
        root = Path(td)
        (root / ".github").mkdir()
        app = root / "app/src/main/res/values/strings.xml"
        app.parent.mkdir(parents=True)
        app.write_text('<resources><string name="app_name">Vinz</string></resources>\n', encoding="utf-8")
        visual = root / "app/src/main/res/drawable/logo.svg"
        visual.parent.mkdir(parents=True)
        visual.write_text("<svg>V</svg>\n", encoding="utf-8")
        lock = root / MANIFEST_REL
        lock.write_text(
            json.dumps({"app/src/main/res/drawable/logo.svg": hashlib.sha256(visual.read_bytes()).hexdigest()}),
            encoding="utf-8",
        )
        assert not verify(root), "clean Vinz fixture should pass"
        visual.write_text("<svg>changed</svg>\n", encoding="utf-8")
        assert any("visual asset changed" in problem for problem in verify(root)), "changed visual must fail"
        visual.write_text("<svg>V</svg>\n", encoding="utf-8")
        forbidden_name = "OtherBrand"
        app.write_text(f"<resources><string>{forbidden_name}</string></resources>\n", encoding="utf-8")
        assert any(
            "forbidden source name outside README" in problem
            for problem in verify(root, forbidden_texts=[forbidden_name])
        ), "source name in app text must fail"
        app.write_text('<resources><string>Vinz</string></resources>\n', encoding="utf-8")
        readme = root / "README.md"
        readme.write_text("attribution and https://example.invalid/source\n", encoding="utf-8")
        assert not verify(root, forbidden_texts=[forbidden_name], forbidden_urls=["example.invalid/source"]), (
            "README attribution is the sole exception"
        )
        other = root / ".github/source.txt"
        other.write_text("https://example.invalid/source\n", encoding="utf-8")
        assert any(
            "forbidden source URL outside README" in problem
            for problem in verify(root, forbidden_urls=["example.invalid/source"])
        ), "source URL outside README must fail"
        other.unlink()
        readme.unlink()
        new_visual = root / "promo/mark.png"
        new_visual.parent.mkdir()
        new_visual.write_bytes(b"unreviewed")
        assert any(
            "unreviewed visual asset added" in problem for problem in verify(root)
        ), "new visual must fail"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--forbid-text", action="append", default=[], help="source name to reject outside README")
    parser.add_argument("--forbid-url", action="append", default=[], help="source URL substring to reject outside README")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        print("Vinz brand guard self-test: PASS")
        return 0
    problems = verify(forbidden_texts=args.forbid_text, forbidden_urls=args.forbid_url)
    if problems:
        print("Vinz brand guard: FAIL", file=sys.stderr)
        for item in problems:
            print(f"  - {item}", file=sys.stderr)
        return 1
    count = len(discover_visuals())
    print(f"Vinz brand guard: PASS ({count} reviewed visual assets; README is the attribution exception)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
