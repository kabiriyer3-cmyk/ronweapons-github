"""Render what the Minecraft first-person view should show, straight from the exported psk/psa.

usage: py preview.py <arms_anim> <weapon_anim> <frame_fraction 0..1> <out.png> [follow_camera 0|1]
"""
import glob
import sys

import numpy as np
from PIL import Image, ImageDraw

from actorx import read_psa, read_psk
from rig import Skel, skin, view_matrix

E = r"C:\Modding\ron-to-minecraft\private\export"


def find(name, sub=""):
    hits = [p for p in glob.glob(E + "\\**\\" + name, recursive=True) if sub.lower() in p.lower()]
    if not hits:
        raise SystemExit(f"not found: {name} ({sub})")
    return hits[0]


def tex(name):
    return np.asarray(Image.open(find(name + ".png")).convert("RGB"), dtype=np.float64)


def main():
    arms_anim_name, weapon_anim_name, frac, out = sys.argv[1], sys.argv[2], float(sys.argv[3]), sys.argv[4]
    follow = len(sys.argv) < 6 or sys.argv[5] != "0"
    arms = read_psk(find("SK_Char_SWAT_Heavy_FP_Arms.psk"))
    gun = read_psk(find("SK_WP_Rifle_M4.psk"))
    aa = read_psa(find(arms_anim_name + ".psa", "\\arms\\"))
    wa = read_psa(find(weapon_anim_name + ".psa", "\\weapon\\"))
    sa, sg = Skel(arms), Skel(gun)
    ga = sa.pose(aa, frac * (aa.frames - 1))
    gg = sg.pose(wa, frac * (wa.frames - 1))
    attach = ga[sa.index("weapon_right")]
    gg = [attach @ m for m in gg]
    V = view_matrix(sa, ga, follow)

    textures = {
        "M_SWAT_Officer_Firstperson_Heavy": tex("T_SwatHeavy_Arms_01_B"),
        "Weapon_1P_M4A1_INST": tex("M4_M4_Rifle_BaseColor"),
        "Weapon_1P_M4A1_CARRYHANDLE_INST": tex("LOW_LOW_BaseColor"),
    }
    W, H, fov = 960, 540, np.radians(70)
    f = (H / 2) / np.tan(fov / 2)
    polys = []
    for mesh, skel, glob_ in [(arms, sa, ga), (gun, sg, gg)]:
        pts = skin(mesh, skel, glob_, V)
        for fi, (a, b, c) in enumerate(mesh.faces):
            pa, pb, pc = (pts[mesh.wedge_point[a]], pts[mesh.wedge_point[b]], pts[mesh.wedge_point[c]])
            z = (pa[2] + pb[2] + pc[2]) / 3
            if max(pa[2], pb[2], pc[2]) > -0.02:
                continue
            n = np.cross(pb - pa, pc - pa)
            nn = np.linalg.norm(n)
            if nn == 0:
                continue
            n /= nn
            mat = mesh.materials[mesh.face_mat[fi]]
            t = textures.get(mat)
            if t is not None:
                uv = (mesh.wedge_uv[a] + mesh.wedge_uv[b] + mesh.wedge_uv[c]) / 3
                th, tw, _ = t.shape
                col = t[int(uv[1] % 1 * (th - 1)), int(uv[0] % 1 * (tw - 1))]
            else:
                col = np.array([200, 160, 60.0])
            shade = 0.35 + 0.65 * abs(np.dot(n, np.array([0.3, 0.8, 0.5]) / np.linalg.norm([0.3, 0.8, 0.5])))
            proj = [(W / 2 + f * p[0] / -p[2], H / 2 - f * p[1] / -p[2]) for p in (pa, pb, pc)]
            polys.append((z, proj, tuple(int(v) for v in np.clip(col * shade, 0, 255))))
    polys.sort(key=lambda x: x[0])
    img = Image.new("RGB", (W, H), (110, 150, 200))
    d = ImageDraw.Draw(img)
    for _, proj, col in polys:
        d.polygon(proj, fill=col)
    d.line([(W / 2 - 8, H / 2), (W / 2 + 8, H / 2)], fill=(255, 255, 255))
    d.line([(W / 2, H / 2 - 8), (W / 2, H / 2 + 8)], fill=(255, 255, 255))
    img.save(out)
    print("saved", out, "polys", len(polys), "arms frames", aa.frames, aa.fps, "weapon frames", wa.frames, wa.fps)


if __name__ == "__main__":
    main()
