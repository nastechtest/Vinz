#!/usr/bin/env python3
"""Sync Prism into Vinz without LLMs, losing local work, or overwriting Vinz's identity.

The upstream baseline is pinned in .github/upstream-prism.json. For each scheduled run this
script materializes the old upstream tree, the new upstream tree, and Vinz's current tree; applies
a deterministic three-way merge; rebrands Prism identifiers; protects the Vinz logo assets; and
updates the upstream watermark. A real conflict is a hard failure, never a guessed resolution.

Usage:
    python3 scripts/upstream/sync_prism.py [--ref refs/heads/main] [--check]
    python3 scripts/upstream/sync_prism.py --self-test

--check performs all discovery and merge checks without writing to the repository. Normal mode
writes changes into the checked-out working tree; the caller owns commit/PR/test/merge steps.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path, PurePosixPath
from typing import Iterable

ROOT = Path(__file__).resolve().parents[2]
UPSTREAM_URL = "https://github.com/Yigder/Prism.git"
STATE_FILE = Path(".github/upstream-prism.json")
BRAND_LOCK_FILE = Path(".github/vinz-brand-lock.json")

# Product/source trees that come from Prism. Vinz's own CI, release process, version properties,
# legal fork notice, and repository settings are deliberately outside this list.
SYNC_ROOTS = (
    "app",
    "baselineprofile",
    "gradle",
    "gradlew",
    "gradlew.bat",
    "build.gradle.kts",
    "gradle.properties",
    "settings.gradle.kts",
    "README.md",
    "CHANGELOG.md",
    "CONTRIBUTING.md",
    "docs",
    "design",
    "themes",
    "promo",
)

# Exact Vinz identity sources. They are never replaced by upstream even if Prism changes a file
# with the same name. The updater still imports new files around them and rewrites references.
PROTECTED_FILES = {
    "app/src/main/res/drawable/ic_launcher_background.xml",
    "app/src/main/res/drawable/ic_launcher_foreground.xml",
    "app/src/main/res/drawable/ic_launcher_monochrome.xml",
    "app/src/main/res/drawable/ic_notification.xml",
    "app/src/main/assets/remote/icon.svg",
}
PROTECTED_PREFIXES = (
    "app/src/main/res/mipmap-anydpi/",
    "app/src/main/res/drawable/ic_launcher_bg_",
)
# Keep the app's own release pipeline and all source/project integration untouched by upstream.
PRESERVE_ROOTS = {
    ".github",
    ".gitattributes",
    ".gitignore",
    "version.properties",
    "FORK_NOTICE.md",
    "LICENSE",
    "scripts",
    "app/lint-baseline.xml",
    "app/signing.properties",
    "local.properties",
}


def run(args: list[str], *, cwd: Path | None = None, capture: bool = False) -> str:
    result = subprocess.run(args, cwd=cwd, text=True, stdout=subprocess.PIPE if capture else None,
                            stderr=subprocess.PIPE if capture else None)
    if result.returncode:
        detail = (result.stderr or result.stdout or "").strip() if capture else ""
        raise RuntimeError(f"command failed ({result.returncode}): {' '.join(args)}\n{detail}")
    return result.stdout.strip() if capture else ""


def is_protected(rel: str) -> bool:
    return rel in PROTECTED_FILES or any(rel.startswith(prefix) for prefix in PROTECTED_PREFIXES)


def in_sync_scope(rel: str) -> bool:
    if rel in PRESERVE_ROOTS or any(rel == p or rel.startswith(p.rstrip("/") + "/") for p in PRESERVE_ROOTS):
        return False
    return any(rel == p or rel.startswith(p.rstrip("/") + "/") for p in SYNC_ROOTS)


def verify_brand_lock(root: Path = ROOT) -> None:
    """Fail closed if a protected Vinz logo, icon background, or remote favicon changed."""
    lock_path = root / BRAND_LOCK_FILE
    expected = json.loads(lock_path.read_text(encoding="utf-8"))
    failures = []
    for rel, digest in expected.items():
        path = root / rel
        if not path.is_file():
            failures.append(f"missing: {rel}")
            continue
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        if actual != digest:
            failures.append(f"changed: {rel}")
    if failures:
        raise RuntimeError("Vinz branding lock failed (reconcile the brand-lock manifest explicitly):\n  " + "\n  ".join(failures))
    icons = root / "app/src/main/java/com/vinz/music/ui/theme/AppIcons.kt"
    if icons.exists() and "ic_launcher_fg_" in icons.read_text(encoding="utf-8"):
        raise RuntimeError("AppIcons.kt still references Prism's per-icon foreground art")
    for path in (root / "app/src/main/res/drawable").glob("ic_launcher_fg_*.xml"):
        raise RuntimeError(f"per-icon foreground art is present: {path.relative_to(root)}")
    icons_source = root / "app/src/main/java/com/vinz/music/ui/theme/VinzIcons.kt"
    icon_dir = root / "design/icons"
    if icons_source.is_file():
        names = re.findall(r'drawn\("([^"\n]+)"\)', icons_source.read_text(encoding="utf-8"))
        missing = [name for name in names if not (icon_dir / f"{name}.svg").is_file()]
        if missing:
            raise RuntimeError("missing vector source(s) in design/icons: " + ", ".join(missing))


def rename_prism_path(name: str) -> str:
    # Case-sensitive, longest form first. Directory renames are performed bottom-up.
    return re.sub(r"PRISM|Prism|prism", lambda m: {"PRISM": "VINZ", "Prism": "Vinz", "prism": "vinz"}[m.group(0)], name)


def rebrand_tree(tree: Path) -> None:
    """Apply the fork's established case-preserving name/path conversion, excluding license texts."""
    for path in sorted(tree.rglob("*"), key=lambda p: len(p.parts), reverse=True):
        if not path.exists() or path.is_symlink():
            continue
        renamed = rename_prism_path(path.name)
        if renamed != path.name:
            target = path.with_name(renamed)
            if target.exists():
                raise RuntimeError(f"rebrand path collision: {path.relative_to(tree)} -> {target.relative_to(tree)}")
            path.rename(target)

    legal_names = {"LICENSE", "COPYING", "NOTICE", "NOTICE.txt", "COPYRIGHT"}
    for path in sorted(tree.rglob("*")):
        if not path.is_file() or path.is_symlink():
            continue
        rel = path.relative_to(tree).as_posix()
        if path.name.upper() in legal_names or "/licenses/" in f"/{rel.lower()}/":
            continue
        try:
            raw = path.read_bytes()
            text = raw.decode("utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        # Keep the author/source credit, but repoint links that named the upstream repository.
        text = text.replace("https://github.com/Yigder/Prism", "https://github.com/nastechtest/Vinz")
        text = text.replace("https://raw.githubusercontent.com/Yigder/Prism/", "https://raw.githubusercontent.com/nastechtest/Vinz/")
        text = re.sub(r"PRISM|Prism|prism", lambda m: {"PRISM": "VINZ", "Prism": "Vinz", "prism": "vinz"}[m.group(0)], text)
        if text.encode("utf-8") != raw:
            path.write_text(text, encoding="utf-8")

    # Prism has a separate one-off foreground asset for each icon choice. Vinz's single vector mark
    # sits over all the existing Vinz backgrounds, and the adaptive monochrome mark is unique too.
    app_icons = tree / "app/src/main/java/com/vinz/music/ui/theme/AppIcons.kt"
    if app_icons.exists():
        text = app_icons.read_text(encoding="utf-8")
        text = re.sub(r"R\.drawable\.ic_launcher_fg_[A-Za-z0-9_]+", "R.drawable.ic_launcher_foreground", text)
        app_icons.write_text(text, encoding="utf-8")
    icon_test = tree / "app/src/test/java/com/vinz/music/IconRenderTest.kt"
    if icon_test.exists():
        text = icon_test.read_text(encoding="utf-8")
        # Themed monochrome launcher rendering must test the actual Vinz monochrome vector.
        text = re.sub(r"val mono = if \(icon == .*?\) R\.drawable\.ic_launcher_fg_[A-Za-z0-9_]+ else R\.drawable\.ic_launcher_fg_[A-Za-z0-9_]+",
                      "val mono = R.drawable.ic_launcher_monochrome", text)
        text = re.sub(r"val mono = R\.drawable\.ic_launcher_fg_[A-Za-z0-9_]+", "val mono = R.drawable.ic_launcher_monochrome", text)
        icon_test.write_text(text, encoding="utf-8")
    for path in (tree / "app/src/main/res/drawable").glob("ic_launcher_fg_*.xml") if (tree / "app/src/main/res/drawable").exists() else ():
        path.unlink()


def export_tree(repo: Path, ref: str, destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    archive = subprocess.Popen(["git", "-C", str(repo), "archive", "--format=tar", ref], stdout=subprocess.PIPE)
    assert archive.stdout is not None
    extractor = subprocess.run(["tar", "-xf", "-", "-C", str(destination)], stdin=archive.stdout)
    archive.stdout.close()
    if archive.wait() or extractor.returncode:
        raise RuntimeError(f"could not export upstream tree at {ref}")
    rebrand_tree(destination)


def file_bytes(path: Path) -> bytes | None:
    if not path.exists():
        return None
    if path.is_symlink():
        raise RuntimeError(f"unexpected symlink in merge input: {path}")
    return path.read_bytes()


def merge_one(rel: str, base: bytes | None, ours: bytes | None, theirs: bytes | None, tmp: Path) -> bytes | None:
    """A content-level three-way merge; conflict markers are never accepted."""
    if ours == base:
        return theirs
    if theirs == base or ours == theirs:
        return ours
    if ours is None or theirs is None or base is None:
        raise RuntimeError(f"add/delete conflict at {rel}; needs a human decision")
    try:
        for name, value in (("ours", ours), ("base", base), ("theirs", theirs)):
            (tmp / name).write_bytes(value)
        # git merge-file order: current (ours), common ancestor, incoming (theirs).
        result = subprocess.run(["git", "merge-file", "-p", str(tmp / "ours"), str(tmp / "base"), str(tmp / "theirs")],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode:
            raise RuntimeError(f"text conflict at {rel}; no automatic resolution was attempted")
        return result.stdout
    except UnicodeDecodeError as exc:
        raise RuntimeError(f"binary conflict at {rel}; no automatic resolution was attempted") from exc


def scoped_files(tree: Path) -> set[str]:
    found: set[str] = set()
    for root in SYNC_ROOTS:
        p = tree / root
        if p.is_file():
            found.add(root)
        elif p.is_dir():
            found.update(x.relative_to(tree).as_posix() for x in p.rglob("*") if x.is_file() or x.is_symlink())
    return {f for f in found if in_sync_scope(f)}


def merge_trees(base_tree: Path, ours_tree: Path, theirs_tree: Path, *, check_only: bool) -> list[str]:
    base_paths, our_paths, their_paths = map(scoped_files, (base_tree, ours_tree, theirs_tree))
    changed: list[str] = []
    with tempfile.TemporaryDirectory(prefix="vinz-merge-") as td:
        temp = Path(td)
        for rel in sorted(base_paths | our_paths | their_paths):
            bp, op, tp = base_tree / rel, ours_tree / rel, theirs_tree / rel
            # Existing Vinz artwork always wins; new upstream aliases may still be imported and
            # will point to Vinz's protected foreground/monochrome source after rebranding.
            if is_protected(rel) and op.exists():
                continue
            merged = merge_one(rel, file_bytes(bp), file_bytes(op), file_bytes(tp), temp)
            if merged == file_bytes(op):
                continue
            changed.append(rel)
            if not check_only:
                if merged is None:
                    if op.exists():
                        op.unlink()
                else:
                    op.parent.mkdir(parents=True, exist_ok=True)
                    op.write_bytes(merged)
                    if tp.exists():
                        op.chmod(tp.stat().st_mode & 0o777)
    return changed


def read_state() -> dict:
    path = ROOT / STATE_FILE
    state = json.loads(path.read_text(encoding="utf-8"))
    if state.get("repository") != "Yigder/Prism" or not re.fullmatch(r"[0-9a-f]{40}", state.get("baseline_sha", "")):
        raise RuntimeError(f"invalid upstream state in {path}")
    return state


def latest_commit(repo: Path, ref: str) -> str:
    return run(["git", "-C", str(repo), "rev-parse", ref], capture=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", default="refs/heads/main", help="Prism git ref to sync (default: main)")
    parser.add_argument("--check", action="store_true", help="validate/preview without modifying Vinz")
    parser.add_argument("--self-test", action="store_true", help="run deterministic local merge/branding tests")
    parser.add_argument("--verify-branding", action="store_true", help="check protected Vinz logo hashes and references")
    args = parser.parse_args()
    if args.verify_branding:
        verify_brand_lock()
        print("Vinz branding lock: PASS")
        return 0
    if args.self_test:
        self_test()
        print("sync_prism self-test: PASS")
        return 0

    state = read_state()
    verify_brand_lock()
    with tempfile.TemporaryDirectory(prefix="vinz-prism-sync-") as td:
        temp = Path(td)
        upstream = temp / "prism"
        print("Fetching Prism refs (no model or token-based decisions)...", flush=True)
        run(["git", "clone", "--filter=blob:none", "--no-checkout", UPSTREAM_URL, str(upstream)])
        run(["git", "-C", str(upstream), "fetch", "--filter=blob:none", "origin", args.ref])
        head = latest_commit(upstream, "FETCH_HEAD")
        baseline = state["baseline_sha"]
        if head == baseline:
            print(f"Already current at {head[:12]}.")
            return 0
        base_tree, theirs_tree = temp / "base", temp / "theirs"
        export_tree(upstream, baseline, base_tree)
        export_tree(upstream, "FETCH_HEAD", theirs_tree)
        changed = merge_trees(base_tree, ROOT, theirs_tree, check_only=args.check)
        verify_brand_lock()
        print(f"Upstream {baseline[:12]} -> {head[:12]}; {len(changed)} files changed.")
        for rel in changed[:40]:
            print(f"  {rel}")
        if len(changed) > 40:
            print(f"  ... and {len(changed) - 40} more")
        if not args.check:
            state["baseline_sha"] = head
            state["baseline_ref"] = args.ref
            (ROOT / STATE_FILE).write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
            print("Updated watermark. Review/build/test the working tree before committing.")
        return 0


def self_test() -> None:
    # Verify case-preserving branding, license preservation, idempotence, and safe conflict behavior.
    with tempfile.TemporaryDirectory(prefix="vinz-sync-test-") as td:
        root = Path(td) / "tree"
        (root / "app/src/main/java/com/prism/music").mkdir(parents=True)
        (root / "LICENSE").write_text("Prism remains the original author's name.\n", encoding="utf-8")
        (root / "README.md").write_text("Prism prism PRISM https://github.com/Yigder/Prism\n", encoding="utf-8")
        (root / "app/src/main/java/com/prism/music/PrismApp.kt").write_text("package com.prism.music\n", encoding="utf-8")
        rebrand_tree(root)
        assert (root / "app/src/main/java/com/vinz/music/VinzApp.kt").read_text() == "package com.vinz.music\n"
        assert "Vinz vinz VINZ https://github.com/nastechtest/Vinz" in (root / "README.md").read_text()
        assert "Prism remains" in (root / "LICENSE").read_text()
        rebrand_tree(root)
        assert "Vinz vinz VINZ" in (root / "README.md").read_text()

        scratch = Path(td) / "merge"
        scratch.mkdir()
        assert merge_one("same", b"a", b"a", b"b", scratch) == b"b"
        assert merge_one("ours", b"a", b"b", b"a", scratch) == b"b"
        assert merge_one("same-change", b"a", b"b", b"b", scratch) == b"b"
        try:
            merge_one("conflict", b"one\ntwo\n", b"ONE\ntwo\n", b"one\nTWO\n", scratch)
        except RuntimeError:
            pass
        else:
            raise AssertionError("overlapping edits must stop for human review")
        assert merge_one("vinz-only", None, b"local\n", None, scratch) == b"local\n"
        assert merge_one("vinz-deleted", None, None, None, scratch) is None


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"sync_prism: ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
