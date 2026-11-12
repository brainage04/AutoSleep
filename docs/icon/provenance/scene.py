"""Shared Blender scene setup for the AutoSleep icon renders (candidates B and C)."""
import hashlib
import json
import math
import os
import sys
import time
from pathlib import Path

import bmesh
import bpy
from mathutils import Matrix, Vector

ROOT = Path(__file__).resolve().parent
SOURCES = ROOT / "sources"
OUT = ROOT / "candidates"
sys.path.insert(0, str(ROOT))
import mcmodel  # noqa: E402

SAMPLES = int(os.environ.get("ICON_SAMPLES", "96"))
THREADS = int(os.environ.get("ICON_RENDER_THREADS", "4"))
RESOLUTION = int(os.environ.get("ICON_RESOLUTION", "1024"))
# ChatFormatting.AQUA, 0x55FFFF (ChatFormatting.java:29).
AQUA = (0x55, 0xFF, 0xFF)
COPLANAR_STEP = 1 / 16 / 1000


def srgb_to_linear(value):
    """An 8-bit sRGB channel as a linear Blender colour channel."""
    c = value / 255
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def reset():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.context.preferences.filepaths.save_version = 0
    scene = bpy.context.scene
    scene.render.engine = "CYCLES"
    scene.cycles.device = "CPU"
    scene.cycles.samples = SAMPLES
    scene.cycles.seed = 0
    scene.cycles.use_denoising = True
    # The player's outer layers are cubes of fully transparent pixels in steve.png, stacked several deep
    # along a ray; with Cycles' default of 8 transparent bounces dark specks show on the trousers and shirt.
    scene.cycles.transparent_max_bounces = 64
    scene.render.threads_mode = "FIXED"
    scene.render.threads = THREADS
    scene.render.resolution_x = RESOLUTION
    scene.render.resolution_y = RESOLUTION
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.image_settings.color_mode = "RGBA"
    scene.render.film_transparent = False
    scene.view_settings.view_transform = "Standard"
    scene.view_settings.look = "None"
    return scene


def texture_material(name, image_name, use_alpha):
    image = bpy.data.images.load(str(SOURCES / image_name), check_existing=True)
    image.pack()
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    nodes, links = mat.node_tree.nodes, mat.node_tree.links
    bsdf = nodes["Principled BSDF"]
    tex = nodes.new("ShaderNodeTexImage")
    tex.image = image
    tex.interpolation = "Closest"
    links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])
    if use_alpha:
        links.new(tex.outputs["Alpha"], bsdf.inputs["Alpha"])
    bsdf.inputs["Roughness"].default_value = 0.85
    bsdf.inputs["Specular IOR Level"].default_value = 0.25
    return mat


def emission_material(name, rgb, strength):
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    nodes, links = mat.node_tree.nodes, mat.node_tree.links
    nodes.remove(nodes["Principled BSDF"])
    emission = nodes.new("ShaderNodeEmission")
    emission.inputs["Color"].default_value = (*[srgb_to_linear(c) for c in rgb], 1.0)
    emission.inputs["Strength"].default_value = strength
    links.new(emission.outputs["Emission"], nodes["Material Output"].inputs["Surface"])
    return mat


def mesh_from_faces(name, faces, material):
    """One object from (part, world corners, uvs, cube centre) faces, every face turned to face away
    from its cube's centre (the winding in ModelPart is not needed once the normal is known)."""
    verts, polys, uvs = [], [], []
    cube_index = {}
    for _part, corners, face_uvs, centre in faces:
        points = [mcmodel.to_blender(c) for c in corners]
        c = mcmodel.to_blender(centre)
        normal = (points[1] - points[0]).cross(points[2] - points[0])
        mid = sum(points, Vector()) / 4
        order = [0, 1, 2, 3] if normal.dot(mid - c) >= 0 else [3, 2, 1, 0]
        # The player's legs overlap by 0.2 model pixels, so their faces are coplanar there. A ray that
        # bounces off one such face starts on the other and hits it at distance 0, which Cycles renders as a
        # black seam. The faces of the n-th cube move out along their normal by n + 1 thousandths of a model
        # pixel, so faces that shared a plane no longer do.
        n = cube_index.setdefault(tuple(round(v, 6) for v in c), len(cube_index))
        offset = normal.normalized() * (1 if normal.dot(mid - c) >= 0 else -1) * COPLANAR_STEP * (n + 1)
        base = len(verts)
        verts.extend(points[i] + offset for i in order)
        polys.append([base + k for k in range(4)])
        uvs.extend((face_uvs[i][0], 1.0 - face_uvs[i][1]) for i in order)
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata(verts, [], polys)
    layer = mesh.uv_layers.new(name="UVMap")
    for loop in mesh.loops:
        layer.data[loop.index].uv = uvs[loop.vertex_index]
    mesh.materials.append(material)
    obj = bpy.data.objects.new(name, mesh)
    bpy.context.scene.collection.objects.link(obj)
    return obj


