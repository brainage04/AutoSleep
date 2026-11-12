"""Candidate C: the default Steve skin asleep in the vanilla red bed, in moonlight.

blender --background -noaudio --python-exit-code 1 --python render_c.py
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mcmodel  # noqa: E402
import scene  # noqa: E402

SLUG = "candidate-c"
HEAD_BLOCK = (0, 0, 0)
FACING = "south"
AZIMUTH, ELEVATION = -140.0, 45.0
MARGIN = 0.06
# The moon sprite, placed in the empty top left after framing: offset right and up from the bed's centre
# on screen, distance behind it, size in blocks.
BED_CENTRE = (0.5, 0.0, 0.5)
MOON = {"image": "moon_waxing_crescent.png", "offset": (-0.85, 0.62), "behind": 3.0, "size": 1.0, "strength": 4.0}

scene.reset()
bed = scene.texture_material("bed", "red_bed_entity.png", use_alpha=False)
skin = scene.texture_material("steve", "steve.png", use_alpha=True)
objects = [
    scene.mesh_from_faces("bed", mcmodel.bed_faces(HEAD_BLOCK, FACING), bed),
    scene.mesh_from_faces("player", mcmodel.sleeping_player_faces(HEAD_BLOCK, FACING), skin),
]
scene.world(camera_rgb=(13, 17, 33), light_rgb=(70, 90, 150), light_strength=0.5)
right, up, toward = scene.screen_axes(AZIMUTH, ELEVATION)
moon_centre = scene.screen_point(BED_CENTRE, AZIMUTH, ELEVATION, *MOON["offset"]) - toward * MOON["behind"]
scene.sprite_billboard("moon", MOON["image"], moon_centre, MOON["size"], MOON["strength"], AZIMUTH, ELEVATION)
scene.sun("moonlight", (205, 220, 255), 3.2, (40.0, 0.0, -150.0))
scene.framed_ortho_camera(AZIMUTH, ELEVATION, objects, MARGIN)
scene.render(SLUG, {
    "subject": "default wide-arm player model with entity/player/wide/steve.png, in the sleeping pose, "
               "in the vanilla red bed facing south",
    "head_block": HEAD_BLOCK,
    "facing": FACING,
    "azimuth_deg": AZIMUTH,
    "elevation_deg": ELEVATION,
    "frame_margin": MARGIN,
})
