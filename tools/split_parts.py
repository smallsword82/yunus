"""Cut the Dolphin's moving parts out of the fused body so the launcher can animate them.

    blender -b -P tools/split_parts.py -- viewer/dolphin.glb viewer/dolphin_parts.glb

Produces these nodes, each an empty sitting on its hinge with identity rotation:
  Tailgate                             hinge along the roof end, opens about X
  Door_FL, Door_FR, Door_RL, Door_RR   hinge on the front edge, opens about the vertical axis
    Window_FL ... (child of its door)  side glass, slides down inside the door
  ChargeFlap                           right front fender, hinge on its front edge

So nothing looks hollow when something opens:
  - moving panels get real thickness: paint outside, dark trim inside, closed edges
  - body openings get jambs: their cut edges are extruded into the car, in a dark gap shade
  - the trunk gets a floor, back wall and side walls; the charge flap a closed recess and socket

Regions are written in car metres measured from the model: x + = car's left, y = distance back
from the front bumper, z = height above ground (see tools/inspect_parts.py). Each part is a
closed outline that follows the model's own panel seams (the corner_lines strips, plotted with
tools/dump_seams.py): doors and the charge flap in the side view, the tailgate in the rear view.
Every mesh is first bisected along the outline so the cut runs exactly on the seam, then the
faces inside are separated.
"""
import math
import re
import sys

import bmesh
import bpy
from mathutils import Vector

REAL_LENGTH = 4.29
WHEELS = r"tyre|Rim|disk|calipar|Black_alloy|bodypaint_rim|Chrome_chrome_rim|Badge_RIM"
UNDER = r"UC_|uc_tex|mudflap|bumper_tex"
GLASS = "winsows"
# Panels that get thickness. Glass, lamps and small trim (mirror housing, handles) stay as they are.
SOLID = re.compile(r"^(bodypaint_A|upper_bodypaint)")


def half(point, normal):
    return (Vector(point), Vector(normal).normalized())


class SideOutline:
    """Region on one side of the car: outboard of |x| = 0.6 and inside a closed outline drawn
    in the side view (y, z), which follows the model's panel seams."""

    def __init__(self, sx, outline, inner_x=0.60):
        self.sx, self.pts, self.inner_x = sx, outline, inner_x
        self.edges = list(zip(outline, outline[1:] + outline[:1]))

    def _seg_dist(self, p, a, b):
        ay, az, by, bz = a[0], a[1], b[0], b[1]
        dy, dz = by - ay, bz - az
        t = max(0.0, min(1.0, ((p.y - ay) * dy + (p.z - az) * dz) / (dy * dy + dz * dz)))
        return math.hypot(p.y - (ay + t * dy), p.z - (az + t * dz))

    def _in_outline(self, p):
        hit = False
        for (ay, az), (by, bz) in self.edges:
            if (az > p.z) != (bz > p.z) and p.y < ay + (p.z - az) * (by - ay) / (bz - az):
                hit = not hit
        return hit

    def inside(self, p, margin=0.0):
        if self.sx * p.x <= self.inner_x - margin:
            return False
        return self._in_outline(p) or (margin > 0 and min(self._seg_dist(p, a, b) for a, b in self.edges) < margin)

    def cuts(self):
        out = [(Vector((self.inner_x * self.sx, 0, 0)), Vector((self.sx, 0, 0)), lambda p: self.inside(p, 0.05))]
        for a, b in self.edges:
            # Plane through the seam segment, perpendicular to the side of the car.
            n = Vector((0, -(b[1] - a[1]), b[0] - a[0])).normalized()
            near = (lambda a, b: lambda p: self.sx * p.x > self.inner_x - 0.1 and self._seg_dist(p, a, b) < 0.05)(a, b)
            out.append((Vector((0, a[0], a[1])), n, near))
        return out


