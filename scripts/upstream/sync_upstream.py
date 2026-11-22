#!/usr/bin/env python3
"""Sync the configured source into Vinz without losing local work or overwriting Vinz's identity.

The source URL and source names are runtime inputs, not stored in this repository. The baseline
is kept in this script so no public source-configuration manifest is needed. Each run applies a
deterministic three-way merge and stops for conflicts or visual changes requiring review.

Usage:
    python3 scripts/upstream/sync_upstream.py [--ref refs/heads/main] [--check]
    python3 scripts/upstream/sync_upstream.py --self-test

--check performs all discovery and merge checks without writing to the repository. Normal mode
writes changes into the checked-out working tree; the caller owns commit/PR/test/merge steps.
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path, PurePosixPath
from typing import Iterable
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
VINZ_REPOSITORY = "nastechtest/Vinz"
BASELINE_SHA = "d068196fcd70641263b808f9dbfe5a02e9d9b1c5"

# Product/source trees that come from the upstream repository. Vinz's CI, releases, version properties,
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
    "CHANGELOG.md",
    "CONTRIBUTING.md",
    "docs",
    "design",
    "themes",
)

# Exact Vinz identity sources. They are never replaced by upstream even if a source file changes
# with the same name. The updater still imports new files around them and rewrites references.
PROTECTED_FILES = {
    "app/src/main/res/drawable/ic_launcher_background.xml",
    "app/src/main/res/drawable/ic_launcher_foreground.xml",
    "app/src/main/res/drawable/ic_launcher_monochrome.xml",
    "app/src/main/res/drawable/ic_notification.xml",
    "app/src/main/res/drawable/tv_banner.xml",
    "design/branding/vinz-logo.png",
    "app/src/main/res/drawable-nodpi/vinz_logo.png",
    "app/src/main/res/drawable-nodpi/vinz_notification_mark.png",
    "app/src/main/assets/remote/icon.png",
    "docs/branding/icon-preview.png",
}
PROTECTED_PREFIXES = (
    "design/branding/",
    "app/src/main/res/mipmap-mdpi/",
    "app/src/main/res/mipmap-hdpi/",
    "app/src/main/res/mipmap-xhdpi/",
    "app/src/main/res/mipmap-xxhdpi/",
    "app/src/main/res/mipmap-xxxhdpi/",
    "app/src/main/assets/remote/favicon",
    "app/src/main/assets/remote/icon-",
    "app/src/main/assets/remote/apple-touch-icon",
    "app/src/main/assets/remote/manifest.json",
    "app/src/main/res/mipmap-anydpi/",
    "app/src/main/res/drawable/ic_launcher_bg_",
)
# Keep the app's own release pipeline and all source/project integration untouched by upstream.
PRESERVE_ROOTS = {
    ".github",
    ".gitattributes",
    ".gitignore",
    "version.properties",
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
    """Check the checked-in Vinz brand assets and their code references without a manifest."""
    required = (
        "app/src/main/res/drawable/ic_launcher_background.xml",
        "app/src/main/res/drawable/ic_launcher_foreground.xml",
        "app/src/main/res/drawable/ic_launcher_monochrome.xml",
        "app/src/main/res/drawable/ic_notification.xml",
        "app/src/main/res/drawable/tv_banner.xml",
        "app/src/main/assets/remote/icon.png",
    )
    missing = [rel for rel in required if not (root / rel).is_file()]
    if missing:
        raise RuntimeError("missing Vinz identity asset(s): " + ", ".join(missing))
    icons = root / "app/src/main/java/com/vinz/music/ui/theme/AppIcons.kt"
    if icons.exists() and "ic_launcher_fg_" in icons.read_text(encoding="utf-8"):
        raise RuntimeError("AppIcons.kt still references upstream per-icon foreground art")
    for path in (root / "app/src/main/res/drawable").glob("ic_launcher_fg_*.xml"):
        raise RuntimeError(f"per-icon foreground art is present: {path.relative_to(root)}")
    icons_source = root / "app/src/main/java/com/vinz/music/ui/theme/VinzIcons.kt"
    icon_dir = root / "design/icons"
    if icons_source.is_file():
        names = re.findall(r'drawn\("([^"\n]+)"\)', icons_source.read_text(encoding="utf-8"))
        missing = [name for name in names if not (icon_dir / f"{name}.svg").is_file()]
        if missing:
            raise RuntimeError("missing vector source(s) in design/icons: " + ", ".join(missing))


def replace_casefolded(value: str, token: str, replacement: str) -> str:
    if not token:
        return value

    def convert(match: re.Match[str]) -> str:
        original = match.group(0)
        if original.isupper():
            return replacement.upper()
        if original.islower():
            return replacement.lower()
        return replacement.capitalize() if original[0].isupper() else replacement.lower()

    return re.sub(re.escape(token), convert, value, flags=re.IGNORECASE)


def rename_source_path(name: str, source_brand: str, source_owner: str) -> str:
    name = replace_casefolded(name, source_brand, "Vinz")
    return replace_casefolded(name, source_owner, "Vinz")


def rebrand_tree(tree: Path, source_brand: str, source_owner: str, source_repository: str) -> None:
    """Rebrand source paths/text while leaving named attribution in root README only."""
    for path in sorted(tree.rglob("*"), key=lambda p: len(p.parts), reverse=True):
        if not path.exists() or path.is_symlink():
            continue
        renamed = rename_source_path(path.name, source_brand, source_owner)
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
        text = text.replace(f"https://github.com/{source_repository}", f"https://github.com/{VINZ_REPOSITORY}")
        text = text.replace(
            f"https://raw.githubusercontent.com/{source_repository}/",
            f"https://raw.githubusercontent.com/{VINZ_REPOSITORY}/",
        )
        text = replace_casefolded(text, source_brand, "Vinz")
        if rel != "README.md":
            text = replace_casefolded(text, source_owner, "Vinz")
        if text.encode("utf-8") != raw:
            path.write_text(text, encoding="utf-8")

    # The upstream has a separate foreground asset for each icon choice. Vinz's single vector mark
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


def export_tree(
    repo: Path,
    ref: str,
    destination: Path,
    source_brand: str,
    source_owner: str,
    source_repository: str,
) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    archive = subprocess.Popen(["git", "-C", str(repo), "archive", "--format=tar", ref], stdout=subprocess.PIPE)
    assert archive.stdout is not None
    extractor = subprocess.run(["tar", "-xf", "-", "-C", str(destination)], stdin=archive.stdout)
    archive.stdout.close()
    if archive.wait() or extractor.returncode:
        raise RuntimeError(f"could not export upstream tree at {ref}")
    rebrand_tree(destination, source_brand, source_owner, source_repository)


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


def is_visual_asset(rel: str) -> bool:
    path = PurePosixPath(rel)
    visual_suffixes = {
        ".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".avif", ".tif", ".tiff",
        ".svg", ".ico", ".icns", ".mp4", ".mov", ".webm", ".m4v",
    }
    if path.suffix.lower() in visual_suffixes:
        return True
    return path.suffix.lower() == ".xml" and any(
        part.startswith(("drawable", "mipmap")) for part in path.parts
    )


def merge_trees(base_tree: Path, ours_tree: Path, theirs_tree: Path, *, check_only: bool) -> list[str]:
    base_paths, our_paths, their_paths = map(scoped_files, (base_tree, ours_tree, theirs_tree))
    changed: list[str] = []
    pending: list[tuple[str, bytes | None, Path]] = []
    visual_changes: list[str] = []
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
            pending.append((rel, merged, tp))
            if is_visual_asset(rel):
                visual_changes.append(rel)
    if visual_changes:
        raise RuntimeError(
            "visual assets changed; inspect them manually before syncing: "
            + ", ".join(visual_changes[:30])
        )
    if not check_only:
        for rel, merged, tp in pending:
            op = ours_tree / rel
            if merged is None:
                if op.exists():
                    op.unlink()
            else:
                op.parent.mkdir(parents=True, exist_ok=True)
                op.write_bytes(merged)
                if tp.exists():
                    op.chmod(tp.stat().st_mode & 0o777)
    return changed


def source_repository_from_url(source_url: str) -> str:
    parsed = urlsplit(source_url)
    if not parsed.hostname or parsed.hostname.casefold() != "github.com":
        raise RuntimeError("source URL must be a GitHub repository URL")
    repository = parsed.path.strip("/")
    if repository.endswith(".git"):
        repository = repository[:-4]
    if not re.fullmatch(r"[^/]+/[^/]+", repository):
        raise RuntimeError("source URL must identify exactly one GitHub owner/repository")
    return repository


def verify_destination() -> None:
    origin = run(["git", "remote", "get-url", "origin"], cwd=ROOT, capture=True)
    match = re.search(r"github\.com[:/]([^/]+/[^/]+?)(?:\.git)?$", origin)
    if not match or match.group(1).casefold() != VINZ_REPOSITORY.casefold():
        raise RuntimeError(f"refusing to sync: origin must be {VINZ_REPOSITORY}; got {origin}")


def update_baseline_sha(new_sha: str) -> None:
    script = Path(__file__).resolve()
    text = script.read_text(encoding="utf-8")
    updated, count = re.subn(
        r'^BASELINE_SHA = "[0-9a-f]{40}"$',
        f'BASELINE_SHA = "{new_sha}"',
        text,
        count=1,
        flags=re.MULTILINE,
    )
    if count != 1:
        raise RuntimeError("could not update the baseline SHA in this script")
    script.write_text(updated, encoding="utf-8")


def latest_commit(repo: Path, ref: str) -> str:
    return run(["git", "-C", str(repo), "rev-parse", ref], capture=True)


def verify_visual_policy(source_brand: str, source_owner: str, source_repository: str) -> None:
    """Reject caller-supplied source names and links outside the root README."""
    command = [sys.executable, str(ROOT / "scripts/ci/check_branding.py")]
    for value in (source_brand, source_owner):
        command.extend(("--forbid-text", value))
    for value in (f"github.com/{source_repository}", f"raw.githubusercontent.com/{source_repository}"):
        command.extend(("--forbid-url", value))
    run(command, cwd=ROOT)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-url", default=os.environ.get("VINZ_SYNC_SOURCE_URL"))
    parser.add_argument("--source-brand", default=os.environ.get("VINZ_SYNC_SOURCE_BRAND"))
    parser.add_argument("--source-owner", default=os.environ.get("VINZ_SYNC_SOURCE_OWNER"))
    parser.add_argument("--ref", default="refs/heads/main", help="source git ref to sync (default: main)")
    parser.add_argument("--check", action="store_true", help="validate/preview without modifying Vinz")
    parser.add_argument("--self-test", action="store_true", help="run deterministic local merge/branding tests")
    parser.add_argument("--verify-branding", action="store_true", help="check Vinz identity assets and references")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        print("upstream sync self-test: PASS")
        return 0
    if not args.source_url or not args.source_brand or not args.source_owner:
        parser.error("source URL, source brand, and source owner must be supplied at runtime")
    source_repository = source_repository_from_url(args.source_url)
    if args.verify_branding:
        verify_brand_lock()
        verify_visual_policy(args.source_brand, args.source_owner, source_repository)
        return 0

    verify_destination()
    dirty = run(["git", "status", "--porcelain"], cwd=ROOT, capture=True)
    if dirty:
        raise RuntimeError("sync requires a clean working tree")
    verify_brand_lock()
    verify_visual_policy(args.source_brand, args.source_owner, source_repository)
    with tempfile.TemporaryDirectory(prefix="vinz-upstream-sync-") as td:
        temp = Path(td)
        upstream = temp / "source"
        print("Fetching source refs (no model or token-based decisions)...", flush=True)
        run(["git", "clone", "--filter=blob:none", "--no-checkout", args.source_url, str(upstream)])
        run(["git", "-C", str(upstream), "fetch", "--filter=blob:none", "origin", args.ref])
        head = latest_commit(upstream, "FETCH_HEAD")
        baseline = BASELINE_SHA
        if head == baseline:
            print(f"Already current at {head[:12]}.")
            return 0
        base_tree, theirs_tree = temp / "base", temp / "theirs"
        export_tree(upstream, baseline, base_tree, args.source_brand, args.source_owner, source_repository)
        export_tree(upstream, "FETCH_HEAD", theirs_tree, args.source_brand, args.source_owner, source_repository)
        changed = merge_trees(base_tree, ROOT, theirs_tree, check_only=args.check)
        verify_brand_lock()
        verify_visual_policy(args.source_brand, args.source_owner, source_repository)
        print(f"Baseline {baseline[:12]} -> {head[:12]}; {len(changed)} files changed.")
        for rel in changed[:40]:
            print(f"  {rel}")
        if len(changed) > 40:
            print(f"  ... and {len(changed) - 40} more")
        if not args.check:
            update_baseline_sha(head)
            print("Updated the baseline in this script. Review/build/test before committing.")
        return 0


def self_test() -> None:
    # Verify case-preserving rebranding, README attribution, idempotence, and safe conflict behavior.
    with tempfile.TemporaryDirectory(prefix="vinz-sync-test-") as td:
        root = Path(td) / "tree"
        (root / "app/src/main/java/com/example/music").mkdir(parents=True)
        (root / "LICENSE").write_text("License terms remain unchanged.\n", encoding="utf-8")
        (root / "README.md").write_text(
            "Example example EXAMPLE https://github.com/sample/Example Author\n", encoding="utf-8"
        )
        (root / "app/src/main/java/com/example/music/ExampleApp.kt").write_text(
            "package com.example.music\n", encoding="utf-8"
        )
        (root / "themes.json").write_text('{"by":"Author"}\n', encoding="utf-8")
        rebrand_tree(root, "Example", "Author", "sample/Example")
        assert (root / "app/src/main/java/com/vinz/music/VinzApp.kt").read_text() == "package com.vinz.music\n"
        assert "Vinz vinz VINZ https://github.com/nastechtest/Vinz Author" in (root / "README.md").read_text()
        assert '"by":"Vinz"' in (root / "themes.json").read_text()
        assert "License terms remain unchanged." in (root / "LICENSE").read_text()
        rebrand_tree(root, "Example", "Author", "sample/Example")
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

        base, ours, theirs = (Path(td) / name for name in ("base-tree", "ours-tree", "theirs-tree"))
        for tree in (base, ours, theirs):
            (tree / "app/src/main/res/drawable").mkdir(parents=True)
        image = "app/src/main/res/drawable/sample.png"
        (base / image).write_bytes(b"old")
        (ours / image).write_bytes(b"old")
        (theirs / image).write_bytes(b"new")
        try:
            merge_trees(base, ours, theirs, check_only=False)
        except RuntimeError as exc:
            assert "visual assets changed" in str(exc)
        else:
            raise AssertionError("changed visual assets must stop for manual review")
        assert (ours / image).read_bytes() == b"old", "visual-review failure must not write partial changes"


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"upstream sync: ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
