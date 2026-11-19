"""Dump any car model's panel seams and mesh bounds in car metres, for tracing the part outlines
that tools/split_model.py cuts along (the generic version of dump_seams.py + inspect_parts.py).

    blender -b -P tools/model_seams.py -- viewer/byd_seal.glb out.json "corner|lines" "bodypaint|DHL"

Car metres: x + = car's left, y = distance back from the front bumper, z = height above ground;
the car is scaled to 4.29 m long, as the launcher does. Writes
  {"size": [w, l, h], "meshes": [[name, material, x0, x1, y0, y1, z0, z1, verts]...],
   "seam": [[x, y, z]...]      vertices of the seam-strip meshes (first regex)
   "gap":  [[x1, y1, z1, x2, y2, z2]...]  open edges of the welded paint meshes (second regex)}
Plot it with tools/plot_seams.py.
"""
import json
import re
import sys

import bmesh
import bpy
from mathutils import Vector

src, dst, seam_re, paint_re = sys.argv[sys.argv.index("--") + 1:]
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
    return [round((w.x - cx) / s, 3), round((w.y - lo.y) / s, 3), round((w.z - lo.z) / s, 3)]


out = {"size": [round((hi.x - lo.x) / s, 3), 4.29, round((hi.z - lo.z) / s, 3)], "meshes": [], "seam": [], "gap": []}
for o in sorted(meshes, key=lambda o: o.name):
    pts = [metres(o.matrix_world @ v.co) for v in o.data.vertices]
    if not pts:
        continue
    xs, ys, zs = zip(*pts)
    out["meshes"].append([o.name, ",".join(m.name for m in o.data.materials if m),
                          min(xs), max(xs), min(ys), max(ys), min(zs), max(zs), len(pts)])
    if re.search(seam_re, o.name):
        out["seam"] += pts
    if re.search(paint_re, o.name):
        bm = bmesh.new()
        bm.from_mesh(o.data)
        bm.transform(o.matrix_world)
        bmesh.ops.remove_doubles(bm, verts=bm.verts[:], dist=2e-4 * s)
        for e in bm.edges:
            if e.is_boundary:
                out["gap"].append(metres(e.verts[0].co) + metres(e.verts[1].co))
        bm.free()
json.dump(out, open(dst, "w"))
print("[seams]", src, "size", out["size"], "seam pts", len(out["seam"]), "gap edges", len(out["gap"]))