class RearOutline(SideOutline):
    """The tailgate: inside a closed outline drawn in the rear view (x, z), on the rear-facing
    part of the body. Below the rear window the cut stays behind y = 3.84 so the quarter panels'
    sides stay on the body; the window and roof end start at the hinge line, y = 3.79."""

    def __init__(self, outline, low_y=3.84, high_y=3.79, split_z=1.10):
        self.pts, self.low_y, self.high_y, self.split_z = outline, low_y, high_y, split_z
        self.edges = list(zip(outline, outline[1:] + outline[:1]))

    def _yz(self, p):
        # Reuse the side-view helpers by feeding them (x, z) in place of (y, z).
        return Vector((0, p.x, p.z))

    def _min_y(self, p):
        return self.high_y if p.z > self.split_z else self.low_y

    def inside(self, p, margin=0.0):
        if p.y <= self._min_y(p) - margin:
            return False
        q = self._yz(p)
        return self._in_outline(q) or (margin > 0 and min(self._seg_dist(q, a, b) for a, b in self.edges) < margin)

    def cuts(self):
        out = [(Vector((0, self.low_y, 0)), Vector((0, 1, 0)),
                lambda p: p.z < self.split_z + 0.05 and self.inside(Vector((p.x, 9, p.z)), 0.05))]
        for a, b in self.edges:
            n = Vector((-(b[1] - a[1]), 0, b[0] - a[0])).normalized()
            near = (lambda a, b: lambda p: p.y > 3.6 and self._seg_dist(self._yz(p), a, b) < 0.05)(a, b)
            out.append((Vector((a[0], 0, a[1])), n, near))
        return out


def arc(cy, cz, r, start_deg, end_deg, steps):
    return [(cy + r * math.cos(math.radians(a)), cz + r * math.sin(math.radians(a)))
            for a in (start_deg + (end_deg - start_deg) * i / steps for i in range(steps + 1))]


# Side-view outlines traced from the corner_lines seams (y back from the front, z up).
FRONT_DOOR = [(1.30, 0.26), (2.36, 0.26), (2.36, 1.00), (2.43, 1.47), (1.95, 1.47), (1.33, 1.02), (1.30, 0.98)]
# The rear door's lower back corner wraps around the wheel arch (wheel centre y 3.51, z 0.33).
REAR_DOOR = ([(2.36, 0.26), (3.05, 0.26)] + arc(3.51, 0.33, 0.46, 186, 120, 8)[1:] +
             [(3.33, 0.85), (3.40, 0.98), (3.43, 1.05), (3.40, 1.15), (3.33, 1.25), (3.25, 1.37),
              (3.18, 1.47), (2.43, 1.47), (2.36, 1.00)])
# Tailgate in the rear view (x, z): roof end, rear window edge, through the light bar at the
# point the seam meets it, then down the seam groove the model has in the body panel (a pair of
# sharp creases, found with a crease plot of bodypaint_A). The bottom edge is the upper crease,
# 0.75 m: there the tailgate's upright face turns into the bumper's top ledge, which runs ~8 cm
# further back at 0.73-0.74 m (and carries the number plate light recess), so a lower cut would
# take that ledge along with the tailgate.
# The sides follow the inner crease of the groove (the outer one belongs to the body).
_TAILGATE_LEFT_HALF = [(-0.58, 1.49), (-0.62, 1.15), (-0.62, 1.04), (-0.62, 0.945), (-0.613, 0.92),
                       (-0.601, 0.88), (-0.591, 0.86), (-0.578, 0.82), (-0.566, 0.80), (-0.55, 0.78),
                       (-0.535, 0.765), (-0.50, 0.755), (-0.45, 0.752), (-0.01, 0.752)]
TAILGATE = _TAILGATE_LEFT_HALF + [(-x, z) for x, z in reversed(_TAILGATE_LEFT_HALF)]
# Charge flap on the right front fender, point for point from its corner_lines outline: flat top,
# square back, well-rounded lower front corner.
CHARGE_FLAP = [(0.95, 0.818), (0.955, 0.81), (0.962, 0.803), (0.97, 0.798), (0.994, 0.787), (1.008, 0.783),
               (1.023, 0.779), (1.036, 0.78), (1.095, 0.785), (1.11, 0.789), (1.118, 0.797), (1.119, 0.842),
               (1.118, 0.885), (1.105, 0.893), (1.098, 0.894), (0.974, 0.889), (0.959, 0.885), (0.953, 0.88),
               (0.947, 0.866), (0.948, 0.858)]


def door_spec(name, sx, outline, hinge_y):
    return dict(name=name, region=SideOutline(sx, outline), skip=rf"^({WHEELS}|{UNDER})",
                hinge=(0.86 * sx, hinge_y, 0.9), window=f"Window_{name[-2:]}",
                jamb=((-sx, 0, 0), 0.10), thickness=0.07)


