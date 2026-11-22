#!/usr/bin/env python3
"""Generate Vinz icon derivatives from the transparent master (requires Pillow).

Run: python3 scripts/generate-brand-assets.py
No image dependency is added to the Android application or runtime CI guard.
"""
from __future__ import annotations

import hashlib
import json
import struct
from io import BytesIO
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
MASTER = "design/branding/vinz-logo.png"
COPIES = (
    "app/src/main/res/drawable-nodpi/vinz_logo.png",
    "app/src/main/assets/remote/icon.png",
    "docs/branding/icon-preview.png",
)
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
REMOTE = "app/src/main/assets/remote"
FAVICONS = (16, 32, 48)
ICO_SIZES = (16, 24, 32, 48, 64, 128, 256)


def notification_mark() -> Image.Image:
    """Optically simplified headphones + double-note, never a filled square.

    Android supplies the color: RGB must remain white and alpha describes the mark.
    The outer patterned frame/glow is deliberately omitted at status-bar scale.
    """
    scale = 4
    mask = Image.new("L", (512 * scale, 512 * scale))
    draw = ImageDraw.Draw(mask)
    def rect(box, radius):
        draw.rounded_rectangle(tuple(v * scale for v in box), radius * scale, fill=255)
    draw.arc(tuple(v * scale for v in (86, 64, 426, 416)), 180, 360, fill=255, width=34 * scale)
    rect((65, 223, 126, 395), 27)
    rect((386, 223, 447, 395), 27)
    draw.polygon([(x * scale, y * scale) for x, y in ((214, 215), (346, 181), (346, 318), (324, 318), (324, 216), (236, 239), (236, 358), (214, 358))], fill=255)
    draw.ellipse(tuple(v * scale for v in (169, 337, 237, 387)), fill=255)
    draw.ellipse(tuple(v * scale for v in (279, 296, 347, 346)), fill=255)
    mask = mask.resize((512, 512), Image.Resampling.LANCZOS)
    # Small antialiasing lobes stay transparent, not ghost-white background pixels.
    mask = mask.point(lambda a: 0 if a < 3 else a)
    result = Image.new("RGBA", (512, 512), (255, 255, 255, 0))
    result.putalpha(mask)
    return result


def generate(root: Path = ROOT) -> None:
    source = root / MASTER
    master = Image.open(source)
    if master.mode != "RGBA" or master.size != (1024, 1024):
        raise ValueError("master must be a 1024px RGBA transparent PNG")
    alpha = master.getchannel("A")
    if alpha.getextrema() != (0, 255):
        raise ValueError("master must include transparent and opaque pixels")
    entries = []
    def record(path: str, width: int, height: int, kind: str) -> None:
        entries.append({"path": path, "width": width, "height": height, "sha256": hashlib.sha256((root / path).read_bytes()).hexdigest(), "kind": kind})
    def save(path: str, image: Image.Image, kind: str) -> None:
        dest = root / path
        dest.parent.mkdir(parents=True, exist_ok=True)
        image.save(dest, format="PNG", optimize=True)
        record(path, image.width, image.height, kind)
    for path in COPIES:
        (root / path).parent.mkdir(parents=True, exist_ok=True)
        (root / path).write_bytes(source.read_bytes())
        record(path, 1024, 1024, "logo")
    save("app/src/main/res/drawable-nodpi/vinz_notification_mark.png", notification_mark(), "notification")
    def resized(size: int) -> Image.Image:
        image = master.resize((size, size), Image.Resampling.LANCZOS)
        # Contain Lanczos lobes: no faint square fringe on tiny icons.
        draw = ImageDraw.Draw(image)
        draw.rectangle((0, 0, size - 1, size - 1), outline=(0, 0, 0, 0))
        return image
    for density, size in DENSITIES.items():
        save(f"app/src/main/res/mipmap-{density}/ic_launcher.png", resized(size), "launcher")
    for size in FAVICONS:
        save(f"{REMOTE}/favicon-{size}.png", resized(size), "favicon")
    for size in (192, 512):
        save(f"{REMOTE}/icon-{size}.png", resized(size), "favicon")
    save(f"{REMOTE}/apple-touch-icon.png", resized(180), "touch")
    ico = root / REMOTE / "favicon.ico"
    frames = []
    for size in ICO_SIZES:
        stream = BytesIO()
        resized(size).save(stream, format="PNG", optimize=True)
        frames.append((size, stream.getvalue()))
    offset = 6 + 16 * len(frames)
    directory, payload = bytearray(), bytearray()
    for size, frame in frames:
        directory.extend(struct.pack("<BBBBHHII", size % 256, size % 256, 0, 0, 1, 32, len(frame), offset))
        payload.extend(frame)
        offset += len(frame)
    ico.write_bytes(struct.pack("<HHH", 0, 1, len(frames)) + directory + payload)
    record(f"{REMOTE}/favicon.ico", 256, 256, "favicon")
    manifest = {"schema": 1, "master": {"path": MASTER, "sha256": hashlib.sha256(source.read_bytes()).hexdigest()}, "assets": entries}
    (root / "design/branding/asset-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Generated {len(entries)} Vinz assets from one transparent master.")


if __name__ == "__main__":
    generate()
