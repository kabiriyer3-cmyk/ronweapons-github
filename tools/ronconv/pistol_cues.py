"""Bone motion over time for the pistol clips -> where the reload / check sounds belong."""
import glob
import sys

import numpy as np

from actorx import read_psa, read_psk
from rig import Skel

E = r"C:\Modding\ron-to-minecraft\private\export"


def f(name, sub=""):
    return [p for p in glob.glob(E + "\\**\\" + name, recursive=True) if sub.lower() in p.lower()][0]


def ang(a, b):
    return np.degrees(np.arccos(np.clip((np.trace(a.T @ b) - 1) / 2, -1, 1)))


def run(gun_file, clips, bones, step=3):
    g = read_psk(f(gun_file))
    sg = Skel(g)
    bind = {b: sg.bind_global[sg.index(b)] for b in bones}
    for clip, sub in clips:
        a = read_psa(f(clip + ".psa", sub))
        print(f"== {clip}: {a.frames} f @ {a.fps:.1f} = {a.frames / a.fps:.2f}s   (pos cm / rot deg vs bind)")
        for fr in range(0, a.frames, step):
            G = sg.pose(a, fr)
            cells = []
            for b in bones:
                m = G[sg.index(b)]
                cells.append(f"{b[4:14]:>10s} {np.linalg.norm(m[:3, 3] - bind[b][:3, 3]):5.1f}/{ang(m[:3, :3], bind[b][:3, :3]):5.1f}")
            print(f"{fr / a.fps:5.2f} " + " ".join(cells))


run("SK_Pistol_G19.psk",
    [("fp_g19_add_tac_reload", "\\weapon\\"), ("fp_g19_add_spd_reload_empty", "\\weapon\\"),
     ("fp_g19_add_mag_check", "\\weapon\\"), ("fp_g19_add_fire_weapon", "\\weapon\\"),
     ("fp_g19_add_fire_empty_60fps_weapon", "\\weapon\\")],
    ["tag_mag_01", "tag_mag_02", "tag_slide", "tag_slidelock"], step=3)
run("SK_Pistol_Python.psk",
    [("fp_python_add_spd_reload_weapon", ""), ("fp_python_add_mag_check_weapon", ""), ("fp_python_add_dryfire_weapon", "")],
    ["tag_chamber_main", "tag_rod", "tag_speedloader", "tag_hammer", "tag_bullets_root"], step=3)