def glyph_pixels(char):
    """Opaque pixel coordinates of `char` in ascii.png, found through the font provider's chars table."""
    providers = json.loads((SOURCES / "font_include_default.json").read_text(encoding="utf-8"))["providers"]
    rows = next(p["chars"] for p in providers if p.get("file") == "minecraft:font/ascii.png")
    row = next(i for i, line in enumerate(rows) if char in line)
    col = rows[row].index(char)
    image = bpy.data.images.load(str(SOURCES / "ascii.png"), check_existing=True)
    w, h = image.size
    px = list(image.pixels)
    out = []
    for y in range(8):
        for x in range(8):
            ix, iy = col * 8 + x, row * 8 + y
            alpha = px[((h - 1 - iy) * w + ix) * 4 + 3]
            if alpha > 0.5:
                out.append((x, y))
    return out


def glyph_object(name, char, origin, size, depth, material, azimuth_deg, elevation_deg):
    """The glyph as one voxel per font pixel, its top-left corner at `origin`, facing the camera."""
    bm = bmesh.new()
    for x, y in glyph_pixels(char):
        geom = bmesh.ops.create_cube(bm, size=1.0)["verts"]
        bmesh.ops.scale(bm, vec=(size, depth, size), verts=geom)
        bmesh.ops.translate(bm, vec=((x + 0.5) * size, 0.0, -(y + 0.5) * size), verts=geom)
    mesh = bpy.data.meshes.new(name)
    bm.to_mesh(mesh)
    bm.free()
    mesh.materials.append(material)
    obj = bpy.data.objects.new(name, mesh)
    obj.location = origin
    right, up, toward = screen_axes(azimuth_deg, elevation_deg)
    obj.rotation_euler = Matrix((right, -toward, up)).transposed().to_euler()
    bpy.context.scene.collection.objects.link(obj)
    return obj


def sprite_billboard(name, image_name, centre, size, strength, azimuth_deg, elevation_deg):
    """A square facing the camera, showing `image_name` as added light: its black pixels add nothing."""
    image = bpy.data.images.load(str(SOURCES / image_name), check_existing=True)
    image.pack()
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    nodes, links = mat.node_tree.nodes, mat.node_tree.links
    nodes.remove(nodes["Principled BSDF"])
    tex = nodes.new("ShaderNodeTexImage")
    tex.image = image
    tex.interpolation = "Closest"
    emission = nodes.new("ShaderNodeEmission")
    emission.inputs["Strength"].default_value = strength
    transparent = nodes.new("ShaderNodeBsdfTransparent")
    add = nodes.new("ShaderNodeAddShader")
    links.new(tex.outputs["Color"], emission.inputs["Color"])
    links.new(emission.outputs["Emission"], add.inputs[0])
    links.new(transparent.outputs["BSDF"], add.inputs[1])
    links.new(add.outputs["Shader"], nodes["Material Output"].inputs["Surface"])
    right, up, _ = screen_axes(azimuth_deg, elevation_deg)
    c, h = Vector(centre), size / 2
    verts = [c - right * h - up * h, c + right * h - up * h, c + right * h + up * h, c - right * h + up * h]
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata(verts, [], [[0, 1, 2, 3]])
    layer = mesh.uv_layers.new(name="UVMap")
    for loop, uv in zip(mesh.loops, ((0, 0), (1, 0), (1, 1), (0, 1))):
        layer.data[loop.index].uv = uv
    mesh.materials.append(mat)
    obj = bpy.data.objects.new(name, mesh)
    obj.visible_shadow = False
    obj.visible_diffuse = False
    bpy.context.scene.collection.objects.link(obj)
    return obj


def world(camera_rgb, light_rgb, light_strength):
    """Flat background colour for camera rays, a separate dim ambient colour for lighting."""
    w = bpy.data.worlds.new("night")
    bpy.context.scene.world = w
    w.use_nodes = True
    nodes, links = w.node_tree.nodes, w.node_tree.links
    out = nodes["World Output"]
    nodes.remove(nodes["Background"])
    seen = nodes.new("ShaderNodeBackground")
    seen.inputs["Color"].default_value = (*[srgb_to_linear(c) for c in camera_rgb], 1.0)
    lit = nodes.new("ShaderNodeBackground")
    lit.inputs["Color"].default_value = (*[srgb_to_linear(c) for c in light_rgb], 1.0)
    lit.inputs["Strength"].default_value = light_strength
    path = nodes.new("ShaderNodeLightPath")
    mix = nodes.new("ShaderNodeMixShader")
    links.new(path.outputs["Is Camera Ray"], mix.inputs["Fac"])
    links.new(lit.outputs["Background"], mix.inputs[1])
    links.new(seen.outputs["Background"], mix.inputs[2])
    links.new(mix.outputs["Shader"], out.inputs["Surface"])


