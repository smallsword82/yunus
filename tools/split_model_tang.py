"""Cut a car model's moving parts out of its fused body, for any model: the generic version of
split_parts.py (which stays as it is for the Dolphin). Everything model-specific comes from a
parts file, tools/parts/<id>.json, whose outlines tools/trace_outline.py has resolved.

    blender -b -P tools/split_model.py -- viewer/byd_seal.glb out.glb tools/parts/byd_seal.json
    python tools/undraco.py out.glb viewer/byd_seal_parts.glb      # plain geometry for the car

Produces the nodes the launcher animates by name: Door_FL/FR/RL/RR (hinge on the front edge) with
Window_XX inside, Tailgate (hinge on its top edge), Wheel_FL/FR, and - when the parts file has a
"charge_flap" - ChargeFlap, ChargeRecess and ChargeSocket. Moving panels get thickness, body
openings get dark jambs, the trunk gets a floor and walls, as for the Dolphin.

Car metres: x + = car's left, y = distance back from the front bumper, z = height above ground.
"""
import json
import math
import re
import sys

import bmesh
import bpy
from mathutils import Vector

REAL_LENGTH = 4.29
argv = sys.argv[sys.argv.index("--") + 1:]
src, dst = argv[0], argv[1]
C = json.load(open(argv[2], encoding="utf-8"))
R = json.load(open(argv[2].replace(".json", ".resolved.json"), encoding="utf-8"))  # from tools/trace_outline.py
if C.get("charge_flap"):
    if R.get("charge_flap"):
        C["charge_flap"].update(R["charge_flap"])
    else:
        C["charge_flap"] = None  # no seam loop was found for it
WHEELS, UNDER = C["wheels"], C.get("under", "^$")
SOLID = re.compile(C["solid"])
GLASS = re.compile(C["glass"])          # original mesh names whose pieces inside a door are its window
CARD = re.compile(C.get("card", "^$"))  # interior meshes: door cards, trimmed to the door outline
INNER_X = C["inner_x"]                  # doors are everything outboard of this
CARD_X = C.get("card_x", INNER_X + 0.03)
BELT_Z = C.get("belt_z", 0.95)
HINGE_X = C.get("hinge_x", INNER_X + 0.2)
# Where side glass leans far in at its top, inner_x has to sit well inboard, and the interior's
# dash ends and seat edges would then go out with the doors: the interior ("card" meshes) may be
# cut at its own, more outboard x instead.
CARD_INNER_X = C.get("card_inner_x")


class SideOutline:
    """Outboard of |x| = inner_x and inside a closed outline drawn in the side view (y, z)."""

    def __init__(self, sx, outline, inner_x=None):
        self.sx, self.pts, self.inner_x = sx, outline, INNER_X if inner_x is None else inner_x
        # [[z0, x0], [z1, x1]]: above z0 the inner limit leans inboard with the side glass (tumblehome)
        self.top = C.get("inner_top") if inner_x is None else None
        self.edges = list(zip(outline, outline[1:] + outline[:1]))

    def _inner(self, z):
        if not self.top or z <= self.top[0][0]:
            return self.inner_x
        (z0, x0), (z1, x1) = self.top
        return x0 + (x1 - x0) * min(1.0, (z - z0) / (z1 - z0))

    def _seg_dist(self, p, a, b):
        ay, az, by, bz = a[0], a[1], b[0], b[1]
        dy, dz = by - ay, bz - az
        t = max(0.0, min(1.0, ((p.y - ay) * dy + (p.z - az) * dz) / (dy * dy + dz * dz or 1e-12)))
        return math.hypot(p.y - (ay + t * dy), p.z - (az + t * dz))

    def _in_outline(self, p):
        hit = False
        for (ay, az), (by, bz) in self.edges:
            if (az > p.z) != (bz > p.z) and p.y < ay + (p.z - az) * (by - ay) / (bz - az):
                hit = not hit
        return hit

    def inside(self, p, margin=0.0):
        if self.sx * p.x <= self._inner(p.z) - margin:
            return False
        return self._in_outline(p) or (margin > 0 and min(self._seg_dist(p, a, b) for a, b in self.edges) < margin)

    def cuts(self):
        out = [(Vector((self.inner_x * self.sx, 0, 0)), Vector((self.sx, 0, 0)), lambda p: self.inside(p, 0.05))]
        if self.top:
            (z0, x0), (z1, x1) = self.top
            out.append((Vector((x0 * self.sx, 0, z0)), Vector(((z1 - z0) * self.sx, 0, -(x1 - x0))).normalized(),
                        lambda p: p.z > z0 - 0.05 and self.inside(p, 0.05)))
        for a, b in self.edges:
            if math.hypot(b[0] - a[0], b[1] - a[1]) < 1e-6:
                continue
            n = Vector((0, -(b[1] - a[1]), b[0] - a[0])).normalized()
            near = (lambda a, b: lambda p: self.sx * p.x > self._inner(p.z) - 0.1 and self._seg_dist(p, a, b) < 0.05)(a, b)
            out.append((Vector((0, a[0], a[1])), n, near))
        return out


