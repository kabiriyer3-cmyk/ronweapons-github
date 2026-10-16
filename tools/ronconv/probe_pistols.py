"""Bones, sockets, materials, sight geometry and clip fit for the pistols."""
import glob
import os

import numpy as np

from actorx import read_psa, read_psk
from rig import Skel

E = r"C:\Modding\ron-to-minecraft\private\export"


def f(name):
    h = glob.glob(E + "\\**\\" + name, recursive=True)
    return h[0] if h else None


arms = read_psk(f("SK_Char_SWAT_Heavy_FP_Arms.psk"))
arm_names = {b.name.lower() for b in arms.bones}
for gun_name, statics, anim_dirs in [
    ("SK_Pistol_G19.psk", ["SM_G19_Mag_Static.pskx", "g19_fmj_bullets_static.pskx"], ["\\G19\\", "pistol_sprint"]),
    ("SK_Pistol_Python.psk", ["SM_Python_FMJ_Bullets_Static.pskx"], ["\\PYTHON\\", "\\Python\\"]),
]:
    g = read_psk(f(gun_name))
    sg = Skel(g)
    print("=====", gun_name, len(g.points), "pts", len(g.faces), "tris", "materials", g.materials)
    P = g.points
    print("bbox", P.min(0).round(2), P.max(0).round(2))
    for i, b in enumerate(g.bones):
        print(f"  {i:2d} {b.name:28s} parent {g.bones[b.parent].name if b.parent >= 0 else '-':22s} bind {sg.bind_global[i][:3, 3].round(2)}")
    dom = {}
    for p, b, w in g.weights:
        if p not in dom or w > dom[p][1]:
            dom[p] = (b, w)
    cnt = {}
    for p, (b, w) in dom.items():
        cnt[g.bones[b].name] = cnt.get(g.bones[b].name, 0) + 1
    print("points per bone", cnt)
    # sights: forward is -Y. top profile along the centre line
    c = np.abs(P[:, 0]) < 0.35
    ys = np.linspace(P[:, 1].min(), P[:, 1].max(), 14)
    for y0, y1 in zip(ys[:-1], ys[1:]):
        s = c & (P[:, 1] >= y0) & (P[:, 1] < y1)
        if s.any():
            print(f"   y[{y0:6.1f},{y1:6.1f}) top z {P[s, 2].max():6.2f}")
    for st in statics:
        p = f(st)
        if p:
            m = read_psk(p)
            print("  static", st, len(m.points), "pts", m.materials, "bbox", m.points.min(0).round(2), m.points.max(0).round(2))
    gun_names = {b.name.lower() for b in g.bones}
    for p in sorted(glob.glob(E + "\\**\\*.psa", recursive=True)):
        if not any(d.lower() in p.lower() for d in anim_dirs):
            continue
        a = read_psa(p)
        names = {n.lower() for n in a.bones}
        print(f"  anim {os.path.basename(p):44s} {a.frames:4d}f {a.fps:5.1f}fps {a.frames / a.fps:5.2f}s arms {len(names & arm_names):3d}/{len(names)} gun {len(names & gun_names):2d}")
