#!/usr/bin/env python3
"""Apply the conservative Vinz rebrand to a local Prism checkout.

Visible product text is rebranded. Upstream attribution, legal notices, historical
release notes, code identifiers, and protocol/storage identifiers are preserved.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

README = """# Vinz

Vinz is an unofficial local rebrand/fork of [Prism](https://github.com/Yigder/Prism),
created by Yigder. It is based on Prism's Android YouTube Music client; this fork
changes the displayed app brand and Android application ID. It is not affiliated
with Yigder, Google, or YouTube.

## Upstream and license

- Original project and full feature documentation: <https://github.com/Yigder/Prism>
- Original author: Yigder
- Original copyright: Copyright (C) 2026 Yigder.
- License: GNU GPL version 3 or later; see [`LICENSE`](LICENSE).
- Original copyright remains with its respective holders. This rebrand does not
  transfer ownership of Prism or remove any upstream attribution. Historical
  entries in [`CHANGELOG.md`](CHANGELOG.md) retain the original Prism name.

Vinz inherits the upstream project's features, including YouTube Music playback,
word-by-word lyrics, offline downloads, recommendations, karaoke, DJ mode, Replay,
Android Auto, widgets, and local-network playback controls. Consult the upstream
README and documentation for detailed setup and feature notes.

## Build

Requirements and Android SDK versions are documented in the upstream project's
[contributing guide](https://github.com/Yigder/Prism/blob/main/CONTRIBUTING.md).

```bash
./gradlew :app:assembleDebug
```

This fork uses Android application ID `com.vinz.music`. The Kotlin namespace stays
`com.prism.music` to preserve the upstream source structure and avoid an unsafe,
mechanical package migration. Existing internal `prism://` links are likewise
retained for compatibility.
"""

NOTICE = """# Fork notice

This is a local Vinz rebrand/fork of [Yigder/Prism](https://github.com/Yigder/Prism).
The upstream author and project are credited; no attempt is made to claim Prism's
original code as newly authored. The project remains under GPL-3.0-or-later; retain
the `LICENSE` and all component notices when modifying or distributing it.

Changes made by `scripts/rebrand_vinz.py` update visible product text, app identity,
and fork documentation. The script deliberately does not alter historical release
notes, upstream copyright, source attribution, code identifiers, or internal
`prism://` protocol and storage identifiers.
"""


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        if new in text:
            return text
        raise SystemExit(f"Expected {label} marker not found: {old!r}")
    return text.replace(old, new)


def rebrand_kotlin_strings(text: str) -> str:
    """Replace product text in Kotlin double-quoted literals only."""
    pattern = re.compile(r'"(?:\\.|[^"\\])*"')
    protected = ("Yigder/Prism", "X-Prism", "prism://", "PRISMLOOK1", "PRISMBLEND", "PRISM_")

    def change(match: re.Match[str]) -> str:
        literal = match.group(0)
        body = literal[1:-1]
        if any(token in literal for token in protected) or body == "PRISM":
            return literal
        if re.fullmatch(r"(?:Prism[A-Z][A-Za-z0-9]*|PRISM[A-Z0-9_]*)", body):
            return literal
        return literal.replace("Prism", "Vinz").replace("PRISM", "VINZ")

    return "".join(pattern.sub(change, line) for line in text.splitlines(keepends=True))


def main() -> None:
    strings_path = ROOT / "app/src/main/res/values/strings.xml"
    text = strings_path.read_text(encoding="utf-8")
    updated = []
    for line in text.splitlines(keepends=True):
        if re.search(r"<string\b", line):
            line = line.replace(">Prism<", ">Vinz<").replace(">PRISM<", ">VINZ<")
            line = line.replace("Prism ·", "Vinz ·").replace("Play in Prism", "Play in Vinz")
            line = line.replace("Open Prism", "Open Vinz")
        updated.append(line)
    strings_path.write_text("".join(updated), encoding="utf-8")

    build_path = ROOT / "app/build.gradle.kts"
    build = build_path.read_text(encoding="utf-8")
    build = replace_once(build, 'applicationId = "com.prism.music"',
                         'applicationId = "com.vinz.music"', "application ID")
    build_path.write_text(build, encoding="utf-8")

    settings_path = ROOT / "settings.gradle.kts"
    settings = settings_path.read_text(encoding="utf-8")
    settings = replace_once(settings, 'rootProject.name = "Prism"',
                            'rootProject.name = "Vinz"', "Gradle project name")
    settings_path.write_text(settings, encoding="utf-8")

    # App copy and the paired tests/benchmark selectors; only quoted strings change.
    ui_roots = [ROOT / "app/src/main/java/com/prism/music",
                ROOT / "app/src/test/java/com/prism/music",
                ROOT / "baselineprofile/src"]
    for base in ui_roots:
        if base.exists():
            for path in base.rglob("*.kt"):
                original = path.read_text(encoding="utf-8")
                updated = rebrand_kotlin_strings(original)
                if updated != original:
                    path.write_text(updated, encoding="utf-8")

    # Release/update identity follows the Vinz repository, not the Prism upstream.
    updates_path = ROOT / "app/src/main/java/com/prism/music/data/Updates.kt"
    if updates_path.exists():
        updates = updates_path.read_text(encoding="utf-8")
        updates = replace_once(updates, 'const val REPO = "Yigder/Prism"',
                               'const val REPO = "nastechtest/Vinz"', "update repository")
        updates = updates.replace('"Prism.apk"', '"Vinz.apk"').replace('"Prism.dm"', '"Vinz.dm"')
        updates = updates.replace('"Prism-${update.version}.apk"', '"Vinz-${update.version}.apk"')
        updates_path.write_text(updates, encoding="utf-8")

    # Browser remote page text; preserve the matching X-Prism request header.
    remote_html = ROOT / "app/src/main/assets/remote/index.html"
    if remote_html.exists():
        remote_html.write_text(remote_html.read_text(encoding="utf-8").replace("Prism", "Vinz"), encoding="utf-8")
    remote_js = ROOT / "app/src/main/assets/remote/remote.js"
    if remote_js.exists():
        remote_js.write_text(remote_js.read_text(encoding="utf-8").replace("Prism", "Vinz").replace("X-Vinz", "X-Prism"), encoding="utf-8")

    for rel in ("CONTRIBUTING.md", "themes/README.md"):
        path = ROOT / rel
        if path.exists():
            path.write_text(path.read_text(encoding="utf-8").replace("Prism", "Vinz"), encoding="utf-8")

    licenses_note = ROOT / "app/src/main/assets/licenses/onnxruntime.txt"
    if licenses_note.exists():
        licenses_note.write_text(licenses_note.read_text(encoding="utf-8").replace("Prism", "Vinz"), encoding="utf-8")

    (ROOT / "README.md").write_text(README, encoding="utf-8")
    (ROOT / "FORK_NOTICE.md").write_text(NOTICE, encoding="utf-8")
    print("Updated visible Vinz branding; preserved upstream attribution, history, license, and technical identifiers.")


if __name__ == "__main__":
    main()