class RearOutline(SideOutline):
    """The tailgate or trunk lid: inside a closed outline drawn in the rear view (x, z), behind a
    front edge. The front edge is y = front_y(|x|) (a hatch: the roof end; a boot lid: the rear
    window's base, which may bow), or behind low_y below split_z and high_y above it."""

    def __init__(self, t):
        half = t["outline_left_half"]
        self.pts = [tuple(p) for p in half] + [(-x, z) for x, z in reversed(half)]
        self.edges = list(zip(self.pts, self.pts[1:] + self.pts[:1]))
        self.low_y, self.high_y, self.split_z = t["low_y"], t.get("high_y", t["low_y"]), t.get("split_z", 9.0)
        self.front = t.get("front_edge")  # [[|x|, y]...] ascending in |x|
        self.near_y = t.get("near_y", self.low_y - 0.25)

    def _yz(self, p):
        return Vector((0, p.x, p.z))

    def _min_y(self, p):
        if self.front:
            ax = abs(p.x)
            f = self.front
            if ax <= f[0][0]:
                return f[0][1]
            for (x0, y0), (x1, y1) in zip(f, f[1:]):
                if ax <= x1:
                    return y0 + (y1 - y0) * (ax - x0) / (x1 - x0)
            return f[-1][1]
        return self.high_y if p.z > self.split_z else self.low_y

    def inside(self, p, margin=0.0):
        if p.y <= self._min_y(p) - margin:
            return False
        q = self._yz(p)
        return self._in_outline(q) or (margin > 0 and min(self._seg_dist(q, a, b) for a, b in self.edges) < margin)

    def cuts(self):
        out = []
        if self.front:
            for (x0, y0), (x1, y1) in zip(self.front, self.front[1:]):
                for sx in (1, -1):
                    a, b = Vector((sx * x0, y0, 0)), Vector((sx * x1, y1, 0))
                    n = Vector((-(b.y - a.y), b.x - a.x, 0)).normalized()
                    near = (lambda a, b: lambda p: min(a.x, b.x) - 0.05 < p.x < max(a.x, b.x) + 0.05
                            and abs(p.y - self._min_y(p)) < 0.05 and self.inside(Vector((p.x, 9, p.z)), 0.05))(a, b)
                    out.append((a, n, near))
        else:
            out.append((Vector((0, self.low_y, 0)), Vector((0, 1, 0)),
                        lambda p: p.z < self.split_z + 0.05 and self.inside(Vector((p.x, 9, p.z)), 0.05)))
        for a, b in self.edges:
            if math.hypot(b[0] - a[0], b[1] - a[1]) < 1e-6:
                continue
            n = Vector((-(b[1] - a[1]), 0, b[0] - a[0])).normalized()
            near = (lambda a, b: lambda p: p.y > self.near_y and self._seg_dist(self._yz(p), a, b) < 0.05)(a, b)
            out.append((Vector((a[0], 0, a[1])), n, near))
        return out


def door_spec(name, sx, outline, hinge_y):
    return dict(name=name, region=SideOutline(sx, [tuple(p) for p in outline]),
                skip=rf"^({WHEELS}|{UNDER}|{C.get('door_skip', 'x^')})",
                hinge=(HINGE_X * sx, hinge_y, 0.9), window=f"Window_{name[-2:]}",
                jamb=((-sx, 0, 0), 0.10), thickness=0.07)


front_hinge_y = min(p[0] for p in R["front_seam"])
rear_hinge_y = min(p[0] for p in R["b_seam"])
SPECS = []
if C.get("tailgate"):
    SPECS.append(dict(name="Tailgate", region=RearOutline(C["tailgate"]),
                      skip=rf"^({WHEELS}|{UNDER}|{C.get('tailgate_skip', 'x^')})", hinge=None,
                      jamb=((0, -1, 0), 0.12), thickness=0.05))
