"""Build the PRIVATE Minecraft resource pack from your own Ready or Not export.

Input : private/export (made by tools/ronexport from your RoN install)
Output: private/RoN-Weapons-Private.zip (+ unpacked folder). Never commit or share it: it contains RoN assets.

usage: py build_pack.py [export_dir] [out_dir] [--install <.minecraft dir>]
"""
import glob
import json
import os
import shutil
import struct
import sys
import zipfile

import numpy as np
from PIL import Image

from actorx import conj, read_psa, read_psk

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
EXPORT = os.path.join(ROOT, "private", "export")
OUT = os.path.join(ROOT, "private")
PACK_NAME = "RoN-Weapons-Private"
NS = "ronweapons"
BASE = f"assets/{NS}/ron/m4a1"
TEX_BASE = f"assets/{NS}/textures/ron/m4a1"
MAX_TEX = 2048

ARMS_MESH = "ReadyOrNot/Content/ReadyOrNot/Character/SWAT/SWAT_Officer/SK_Char_SWAT_Heavy_FP_Arms.psk"
GUN_MESH = "ReadyOrNot/Content/ReadyOrNot/Assets/Weapons/M4_Rifle/Mesh/SK_WP_Rifle_M4.psk"
# RoN attaches the magazine as its own static mesh to mag_01_socket / mag_02_socket
MAG_MESH = "ReadyOrNot/Content/ReadyOrNot/Assets/Weapons/M4_Rifle/Mesh/SM_Rif_M4A1_new_mag_static.psk"
# iron-sight line (gun space, cm): front post tip z = 9.42 at y = -34.2, rear aperture at y ~ +3; eye 8 cm behind it
ADS_EYE = [0.0, 11.0, 9.42]
FP = "ReadyOrNot/Content/ReadyOrNot/Animations/FP/M4A1"
REV = "ReadyOrNot/Content/ReadyOrNot/Animations/Revised/FirstPerson/Weapons/M4_Rifle"
# action -> (arms clip, weapon clip); every clip is absolute (CUE4Parse bakes RoN additives onto the ref pose)
ACTIONS = {
    "draw":         (f"{FP}/arms/fp_m4a1_add_draw", None),
    "idle":         (f"{FP}/arms/fp_gen_idle_01", f"{FP}/weapon/fp_m4a1_idle_pose_weapon"),
    "idle_empty":   (None, f"{FP}/weapon/fp_m4a1_idle_pose_empty"),
    "walk":         (f"{FP}/arms/fp_m4a1_add_walk_loop", None),
    "run":          (f"{REV}/arms/fp_m4a1_add_run_variance", None),
    "fire":         (f"{REV}/arms/fp_m4a1_fire_single_aim_01", f"{REV}/weapon/fp_m4a1_add_fire_single_weapon"),
    "fire_last":    (f"{REV}/arms/fp_m4a1_fire_single_aim_01", f"{REV}/weapon/fp_m4a1_add_fire_single_last_weapon"),
    "reload_tac":   (f"{FP}/arms/fp_m4a1_add_tac_reload", f"{FP}/weapon/fp_m4a1_add_tac_reload"),
    "reload_empty": (f"{FP}/arms/fp_m4a1_spd_reload_empty", f"{FP}/weapon/fp_m4a1_spd_reload_empty"),
    "magcheck":     (f"{FP}/arms/fp_m4a1_add_mag_check", f"{FP}/weapon/fp_m4a1_add_mag_check"),
    "select_auto":  (f"{FP}/arms/fp_m4a1_add_fire_select_auto", None),
    "select_semi":  (f"{FP}/arms/fp_m4a1_add_fire_select_semi", None),
}


