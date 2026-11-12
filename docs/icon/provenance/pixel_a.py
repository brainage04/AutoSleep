#!/usr/bin/env python3
"""Candidate A: the flat vanilla bed item sprite with "zZ" from the vanilla font, on one 64x64 grid.

Every pixel comes from sources/bed_item_1.8.9.png (enlarged 3x) and sources/ascii.png (enlarged 2x).
The glyphs are drawn the way vanilla draws coloured text: the white glyph multiplied by the
ChatFormatting.AQUA colour 0x55FFFF (ChatFormatting.java:29 in 1.21.11), over its text shadow, which is
that colour scaled by 0.25 and drawn one glyph pixel down and right (client/gui/Font.java:406).
Every enlargement is an integer NEAREST one: the grid goes 16x to the 1024 px master and 2x to the
128 px in-jar copy.
"""
import json
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent
SOURCES = ROOT / "sources"
OUT = ROOT / "candidates"

GRID = 64
BED_SCALE = 3
BED_AT = (-3, 19)
GLYPH_SCALE = 2
GLYPH_CELL = 8
AQUA = (0x55, 0xFF, 0xFF)
# (character, x, y): where each glyph's 8x8 cell lands on the grid, after enlargement.
GLYPHS = [("z", 38, 9), ("Z", 48, 0)]


def glyph(font, char):
    """The 8x8 cell of `char`, located through the ascii.png provider's "chars" table."""
    providers = json.loads((SOURCES / "font_include_default.json").read_text(encoding="utf-8"))["providers"]
    rows = next(p["chars"] for p in providers if p.get("file") == "minecraft:font/ascii.png")
    row = next(i for i, line in enumerate(rows) if char in line)
    x, y = rows[row].index(char) * GLYPH_CELL, row * GLYPH_CELL
    return font.crop((x, y, x + GLYPH_CELL, y + GLYPH_CELL))


def tint(img, rgb):
    r, g, b, a = img.split()
    return Image.merge("RGBA", [c.point(lambda v, k=k: v * k // 255) for c, k in zip((r, g, b), rgb)] + [a])


def shadow(img):
    r, g, b, a = img.split()
    return Image.merge("RGBA", [c.point(lambda v: int(v * 0.25)) for c in (r, g, b)] + [a])


def enlarge(img, factor):
    return img.resize((img.width * factor, img.height * factor), Image.Resampling.NEAREST)


def place(grid, img, at):
    layer = Image.new("RGBA", grid.size, (0, 0, 0, 0))
    layer.paste(img, at)
    return Image.alpha_composite(grid, layer)


def compose():
    bed = Image.open(SOURCES / "bed_item_1.8.9.png").convert("RGBA")
    font = Image.open(SOURCES / "ascii.png").convert("RGBA")
    grid = place(Image.new("RGBA", (GRID, GRID), (0, 0, 0, 0)), enlarge(bed, BED_SCALE), BED_AT)
    for char, x, y in GLYPHS:
        g = tint(enlarge(glyph(font, char), GLYPH_SCALE), AQUA)
        grid = place(grid, shadow(g), (x + GLYPH_SCALE, y + GLYPH_SCALE))
        grid = place(grid, g, (x, y))
    return grid


def main():
    OUT.mkdir(exist_ok=True)
    grid = compose()
    grid.save(OUT / "candidate-a-64.png")
    grid.resize((1024, 1024), Image.Resampling.NEAREST).save(OUT / "candidate-a.png")
    grid.resize((128, 128), Image.Resampling.NEAREST).save(OUT / "candidate-a-128.png")


if __name__ == "__main__":
    main()