SPECS += [door_spec("Door_FL", 1, R["front_door"], front_hinge_y), door_spec("Door_FR", -1, R["front_door"], front_hinge_y),
          door_spec("Door_RL", 1, R["rear_door"], rear_hinge_y), door_spec("Door_RR", -1, R["rear_door"], rear_hinge_y)]
F = C.get("charge_flap")
if F:
    fy = [p[0] for p in F["outline"]]
    fz = [p[1] for p in F["outline"]]
    SPECS.append(dict(name="ChargeFlap", region=SideOutline(F["side"], [tuple(p) for p in F["outline"]], inner_x=F["inner_x"]),
                      only=F["only"], hinge=(F["side"] * (F["inner_x"] + 0.14), min(fy), (min(fz) + max(fz)) / 2),
                      jamb=((-F["side"], 0, 0), 0.05), thickness=0.012))

bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=src)

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

NORMAL_ATTR = "orig_normal"
for o in meshes:
    attr = o.data.attributes.new(NORMAL_ATTR, "FLOAT_VECTOR", "CORNER")
    attr.data.foreach_set("vector", [c for n in o.data.corner_normals for c in n.vector])


def restore_normals(obj):
    attr = obj.data.attributes.get(NORMAL_ATTR)
    if attr is None:
        return
    normals = [tuple(v.vector) for v in attr.data]
    obj.data.attributes.remove(attr)
    obj.data.normals_split_custom_set(normals)


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
TRUNK = material("TrunkInterior", (0.11, 0.115, 0.12), 1.0)
PORT = material("ChargePort", (0.02, 0.02, 0.022), 0.6)
GAP = material("PanelGap", (0.03, 0.032, 0.035), 0.7)


def gap_index(mesh):
    if GAP.name not in [m.name for m in mesh.materials if m]:
        mesh.materials.append(GAP)
    return [m.name if m else "" for m in mesh.materials].index(GAP.name)


def separate(o, spec):
    region = spec["region"]
    if not wants(o, spec) or not any(region.inside(to_metres(v.co)) for v in o.data.vertices):
        return None
    bm = bmesh.new()
    bm.from_mesh(o.data)
    for pt, n, is_near in region.cuts():
        near = [f for f in bm.faces if any(is_near(to_metres(v.co)) for v in f.verts)]
        if not near:
            continue
        geom = list({v for f in near for v in f.verts}) + list({e for f in near for e in f.edges}) + near
        bmesh.ops.bisect_plane(bm, geom=geom, plane_co=to_world(pt), plane_no=n)
    bmesh.ops.dissolve_degenerate(bm, edges=bm.edges[:], dist=1e-5 * s)
    picked = [f for f in bm.faces if region.inside(to_metres(f.calc_center_median()))]
    if not picked:
        bm.free()
        return None
    whole = len(picked) == len(bm.faces)
    for f in bm.faces:
        f.select_set(f in set(picked))
    bm.to_mesh(o.data)
    bm.free()
    if whole:
        return o  # the whole mesh lies inside (a mirror cap, a handle): it moves as it is
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
                loop[layer] = -loop[layer]
    gap = gap_index(part.data)
    ext = bmesh.ops.extrude_edge_only(bm, edges=rim)
    for v in (g for g in ext["geom"] if isinstance(g, bmesh.types.BMVert)):
        v.co += vec
        p = to_metres(v.co)
        if spec.get("window") and p.z < BELT_Z and abs(p.x) > CARD_X:
            v.co.x = cx + math.copysign(CARD_X, p.x) * s
    for f in (g for g in ext["geom"] if isinstance(g, bmesh.types.BMFace)):
        f.material_index = gap
    bm.to_mesh(part.data)
    bm.free()


def add_box(name, centre_m, size_m, mat):
    bpy.ops.mesh.primitive_cube_add(location=to_world(centre_m))
    box = bpy.context.active_object
    box.name = name
    box.scale = (size_m[0] * s / 2, size_m[1] * s / 2, size_m[2] * s / 2)
    bpy.ops.object.transform_apply(scale=True)
    box.data.materials.append(mat)
    return box


summary = []

# B-pillars: a body-coloured post behind the doors' shared seam, so the roof does not float with
# both doors open (the model is a shell). A copy of the skin either side of the seam, set back.
b = R["b_seam"]
(by0, bz0), (by1, bz1) = b[0], b[-1]
mid = min(b, key=lambda p: abs(p[1] - BELT_Z))
B_PILLAR = [(by0 - 0.03, bz0), (by0 + 0.09, bz0), (mid[0] + 0.08, BELT_Z), (by1 + 0.06, bz1), (by1 - 0.07, bz1), (mid[0] - 0.03, BELT_Z)]


