"""Print every mesh's bounds in car metres (origin: front, ground, centre line).

    blender -b -P tools/inspect_parts.py -- viewer/dolphin.glb

x: + = car's left, y: distance back from the front bumper, z: height above ground.
"""
import sys

import bpy
from mathutils import Vector

src = sys.argv[sys.argv.index("--") + 1]
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=src)
meshes = [o for o in bpy.context.scene.objects if o.type == "MESH"]


def world_verts(o):
    mw = o.matrix_world
    return [mw @ v.co for v in o.data.vertices]


lo, hi = Vector((1e9,) * 3), Vector((-1e9,) * 3)
cache = {}
for o in meshes:
    vs = cache[o.name] = world_verts(o)
    for v in vs:
        lo = Vector(map(min, lo, v))
        hi = Vector(map(max, hi, v))
s = (hi.y - lo.y) / 4.29
cx = (lo.x + hi.x) / 2
print(f"[inspect] scale={s:.4f} width={(hi.x - lo.x) / s:.2f} height={(hi.z - lo.z) / s:.2f}")
for o in sorted(meshes, key=lambda o: o.name):
    vs = cache[o.name]
    xs = [(v.x - cx) / s for v in vs]
    ys = [(v.y - lo.y) / s for v in vs]
    zs = [(v.z - lo.z) / s for v in vs]
    mats = ",".join(m.name for m in o.data.materials if m)
    print(f"[inspect] {o.name:40s} x[{min(xs):6.2f},{max(xs):6.2f}] y[{min(ys):5.2f},{max(ys):5.2f}] "
          f"z[{min(zs):5.2f},{max(zs):5.2f}] verts={len(vs):6d} mats={mats}")
