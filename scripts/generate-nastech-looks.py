#!/usr/bin/env python3
"""Generate only Nastech Research gallery entries using Vinz's raw-DEFLATE wire format.

No dependencies. Run --check in CI/review; omit it to regenerate after editing the
human-readable themes/nastech-research.json profiles. Existing entries are preserved.
"""
import argparse
import base64
import json
from pathlib import Path
import re
import sys
import zlib

ROOT = Path(__file__).resolve().parents[1]
PROFILES = ROOT / "themes/nastech-research.json"
CATALOG = ROOT / "themes/index.json"
SCREENS = ("HOME", "SEARCH", "LIBRARY", "REPLAY", "SETTINGS", "PLAYER")


def encode(name, values):
    wire = json.dumps({"n": name, "v": values}, ensure_ascii=False, separators=(",", ":")).encode()
    compressor = zlib.compressobj(level=9, wbits=-15)
    compressed = compressor.compress(wire) + compressor.flush()
    return "VINZLOOK1:" + base64.urlsafe_b64encode(compressed).decode().rstrip("=")


def generate(pack):
    assert pack["format"] == 1 and pack["by"] == "Nastech Research"
    assert len(pack["looks"]) == 10, "The pack must contain exactly ten Looks"
    result = []
    for profile in pack["looks"]:
        assert re.fullmatch(r"nr-[a-z0-9-]{1,37}", profile["id"])
        assert 0 < len(profile["name"]) <= 40
        assert 0 < len(profile["note"]) <= 80
        values = dict(pack["base"], **profile["values"])
        assert "reduce_motion" not in values, "A Look must never override accessibility"
        accent = int("FF" + profile["accent"], 16)
        values["accent"] = f"i:{accent - 2**32}"
        if "backdrop" in profile:
            values["backdrops"] = "s:" + json.dumps(
                dict.fromkeys(SCREENS, profile["backdrop"]), separators=(",", ":")
            )
        code = encode(profile["name"], values)
        assert len(code) <= 1800, "Code no longer fits Vinz's QR limit"
        result.append({"id": profile["id"], "name": profile["name"], "by": pack["by"],
                       "note": profile["note"], "code": code})
    assert len({entry["id"] for entry in result}) == 10
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify without writing")
    args = parser.parse_args()
    pack = json.loads(PROFILES.read_text(encoding="utf-8"))
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    entries = generate(pack)
    ids = {entry["id"] for entry in entries}
    old_pack = [entry for entry in catalog["looks"] if entry["id"] in ids]
    other = [entry for entry in catalog["looks"] if entry["id"] not in ids]
    assert not any(entry["id"].startswith("nr-") for entry in other), "Unexpected pack ID"
    assert not any(entry.get("by") == pack["by"] for entry in other), "Unexpected pack entry"
    assert len({entry["id"] for entry in other + entries}) == len(other) + len(entries)
    if args.check:
        if old_pack != entries:
            print("Catalog differs from Nastech Research profiles. Regenerate it.", file=sys.stderr)
            return 1
        print(f"PASS: ten reproducible Nastech Research Looks; {len(other)} existing entries preserved")
        return 0
    catalog["looks"] = other + entries
    CATALOG.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Generated ten Nastech Research Looks; {len(other)} existing entries preserved")
    return 0


if __name__ == "__main__":
    sys.exit(main())