SPECS = [
    dict(name="Tailgate", region=RearOutline(TAILGATE),
         skip=rf"^(interior|sticker|rubber|{WHEELS}|{UNDER})", hinge=None,
         jamb=((0, -1, 0), 0.12), thickness=0.05),
    door_spec("Door_FL", 1, FRONT_DOOR, 1.30),
    door_spec("Door_FR", -1, FRONT_DOOR, 1.30),
    door_spec("Door_RL", 1, REAR_DOOR, 2.36),
    door_spec("Door_RR", -1, REAR_DOOR, 2.36),
    dict(name="ChargeFlap", region=SideOutline(-1, CHARGE_FLAP, inner_x=0.70),
         only=r"^(bodypaint_A|upper_bodypaint|corner_lines)", hinge=(-0.84, 0.947, 0.84),
         jamb=((1, 0, 0), 0.05), thickness=0.012),
]

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
bpy.ops.object.make_single_user(object=True, obdata=True)
bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)

# Blender keeps custom normals relative to the surrounding geometry, so cutting a mesh shifts
# the shading near every cut (pale streaks along the seams). Record each corner's absolute
# normal as a plain attribute now; bmesh carries it through the cuts, and restore_normals()
# writes it back as custom normals just before export.
NORMAL_ATTR = "orig_normal"
for o in meshes:
    attr = o.data.attributes.new(NORMAL_ATTR, "FLOAT_VECTOR", "CORNER")
    attr.data.foreach_set("vector", [c for n in o.data.corner_normals for c in n.vector])


def restore_normals(obj):
    attr = obj.data.attributes.get(NORMAL_ATTR)
    if attr is None:
        return
    # Zero vectors (faces this script added) fall back to Blender's own normals.
    normals = [tuple(v.vector) for v in attr.data]
    obj.data.attributes.remove(attr)
    obj.data.normals_split_custom_set(normals)

# Car metres <-> Blender world. glTF +Z (front) imports as Blender -Y, so "back" is +Y.
lo = Vector((1e9,) * 3)
hi = Vector((-1e9,) * 3)
for o in meshes:
    for v in o.data.vertices:
        lo = Vector(map(min, lo, v.co))
        hi = Vector(map(max, hi, v.co))
s = (hi.y - lo.y) / REAL_LENGTH
cx = (lo.x + hi.x) / 2


def to_world(p):
    return Vector((cx + p[0] * s, lo.y + p[1] * s, lo.z + p[2] * s))


def to_metres(w):
    return Vector(((w.x - cx) / s, (w.y - lo.y) / s, (w.z - lo.z) / s))


def inside(p_m, spec, margin=0.0):
    return spec["region"].inside(p_m, margin)


def wants(o, spec):
    if spec.get("skip") and re.match(spec["skip"], o.name):
        return False
    if spec.get("only") and not re.match(spec["only"], o.name):
        return False
    return True


def material(name, rgb, roughness=0.9, metallic=0.0):
    mat = bpy.data.materials.get(name) or bpy.data.materials.new(name)
    mat.use_nodes = True
    bsdf = mat.node_tree.nodes["Principled BSDF"]
    bsdf.inputs["Base Color"].default_value = (*rgb, 1)
    bsdf.inputs["Roughness"].default_value = roughness
    bsdf.inputs["Metallic"].default_value = metallic
    return mat


TRIM = material("DoorTrim", (0.045, 0.047, 0.05))
TRUNK = material("TrunkInterior", (0.11, 0.115, 0.12), 1.0)  # dark grey carpet, not a black void
PORT = material("ChargePort", (0.02, 0.02, 0.022), 0.6)
# Door gaps and jambs. The launcher has no ambient occlusion, so body-coloured jambs light up
# as pale stripes along every seam; a dark shade reads as the shadowed gap it is on a real car.
GAP = material("PanelGap", (0.03, 0.032, 0.035), 0.7)


def gap_index(mesh):
    if GAP.name not in [m.name for m in mesh.materials if m]:
        mesh.materials.append(GAP)
    return [m.name if m else "" for m in mesh.materials].index(GAP.name)


