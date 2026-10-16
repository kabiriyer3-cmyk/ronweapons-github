"""Derive sound cue times (seconds) for the M4A1 clips from the weapon bone motion.
mag out = the mag in the well starts leaving it; mag in = a mag arrives back at the well;
bolt = the bolt catch / bolt moves (empty reload); also lists montage assets that might hold FMOD notifies."""
import glob
import json
import re
import sys

import numpy as np

from actorx import read_psa, read_psk
from rig import Skel

E = r"C:\Modding\ron-to-minecraft\private\export"


def f(name, sub=""):
    return [p for p in glob.glob(E + "\\**\\" + name, recursive=True) if sub.lower() in p.lower()][0]


gun = read_psk(f("SK_WP_Rifle_M4.psk"))
sg = Skel(gun)
WELL = sg.bind_global[sg.index("tag_mag_01")][:3, 3]


def track(an):
    a = read_psa(f(an + ".psa", "\\weapon\\"))
    fps = a.frames / max(a.length, 1e-6) if hasattr(a, "length") else 24.0
    rows = []
    for fr in range(a.frames):
        g = sg.pose(a, fr)
        d1 = np.linalg.norm(g[sg.index("tag_mag_01")][:3, 3] - WELL)
        d2 = np.linalg.norm(g[sg.index("tag_mag_02")][:3, 3] - WELL)
        bolt = g[sg.index("tag_bolt")][:3, 3] if sg.index("tag_bolt") >= 0 else np.zeros(3)
        ch = g[sg.index("tag_charginghandle")][:3, 3] if sg.index("tag_charginghandle") >= 0 else np.zeros(3)
        bc = g[sg.index("tag_boltcatch")][:3, :3] if sg.index("tag_boltcatch") >= 0 else np.eye(3)
        rows.append((d1, d2, bolt, ch, bc))
    return a, rows


for an in ["fp_m4a1_add_tac_reload", "fp_m4a1_spd_reload_empty", "fp_m4a1_add_mag_check"]:
    a, rows = track(an)
    dt = 1.0 / 24.0
    print(f"== {an}: {a.frames} frames ({a.frames * dt:.2f}s @24)")
    line = []
    for fr, (d1, d2, bolt, ch, bc) in enumerate(rows):
        b0 = rows[0][2]
        c0 = rows[0][3]
        ang = np.degrees(np.arccos(np.clip((np.trace(rows[0][4].T @ bc) - 1) / 2, -1, 1)))
        line.append(f"{fr * dt:4.2f} m1 {d1:5.1f} m2 {d2:5.1f} bolt {np.linalg.norm(bolt - b0):4.1f} ch {np.linalg.norm(ch - c0):4.1f} bc {ang:4.1f}")
    print("\n".join(line[::2]))

L = open(r"C:\Modding\ron-to-minecraft\private\scan\all_files.txt", encoding="utf-8").read().splitlines()
m = [p for p in L if re.search(r"m4a1|m4_rifle", p, re.I) and re.search(r"montage|/AM_|_AM_|AnimMontage", p, re.I) and p.endswith(".uasset")]
print("montages", len(m))
print("\n".join(m[:60]))
