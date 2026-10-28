"""Split the Dolphin's tailgate into its own hinged node and add a simple trunk interior.

Run headless:
    blender -b -P tools/split_tailgate.py -- viewer/dolphin.glb viewer/dolphin_tailgate.glb

The model ships the tailgate fused to the body. This cuts the rear of every body mesh with
straight planes (bumper line, both tailgate edges, roof end), moves the faces inside that box
under an empty called "Tailgate" whose origin sits on the hinge line, and adds a dark trunk
floor and back wall that only show when the tailgate is open.

Region values are in metres of the real car (4.29 m long) and are scaled to the model.
"""
import re
import sys

import bmesh
import bpy
from mathutils import Vector

REAL_LENGTH = 4.29
DEPTH = 0.50       # how far forward from the rear the tailgate reaches
BUMPER_Z = 0.62    # bottom edge of the tailgate above the ground
HALF_WIDTH = 0.62  # tailgate half width
SKIP = re.compile(r"^(interior|sticker|UC|uc_tex|tyre|Rim|disk|calipar|Black_alloy|bodypaint_rim|rubber|mudflap|bumper)")

argv = sys.argv[sys.argv.index("--") + 1:]
src, dst = argv[0], argv[1]

bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=src)

# Flatten the imported hierarchy so every mesh lives in plain world space.
meshes = [o for o in bpy.context.scene.objects if o.type == "MESH"]
for o in meshes:
    mw = o.matrix_world.copy()
    o.parent = None
    o.matrix_world = mw
for o in [o for o in bpy.context.scene.objects if o.type == "EMPTY"]:
    bpy.data.objects.remove(o, do_unlink=True)
bpy.ops.object.select_all(action="DESELECT")
for o in meshes:
    o.select_set(True)
bpy.context.view_layer.objects.active = meshes[0]
# Shared mesh data cannot take per-object transforms, so make each one single-user first.
bpy.ops.object.make_single_user(object=True, obdata=True)
bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)

# Bounds. glTF +Z (car front) imports as Blender -Y, so the rear is at max Y and up is +Z.
lo = Vector((1e9, 1e9, 1e9))
hi = Vector((-1e9, -1e9, -1e9))
for o in meshes:
    for v in o.data.vertices:
        lo = Vector(map(min, lo, v.co))
        hi = Vector(map(max, hi, v.co))
length = hi.y - lo.y
s = length / REAL_LENGTH
cx = (lo.x + hi.x) / 2
y_cut = hi.y - DEPTH * s
z_cut = lo.z + BUMPER_Z * s
x_lo, x_hi = cx - HALF_WIDTH * s, cx + HALF_WIDTH * s
print(f"[tailgate] length={length:.3f} scale={s:.4f} y_cut={y_cut:.3f} z_cut={z_cut:.3f}")


def in_region(p):
    return p.y > y_cut and p.z > z_cut and x_lo < p.x < x_hi


gate_parts = []
hinge = None
for o in meshes:
    if SKIP.match(o.name):
        continue
    if not any(in_region(v.co) for v in o.data.vertices):
        continue
    bm = bmesh.new()
    bm.from_mesh(o.data)
    # Straight cuts so the seam is a clean line instead of a triangle staircase.
    for co, no in (((0, y_cut, 0), (0, 1, 0)), ((0, 0, z_cut), (0, 0, 1)),
                   ((x_lo, 0, 0), (1, 0, 0)), ((x_hi, 0, 0), (1, 0, 0))):
        geom = bm.verts[:] + bm.edges[:] + bm.faces[:]
        bmesh.ops.bisect_plane(bm, geom=geom, plane_co=co, plane_no=no)
    picked = [f for f in bm.faces if in_region(f.calc_center_median())]
    if not picked:
        bm.free()
        continue
    for f in bm.faces:
        f.select_set(f in picked)
    for f in picked:
        for v in f.verts:
            if hinge is None or v.co.z > hinge.z:
                hinge = v.co.copy()
    bm.to_mesh(o.data)
    bm.free()
    bpy.ops.object.select_all(action="DESELECT")
    o.select_set(True)
    bpy.context.view_layer.objects.active = o
    bpy.ops.object.mode_set(mode="EDIT")
    bpy.ops.mesh.separate(type="SELECTED")
    bpy.ops.object.mode_set(mode="OBJECT")
    part = [x for x in bpy.context.selected_objects if x is not o][0]
    part.name = o.name + "_gate"
    gate_parts.append(part)

if not gate_parts:
    sys.exit("[tailgate] nothing selected, check the region values")

gate = bpy.data.objects.new("Tailgate", None)
bpy.context.scene.collection.objects.link(gate)
gate.location = (cx, hinge.y, hinge.z)
bpy.context.view_layer.update()
for p in gate_parts:
    mw = p.matrix_world.copy()
    p.parent = gate
    p.matrix_world = mw
print(f"[tailgate] {len(gate_parts)} parts, hinge at {tuple(round(c, 3) for c in gate.location)}")

# Trunk interior: a floor at bumper height and a wall where the cabin starts.
mat = bpy.data.materials.new("TrunkInterior")
mat.use_nodes = True
bsdf = mat.node_tree.nodes["Principled BSDF"]
bsdf.inputs["Base Color"].default_value = (0.035, 0.037, 0.04, 1)
bsdf.inputs["Roughness"].default_value = 1.0
inner_w = (x_hi - x_lo) * 0.97
for name, loc, size in (
    ("TrunkFloor", (cx, (y_cut + hi.y) / 2 - 0.05 * s, z_cut - 0.01 * s), (inner_w, hi.y - y_cut - 0.15 * s, 0.02 * s)),
    ("TrunkWall", (cx, y_cut + 0.01 * s, (z_cut + hinge.z) / 2 - 0.1 * s), (inner_w, 0.02 * s, hinge.z - z_cut - 0.25 * s)),
):
    bpy.ops.mesh.primitive_cube_add(location=loc)
    box = bpy.context.active_object
    box.name = name
    box.scale = (size[0] / 2, size[1] / 2, size[2] / 2)
    bpy.ops.object.transform_apply(scale=True)
    box.data.materials.append(mat)

bpy.ops.export_scene.gltf(
    filepath=dst,
    export_format="GLB",
    export_draco_mesh_compression_enable=True,
    export_draco_mesh_compression_level=7,
    export_image_format="WEBP",
)
print("[tailgate] wrote", dst)