def sun(name, rgb, strength, rotation_deg, angle_deg=2.0):
    data = bpy.data.lights.new(name, "SUN")
    data.color = [srgb_to_linear(c) for c in rgb]
    data.energy = strength
    data.angle = math.radians(angle_deg)
    obj = bpy.data.objects.new(name, data)
    obj.rotation_euler = [math.radians(a) for a in rotation_deg]
    bpy.context.scene.collection.objects.link(obj)
    return obj


def area(name, rgb, power, location, target, size):
    data = bpy.data.lights.new(name, "AREA")
    data.color = [srgb_to_linear(c) for c in rgb]
    data.energy = power
    data.size = size
    obj = bpy.data.objects.new(name, data)
    obj.location = location
    obj.rotation_euler = (Vector(target) - Vector(location)).to_track_quat("-Z", "Y").to_euler()
    bpy.context.scene.collection.objects.link(obj)
    return obj


def screen_axes(azimuth_deg, elevation_deg):
    """(right, up, toward camera) unit vectors of a camera at this azimuth and elevation.
    Azimuth 0 puts the camera on Blender -Y (Minecraft south) looking north."""
    az, el = math.radians(azimuth_deg), math.radians(elevation_deg)
    toward = Vector((math.sin(az) * math.cos(el), -math.cos(az) * math.cos(el), math.sin(el)))
    right = Vector((math.cos(az), math.sin(az), 0.0))
    up = toward.cross(right)
    return right, up, toward


def screen_point(anchor, azimuth_deg, elevation_deg, dx, dy):
    right, up, _ = screen_axes(azimuth_deg, elevation_deg)
    return Vector(anchor) + right * dx + up * dy


def framed_ortho_camera(azimuth_deg, elevation_deg, objects, margin, distance=20.0):
    """Orthographic camera that fits every vertex of `objects` in a square frame with `margin`
    (fraction of the frame) left free on the longer side."""
    bpy.context.view_layer.update()
    right, up, toward = screen_axes(azimuth_deg, elevation_deg)
    points = [obj.matrix_world @ v.co for obj in objects for v in obj.data.vertices]
    xs, ys = [p.dot(right) for p in points], [p.dot(up) for p in points]
    depth = sum(p.dot(toward) for p in points) / len(points)
    centre = right * (min(xs) + max(xs)) / 2 + up * (min(ys) + max(ys)) / 2 + toward * depth
    data = bpy.data.cameras.new("camera")
    data.type = "ORTHO"
    data.ortho_scale = max(max(xs) - min(xs), max(ys) - min(ys)) / (1.0 - 2.0 * margin)
    obj = bpy.data.objects.new("camera", data)
    obj.location = centre + toward * distance
    obj.rotation_euler = (-toward).to_track_quat("-Z", "Y").to_euler()
    bpy.context.scene.collection.objects.link(obj)
    bpy.context.scene.camera = obj
    return obj


def render(slug, record):
    """Render to candidates/<slug>.png, save the packed scene and write candidates/<slug>-metadata.json."""
    OUT.mkdir(exist_ok=True)
    scene = bpy.context.scene
    png = OUT / f"{slug}.png"
    scene.render.filepath = str(png)
    start = time.monotonic()
    bpy.ops.render.render(write_still=True)
    seconds = round(time.monotonic() - start, 3)
    blend = OUT / f"{slug}.blend"
    bpy.ops.wm.save_as_mainfile(filepath=str(blend), compress=True)
    cam = scene.camera
    meta = {
        "slug": slug,
        "blender": bpy.app.version_string,
        "engine": "CYCLES CPU",
        "samples": SAMPLES,
        "threads": THREADS,
        "seed": scene.cycles.seed,
        "denoise": scene.cycles.use_denoising,
        "resolution": [RESOLUTION, RESOLUTION],
        "view_transform": scene.view_settings.view_transform,
        "render_seconds": seconds,
        "camera": {
            "type": cam.data.type,
            "ortho_scale": cam.data.ortho_scale,
            "location": list(cam.location),
            "rotation_euler_deg": [math.degrees(a) for a in cam.rotation_euler],
            "shift": [cam.data.shift_x, cam.data.shift_y],
        },
        "textures": {
            img.name: sha256(SOURCES / img.name) for img in bpy.data.images if (SOURCES / img.name).exists()
        },
        "scripts": {name: sha256(ROOT / name) for name in ("scene.py", "mcmodel.py", f"render_{slug[-1]}.py")},
        "png_sha256": sha256(png),
        "blend_sha256": sha256(blend),
        **record,
    }
    (OUT / f"{slug}-metadata.json").write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(meta, indent=2))
