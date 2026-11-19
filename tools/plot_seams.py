"""Plot tools/model_seams.py output as three gridded views (side, rear, top of the rear half).

    python tools/plot_seams.py out.json out_prefix      -> out_prefix_side.png, _rear.png, _top.png

Red = seam-strip vertices, blue = open edges of the paint panels. Grid every 0.1 m, labelled every
0.5 m, so outline points can be read off in car metres (x + left, y back from the front, z up).
"""
import json
import sys

from PIL import Image, ImageDraw

d = json.load(open(sys.argv[1]))
prefix = sys.argv[2]
K = 420  # pixels per metre


def view(name, a, b, a0, a1, b0, b1, keep, flip_a=False):
    w, h = int((a1 - a0) * K) + 60, int((b1 - b0) * K) + 40
    im = Image.new("RGB", (w, h), "white")
    dr = ImageDraw.Draw(im)

    def px(u, v):
        uu = (a1 - u) if flip_a else (u - a0)
        return 50 + uu * K, h - 30 - (v - b0) * K

    t = a0
    while t <= a1 + 1e-6:
        major = abs(t * 10 - round(t * 10 / 5) * 5) < 1e-6
        dr.line([px(t, b0), px(t, b1)], fill=(150, 150, 150) if major else (228, 228, 228))
        if major:
            dr.text((px(t, b0)[0] - 10, h - 24), f"{t:.1f}", fill="black")
        t = round(t + 0.1, 3)
    t = b0
    while t <= b1 + 1e-6:
        major = abs(t * 10 - round(t * 10 / 5) * 5) < 1e-6
        dr.line([px(a0, t), px(a1, t)], fill=(150, 150, 150) if major else (228, 228, 228))
        if major:
            dr.text((8, px(a0, t)[1] - 6), f"{t:.1f}", fill="black")
        t = round(t + 0.1, 3)
    for e in d["gap"]:
        p, q = e[:3], e[3:]
        if keep(p) and keep(q):
            dr.line([px(p[a], p[b]), px(q[a], q[b])], fill=(60, 110, 230), width=1)
    for p in d["seam"]:
        if keep(p):
            x, y = px(p[a], p[b])
            dr.ellipse([x - 1.5, y - 1.5, x + 1.5, y + 1.5], fill=(220, 30, 30))
    im.save(f"{prefix}_{name}.png")


W, L, H = d["size"]
hw = W / 2
view("side", 1, 2, 0, L, 0, H + 0.1, lambda p: p[0] > hw * 0.55)            # left side, front at the left
view("rear", 0, 2, -hw - 0.05, hw + 0.05, 0, H + 0.1, lambda p: p[1] > L - 0.75)
view("top", 0, 1, -hw - 0.05, hw + 0.05, L - 1.6, L, lambda p: p[2] > 0.6)   # rear deck from above, back at the top
print("wrote", prefix)