def separate(o, spec):
    """Bisect `o` along the spec's planes and split off the faces inside. Returns the new object."""
    if not wants(o, spec) or not any(inside(to_metres(v.co), spec) for v in o.data.vertices):
        return None
    bm = bmesh.new()
    bm.from_mesh(o.data)
    for pt, n, is_near in spec["region"].cuts():
        # Only cut around the seam: the planes are infinite, and slicing the whole car leaves
        # sliver triangles elsewhere that shade as white specks.
        near = [f for f in bm.faces if any(is_near(to_metres(v.co)) for v in f.verts)]
        geom = list({v for f in near for v in f.verts}) + list({e for f in near for e in f.edges}) + near
        bmesh.ops.bisect_plane(bm, geom=geom, plane_co=to_world(pt), plane_no=n)
    bmesh.ops.dissolve_degenerate(bm, edges=bm.edges[:], dist=1e-5 * s)
    picked =[f for f in bm.faces if inside(to_metres(f.calc_center_median()), spec)]
    if not picked:
        bm.free()
        return None
    for f in bm.faces:
        f.select_set(f in set(picked))
    bm.to_mesh(o.data)
    bm.free()
    bpy.ops.object.select_all(action="DESELECT")
    o.select_set(True)
    bpy.context.view_layer.objects.active = o
    bpy.ops.object.mode_set(mode="EDIT")
    bpy.ops.mesh.separate(type="SELECTED")
    bpy.ops.object.mode_set(mode="OBJECT")
    return [x for x in bpy.context.selected_objects if x is not o][0]


def on_cut(v_world, spec):
    p = to_metres(v_world)
    on_seam = any(abs((p - pt).dot(n)) < 0.004 and is_near(p) for pt, n, is_near in spec["region"].cuts())
    return on_seam and inside(p, spec, margin=0.01)


def add_jamb(o, spec):
    """Extrude the opening's cut edges on the body into the car, so the gap has walls."""
    direction, depth = spec["jamb"]
    offset = Vector(direction).normalized() * depth * s
    bm = bmesh.new()
    bm.from_mesh(o.data)
    edges = [e for e in bm.edges if e.is_boundary and all(on_cut(v.co, spec) for v in e.verts)]
    if edges:
        gap = gap_index(o.data)
        ret = bmesh.ops.extrude_edge_only(bm, edges=edges)
        new_verts = [g for g in ret["geom"] if isinstance(g, bmesh.types.BMVert)]
        bmesh.ops.translate(bm, verts=new_verts, vec=offset)
        for f in (g for g in ret["geom"] if isinstance(g, bmesh.types.BMFace)):
            f.material_index = gap
        bm.to_mesh(o.data)
    bm.free()
    return len(edges)


def thicken(part, spec):
    """Give a moving panel real depth: a dark trim skin parallel to the paint, set back into
    the car along the spec's jamb direction, and dark gap-shaded edges along the cut line
    closing the gap between them.

    The imported mesh is not welded (nearly every triangle edge is a boundary), so the edge
    strip is built only from edges on the cut planes; extruding every boundary edge, or using
    Solidify, grows fins all over the panel and it looks crumpled."""
    part.data.materials.append(TRIM)
    trim_index = len(part.data.materials) - 1
    direction, _ = spec["jamb"]
    vec = Vector(direction).normalized() * spec["thickness"] * s
    bm = bmesh.new()
    bm.from_mesh(part.data)
    rim = [e for e in bm.edges if e.is_boundary and all(on_cut(v.co, spec) for v in e.verts)]
    dup = bmesh.ops.duplicate(bm, geom=bm.faces[:])
    inner_faces = [g for g in dup["geom"] if isinstance(g, bmesh.types.BMFace)]
    inner_verts = {v for f in inner_faces for v in f.verts}
    bmesh.ops.translate(bm, verts=list(inner_verts), vec=vec)
    bmesh.ops.reverse_faces(bm, faces=inner_faces)
    layer = bm.loops.layers.float_vector.get(NORMAL_ATTR)
    for f in inner_faces:
        f.material_index = trim_index
        if layer:
            for loop in f.loops:
                loop[layer] = -loop[layer]  # the inner skin faces into the car
    gap = gap_index(part.data)
    ext = bmesh.ops.extrude_edge_only(bm, edges=rim)
    bmesh.ops.translate(bm, verts=[g for g in ext["geom"] if isinstance(g, bmesh.types.BMVert)], vec=vec)
    for f in (g for g in ext["geom"] if isinstance(g, bmesh.types.BMFace)):
        f.material_index = gap
    bm.to_mesh(part.data)
    bm.free()


