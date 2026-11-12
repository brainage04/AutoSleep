"""Vanilla 1.21.11 entity model geometry for Blender, transcribed from the decompiled client.

Everything here follows the source in ~/agent-work/mc-src/1.21.11/merged/net/minecraft:
- PoseStack operations and ModelPart.translateAndRotate (client/model/geom/ModelPart.java:165):
  translate by the part offset / 16, then rotate by Quaternionf.rotationZYX(zRot, yRot, xRot).
- ModelPart.Cube (ModelPart.java:242): the eight corners, the six polygons and their box UV rectangles,
  including CubeDeformation growth and the mirror flag.
- The bed: BedRenderer.createHeadLayer/createFootLayer (client/renderer/blockentity/BedRenderer.java:58,75)
  and BedRenderer.preparePose (BedRenderer.java:157).
- The player: HumanoidModel.createMesh (client/model/HumanoidModel.java:66) with the PlayerModel.createMesh
  wide-arm overrides and outer layers (client/model/player/PlayerModel.java:46), posed in bed by
  LivingEntityRenderer.submit/setupRotations (client/renderer/entity/LivingEntityRenderer.java:74,178) and
  AvatarRenderer.scale (client/renderer/entity/player/AvatarRenderer.java:152).

Minecraft world space (x east, y up, z south, 1 unit = 1 block) maps to Blender as (x, -z, y).
"""
import math

from mathutils import Matrix, Vector

PI = math.pi


class PoseStack:
    def __init__(self):
        self.stack = [Matrix.Identity(4)]

    @property
    def pose(self):
        return self.stack[-1]

    def push(self):
        self.stack.append(self.stack[-1].copy())

    def pop(self):
        self.stack.pop()

    def translate(self, x, y, z):
        self.stack[-1] = self.stack[-1] @ Matrix.Translation((x, y, z))

    def rotate_deg(self, axis, degrees):
        self.stack[-1] = self.stack[-1] @ Matrix.Rotation(math.radians(degrees), 4, axis)

    def rotation_zyx(self, z, y, x):
        self.stack[-1] = (
            self.stack[-1] @ Matrix.Rotation(z, 4, "Z") @ Matrix.Rotation(y, 4, "Y") @ Matrix.Rotation(x, 4, "X")
        )

    def scale(self, x, y, z):
        self.stack[-1] = self.stack[-1] @ Matrix.Diagonal((x, y, z, 1.0))


class Cube:
    def __init__(self, u, v, x, y, z, w, h, d, grow=0.0, mirror=False):
        self.u, self.v = u, v
        self.box = (x, y, z, w, h, d)
        self.grow = grow
        self.mirror = mirror

    def polygons(self, tex_w, tex_h):
        """(corners in model units, uvs) per face, as ModelPart.Cube builds them."""
        x, y, z, w, h, d = self.box
        f, g, hh = x - self.grow, y - self.grow, z - self.grow
        s, t, uu = x + w + self.grow, y + h + self.grow, z + d + self.grow
        if self.mirror:
            f, s = s, f
        v1, v2, v3, v4 = (f, g, hh), (s, g, hh), (s, t, hh), (f, t, hh)
        v5, v6, v7, v8 = (f, g, uu), (s, g, uu), (s, t, uu), (f, t, uu)
        i, j = self.u, self.v
        cw, cx, cy, cz = i, i + d, i + d + w, i + d + w + w
        caa, cab = i + d + w + d, i + d + w + d + w
        cac, cad, cae = j, j + d, j + d + h
        faces = [
            ((v6, v5, v1, v2), (cx, cac, cy, cad)),  # DOWN
            ((v3, v4, v8, v7), (cy, cad, cz, cac)),  # UP
            ((v1, v5, v8, v4), (cw, cad, cx, cae)),  # WEST
            ((v2, v1, v4, v3), (cx, cad, cy, cae)),  # NORTH
            ((v6, v2, v3, v7), (cy, cad, caa, cae)),  # EAST
            ((v5, v6, v7, v8), (caa, cad, cab, cae)),  # SOUTH
        ]
        out = []
        for corners, (pf, pg, ph, pi) in faces:
            uvs = [(ph / tex_w, pg / tex_h), (pf / tex_w, pg / tex_h), (pf / tex_w, pi / tex_h), (ph / tex_w, pi / tex_h)]
            corners = list(corners)
            if self.mirror:
                corners.reverse()
                uvs.reverse()
            out.append((corners, uvs))
        return out

    def centre(self):
        x, y, z, w, h, d = self.box
        return (x + w / 2, y + h / 2, z + d / 2)


class Part:
    def __init__(self, name, cubes=(), offset=(0.0, 0.0, 0.0), rot=(0.0, 0.0, 0.0), children=()):
        self.name = name
        self.cubes = list(cubes)
        self.offset = offset
        self.rot = rot
        self.children = list(children)

    def emit(self, ps, tex_w, tex_h, out, prefix=""):
        ps.push()
        ps.translate(self.offset[0] / 16.0, self.offset[1] / 16.0, self.offset[2] / 16.0)
        if any(self.rot):
            ps.rotation_zyx(self.rot[2], self.rot[1], self.rot[0])
        name = prefix + self.name
        for cube in self.cubes:
            centre = ps.pose @ Vector([c / 16.0 for c in cube.centre()])
            for corners, uvs in cube.polygons(tex_w, tex_h):
                world = [ps.pose @ Vector([c / 16.0 for c in p]) for p in corners]
                out.append((name, world, uvs, centre))
        for child in self.children:
            child.emit(ps, tex_w, tex_h, out, name + ".")
        ps.pop()


