"""Turn rough guide lines into exact part outlines that run on the model's own panel seams.

    python tools/trace_outline.py seams.json tools/parts/byd_seal.json out_prefix

seams.json comes from tools/model_seams.py. The parts file holds, per seam, a rough "guide" polyline
read off the plot (tools/plot_seams.py) and what to snap it to: "seam" (the seam-strip vertices),
"gap" (open edges of the paint panels) or "none". Every guide is walked in small steps and each
step is pulled onto the nearest seam/gap points within "tol" metres; where the model draws no
line there, the guide itself is kept. The resolved outlines are written next to the parts file as
<id>.resolved.json (what tools/split_model.py cuts along) and drawn over the side plot in green
(out_prefix_outline.png) to check by eye.

Side-view points are (y, z): y back from the front bumper, z up; the left side is traced and
mirrored. Doors: front = b_seam + front_top + front_seam reversed; rear = rear_seam + rear_top +
b_seam reversed. Seams run bottom to top; front_top from the B seam's top forward, rear_top from
the rear seam's top forward to the B seam's top.
"""
import json
import math
import sys

from PIL import Image, ImageDraw

seams = json.load(open(sys.argv[1]))
cfg_path = sys.argv[2]
cfg = json.load(open(cfg_path, encoding="utf-8"))
prefix = sys.argv[3]
W, L, H = seams["size"]
hw = W / 2
side = lambda p: p[0] > hw * 0.55
pools = {
    "seam": [(p[1], p[2]) for p in seams["seam"] if side(p)],
    "gap": [(q[1], q[2]) for e in seams["gap"] for q in (e[:3], e[3:]) if side(q)],
    "none": [],
}
# Gap edges are long: add points along them so a guide can snap anywhere on the line.
for e in seams["gap"]:
    p, q = e[:3], e[3:]
    if side(p) and side(q):
        n = int(math.hypot(q[1] - p[1], q[2] - p[2]) / 0.01)
        pools["gap"] += [(p[1] + (q[1] - p[1]) * i / (n + 1), p[2] + (q[2] - p[2]) * i / (n + 1)) for i in range(1, n + 1)]


def walk(guide, step=0.025):
    out = [tuple(guide[0])]
    for a, b in zip(guide, guide[1:]):
        d = math.hypot(b[0] - a[0], b[1] - a[1])
        n = max(1, int(d / step))
        out += [(a[0] + (b[0] - a[0]) * i / n, a[1] + (b[1] - a[1]) * i / n) for i in range(1, n + 1)]
    return out


def snap(spec):
    pool = pools[spec.get("snap", "seam")]
    tol = spec.get("tol", 0.035)
    dz = spec.get("dz", 0.0)
    out = []
    for g in walk(spec["guide"]):
        near = [p for p in pool if abs(p[0] - g[0]) < tol and abs(p[1] - g[1]) < tol and math.hypot(p[0] - g[0], p[1] - g[1]) < tol]
        if near:
            c = min(near, key=lambda p: math.hypot(p[0] - g[0], p[1] - g[1]))
            grp = [p for p in near if math.hypot(p[0] - c[0], p[1] - c[1]) < 0.012]  # both rows of a seam strip
            pt = (sum(p[0] for p in grp) / len(grp), sum(p[1] for p in grp) / len(grp) + dz)
        else:
            pt = (g[0], g[1] + dz)
        if not out or math.hypot(pt[0] - out[-1][0], pt[1] - out[-1][1]) > 0.012:
            out.append((round(pt[0], 4), round(pt[1], 4)))
    # The ends stay where the guide puts them only if nothing was found there.
    return out


r = {k: snap(cfg[k]) for k in ("front_seam", "b_seam", "rear_seam", "front_top", "rear_top")}
drop = cfg.get("extend_down", 0.03)  # below the sill's paint edge, so no sliver of sill stays on the body
for k in ("front_seam", "b_seam", "rear_seam"):
    y, z = r[k][0]
    r[k].insert(0, (y, round(z - drop, 4)))
front = r["b_seam"] + r["front_top"][1:-1] + r["front_seam"][::-1]
rear = r["rear_seam"] + r["rear_top"][1:-1] + r["b_seam"][::-1]
resolved = {"front_door": front, "rear_door": rear, "b_seam": r["b_seam"],
            "front_seam": r["front_seam"], "rear_seam": r["rear_seam"]}
# Charge flap: the closed seam loop drawn on one side only, inside "box" [y0, y1, z0, z1] of the
# side view. Its points are put in order around their centre; inner_x sits just inboard of them.
flap = cfg.get("charge_flap")
if flap and flap.get("box"):
    y0, y1, z0, z1 = flap["box"]
    sx = flap["side"]
    pts = [p for p in seams["seam"] if sx * p[0] > hw * 0.55 and y0 <= p[1] <= y1 and z0 <= p[2] <= z1]
    if len(pts) >= 6:
        cy_, cz_ = sum(p[1] for p in pts) / len(pts), sum(p[2] for p in pts) / len(pts)
        ring = sorted(pts, key=lambda p: math.atan2(p[2] - cz_, p[1] - cy_))
        outline = []
        for p in ring:
            q = (round(p[1], 4), round(p[2], 4))
            if not outline or math.hypot(q[0] - outline[-1][0], q[1] - outline[-1][1]) > 0.012:
                outline.append(q)
        resolved["charge_flap"] = {"outline": outline, "inner_x": round(min(abs(p[0]) for p in pts) - 0.08, 3)}
        print("charge flap", len(outline), "pts, inner_x", resolved["charge_flap"]["inner_x"])
    else:
        print("charge flap: no seam loop found in the box")
# The parts file stays as written by hand; the traced outlines go next to it.
json.dump(resolved, open(cfg_path.replace(".json", ".resolved.json"), "w", encoding="utf-8", newline="\n"))

K = 420
im = Image.open(f"{prefix}_side.png").convert("RGB")
dr = ImageDraw.Draw(im)
h = im.size[1]
px = lambda p: (50 + p[0] * K, h - 30 - p[1] * K)
for poly, col in ((front, (0, 150, 0)), (rear, (150, 0, 150))):
    dr.line([px(p) for p in poly + poly[:1]], fill=col, width=2)
im.save(f"{prefix}_outline.png")
print("front door", len(front), "pts; rear door", len(rear), "pts ->", f"{prefix}_outline.png")