def add_box(name, centre_m, size_m, mat, parent=None):
    bpy.ops.mesh.primitive_cube_add(location=to_world(centre_m))
    box = bpy.context.active_object
    box.name = name
    box.scale = (size_m[0] * s / 2, size_m[1] * s / 2, size_m[2] * s / 2)
    bpy.ops.object.transform_apply(scale=True)
    box.data.materials.append(mat)
    if parent:
        mw = box.matrix_world.copy()
        box.parent = parent
        box.matrix_world = mw
    return box


summary = []
for spec in SPECS:
    parts, cut_bodies = [], []
    for o in meshes:
        p = separate(o, spec)
        if p:
            p.name = f"{o.name}_{spec['name']}"
            parts.append(p)
            cut_bodies.append(o)
    if not parts:
        summary.append(f"{spec['name']}: nothing selected")
        continue

    jamb_edges = sum(add_jamb(o, spec) for o in cut_bodies if SOLID.match(o.name))
    solid = [p for p in parts if SOLID.match(p.name)]
    for p in solid:
        thicken(p, spec)

    if spec["hinge"] is None:
        # Tailgate: hinge on the highest point of the cut-out (the roof end).
        top = max((p.matrix_world @ v.co for p in parts for v in p.data.vertices), key=lambda w: w.z)
        hinge_w = Vector((cx, top.y, top.z))
    else:
        hinge_w = to_world(spec["hinge"])
    pivot = bpy.data.objects.new(spec["name"], None)
    bpy.context.scene.collection.objects.link(pivot)
    pivot.location = hinge_w
    bpy.context.view_layer.update()
    windows = 0
    for p in parts:
        if spec.get("window") and any(m and m.name == GLASS for m in p.data.materials):
            p.name = spec["window"] if not windows else f"{spec['window']}_{windows}"
            windows += 1
        mw = p.matrix_world.copy()
        p.parent = pivot
        p.matrix_world = mw
    spec["pivot"] = pivot
    summary.append(f"{spec['name']}: {len(parts)} parts, {len(solid)} thickened, {jamb_edges} jamb edges, "
                   f"windows={windows}, hinge_m={tuple(round(c, 2) for c in to_metres(hinge_w))}")

# Trunk: floor at the bumper line, back wall where the cabin starts, side walls under the
# tailgate's edges. All of it sits inside the body and only shows with the tailgate up.
# Kept well inside the rear corners, which curve forward; the launcher also hides these
# until the tailgate starts to open.
add_box("TrunkFloor", (0, 3.93, 0.745), (1.16, 0.26, 0.02), TRUNK)
add_box("TrunkWall", (0, 3.80, 1.00), (1.16, 0.02, 0.52), TRUNK)
for sx in (1, -1):
    add_box(f"TrunkSide_{'L' if sx > 0 else 'R'}", (0.58 * sx, 3.93, 0.87), (0.02, 0.26, 0.26), TRUNK)

# Charge port: closed recess behind the flap with a socket face in it.
add_box("ChargeRecess", (-0.78, 1.033, 0.836), (0.05, 0.17, 0.115), PORT)
bpy.ops.mesh.primitive_cylinder_add(radius=0.04 * s, depth=0.02 * s, location=to_world((-0.80, 1.033, 0.836)),
                                    rotation=(0, 1.5708, 0))
socket = bpy.context.active_object
socket.name = "ChargeSocket"
socket.data.materials.append(material("Socket", (0.01, 0.01, 0.012), 0.4))

for o in bpy.context.scene.objects:
    if o.type == "MESH":
        restore_normals(o)

bpy.ops.export_scene.gltf(
    filepath=dst,
    export_format="GLB",
    export_draco_mesh_compression_enable=True,
    export_draco_mesh_compression_level=7,
    export_image_format="WEBP",
)
for line in summary:
    print("[split]", line)
print("[split] wrote", dst)