# BedRenderer.createHeadLayer / createFootLayer, texture 64x64.
BED_HEAD = Part("bed_head", children=[
    Part("main", [Cube(0, 0, 0, 0, 0, 16, 16, 6)]),
    Part("left_leg", [Cube(50, 6, 0, 6, 0, 3, 3, 3)], rot=(PI / 2, 0.0, PI / 2)),
    Part("right_leg", [Cube(50, 18, -16, 6, 0, 3, 3, 3)], rot=(PI / 2, 0.0, PI)),
])
BED_FOOT = Part("bed_foot", children=[
    Part("main", [Cube(0, 22, 0, 0, 0, 16, 16, 6)]),
    Part("left_leg", [Cube(50, 0, 0, 6, -16, 3, 3, 3)], rot=(PI / 2, 0.0, 0.0)),
    Part("right_leg", [Cube(50, 12, -16, 6, -16, 3, 3, 3)], rot=(PI / 2, 0.0, PI * 3 / 2)),
])

DIRECTIONS = {"south": (0, 0, 1), "north": (0, 0, -1), "east": (1, 0, 0), "west": (-1, 0, 0)}
# Direction.toYRot is (data2d & 3) * 90: south 0, west 90, north 180, east 270 (core/Direction.java:297).
TO_Y_ROT = {"south": 0.0, "west": 90.0, "north": 180.0, "east": 270.0}
# LivingEntityRenderer.sleepDirectionToRotation (LivingEntityRenderer.java:159).
SLEEP_ROTATION = {"south": 90.0, "west": 0.0, "north": 270.0, "east": 180.0}


def bed_faces(head_block, facing):
    """Both bed pieces as world-space faces. The foot block is the head block minus `facing`."""
    out = []
    step = DIRECTIONS[facing]
    for part, block in ((BED_HEAD, head_block), (BED_FOOT, tuple(h - s for h, s in zip(head_block, step)))):
        ps = PoseStack()
        ps.translate(*block)
        # BedRenderer.preparePose(poseStack, false, facing)
        ps.translate(0.0, 0.5625, 0.0)
        ps.rotate_deg("X", 90.0)
        ps.translate(0.5, 0.5, 0.5)
        ps.rotate_deg("Z", 180.0 + TO_Y_ROT[facing])
        ps.translate(-0.5, -0.5, -0.5)
        part.emit(ps, 64, 64, out)
    return out


def player_model():
    """PlayerModel.createMesh(CubeDeformation.NONE, slim=false): wide arms, all outer layers, rest pose."""
    return Part("player", children=[
        Part("head", [Cube(0, 0, -4, -8, -4, 8, 8, 8)], children=[
            Part("hat", [Cube(32, 0, -4, -8, -4, 8, 8, 8, grow=0.5)]),
        ]),
        Part("body", [Cube(16, 16, -4, 0, -2, 8, 12, 4)], children=[
            Part("jacket", [Cube(16, 32, -4, 0, -2, 8, 12, 4, grow=0.25)]),
        ]),
        Part("right_arm", [Cube(40, 16, -3, -2, -2, 4, 12, 4)], offset=(-5, 2, 0), children=[
            Part("right_sleeve", [Cube(40, 32, -3, -2, -2, 4, 12, 4, grow=0.25)]),
        ]),
        Part("left_arm", [Cube(32, 48, -1, -2, -2, 4, 12, 4)], offset=(5, 2, 0), children=[
            Part("left_sleeve", [Cube(48, 48, -1, -2, -2, 4, 12, 4, grow=0.25)]),
        ]),
        Part("right_leg", [Cube(0, 16, -2, 0, -2, 4, 12, 4)], offset=(-1.9, 12, 0), children=[
            Part("right_pants", [Cube(0, 32, -2, 0, -2, 4, 12, 4, grow=0.25)]),
        ]),
        Part("left_leg", [Cube(16, 48, -2, 0, -2, 4, 12, 4)], offset=(1.9, 12, 0), children=[
            Part("left_pants", [Cube(0, 48, -2, 0, -2, 4, 12, 4, grow=0.25)]),
        ]),
    ])


def sleeping_player_faces(head_block, facing):
    """A player asleep in the bed whose head block is `head_block`.

    LivingEntity.setPosToBed puts the entity at the head block + (0.5, 0.6875, 0.5); the renderer then
    shifts it by -facing * (standing eye height 1.62 - 0.1) and turns it with the sleeping rotations.
    """
    step = DIRECTIONS[facing]
    eye = 1.62 - 0.1
    ps = PoseStack()
    ps.translate(head_block[0] + 0.5, head_block[1] + 0.6875, head_block[2] + 0.5)
    ps.translate(-step[0] * eye, 0.0, -step[2] * eye)
    ps.rotate_deg("Y", SLEEP_ROTATION[facing])
    ps.rotate_deg("Z", 90.0)  # getFlipDegrees
    ps.rotate_deg("Y", 270.0)
    ps.scale(-1.0, -1.0, 1.0)
    ps.scale(0.9375, 0.9375, 0.9375)
    ps.translate(0.0, -1.501, 0.0)
    out = []
    player_model().emit(ps, 64, 64, out)
    return out


def to_blender(p):
    return Vector((p[0], -p[2], p[1]))
