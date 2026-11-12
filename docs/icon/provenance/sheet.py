#!/usr/bin/env python3
"""The review sheet candidates/candidates.png, the 128 px in-jar copies of B and C, and manifest.json.

One column per candidate: the 1024 px master, then on a light and on a dark background the 64 px and
32 px sizes (Modrinth's project list, Mod Menu's list) at actual size and enlarged with NEAREST so their
pixels can be judged. Small sizes are LANCZOS reductions of the 1024 px master. The column labels are
drawn with the vanilla font glyphs of sources/ascii.png.

manifest.json records each candidate's file and pixel sha256. Cycles writes its render times into the PNG
text chunks, so a re-render of B or C has new file bytes; its pixels_sha256 (of the decoded RGBA pixels)
stays the same.
"""
import hashlib
import json
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent
SOURCES = ROOT / "sources"
OUT = ROOT / "candidates"

CANDIDATES = ["a", "b", "c"]
MASTER = 1024
PAD = 32
LIGHT = (255, 255, 255, 255)
DARK = (22, 24, 28, 255)
SHEET_BG = (64, 64, 64, 255)
LABEL_SCALE = 8
SMALL = [(64, 4), (32, 8)]  # (size, NEAREST enlargement)
METHODS = {
    "a": {"method": "pixel art", "script": "pixel_a.py",
          "sources": ["bed_item_1.8.9.png", "ascii.png", "font_include_default.json"]},
    "b": {"method": "Blender render", "script": "render_b.py", "metadata": "candidates/candidate-b-metadata.json",
          "sources": ["red_bed_entity.png", "ascii.png", "font_include_default.json", "moon_waxing_crescent.png"]},
    "c": {"method": "Blender render", "script": "render_c.py", "metadata": "candidates/candidate-c-metadata.json",
          "sources": ["red_bed_entity.png", "steve.png", "moon_waxing_crescent.png"]},
}


def hashes(path):
    im = Image.open(path)
    return {
        "file": str(path.relative_to(ROOT)),
        "size": list(im.size),
        "bytes": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "pixels_sha256": hashlib.sha256(im.convert("RGBA").tobytes()).hexdigest(),
    }


def glyph(font, char):
    providers = json.loads((SOURCES / "font_include_default.json").read_text(encoding="utf-8"))["providers"]
    rows = next(p["chars"] for p in providers if p.get("file") == "minecraft:font/ascii.png")
    row = next(i for i, line in enumerate(rows) if char in line)
    x, y = rows[row].index(char) * 8, row * 8
    return font.crop((x, y, x + 8, y + 8))


def small_row(master, background):
    """The 64 and 32 px sizes at actual size, then each enlarged to 256 px, on `background`."""
    height = 256 + 2 * PAD
    row = Image.new("RGBA", (MASTER, height), background)
    x = PAD
    for size, _ in SMALL:
        tile = master.resize((size, size), Image.Resampling.LANCZOS)
        row.alpha_composite(tile, (x, PAD))
        x += size + PAD
    for size, factor in SMALL:
        tile = master.resize((size, size), Image.Resampling.LANCZOS)
        row.alpha_composite(tile.resize((size * factor, size * factor), Image.Resampling.NEAREST), (x, PAD))
        x += size * factor + PAD
    return row


def main():
    font = Image.open(SOURCES / "ascii.png").convert("RGBA")
    label_h = 8 * LABEL_SCALE + 2 * PAD
    columns, manifest = [], {}
    for slug in CANDIDATES:
        master = Image.open(OUT / f"candidate-{slug}.png").convert("RGBA")
        assert master.size == (MASTER, MASTER), master.size
        if slug != "a":
            master.resize((128, 128), Image.Resampling.LANCZOS).save(OUT / f"candidate-{slug}-128.png")
        manifest[slug] = {
            **METHODS[slug],
            "master": hashes(OUT / f"candidate-{slug}.png"),
            "in_jar": hashes(OUT / f"candidate-{slug}-128.png"),
        }
        label = glyph(font, slug.upper())
        label = label.resize((8 * LABEL_SCALE, 8 * LABEL_SCALE), Image.Resampling.NEAREST)
        light, dark = small_row(master, LIGHT), small_row(master, DARK)
        column = Image.new("RGBA", (MASTER, label_h + MASTER + light.height + dark.height), SHEET_BG)
        column.alpha_composite(label, (0, PAD))
        backdrop = Image.new("RGBA", (MASTER, MASTER), DARK)
        backdrop.alpha_composite(master)
        column.alpha_composite(backdrop, (0, label_h))
        column.alpha_composite(light, (0, label_h + MASTER))
        column.alpha_composite(dark, (0, label_h + MASTER + light.height))
        columns.append(column)
    width = PAD + len(columns) * (MASTER + PAD)
    sheet = Image.new("RGBA", (width, columns[0].height + 2 * PAD), SHEET_BG)
    for i, column in enumerate(columns):
        sheet.alpha_composite(column, (PAD + i * (MASTER + PAD), PAD))
    sheet.save(OUT / "candidates.png")
    (ROOT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(sheet.size)


if __name__ == "__main__":
    main()