def utf(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def exported(rel, ext):
    p = os.path.join(EXPORT, *rel.split("/")) + ext
    if os.path.exists(p):
        return p
    alt = p + "x" if ext == ".psk" else None
    if alt and os.path.exists(alt):
        return alt
    return None


def find_one(name):
    hits = glob.glob(os.path.join(EXPORT, "**", name), recursive=True)
    return hits[0] if hits else None


def material_texture(mat):
    """Base colour texture for a material, from the material JSON CUE4Parse wrote."""
    j = find_one(mat + ".json")
    if not j:
        return None
    tex = json.load(open(j, encoding="utf-8")).get("Textures", {})
    names = []
    for key, v in tex.items():
        name = v.get("ObjectName", "").split("'")[1] if "'" in v.get("ObjectName", "") else key
        names.append((key, name))
    for pref in (lambda k, n: k == "B", lambda k, n: "basecolor" in n.lower(), lambda k, n: n.lower().endswith("_b")):
        for k, n in names:
            if pref(k, n) and find_one(n + ".png"):
                return find_one(n + ".png")
    return None


def write_texture(src, pack_dir, written):
    name = os.path.splitext(os.path.basename(src))[0].lower()
    name = "".join(c if c.isalnum() or c in "_-." else "_" for c in name)
    if name not in written:
        im = Image.open(src).convert("RGBA")
        if max(im.size) > MAX_TEX:
            s = MAX_TEX / max(im.size)
            im = im.resize((max(1, round(im.width * s)), max(1, round(im.height * s))), Image.LANCZOS)
        im.putalpha(255)  # base colour alpha in RoN is not opacity
        dst = os.path.join(pack_dir, *TEX_BASE.split("/"), name + ".png")
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        im.save(dst, optimize=True)
        written.add(name)
    return f"{NS}:textures/ron/m4a1/{name}.png"


def bind_data(mesh):
    """Local bind (pos, fixed quat) and inverse bind globals."""
    locs, glob_ = [], []
    for i, b in enumerate(mesh.bones):
        q = b.quat if b.parent < 0 else conj(b.quat)
        q = q / np.linalg.norm(q)
        m = np.eye(4)
        x, y, z, w = q
        m[:3, :3] = [[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]]
        m[:3, 3] = b.pos
        glob_.append(m if b.parent < 0 else glob_[b.parent] @ m)
        locs.append((b.pos, q))
    return locs, [np.linalg.inv(g) for g in glob_]


def write_mesh(mesh, path, tex_for_material, rigid):
    for i, b in enumerate(mesh.bones):
        assert b.parent < i, "parents must precede children"
    per_point = {}
    for p, b, w in mesh.weights:
        per_point.setdefault(p, []).append((w, b))
    out = bytearray(b"RONM" + struct.pack(">i", 1))
    if not mesh.bones:
        # static mesh (e.g. the magazine): one identity root bone so the renderer always has bone 0
        out += struct.pack(">i", 1) + utf("root") + struct.pack(">i", -1)
        out += struct.pack(">3f", 0, 0, 0) + struct.pack(">4f", 0, 0, 0, 1)
        out += struct.pack(">16f", *np.eye(4).T.flatten())
    else:
        locs, inv = bind_data(mesh)
        out += struct.pack(">i", len(mesh.bones))
        for i, b in enumerate(mesh.bones):
            pos, q = locs[i]
            out += utf(b.name) + struct.pack(">i", b.parent)
            out += struct.pack(">3f", *pos) + struct.pack(">4f", *q)
            out += struct.pack(">16f", *inv[i].T.flatten())  # column-major for JOML
    out += struct.pack(">i", len(mesh.materials))
    for m in mesh.materials:
        out += utf(m) + utf(tex_for_material(m))
    nw = len(mesh.wedge_point)
    normals = mesh.normals
    out += struct.pack(">i", nw)
    for wi in range(nw):
        p = mesh.wedge_point[wi]
        n = normals[p] if normals is not None and len(normals) != nw else (normals[wi] if normals is not None else (0, 0, 1))
        ws = sorted(per_point.get(p, [(1.0, 0)]), reverse=True)
        ws = ws[:1] if rigid else ws[:4]
        tot = sum(w for w, _ in ws) or 1.0
        out += struct.pack(">3f3f2f", *mesh.points[p], *n, *mesh.wedge_uv[wi])
        for k in range(4):
            if k < len(ws):
                out += struct.pack(">hf", ws[k][1], (1.0 if rigid else ws[k][0] / tot))
            else:
                out += struct.pack(">hf", -1, 0.0)
    by_mat = {}
    for fi, f in enumerate(mesh.faces):
        by_mat.setdefault(int(mesh.face_mat[fi]), []).append(f)
    out += struct.pack(">i", len(by_mat))
    for m, faces in sorted(by_mat.items()):
        out += struct.pack(">ii", m, len(faces))
        out += struct.pack(f">{len(faces) * 3}i", *np.array(faces).flatten())
    with open(path, "wb") as fh:
        fh.write(out)
    return nw, len(mesh.faces)


def write_anim(anim, mesh, path):
    names = {b.name.lower() for b in mesh.bones}
    keep = [i for i, n in enumerate(anim.bones) if n.lower() in names]
    out = bytearray(b"RONA" + struct.pack(">i", 1))
    out += struct.pack(">fii", anim.fps, anim.frames, len(keep))
    for i in keep:
        out += utf(anim.bones[i])
    data = np.zeros((anim.frames, len(keep), 7), dtype=">f4")
    for j, i in enumerate(keep):
        data[:, j, 0:3] = anim.pos[:, i]
        q = anim.quat[:, i].copy()
        if i != 0:
            q[:, 0:3] *= -1  # conjugate non-root
        q /= np.linalg.norm(q, axis=1, keepdims=True)
        data[:, j, 3:7] = q
    out += data.tobytes()
    with open(path, "wb") as fh:
        fh.write(out)
    return len(keep), anim.frames / max(anim.fps, 1e-6)


def main():
    global EXPORT, OUT
    args = sys.argv[1:]
    install = None
    if "--install" in args:
        k = args.index("--install"); install = args[k + 1]; del args[k:k + 2]
    if args:
        EXPORT = os.path.abspath(args[0])
    if len(args) > 1:
        OUT = os.path.abspath(args[1])
    pack_dir = os.path.join(OUT, PACK_NAME)
    if os.path.exists(pack_dir):
        shutil.rmtree(pack_dir)
    os.makedirs(os.path.join(pack_dir, *BASE.split("/"), "anims"))
    json.dump({"pack": {"pack_format": 34, "description": "RoN Weapons: PRIVATE, made from your own Ready or Not install. Do not share."}},
              open(os.path.join(pack_dir, "pack.mcmeta"), "w"), indent=2)

    written = set()

    def tex_for(mat):
        src = material_texture(mat)
        if not src:
            print(f"  ! no base colour for material {mat}, using a grey placeholder")
            grey = os.path.join(OUT, "_grey.png")
            Image.new("RGBA", (4, 4), (90, 90, 90, 255)).save(grey)
            src = grey
        return write_texture(src, pack_dir, written)

    arms = read_psk(exported(ARMS_MESH[:-4], ".psk"))
    gun = read_psk(exported(GUN_MESH[:-4], ".psk"))
    print("arms mesh:", write_mesh(arms, os.path.join(pack_dir, *BASE.split("/"), "arms.ronmesh"), tex_for, rigid=False))
    print("gun mesh :", write_mesh(gun, os.path.join(pack_dir, *BASE.split("/"), "gun.ronmesh"), tex_for, rigid=True))
    mag_src = exported(MAG_MESH[:-4], ".psk")
    if mag_src:
        print("mag mesh :", write_mesh(read_psk(mag_src), os.path.join(pack_dir, *BASE.split("/"), "mag.ronmesh"), tex_for, rigid=True))
    else:
        print("  ! magazine mesh not exported, the rifle will have no magazine")
    json.dump({"ads_eye": ADS_EYE}, open(os.path.join(pack_dir, *BASE.split("/"), "rig.json"), "w"))
    for action, (a_rel, w_rel) in ACTIONS.items():
        for kind, rel, mesh in (("arms", a_rel, arms), ("weapon", w_rel, gun)):
            if not rel:
                continue
            src = exported(rel, ".psa")
            if not src:
                print(f"  ! {action}/{kind}: {rel}.psa not exported, skipped")
                continue
            tracks, dur = write_anim(read_psa(src), mesh, os.path.join(pack_dir, *BASE.split("/"), "anims", f"{action}_{kind}.ronanim"))
            print(f"  {action:13s} {kind:6s} {tracks:3d} tracks {dur:5.2f}s")

    zpath = os.path.join(OUT, PACK_NAME + ".zip")
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
        for dp, _, files in os.walk(pack_dir):
            for f in files:
                full = os.path.join(dp, f)
                z.write(full, os.path.relpath(full, pack_dir).replace(os.sep, "/"))
    print("pack:", zpath, f"{os.path.getsize(zpath) / 1e6:.1f} MB")
    if install:
        dst = os.path.join(install, "resourcepacks", PACK_NAME + ".zip")
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(zpath, dst)
        print("installed:", dst)


if __name__ == "__main__":
    main()
