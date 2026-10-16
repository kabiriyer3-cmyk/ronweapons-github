"""Numeric oracle for a first-person pose: where things land in Minecraft camera space and on screen.

usage: py diag.py <arms_anim> <weapon_anim> <frac> [png]
"""
import sys

import numpy as np
from PIL import Image

from actorx import read_psa, read_psk
from preview import find
from rig import Skel, skin, view_matrix

aa_n, wa_n, frac = sys.argv[1], sys.argv[2], float(sys.argv[3])
arms = read_psk(find("SK_Char_SWAT_Heavy_FP_Arms.psk"))
gun = read_psk(find("SK_WP_Rifle_M4.psk"))
aa = read_psa(find(aa_n + ".psa", "\\arms\\"))
wa = read_psa(find(wa_n + ".psa", "\\weapon\\"))
sa, sg = Skel(arms), Skel(gun)
ga = sa.pose(aa, frac * (aa.frames - 1))
gg0 = sg.pose(wa, frac * (wa.frames - 1))
attach = ga[sa.index("weapon_right")]
gg = [attach @ m for m in gg0]
V = view_matrix(sa, ga, True)
W, H, fov = 960, 540, np.radians(70)
f = (H / 2) / np.tan(fov / 2)


def scr(p):
    return (round(W / 2 + f * p[0] / -p[2]), round(H / 2 - f * p[1] / -p[2])) if p[2] < 0 else None


def cam(m):
    return (V @ m)[:3, 3]


print("arms anim bones matched:", sum(1 for n in aa.bones if n.lower() in sa.lower), "/", len(aa.bones))
print("weapon anim bones matched:", sum(1 for n in wa.bones if n.lower() in sg.lower), "/", len(wa.bones))
for nm in ["hand_RI", "hand_LE", "weapon_right", "fp_camera"]:
    p = cam(ga[sa.index(nm)]); print(f"arms {nm:14s} cam {p.round(3)} screen {scr(p)}")
for nm in ["J_Gun", "tag_muzzle", "tag_stock", "tag_mag_01", "tag_foregrip", "tag_brass", "SightSocket"]:
    p = cam(gg[sg.index(nm)]); print(f"gun  {nm:14s} cam {p.round(3)} screen {scr(p)}")
gp = skin(gun, sg, gg)
for nm in ["hand_RI", "hand_LE"]:
    hp = ga[sa.index(nm)][:3, 3]
    print(f"{nm} -> nearest gun vertex {np.linalg.norm(gp - hp, axis=1).min():.1f} cm")
ap = skin(arms, sa, ga, V); gpv = skin(gun, sg, gg, V)
for label, pts in [("arms", ap), ("gun", gpv)]:
    vis = pts[pts[:, 2] < -0.02]
    print(f"{label}: {len(vis)}/{len(pts)} in front; cam bbox {vis.min(0).round(2)} .. {vis.max(0).round(2)}")
if len(sys.argv) > 4:
    im = np.asarray(Image.open(sys.argv[4]).convert("RGB")).astype(int)
    sky = (np.abs(im - np.array([110, 150, 200])).sum(2) < 6)
    ys, xs = np.where(~sky)
    print(f"png: {100 * (~sky).mean():.1f}% covered, bbox x {xs.min()}..{xs.max()} y {ys.min()}..{ys.max()}")
    for gy in range(0, H, 60):
        print("".join("#" if (~sky)[gy:gy + 60, gx:gx + 40].mean() > 0.5 else ("+" if (~sky)[gy:gy + 60, gx:gx + 40].mean() > 0.1 else ".") for gx in range(0, W, 40)))
