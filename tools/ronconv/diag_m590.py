"""M590 oracle: idle fit, camera in gun space, pump timing (fire), shell path + hand distance (reload clips)."""
import numpy as np

from actorx import read_psa, read_psk
from preview import find
from rig import Skel, skin, view_matrix

arms = read_psk(find("SK_Char_SWAT_Heavy_FP_Arms.psk"))
sa = Skel(arms)
gun = read_psk(find("SK_Shotgun_M590_Assault.psk"))
sg = Skel(gun)
W, H, fov = 960, 540, np.radians(70)
f = (H / 2) / np.tan(fov / 2)


def scr(p):
    return (round(W / 2 + f * p[0] / -p[2]), round(H / 2 - f * p[1] / -p[2])) if p[2] < 0 else "behind"


def pose(arms_anim, a_sub, w_anim, fa, fw):
    aa = read_psa(find(arms_anim + ".psa", a_sub))
    wa = read_psa(find(w_anim + ".psa", "\\ron_m590\\"))
    ga = sa.pose(aa, min(fa, aa.frames - 1))
    gl = sg.pose(wa, min(fw, wa.frames - 1))
    gg = [ga[sa.index("weapon_right")] @ m for m in gl]
    return aa, wa, ga, gl, gg


aa, wa, ga, gl, gg = pose("fp_m590_idle_pose", "\\M590\\base_male\\", "fp_m590_add_fire_weapon", 0, 0)
V = view_matrix(sa, ga, True)
print("== idle")
for nm in ["J_Gun", "tag_muzzle", "tag_sight", "tag_bullet_socket", "tag_charginghandle"]:
    p = (V @ gg[sg.index(nm)])[:3, 3]
    print(f"  {nm:20s} cam {p.round(3)} screen {scr(p)}")
gp = skin(gun, sg, gg)
for nm in ["hand_RI", "hand_LE"]:
    hp = ga[sa.index(nm)][:3, 3]
    print(f"  {nm} -> nearest gun vertex {np.linalg.norm(gp - hp, axis=1).min():.1f} cm")
print("  camera in gun space:", np.linalg.inv(V @ gg[0])[:3, 3].round(2))
print("  weapon clip bones:", wa.bones)

for arms_anim, w_anim in [("fp_m590_add_fire", "fp_m590_add_fire_weapon"), ("fp_M590_add_reload_start_A", "fp_M590_add_reload_start_A_weapon"),
                          ("fp_M590_add_reload_loop_A", "fp_M590_add_reload_loop_A_weapon"), ("fp_M590_add_reload_end_A", "fp_M590_add_reload_end_A_weapon"),
                          ("fp_M590_add_reload_start_empty_A", "fp_M590_add_reload_start_empty_A_weapon"), ("fp_M590_add_magcheck", "fp_M590_add_magcheck_weapon")]:
    aa = read_psa(find(arms_anim + ".psa", "\\M590\\base_male\\"))
    wa = read_psa(find(w_anim + ".psa", "\\ron_m590\\"))
    print(f"== {arms_anim} {aa.frames}f @{aa.fps:.1f} | weapon {wa.frames}f")
    step = max(1, wa.frames // 12)
    for fr in list(range(0, wa.frames, step)) + [wa.frames - 1]:
        ga = sa.pose(aa, min(fr, aa.frames - 1))
        gl = sg.pose(wa, fr)
        gg = [ga[sa.index("weapon_right")] @ m for m in gl]
        ch = gl[sg.index("tag_charginghandle")][:3, 3]
        bu = gl[sg.index("tag_bullet_socket")][:3, 3]
        hl = ga[sa.index("hand_LE")][:3, 3]
        bw = gg[sg.index("tag_bullet_socket")][:3, 3]
        gp = skin(gun, sg, gg)
        dl = np.linalg.norm(gp - hl, axis=1).min()
        dr = np.linalg.norm(gp - ga[sa.index("hand_RI")][:3, 3], axis=1).min()
        print(f"  t {fr / wa.fps:5.2f}s pump y {ch[1]:7.2f} | shell(gun) {bu.round(1)} shell-handL {np.linalg.norm(bw - hl):5.1f} | handL-gun {dl:4.1f} handR-gun {dr:4.1f}")
