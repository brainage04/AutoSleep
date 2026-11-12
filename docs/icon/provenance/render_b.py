"""Candidate B: the vanilla red bed in moonlight, with "zZ" rising from the pillow as voxels of the
vanilla font glyphs.

blender --background -noaudio --python-exit-code 1 --python render_b.py
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mcmodel  # noqa: E402
import scene  # noqa: E402

SLUG = "candidate-b"
HEAD_BLOCK = (0, 0, 0)
FACING = "south"
AZIMUTH, ELEVATION = -135.0, 33.0
MARGIN = 0.06
# The pillow's top centre (Blender coordinates): head block, 9/16 high, towards the head end.
PILLOW = (0.5, -0.75, 0.5625)
# (char, offset right and up from the pillow on screen, in blocks, font pixel size in blocks)
GLYPHS = [("z", (0.12, 0.66), 0.06), ("Z", (0.48, 1.16), 0.085)]
# The moon sprite, placed in the empty top left after framing the bed and glyphs: offset right and up
# from the pillow on screen, distance behind it, size in blocks.
MOON = {"image": "moon_waxing_crescent.png", "offset": (-1.3, 0.8), "behind": 3.0, "size": 1.3, "strength": 4.0}

scene.reset()
bed = scene.texture_material("bed", "red_bed_entity.png", use_alpha=False)
objects = [scene.mesh_from_faces("bed", mcmodel.bed_faces(HEAD_BLOCK, FACING), bed)]
glow = scene.emission_material("glyph", scene.AQUA, 2.2)
for i, (char, (dx, dy), size) in enumerate(GLYPHS):
    origin = scene.screen_point(PILLOW, AZIMUTH, ELEVATION, dx, dy)
    objects.append(scene.glyph_object(f"glyph_{i}_{char}", char, origin, size, size, glow, AZIMUTH, ELEVATION))
scene.world(camera_rgb=(13, 17, 33), light_rgb=(70, 90, 150), light_strength=0.5)
right, up, toward = scene.screen_axes(AZIMUTH, ELEVATION)
moon_centre = scene.screen_point(PILLOW, AZIMUTH, ELEVATION, *MOON["offset"]) - toward * MOON["behind"]
scene.sprite_billboard("moon", MOON["image"], moon_centre, MOON["size"], MOON["strength"], AZIMUTH, ELEVATION)
scene.sun("moonlight", (205, 220, 255), 3.2, (40.0, 0.0, -150.0))
scene.area("glyph_glow", scene.AQUA, 6.0, scene.screen_point(PILLOW, AZIMUTH, ELEVATION, 0.3, 0.8), PILLOW, 0.6)
scene.framed_ortho_camera(AZIMUTH, ELEVATION, objects, MARGIN)
scene.render(SLUG, {
    "subject": "vanilla red bed (BedRenderer head and foot models, entity/bed/red.png) facing south, "
               "with the ascii.png glyphs 'z' and 'Z' as one voxel per font pixel in ChatFormatting.AQUA",
    "head_block": HEAD_BLOCK,
    "facing": FACING,
    "azimuth_deg": AZIMUTH,
    "elevation_deg": ELEVATION,
    "frame_margin": MARGIN,
    "glyphs": [{"char": c, "screen_offset_from_pillow": o, "pixel_size": s} for c, o, s in GLYPHS],
})
