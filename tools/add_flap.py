"""Adds the charge flap outline to <id>.resolved.json for a model without seam strips: the convex
hull (side view) of the body's open edges inside a box, pushed out 4 mm.
    python add_flap.py seams.json parts.resolved.json side y0 y1 z0 z1"""
import json, math, sys
seams = json.load(open(sys.argv[1])); path = sys.argv[2]
sx = int(sys.argv[3]); y0, y1, z0, z1 = map(float, sys.argv[4:8])
pts = [q for e in seams["gap"] for q in (e[:3], e[3:]) if sx * q[0] > 0.8 and y0 <= q[1] <= y1 and z0 <= q[2] <= z1
       and all(y0 <= w[1] <= y1 and z0 <= w[2] <= z1 for w in (e[:3], e[3:]))]
p2 = sorted({(p[1], p[2]) for p in pts})
def half(ps):
    h = []
    for p in ps:
        while len(h) >= 2 and (h[-1][0]-h[-2][0])*(p[1]-h[-2][1]) - (h[-1][1]-h[-2][1])*(p[0]-h[-2][0]) <= 0:
            h.pop()
        h.append(p)
    return h
hull = half(p2)[:-1] + half(p2[::-1])[:-1]
cy, cz = sum(p[0] for p in hull)/len(hull), sum(p[1] for p in hull)/len(hull)
out = []
for y, z in hull:
    d = math.hypot(y-cy, z-cz)
    out.append([round(y + (y-cy)/d*0.004, 4), round(z + (z-cz)/d*0.004, 4)])
r = json.load(open(path, encoding="utf-8"))
r["charge_flap"] = {"outline": out, "inner_x": round(min(abs(p[0]) for p in pts) - 0.08, 3)}
json.dump(r, open(path, "w", encoding="utf-8", newline="\n"))
print("flap", len(pts), "pts ->", len(out), "hull pts", out, r["charge_flap"]["inner_x"])
