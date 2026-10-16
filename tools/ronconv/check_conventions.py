"""Oracle for the psk bone convention: under the right one, each vertex sits close to its dominant bone."""
import glob
import sys

import numpy as np

from actorx import bind_globals, read_psk

E = r"C:\Modding\ron-to-minecraft\private\export"
for name in ["SK_Char_SWAT_Heavy_FP_Arms.psk", "SK_WP_Rifle_M4.psk"]:
    path = glob.glob(E + r"\**\\" + name, recursive=True)[0]
    m = read_psk(path)
    dom = {}
    for p, b, w in m.weights:
        if p not in dom or w > dom[p][1]:
            dom[p] = (b, w)
    pts = np.array(sorted(dom))
    bidx = np.array([dom[p][0] for p in pts])
    print(f"== {name}: {len(m.points)} pts, bbox {m.points.min(0).round(1)} .. {m.points.max(0).round(1)}")
    for mode in ["nonroot", "all", "none"]:
        g = bind_globals(m.bones, mode)
        bp = np.array([gg[:3, 3] for gg in g])
        d = np.linalg.norm(m.points[pts] - bp[bidx], axis=1)
        print(f"  {mode:8s} median dist {np.median(d):8.2f}  p90 {np.percentile(d, 90):8.2f}")
    g = bind_globals(m.bones, "nonroot")
    for nm in ["fp_camera", "weapon_right", "hand_RI", "hand_LE", "head", "J_Gun", "tag_brass", "tag_muzzle", "tag_mag_01", "tag_charginghandle"]:
        for i, b in enumerate(m.bones):
            if b.name == nm:
                print(f"  {nm:20s} pos {g[i][:3, 3].round(2)}  x-axis {g[i][:3, 0].round(2)}")
