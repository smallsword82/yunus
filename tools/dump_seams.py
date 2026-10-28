"""Dump the model's panel seams as car-metre points for plotting.

    blender -b -P tools/dump_seams.py -- viewer/dolphin.glb seams.json

Two sources:
  lines  - the corner_lines strips the model draws along door and fender seams
  gaps   - open edges left after welding the body panels; real panel gaps (the tailgate,
           which corner_lines does not draw) show up here
Writes {"lines": {"left": [[y, z]...], "right": [...], "rear": [[x, z]...]},
        "gaps": {"rear": [[x1, z1, x2, z2, y], ...]}}
with x + = car's left, y = distance back from the front bumper, z = height above ground.
"""
import json
import sys

import bmesh
import bpy
from mathutils import Vector

src, dst = sys.argv[sys.argv.index("--") + 1:]
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=src)
meshes = [o for o in bpy.context.scene.objects if o.type == "MESH"]
lo, hi = Vector((1e9,) * 3), Vector((-1e9,) * 3)
for o in meshes:
    for v in o.data.vertices:
        w = o.matrix_world @ v.co
        lo, hi = Vector(map(min, lo, w)), Vector(map(max, hi, w))
s = (hi.y - lo.y) / 4.29
cx = (lo.x + hi.x) / 2


def metres(w):
    return (w.x - cx) / s, (w.y - lo.y) / s, (w.z - lo.z) / s


out = {"lines": {"left": [], "right": [], "rear": []}, "gaps": {"rear": []}}
for o in meshes:
    if o.name.startswith("corner_lines"):
        for v in o.data.vertices:
            x, y, z = metres(o.matrix_world @ v.co)
            if x > 0.5:
                out["lines"]["left"].append([round(y, 3), round(z, 3)])
            elif x < -0.5:
                out["lines"]["right"].append([round(y, 3), round(z, 3)])
            if y > 3.6:
                out["lines"]["rear"].append([round(x, 3), round(z, 3)])
    if o.name.startswith(("bodypaint_A", "upper_bodypaint", "blk_gloss", "blk_mat_")):
        bm = bmesh.new()
        bm.from_mesh(o.data)
        bm.transform(o.matrix_world)
        bmesh.ops.remove_doubles(bm, verts=bm.verts[:], dist=1e-4 * s)
        for e in bm.edges:
            if not e.is_boundary:
                continue
            (x1, y1, z1), (x2, y2, z2) = (metres(v.co) for v in e.verts)
            if min(y1, y2) > 3.55:
                out["gaps"]["rear"].append([round(x1, 3), round(z1, 3), round(x2, 3), round(z2, 3),
                                            round((y1 + y2) / 2, 3), o.name[:12]])
        bm.free()
json.dump(out, open(dst, "w"))
print("[seams]", {k: len(v) for k, v in out["lines"].items()}, "rear gap edges:", len(out["gaps"]["rear"]))
