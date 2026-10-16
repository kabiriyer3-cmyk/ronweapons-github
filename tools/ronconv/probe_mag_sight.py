"""Where are the iron sights, the magazine meshes and the mag bones during RoN anims?"""
import glob
import os

import numpy as np

from actorx import read_psa, read_psk
from rig import Skel

E = r"C:\Modding\ron-to-minecraft\private\export"


def f(name, sub=""):
    return [p for p in glob.glob(E + "\\**\\" + name, recursive=True) if sub.lower() in p.lower()][0]


gun = read_psk(f("SK_WP_Rifle_M4.psk"))
sg = Skel(gun)
print("== gun bones of interest")
for i, b in enumerate(gun.bones):
    if "mag" in b.name.lower() or "sight" in b.name.lower() or b.name in ("tag_bullet", "J_Gun"):
        print(f"  {i:2d} {b.name:24s} parent {gun.bones[b.parent].name if b.parent >= 0 else '-':14s} bindpos {sg.bind_global[i][:3, 3].round(2)}")
mats = gun.materials
P = gun.points
wm = gun.face_mat
for mi, m in enumerate(mats):
    pts = np.unique(gun.wedge_point[gun.faces[wm == mi].flatten()])
    print(f"  material {mi} {m}: {len(pts)} pts bbox {P[pts].min(0).round(2)} .. {P[pts].max(0).round(2)}")
# front sight post: forward (y<-20), near centre line, highest z
fwd = np.where((P[:, 1] < -20) & (np.abs(P[:, 0]) < 0.6))[0]
top = fwd[np.argmax(P[fwd, 2])]
print("front post tip", P[top].round(2))
# rear sight: carry handle points behind y>0, profile of z along y
ch = np.unique(gun.wedge_point[gun.faces[wm == 1].flatten()]) if len(mats) > 1 else np.array([], int)
if len(ch):
    for y0 in range(-6, 14, 2):
        sel = ch[(P[ch, 1] >= y0) & (P[ch, 1] < y0 + 2) & (np.abs(P[ch, 0]) < 0.4)]
        if len(sel):
            print(f"  carry handle y[{y0},{y0 + 2}) centre-line z range {P[sel, 2].min():.2f} .. {P[sel, 2].max():.2f} ({len(sel)} pts)")
# dominant bones of points, to see what is rigid
dom = {}
for p, b, w in gun.weights:
    if p not in dom or w > dom[p][1]:
        dom[p] = (b, w)
cnt = {}
for p, (b, w) in dom.items():
    cnt[gun.bones[b].name] = cnt.get(gun.bones[b].name, 0) + 1
print("points per bone", cnt)

print("== separate meshes")
for n in ["SM_Rif_M4A1_new_mag_static.pskx", "SM_Rif_M4A1_new_mag_static.psk"]:
    for p in glob.glob(E + "\\**\\" + n, recursive=True):
        m = read_psk(p)
        print(" ", os.path.basename(p), len(m.points), "pts", len(m.faces), "tris", m.materials, "bbox", m.points.min(0).round(2), m.points.max(0).round(2))
print([os.path.basename(p) for p in glob.glob(E + "\\**\\*bullet*", recursive=True)])

print("== mag bones over time (gun space, cm)")
for an in ["fp_m4a1_idle_pose_weapon", "fp_m4a1_add_tac_reload", "fp_m4a1_spd_reload_empty", "fp_m4a1_add_mag_check"]:
    a = read_psa(f(an + ".psa", "\\weapon\\"))
    row = []
    for frac in (0, 0.2, 0.4, 0.6, 0.8, 1.0):
        g = sg.pose(a, frac * (a.frames - 1))
        p1 = g[sg.index("tag_mag_01")][:3, 3]
        p2 = g[sg.index("tag_mag_02")][:3, 3]
        row.append(f"m1{p1.round(1)} m2{p2.round(1)}")
    print(f"  {an:28s} " + " | ".join(row))
    names = [n.lower() for n in a.bones]
    for bn in ("tag_mag_01", "tag_mag_02"):
        i = names.index(bn)
        print(f"     {bn} local pos range {a.pos[:, i].min(0).round(1)} .. {a.pos[:, i].max(0).round(1)}")
