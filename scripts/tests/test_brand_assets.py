#!/usr/bin/env python3
"""Synthetic regression coverage for scripts.ci.check_branding (stdlib only)."""
import hashlib
import importlib.util
import json
import struct
import tempfile
import unittest
import zlib
from pathlib import Path

CHECKER = Path(__file__).resolve().parents[1] / "ci" / "check_branding.py"
spec = importlib.util.spec_from_file_location("check_branding", CHECKER)
branding = importlib.util.module_from_spec(spec)
spec.loader.exec_module(branding)


def png(size, pixel=(30, 80, 200, 255), *, opaque=False, rectangular=False):
    width, height = (size + 1, size) if rectangular else (size, size)
    rows = []
    for y in range(height):
        row = bytearray(width * 4)
        if opaque:
            for x in range(width): row[x * 4:x * 4 + 4] = bytes(pixel)
        elif 0 < y < height - 1:
            x = width // 2
            row[x * 4:x * 4 + 4] = bytes(pixel)
        rows.append(b"\0" + bytes(row))
    body = zlib.compress(b"".join(rows))
    chunk = lambda name, value: struct.pack(">I", len(value)) + name + value + struct.pack(">I", zlib.crc32(name + value) & 0xffffffff)
    return branding.PNG_SIGNATURE + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)) + chunk(b"IDAT", body) + chunk(b"IEND", b"")


def ico(*, corrupt_body=False, mismatched_directory=False, opaque_frame=False):
    sizes = (16, 24, 32, 48, 64, 128, 256)
    entries, payload, offset = [], bytearray(), 6 + 16 * len(sizes)
    for size in sizes:
        frame = png(size, opaque=opaque_frame)
        directory_size = size + 1 if mismatched_directory and size == 16 else size
        entries.append(bytes((0 if directory_size == 256 else directory_size, 0 if directory_size == 256 else directory_size, 0, 0)) + struct.pack("<HHII", 1, 32, len(frame), offset))
        payload += frame; offset += len(frame)
    data = struct.pack("<HHH", 0, 1, len(sizes)) + b"".join(entries) + payload
    if corrupt_body:
        # Damage a compressed IDAT byte, leaving the ICO directory untouched.
        body_offset = 6 + 16 * len(sizes) + 8 + 25 + 8  # PNG sig + IHDR + IDAT length/type
        data = data[:body_offset] + bytes((data[body_offset] ^ 0xff,)) + data[body_offset + 1:]
    return data


