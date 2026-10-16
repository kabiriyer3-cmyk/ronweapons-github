"""For every candidate animation: is it absolute or additive, and do the hands stay on the gun?"""
import glob

import numpy as np

from actorx import read_psa, read_psk
from preview import E, find
from rig import Skel, view_matrix

arms = read_psk(find("SK_Char_SWAT_Heavy_FP_Arms.psk"))
gun = read_psk(find("SK_WP_Rifle_M4.psk"))
sa, sg = Skel(arms), Skel(gun)
idle_w = read_psa(find("fp_m4a1_idle_pose_weapon.psa", "\\weapon\\"))
hr, hl, wr, cam = (sa.index(n) for n in ["hand_RI", "hand_LE", "weapon_right", "fp_camera"])
for path in sorted(glob.glob(E + "\\**\\*.psa", recursive=True)):
    a = read_psa(path)
    kind = "weapon" if a.bones[0] == "J_Gun" else "arms"
    rel = path.split("Animations\\")[-1]
    if kind == "weapon":
        # additive weapon anims have J_Gun near identity translation AND parts near zero offset
        mag = np.linalg.norm(a.pos[:, 1:], axis=2).mean()
        print(f"W {rel:75s} f={a.frames:4d} fps={a.fps:5.1f} mean part offset {mag:6.2f}")
        continue
    out = []
    for frac in (0, 0.25, 0.5, 0.75, 1):
        g = sa.pose(a, frac * (a.frames - 1))
        gg = [g[wr] @ m for m in sg.pose(idle_w, 0)]
        V = view_matrix(sa, g, True)
        grip = gg[sg.index("J_Gun")][:3, 3]
        fore = gg[sg.index("tag_foregrip")][:3, 3]
        dr = np.linalg.norm(g[hr][:3, 3] - grip)
        dl = np.linalg.norm(g[hl][:3, 3] - fore)
        mz = (V @ gg[sg.index("tag_muzzle")])[:3, 3]
        out.append(f"R{dr:5.1f} L{dl:5.1f} mz({mz[0]:+.2f},{mz[1]:+.2f},{mz[2]:+.2f})")
    pel = a.pos[0, 1] if len(a.bones) > 1 else np.zeros(3)
    print(f"A {rel:75s} f={a.frames:4d} fps={a.fps:5.1f} pelvis0={np.round(pel, 1)} | " + " | ".join(out))