def add_pillar(sx):
    spec = dict(name="BPillar", region=SideOutline(sx, B_PILLAR), only=C.get("pillar_only", C["solid"]),
                jamb=((-sx, 0, 0), 0), thickness=0.05)
    pieces = []
    for o in meshes:
        if not wants(o, spec):
            continue
        tmp = o.copy()
        tmp.data = o.data.copy()
        bpy.context.scene.collection.objects.link(tmp)
        piece = separate(tmp, spec)
        if piece is not tmp:
            bpy.data.objects.remove(tmp, do_unlink=True)
        if piece:
            pieces.append(piece)
    if not pieces:
        return None
    bpy.ops.object.select_all(action="DESELECT")
    for p in pieces:
        p.select_set(True)
    bpy.context.view_layer.objects.active = pieces[0]
    bpy.ops.object.join()
    pillar = bpy.context.active_object
    pillar.name = f"BPillar_{'L' if sx > 0 else 'R'}"
    thicken(pillar, spec)
    pillar.data.materials.clear()
    pillar.data.materials.append(bpy.data.materials[C["paint_mat"]])
    for f in pillar.data.polygons:
        f.material_index = 0
    pillar.location.x -= sx * 0.06 * s
    bpy.ops.object.transform_apply(location=True)
    return pillar


if C.get("pillars", True):
    summary.append(f"pillars: {[bool(add_pillar(sx)) for sx in (1, -1)]}")

for spec in SPECS:
    parts, cut_bodies = [], []
    for o in list(meshes):
        if o.name.startswith("BPillar"):
            continue
        orig_name = o.name
        cut_spec = spec
        if CARD_INNER_X and spec.get("window") and CARD.match(o.name):
            cut_spec = dict(spec, region=SideOutline(spec["region"].sx, spec["region"].pts, inner_x=CARD_INNER_X))
        p = separate(o, cut_spec)
        if p:
            if p is o:
                meshes.remove(o)
            else:
                cut_bodies.append(o)
            p["orig"] = orig_name
            p.name = f"{orig_name}_{spec['name']}"
            parts.append(p)
    if not parts:
        summary.append(f"{spec['name']}: nothing selected")
        continue

    for p in parts:
        if spec.get("window") and CARD.match(p["orig"]):
            bm = bmesh.new()
            bm.from_mesh(p.data)
            out = [f for f in bm.faces if not all(inside(to_metres(v.co), spec, margin=0.015) for v in f.verts)]
            bmesh.ops.delete(bm, geom=out, context="FACES")
            bm.to_mesh(p.data)
            bm.free()

    jamb_edges = sum(add_jamb(o, spec) for o in cut_bodies if SOLID.match(o.name))
    solid = [p for p in parts if SOLID.match(p["orig"])]
    for p in solid:
        thicken(p, spec)

    if spec["hinge"] is None:
        top = max((p.matrix_world @ v.co for p in parts for v in p.data.vertices), key=lambda w: w.z)
        hinge_w = Vector((cx, top.y, top.z))
        if C["tailgate"].get("hinge"):
            hinge_w = to_world([0] + C["tailgate"]["hinge"])
    else:
        hinge_w = to_world(spec["hinge"])
    pivot = bpy.data.objects.new(spec["name"], None)
    bpy.context.scene.collection.objects.link(pivot)
    pivot.location = hinge_w
    bpy.context.view_layer.update()
    windows = 0
    for p in parts:
        if spec.get("window") and GLASS.match(p["orig"]):
            p.name = spec["window"] if not windows else f"{spec['window']}_{windows}"
            windows += 1
        mw = p.matrix_world.copy()
        p.parent = pivot
        p.matrix_world = mw
    spec["pivot"] = pivot
    summary.append(f"{spec['name']}: {len(parts)} parts, {len(solid)} thickened, {jamb_edges} jamb edges, "
                   f"windows={windows}, hinge_m={tuple(round(c, 2) for c in to_metres(hinge_w))}")

# Front wheels steer: each front wheel's faces go under Wheel_FL / Wheel_FR, an empty on its centre.
tyres = [o for o in meshes if re.match(C["tyre"], o.name)]
pts = [to_metres(v.co) for o in tyres for v in o.data.vertices]
fl = [p for p in pts if p.y < REAL_LENGTH / 2 and p.x > 0]
FW = dict(y=(min(p.y for p in fl) + max(p.y for p in fl)) / 2, z=max(p.z for p in fl) / 2,
          x=(min(p.x for p in fl) + max(p.x for p in fl)) / 2, r=max(p.z for p in fl) / 2 * 1.13,
          inner_x=min(p.x for p in fl) - 0.13)