class BrandAssetsTest(unittest.TestCase):
    def make_root(self):
        td = tempfile.TemporaryDirectory(); root = Path(td.name)
        manifest, files = [], {}
        for rel, (size, kind) in branding.EXPECTED_ASSETS.items():
            data = ico() if rel.endswith(".ico") else png(size, (255, 255, 255, 180) if kind == "notification" else (30, 80, 200, 255))
            path = root / rel; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data); files[rel] = data
            entry = {"path": rel, "sha256": hashlib.sha256(data).hexdigest()}
            if rel != branding.LOGO_FILES[0]: entry.update(width=size, height=size, kind=kind); manifest.append(entry)
            else: master = entry
        path = root / branding.MANIFEST_FILE; path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({"schema": 1, "master": master, "assets": manifest}))
        for rel, expected in branding.RESOURCE_REFERENCES.items():
            path = root / rel; path.parent.mkdir(parents=True, exist_ok=True); path.write_text(expected)
        return td, root, files

    def test_complete_valid_fixture_passes(self):
        td, root, _ = self.make_root()
        with td: self.assertEqual(branding.verify_brand_assets(root), [])

    def test_hash_and_logo_parity_regressions_fail(self):
        td, root, files = self.make_root()
        with td:
            copy = root / branding.LOGO_FILES[1]; copy.write_bytes(png(1024, (1, 2, 3, 255)))
            problems = branding.verify_brand_assets(root)
            self.assertTrue(any("hash mismatch" in item for item in problems))
            self.assertTrue(any("differs" in item for item in problems))

    def test_master_manifest_hash_must_match_canonical_logo(self):
        td, root, _ = self.make_root()
        with td:
            manifest_path = root / branding.MANIFEST_FILE
            manifest = json.loads(manifest_path.read_text())
            manifest["master"]["sha256"] = "0" * 64
            manifest_path.write_text(json.dumps(manifest))
            problems = branding.verify_brand_assets(root)
            self.assertTrue(any("master hash" in item for item in problems))

    def test_png_rgba_decodes_all_png_filters_and_mixed_rows(self):
        width = 3
        rows = (
            bytes((3, 17, 251, 9, 128, 64, 32, 255, 200, 1, 99, 180)),
            bytes((250, 7, 19, 201, 4, 220, 87, 15, 99, 156, 40, 73)),
            bytes((82, 201, 6, 140, 255, 13, 170, 48, 31, 90, 222, 5)),
            bytes((11, 240, 121, 66, 77, 33, 199, 230, 144, 8, 63, 190)),
            bytes((219, 55, 14, 250, 36, 178, 105, 27, 168, 94, 2, 117)),
        )
        expected = b"".join(rows)

        def paeth(left, up, up_left):
            estimate = left + up - up_left
            distances = abs(estimate - left), abs(estimate - up), abs(estimate - up_left)
            return left if distances[0] <= distances[1] and distances[0] <= distances[2] else up if distances[1] <= distances[2] else up_left

        def filtered_png(filters):
            encoded_rows = []
            previous = bytes(width * 4)
            for filter_type, row in zip(filters, rows):
                encoded = bytearray()
                for index, value in enumerate(row):
                    left = row[index - 4] if index >= 4 else 0
                    up = previous[index]
                    up_left = previous[index - 4] if index >= 4 else 0
                    predictor = (0 if filter_type == 0 else left if filter_type == 1 else up if filter_type == 2
                                 else (left + up) // 2 if filter_type == 3 else paeth(left, up, up_left))
                    encoded.append((value - predictor) & 255)
                encoded_rows.append(bytes((filter_type,)) + encoded)
                previous = row
            chunk = lambda name, value: struct.pack(">I", len(value)) + name + value + struct.pack(">I", zlib.crc32(name + value) & 0xffffffff)
            return (branding.PNG_SIGNATURE + chunk(b"IHDR", struct.pack(">IIBBBBB", width, len(rows), 8, 6, 0, 0, 0)) +
                    chunk(b"IDAT", zlib.compress(b"".join(encoded_rows))) + chunk(b"IEND", b""))

        for filter_type in range(5):
            self.assertEqual(branding.png_rgba(filtered_png((filter_type,) * len(rows))), (width, len(rows), expected))
        self.assertEqual(branding.png_rgba(filtered_png((0, 1, 2, 3, 4))), (width, len(rows), expected))

    def test_opaque_and_rectangular_rgba_fail(self):
        td, root, _ = self.make_root()
        with td:
            self.assertTrue(branding.png_problems("x.png", png(8, opaque=True), 8))
            self.assertTrue(branding.png_problems("x.png", png(8, rectangular=True), 8))

    def test_transparent_png_requires_foreground_and_outer_padding(self):
        blank = png(8, (0, 0, 0, 0))
        decoded = branding.png_rgba(png(8))
        width, height, pixels = decoded
        pixels = bytearray(pixels)
        pixels[3] = 255  # The top-left pixel is an opaque square-fringe regression.
        rows = b"".join(b"\0" + pixels[row * width * 4:(row + 1) * width * 4] for row in range(height))
        chunk = lambda name, value: struct.pack(">I", len(value)) + name + value + struct.pack(">I", zlib.crc32(name + value) & 0xffffffff)
        edged = branding.PNG_SIGNATURE + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")
        self.assertTrue(branding.png_problems("blank.png", blank, 8))
        self.assertTrue(branding.png_problems("edge.png", edged, 8))

    def test_swapped_png_colours_and_bad_white_mask_fail(self):
        # A colour-type-2 PNG is rejected even though its header dimensions look valid.
        raw = struct.pack(">IIBBBBB", 8, 8, 8, 2, 0, 0, 0)
        fake = branding.PNG_SIGNATURE + struct.pack(">I", 13) + b"IHDR" + raw + b"\0\0\0\0"
        self.assertTrue(branding.png_problems("swapped.png", fake, 8))
        self.assertTrue(branding.png_problems("mask.png", png(8, (1, 2, 3, 255)), 8, white_only=True))

    def test_ico_embedded_png_decoding_and_corrupt_body_fail(self):
        self.assertEqual(branding.ico_problems("favicon.ico", ico()), [])
        self.assertTrue(branding.ico_problems("favicon.ico", ico(corrupt_body=True)))
        self.assertTrue(branding.ico_problems("favicon.ico", ico(mismatched_directory=True)))
        self.assertTrue(branding.ico_problems("favicon.ico", ico(opaque_frame=True)))

    def test_manifest_path_traversal_and_exact_set_fail(self):
        td, root, _ = self.make_root()
        with td:
            manifest_path = root / branding.MANIFEST_FILE
            manifest = json.loads(manifest_path.read_text())
            manifest["assets"][0]["path"] = "../escape.png"
            manifest_path.write_text(json.dumps(manifest))
            problems = branding.verify_brand_assets(root)
            self.assertTrue(any("unsafe" in item for item in problems))
            self.assertTrue(any("exact expected" in item for item in problems))

    def test_manifest_canonical_duplicate_in_assets_fails(self):
        td, root, files = self.make_root()
        with td:
            manifest_path = root / branding.MANIFEST_FILE
            manifest = json.loads(manifest_path.read_text())
            manifest["assets"].append({
                "path": branding.LOGO_FILES[0],
                "sha256": hashlib.sha256(files[branding.LOGO_FILES[0]]).hexdigest(),
                "width": 1024,
                "height": 1024,
                "kind": "logo",
            })
            manifest_path.write_text(json.dumps(manifest))
            problems = branding.verify_brand_assets(root)
            self.assertTrue(any("unsafe or invalid" in item for item in problems))


if __name__ == "__main__": unittest.main()
