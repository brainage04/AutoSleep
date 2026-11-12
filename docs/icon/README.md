# AutoSleep icon

## Status

The owner picked candidate A on 13 Nov 2026. `src/main/resources/assets/autosleep/icon.png` is A's 128 x 128 copy, sha256 `60750a820bf81b52e544aac337400b1a4a1dec8cc8f47686f73d9c547857ed98`, and there is no `docs/icon/icon.png`. The review sheet `provenance/candidates/candidates.png` shows each candidate at 1024 px and at 64 and 32 px on a light and a dark background, the sizes of Modrinth's project list and Mod Menu's list.

| Candidate | Method | Subject |
|---|---|---|
| A | pixel art | the flat vanilla bed item sprite with "zZ" in the vanilla font |
| B | Blender render | the vanilla red bed in moonlight, "zZ" rising from the pillow, the vanilla crescent moon |
| C | Blender render | Steve asleep in the vanilla red bed, the vanilla crescent moon |

Each candidate has a 1024 x 1024 master and a 128 x 128 in-jar copy. `provenance/manifest.json` records the size, bytes, file sha256 and pixel sha256 of both.

## Sources

Every texture comes from a vanilla Minecraft client jar in the Prism Launcher library. `provenance/extract_sources.py` copies them into `provenance/sources/` and writes `sources/sources.json` with the jar paths, the jar sha256 values and each file's sha256.

| File in `sources/` | Jar member | Used by |
|---|---|---|
| `red_bed_entity.png` | 1.21.11 `assets/minecraft/textures/entity/bed/red.png` | B, C |
| `steve.png` | 1.21.11 `assets/minecraft/textures/entity/player/wide/steve.png` | C |
| `ascii.png` | 1.21.11 `assets/minecraft/textures/font/ascii.png` | A, B, sheet labels |
| `font_include_default.json` | 1.21.11 `assets/minecraft/font/include/default.json` | A, B, sheet labels |
| `moon_waxing_crescent.png` | 1.21.11 `assets/minecraft/textures/environment/celestial/moon/waxing_crescent.png` | B, C |
| `bed_item_1.8.9.png` | 1.8.9 `assets/minecraft/textures/items/bed.png` | A |

The 1.21.11 jar is `~/.local/share/PrismLauncher/libraries/com/mojang/minecraft/1.21.11/minecraft-1.21.11-client.jar`, sha256 `1473c9489ac50fda3c435049a76a70d61a10b8610db27f5ba9d8756b686cd3bd`. The 1.8.9 jar is the same library's `1.8.9/minecraft-1.8.9-client.jar`, sha256 `14f0d96d1a56fb4f5c3b2233d00699525893fe5ce3dcf181e7de59120595d298`. Candidate A needs the 1.8.9 jar because 1.21.11 has no flat bed sprite: its bed items are drawn from the 3D bed model (`assets/minecraft/items/red_bed.json` has `"type": "minecraft:special"`), and the 1.12.2 jar has no bed item sprite either. 1.8.9 is the newest jar in the library that has one.

The glyphs are found through the `"chars"` table of the `minecraft:font/ascii.png` provider in `font_include_default.json`, so a character's cell in `ascii.png` is never hardcoded.

## Candidate A

`provenance/pixel_a.py` draws on one 64 x 64 grid and enlarges it with NEAREST only: 16x to the 1024 px master, 2x to the 128 px in-jar copy. The bed sprite is enlarged 3x and placed at (-3, 19), so its lower left touches the grid's edges. The glyphs "z" and "Z" are enlarged 2x and placed at (38, 9) and (48, 0), rising from the pillow. They are drawn the way vanilla draws coloured text: the white glyph multiplied by `ChatFormatting.AQUA` (0x55FFFF, `ChatFormatting.java:29` in 1.21.11) over its shadow, which is that colour scaled by 0.25 (`client/gui/Font.java:406`) and offset one glyph pixel down and right. The background is transparent. Every pixel comes from the two sprites or from that shadow rule.

## Candidates B and C

Blender 5.1.1, headless (`--background -noaudio`), Cycles on the CPU, 96 samples, seed 0, denoising on, 4 threads, 1024 x 1024, view transform Standard. B renders in about 10 s and C in about 16 s.

`provenance/mcmodel.py` builds the geometry from a transcription of the decompiled 1.21.11 client (`~/agent-work/mc-src/1.21.11/merged/net/minecraft`):

* `ModelPart.Cube` (`client/model/geom/ModelPart.java:242`): the cube corners, the six faces and their box UV rectangles, including cube growth and the mirror flag. Parts are posed with `ModelPart.translateAndRotate` (`ModelPart.java:165`).
* The bed: `BedRenderer.createHeadLayer` and `createFootLayer` (`client/renderer/blockentity/BedRenderer.java:58,75`), placed by `BedRenderer.preparePose` (`BedRenderer.java:157`). The bed faces south with its head block at the origin.
* The player in C: `HumanoidModel.createMesh` (`client/model/HumanoidModel.java:66`) with the wide-arm parts and outer layers of `PlayerModel.createMesh` (`client/model/player/PlayerModel.java:46`). It lies in the bed as the game places a sleeping player: `LivingEntity.setPosToBed` (`world/entity/LivingEntity.java:3639`), the shift by the standing eye height minus 0.1 in `LivingEntityRenderer.submit` (`LivingEntityRenderer.java:74`), the sleeping rotations of `setupRotations` (`LivingEntityRenderer.java:178`) and the 0.9375 scale of `AvatarRenderer.scale` (`client/renderer/entity/player/AvatarRenderer.java:152`). The head is not turned and the limbs are at rest: the render leaves out the 0.005 rad leg splay and the idle arm sway (`AnimationUtils.bobModelPart`) that `HumanoidModel.setupAnim` adds.