def split_front_wheel(sx):
    centre = to_world((FW["x"] * sx, FW["y"], FW["z"]))
    pivot = bpy.data.objects.new(f"Wheel_F{'L' if sx > 0 else 'R'}", None)
    bpy.context.scene.collection.objects.link(pivot)
    pivot.location = centre
    bpy.context.view_layer.update()

    def in_wheel(p):
        return sx * p.x > FW["inner_x"] and math.hypot(p.y - FW["y"], p.z - FW["z"]) < FW["r"]

    count = 0
    for o in [o for o in meshes if re.match(rf"^({WHEELS})", o.name)]:
        bm = bmesh.new()
        bm.from_mesh(o.data)
        picked = {f for f in bm.faces if in_wheel(to_metres(f.calc_center_median()))}
        if not picked:
            bm.free()
            continue
        if len(picked) == len(bm.faces):  # what is left of a front-only mesh (caliper logo) after the other wheel took its half
            bm.free()
            if o.parent is None and re.search("_Wheel_F[LR]$", o.name) is None:
                mw = o.matrix_world.copy()
                o.parent = pivot
                o.matrix_world = mw
                count += 1
            continue
        for f in bm.faces:
            f.select_set(f in picked)
        bm.to_mesh(o.data)
        bm.free()
        bpy.ops.object.select_all(action="DESELECT")
        o.select_set(True)
        bpy.context.view_layer.objects.active = o
        bpy.ops.object.mode_set(mode="EDIT")
        bpy.ops.mesh.separate(type="SELECTED")
        bpy.ops.object.mode_set(mode="OBJECT")
        part = [x for x in bpy.context.selected_objects if x is not o][0]
        part.name = f"{o.name}_{pivot.name}"
        mw = part.matrix_world.copy()
        part.parent = pivot
        part.matrix_world = mw
        count += 1
    summary.append(f"{pivot.name}: {count} parts, centre_m={tuple(round(c, 3) for c in to_metres(centre))}")


for sx in (1, -1):
    split_front_wheel(sx)

# Trunk: floor, back wall and side walls inside the body, seen only with the lid up.
T = C.get("tailgate")
if T and T.get("trunk"):
    k = T["trunk"]  # {"floor_z", "front_y", "back_y", "half_w", "top_z"}
    cy_, d = (k["front_y"] + k["back_y"]) / 2, k["back_y"] - k["front_y"]
    add_box("TrunkFloor", (0, cy_, k["floor_z"]), (2 * k["half_w"], d, 0.02), TRUNK)
    add_box("TrunkWall", (0, k["front_y"], (k["floor_z"] + k["top_z"]) / 2), (2 * k["half_w"], 0.02, k["top_z"] - k["floor_z"]), TRUNK)
    for sx in (1, -1):
        add_box(f"TrunkSide_{'L' if sx > 0 else 'R'}", (k["half_w"] * sx, cy_, (k["floor_z"] + k["top_z"]) / 2),
                (0.02, d, k["top_z"] - k["floor_z"]), TRUNK)

# Charge port: a closed recess behind the flap with a socket face in it.
if F:
    yc, zc = (min(fy) + max(fy)) / 2, (min(fz) + max(fz)) / 2
    sxf, xin = F["side"], F["inner_x"]
    add_box("ChargeRecess", (sxf * (xin + 0.06), yc, zc), (0.05, max(fy) - min(fy) - 0.01, max(fz) - min(fz) - 0.01), PORT)
    bpy.ops.mesh.primitive_cylinder_add(radius=0.04 * s, depth=0.02 * s, location=to_world((sxf * (xin + 0.09), yc, zc)),
                                        rotation=(0, 1.5708, 0))
    socket = bpy.context.active_object
    socket.name = "ChargeSocket"
    socket.data.materials.append(material("Socket", (0.01, 0.01, 0.012), 0.4))

for o in bpy.context.scene.objects:
    if o.type == "MESH":
        restore_normals(o)

bpy.ops.export_scene.gltf(filepath=dst, export_format="GLB", export_draco_mesh_compression_enable=True,
                          export_draco_mesh_compression_level=7, export_image_format="WEBP")
for line in summary:
    print("[split]", line)
print("[split] wrote", dst)
