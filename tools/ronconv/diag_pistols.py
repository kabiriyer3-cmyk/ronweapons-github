"""Idle-pose oracle for the pistols: hands on the grip? gun in view? where do parked mags / speedloader sit?"""
import numpy as np

from actorx import read_psa, read_psk
from preview import find
from rig import Skel, skin, view_matrix

arms = read_psk(find("SK_Char_SWAT_Heavy_FP_Arms.psk"))
sa = Skel(arms)
W, H, fov = 960, 540, np.radians(70)
f = (H / 2) / np.tan(fov / 2)


def scr(p):
    return (round(W / 2 + f * p[0] / -p[2]), round(H / 2 - f * p[1] / -p[2])) if p[2] < 0 else "behind"


for gun_file, arms_anim, arms_sub, w_anim, w_sub, w_frame, bones in [
    ("SK_Pistol_G19.psk", "fp_g19_idle_high_ready", "\\G19\\arms\\", "fp_g19_idle_pose", "\\G19\\weapon\\", 0,
     ["J_Gun", "tag_muzzle", "tag_slide", "tag_mag_01_socket", "tag_mag_02_socket"]),
    ("SK_Pistol_G19.psk", "fp_g19_add_tac_reload", "\\G19\\arms\\", "fp_g19_add_tac_reload", "\\G19\\weapon\\", 45,
     ["J_Gun", "tag_mag_01_socket", "tag_mag_02_socket"]),
    ("SK_Pistol_Python.psk", "fp_python_idle_pose", "\\Revised\\", "fp_python_add_mag_check_weapon", "", 0,
     ["J_Gun", "tag_muzzle", "tag_speedloader", "mag_01_bullets_socket"]),
    ("SK_Pistol_Python.psk", "fp_python_add_spd_reload", "\\base_male\\", "fp_python_add_spd_reload_weapon", "", 44,
     ["J_Gun", "tag_speedloader", "mag_01_bullets_socket"]),
]:
    gun = read_psk(find(gun_file))
    sg = Skel(gun)
    aa = read_psa(find(arms_anim + ".psa", arms_sub))
    wa = read_psa(find(w_anim + ".psa", w_sub))
    fr_a = min(w_frame, aa.frames - 1)
    ga = sa.pose(aa, fr_a)
    gg = [ga[sa.index("weapon_right")] @ m for m in sg.pose(wa, min(w_frame, wa.frames - 1))]
    V = view_matrix(sa, ga, True)
    print(f"===== {gun_file} | {arms_anim} f{fr_a} + {w_anim} f{w_frame}")
    for nm in bones:
        p = (V @ gg[sg.index(nm)])[:3, 3]
        print(f"  {nm:22s} cam {p.round(3)} screen {scr(p)}")
    gp = skin(gun, sg, gg)
    for nm in ["hand_RI", "hand_LE"]:
        hp = ga[sa.index(nm)][:3, 3]
        print(f"  {nm} -> nearest gun vertex {np.linalg.norm(gp - hp, axis=1).min():.1f} cm")
    gpv = skin(gun, sg, gg, V)
    vis = gpv[gpv[:, 2] < -0.05]
    print(f"  gun verts in front {len(vis)}/{len(gpv)}; cam bbox {vis.min(0).round(2) if len(vis) else '-'} .. {vis.max(0).round(2) if len(vis) else '-'}")
    cam_in_gun = np.linalg.inv(V @ gg[0])[:3, 3] * 1.0
    print(f"  camera in gun space (cm, incl. view scale): {cam_in_gun.round(2)}")