Textures use Closest filtering. The bed ignores its texture's alpha, like its solid render type, and the player uses it for the outer layers.

`provenance/scene.py` sets up the shared parts:

* A navy background (sRGB 13, 17, 33) for camera rays, and a dim blue ambient light (sRGB 70, 90, 150, strength 0.5) for lighting.
* A pale blue sun lamp as moonlight (sRGB 205, 220, 255, strength 3.2).
* The moon as a square that faces the camera and adds its texture as light, so the texture's black surround adds nothing.
* An orthographic camera at a set azimuth and elevation, framed to fit the bed, the player and the glyphs with a 6 % margin. B uses azimuth -135° and elevation 33°, C uses -140° and 45°.

In B the glyphs "z" and "Z" are one voxel per font pixel (0.06 and 0.085 blocks), facing the camera, glowing in `ChatFormatting.AQUA`, with a small aqua area light that tints the pillow. The lighting, the shadows, the glowing letters and the moon's place are choices of the render, not something the game shows.

Two changes in `scene.py` work around Cycles:

* The player's legs overlap by 0.2 model pixels, so their faces are coplanar there and Cycles drew a black seam between them. The faces of the n-th cube move out along their normal by n + 1 thousandths of a model pixel, at most 0.012 model pixels for the player's 12 cubes.
* The outer layers of `steve.png` are fully transparent cubes, stacked several deep along a ray. With Cycles' default of 8 transparent bounces they left dark specks on the trousers and shirt, so the limit is 64.

Each render writes `candidates/candidate-<b|c>.png`, the packed scene `candidates/candidate-<b|c>.blend` and `candidates/candidate-<b|c>-metadata.json`. The metadata records the camera, settings, render time, texture sha256 values and script sha256 values.

## Provenance files

| File | Content |
|---|---|
| `extract_sources.py` | copies the vanilla assets into `sources/` and writes `sources/sources.json` |
| `pixel_a.py` | candidate A: `candidates/candidate-a.png`, `candidate-a-64.png` (the native grid), `candidate-a-128.png` |
| `mcmodel.py` | the bed and player geometry transcribed from the 1.21.11 client |
| `scene.py` | shared Blender setup: materials, glyph voxels, moon, lights, camera, render and metadata |
| `render_b.py`, `render_c.py` | candidates B and C |
| `sheet.py` | `candidates/candidates.png`, the LANCZOS 128 px copies of B and C, and `manifest.json` |
| `manifest.json` | per candidate: method, script, sources, and size, bytes, sha256 and pixel sha256 of the master and the in-jar copy |
| `candidates/` | A's three PNGs, the 128 px copies of B and C, and the B and C scenes and metadata |

The B and C masters (about 0.9 MB and 1 MB) and the review sheet (1.5 MB) are not committed. The commands below rebuild them, and a copy of the candidates and the sheet is in `~/agent-work/autosleep-icon/` on thomas-pc.

## Regenerating

From `docs/icon/provenance`:

```sh
python3 extract_sources.py
PILLOW='with import (builtins.getFlake "nixpkgs") {}; python3.withPackages (p: [ p.pillow ])'
nix shell --impure --expr "$PILLOW" --command python3 pixel_a.py
nix shell nixpkgs#blender --command blender --background -noaudio --python-exit-code 1 --python render_b.py
nix shell nixpkgs#blender --command blender --background -noaudio --python-exit-code 1 --python render_c.py
nix shell --impure --expr "$PILLOW" --command python3 sheet.py
```

`ICON_SAMPLES` (default 96), `ICON_RENDER_THREADS` (default 4) and `ICON_RESOLUTION` (default 1024) override the render settings. Use small values for quick previews.

Two renders of the same scene have identical pixels, but their files differ, because Cycles writes its render times into the PNG's text chunks. Compare `pixels_sha256` in `manifest.json`, not `sha256`. The A outputs and the 128 px copies are byte-identical between runs.

## Installing the chosen candidate

For candidate X (`a`, `b` or `c`), from `docs/icon`:

```sh
cp provenance/candidates/candidate-X.png icon.png
cp provenance/candidates/candidate-X-128.png ../../src/main/resources/assets/autosleep/icon.png
```

Both `src/main/resources/fabric.mod.json` and `src/gametest/resources/fabric.mod.json` declare `"icon": "assets/autosleep/icon.png"`. After the pick, rewrite this README to describe the shipped icon and its hashes, and drop the unchosen candidates' scripts from `provenance/`.
