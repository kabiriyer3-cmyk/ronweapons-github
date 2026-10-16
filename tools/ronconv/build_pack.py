"""Build the PRIVATE Minecraft resource pack from your own Ready or Not export (all weapons).

Input : private/export (tools/ronexport) and private/sound_pack (sound_pack.py, optional)
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
MAX_TEX = 2048

ARMS_MESH = "ReadyOrNot/Content/ReadyOrNot/Character/SWAT/SWAT_Officer/SK_Char_SWAT_Heavy_FP_Arms"
A = "ReadyOrNot/Content/ReadyOrNot/Animations"
M4FP, M4REV = f"{A}/FP/M4A1", f"{A}/Revised/FirstPerson/Weapons/M4_Rifle"
G19FP, PYFP, PYREV = f"{A}/FP/G19", f"{A}/FP/PYTHON", f"{A}/Revised/FirstPerson/Weapons/Python"
M590A, M590W = f"{A}/FP/M590/base_male", f"{A}/FP/M590/ron_m590"
ATT = "ReadyOrNot/Content/ReadyOrNot/Assets/Weapons/Attachments"

# Optics: mesh on a gun socket. The ADS eye goes through the centre of the RoN reticle plane ("Optic_Picture" material);
# our own reticle texture is drawn there (dot / holo ring), lens glass and invisible helpers are skipped.
# ids must match WeaponDef.optics in the mod. eye_y None = keep the hip-fire eye distance (pistols).

# Clip spec: path, or (path, "first"|"last") to take a single pose from a clip.
# Every clip is absolute (CUE4Parse bakes RoN additives onto the ref pose).
# Clips RoN stores with ACL compression cannot be exported yet; those slots use a pose from another clip or idle.
WEAPONS = {
    "m4a1": {
        "gun": "ReadyOrNot/Content/ReadyOrNot/Assets/Weapons/M4_Rifle/Mesh/SK_WP_Rifle_M4",
        # iron-sight line (gun space, cm): front post tip z = 9.42 at y = -34.2, rear aperture y ~ +3; eye 8 cm behind
        "ads_eye": [0.0, 11.0, 9.42],
        "attachments": [
            {"mesh": "ReadyOrNot/Content/ReadyOrNot/Assets/Weapons/M4_Rifle/Mesh/SM_Rif_M4A1_new_mag_static",
             "file": "mag", "sockets": ["mag_01_socket", "mag_02_socket"]},
        ],
        "optics": [
            {"id": "exps3", "mesh": f"{ATT}/Optic_EPXS3/Holosight_EXPS3", "socket": "EXPS3_Socket", "style": "holo", "hide": ["Weapon_1P_M4A1_CARRYHANDLE_INST"]},
            {"id": "m5b", "mesh": f"{ATT}/Optic_M5B/sk_optic_m5", "socket": "M5B_Socket", "style": "dot", "hide": ["Weapon_1P_M4A1_CARRYHANDLE_INST"]},
            {"id": "t2", "mesh": f"{ATT}/Optic_MicroT2/SK_MicroT2_TallMount", "socket": "MicroT2Raised_Socket", "style": "dot", "hide": ["Weapon_1P_M4A1_CARRYHANDLE_INST"]},
        ],
        "suppressor": {"mesh": f"{ATT}/Suppressor_SOCOM338/sk_suppressor_socom338", "socket": "socom338_socket"},
        "tp": {"back": 0.30},
        "actions": {
            "draw":         (f"{M4FP}/arms/fp_m4a1_add_draw", None),
            "idle":         (f"{M4FP}/arms/fp_gen_idle_01", f"{M4FP}/weapon/fp_m4a1_idle_pose_weapon"),
            "idle_empty":   (None, f"{M4FP}/weapon/fp_m4a1_idle_pose_empty"),
            "walk":         (f"{M4FP}/arms/fp_m4a1_add_walk_loop", None),
            "run":          (f"{M4REV}/arms/fp_m4a1_add_run_variance", None),
            "fire":         (f"{M4REV}/arms/fp_m4a1_fire_single_aim_01", f"{M4REV}/weapon/fp_m4a1_add_fire_single_weapon"),
            "fire_last":    (f"{M4REV}/arms/fp_m4a1_fire_single_aim_01", f"{M4REV}/weapon/fp_m4a1_add_fire_single_last_weapon"),
            "reload_tac":   (f"{M4FP}/arms/fp_m4a1_add_tac_reload", f"{M4FP}/weapon/fp_m4a1_add_tac_reload"),
            "reload_empty": (f"{M4FP}/arms/fp_m4a1_spd_reload_empty", f"{M4FP}/weapon/fp_m4a1_spd_reload_empty"),
            "magcheck":     (f"{M4FP}/arms/fp_m4a1_add_mag_check", f"{M4FP}/weapon/fp_m4a1_add_mag_check"),
            "select_auto":  (f"{M4FP}/arms/fp_m4a1_add_fire_select_auto", None),
            "select_semi":  (f"{M4FP}/arms/fp_m4a1_add_fire_select_semi", None),
        },
    },
    "g19": {
        "gun": "ReadyOrNot/Content/ReadyOrNot/Assets/Weapons/G19/Meshes/SK_Pistol_G19",
        # front sight top z 3.62 (y -9), rear sight top z 3.65 (y 7); null y = keep the hip-fire distance to the eye
        "ads_eye": [0.0, None, 3.64],
        "attachments": [
            {"mesh": "ReadyOrNot/Content/ReadyOrNot/Assets/Weapons/G19/Meshes/SM_G19_Mag_Static",
             "file": "mag", "sockets": ["tag_mag_01_socket", "tag_mag_02_socket"]},
            {"mesh": "ReadyOrNot/Content/ReadyOrNot/Assets/Weapons/G19/Meshes/g19_fmj_bullets_static",
             "file": "rounds", "sockets": ["tag_mag_01_bullets_socket", "tag_mag_02_bullets_socket"]},
        ],
        "optics": [
            {"id": "rmr", "mesh": f"{ATT}/Optic_RMR/sk_optic_rmr_pistol", "socket": "RMRPistol_Socket", "style": "dot"},
            {"id": "sro", "mesh": f"{ATT}/Optic_SRO/sk_optic_sro_glock", "socket": "SROPistol_Socket", "style": "dot"},
        ],
        "suppressor": {"mesh": f"{ATT}/Mesh/Pistol_SuppressorSmall", "socket": "supressor_socket"},
        "tp": {"back": 0.0},
        "actions": {
            "draw":         (f"{G19FP}/arms/fp_g19_add_draw", None),
            # fp_g19_idle_high_ready points the gun at the sky and the real idle is ACL-only: frame 0 of the tac reload is
            # the same pose every G19 clip starts and ends in (checked with diag_pistols.py)
            "idle":         ((f"{G19FP}/arms/fp_g19_add_tac_reload", "first"), f"{G19FP}/weapon/fp_g19_idle_pose"),
            "idle_empty":   (None, (f"{G19FP}/weapon/fp_g19_add_fire_empty_60fps_weapon", "last")),
            "run":          (f"{A}/FP/SHARED/pistol_sprint/fp_g19_add_loco_sprint_fwd", None),
            # no exportable arms fire clip for the G19: idle arms + procedural kick, the slide still cycles
            "fire":         (None, f"{G19FP}/weapon/fp_g19_add_fire_weapon"),
            "fire_last":    (None, f"{G19FP}/weapon/fp_g19_add_fire_empty_60fps_weapon"),
            "reload_tac":   (f"{G19FP}/arms/fp_g19_add_tac_reload", f"{G19FP}/weapon/fp_g19_add_tac_reload"),
            "reload_empty": (f"{G19FP}/arms/fp_g19_add_spd_reload_empty", f"{G19FP}/weapon/fp_g19_add_spd_reload_empty"),
            "magcheck":     (f"{G19FP}/arms/fp_g19_add_mag_check", f"{G19FP}/weapon/fp_g19_add_mag_check"),
        },
    },
    "python": {
        "gun": "ReadyOrNot/Content/ReadyOrNot/Assets/Weapons/Python/Meshes/SK_Pistol_Python",
        # front ramp top z 5.66 (y -17), rear notch z 5.61 (y 0)
        "ads_eye": [0.0, None, 5.63],
        "attachments": [
            {"mesh": "ReadyOrNot/Content/ReadyOrNot/Assets/Weapons/Python/Meshes/SM_Python_FMJ_Bullets_Static",
             "file": "rounds", "sockets": ["mag_01_bullets_socket"]},
        ],
        "optics": [
            {"id": "t2", "mesh": f"{ATT}/Optic_MicroT2/V2/SK_T2_shortmount", "socket": "PythonT2_socket", "style": "dot"},
        ],
        "tp": {"back": 0.0},
        "actions": {
            # weapon idle is ACL-only: frame 0 of the cylinder check has the cylinder shut and the speedloader parked
            "idle":         (f"{PYREV}/arms/fp_python_idle_pose", (f"{PYFP}/ron_python_fix/fp_python_add_mag_check_weapon", "first")),
            "fire":         (f"{PYREV}/arms/fp_m92fs_add_fire_single_python", None),
            "reload_tac":   (f"{PYFP}/base_male/fp_python_add_spd_reload", f"{PYFP}/ron_python_fix/fp_python_add_spd_reload_weapon"),
            "reload_empty": (f"{PYFP}/base_male/fp_python_add_spd_reload", f"{PYFP}/ron_python_fix/fp_python_add_spd_reload_weapon"),
            "magcheck":     (f"{PYFP}/base_male/fp_python_add_mag_check", f"{PYFP}/ron_python_fix/fp_python_add_mag_check_weapon"),
        },
    },
    "m590": {
        "gun": "ReadyOrNot/Content/ReadyOrNot/Assets/Weapons/M590/Meshes/SK_Shotgun_M590_Assault",
        # front blade top z 5.27 (y -63.5), ghost ring at y -3.3 around z 5.3; eye 9 cm behind the ring
        "ads_eye": [0.0, 6.0, 5.3],
        "attachments": [
            # the shell the left hand carries; parked inside the stock (y > 3.5) between inserts -> hidden there
            {"mesh": "ReadyOrNot/Content/ReadyOrNot/Assets/Weapons/M590/Meshes/Shells_Scaled/m590_buckshot_static_shell",
             "file": "shell", "sockets": ["tag_bullet_socket"], "hide_y_above": 3.5},
        ],
        "tp": {"back": 0.35},
        "actions": {
            # weapon idle / draw clips are ACL-only: frame 0 of the fire clip is the rest pose (pump forward, shell in the tube)
            "draw":               (f"{M590A}/fp_M590_add_draw", None),
            "idle":               (f"{M590A}/fp_m590_idle_pose", (f"{M590W}/fp_m590_add_fire_weapon", "first")),
            "fire":               (f"{M590A}/fp_m590_add_fire", f"{M590W}/fp_m590_add_fire_weapon"),
            "fire_ads":           (f"{M590A}/fp_m590_add_fire_ads", f"{M590W}/fp_m590_add_fire_weapon"),
            "reload_start":       (f"{M590A}/fp_M590_add_reload_start_A", f"{M590W}/fp_M590_add_reload_start_A_weapon"),
            "reload_start_empty": (f"{M590A}/fp_M590_add_reload_start_empty_A", f"{M590W}/fp_M590_add_reload_start_empty_A_weapon"),
            "reload_loop":        (f"{M590A}/fp_M590_add_reload_loop_A", f"{M590W}/fp_M590_add_reload_loop_A_weapon"),
            "reload_end":         (f"{M590A}/fp_M590_add_reload_end_A", f"{M590W}/fp_M590_add_reload_end_A_weapon"),
            "magcheck":           (f"{M590A}/fp_M590_add_magcheck", f"{M590W}/fp_M590_add_magcheck_weapon"),
        },
    },
}

RETICLE_RGB = (255, 36, 24)
SKIP_MATERIAL = ("glass", "invisible")


def socket_matrix(gun, name):
    """bind global of a gun socket, relative to J_Gun (bone 0)"""
    _, inv = bind_data(gun)
    G = [np.linalg.inv(i) for i in inv]
    for i, b in enumerate(gun.bones):
        if b.name.lower() == name.lower():
            return np.linalg.inv(G[0]) @ G[i]
    return None


def prepare_optic(mesh, S, style, eye_dist_cm, png_path):
    """Marks lens / helper materials #skip and the reticle plane #reticle (planar UVs, centre = (0.5, 0.5)).
    Returns the reticle centre in gun space (cm) or None. The reticle texture is our own drawing, sized in angle at the eye."""
    pts = np.array(mesh.points, dtype=np.float64)
    wp = np.array(mesh.wedge_point)
    fm = np.array(mesh.face_mat)
    F = np.array(mesh.faces)
    uv = np.array(mesh.wedge_uv, dtype=np.float64).copy()
    centre = None
    names = list(mesh.materials)
    for mi, mn in enumerate(names):
        low = mn.lower()
        if any(k in low for k in SKIP_MATERIAL):
            names[mi] = "#skip " + mn
        elif "picture" in low and centre is None:
            wedges = np.unique(F[fm == mi].flatten())
            if not len(wedges):
                continue
            q = pts[wp[wedges]]
            lo, hi = q.min(0), q.max(0)
            c = (lo + hi) / 2
            ax = np.argsort(hi - lo)[::-1][:2]          # the two in-plane axes
            size = float((hi - lo)[ax].max()) or 1.0
            for w in wedges:
                p = pts[wp[w]]
                uv[w] = (0.5 + (p[ax[0]] - c[ax[0]]) / size, 0.5 - (p[ax[1]] - c[ax[1]]) / size)
            draw_reticle(png_path, style, size, eye_dist_cm)
            centre = (S @ np.append(c, 1.0))[:3]
            names[mi] = "#reticle " + mn
        elif "picture" in low:
            names[mi] = "#skip " + mn
    mesh.materials = names
    mesh.wedge_uv = uv
    return centre


def draw_reticle(path, style, size_cm, dist_cm, res=512):
    """dot: 1 MRAD-ish dot; holo: 65 MOA ring + 1 MOA-ish dot (exaggerated a bit so it reads at Minecraft resolutions)"""
    px = res / size_cm
    deg = lambda a: dist_cm * np.tan(np.radians(a)) * px     # angle -> pixels on the plane
    y, x = np.mgrid[0:res, 0:res]
    r = np.hypot(x - res / 2 + 0.5, y - res / 2 + 0.5)
    def disc(radius):
        return np.clip(radius + 0.75 - r, 0, 1.5) / 1.5
    a = disc(max(1.6, deg(0.09)))
    if style == "holo":
        rr, w = deg(0.54), max(1.2, deg(0.035))
        a = np.maximum(a, np.clip(w + 0.75 - np.abs(r - rr), 0, 1.5) / 1.5)
    glow = np.exp(-(r / max(2.0, deg(0.16))) ** 2) * 0.35
    a = np.clip(np.maximum(a, glow), 0, 1)
    im = np.zeros((res, res, 4), np.uint8)
    im[..., 0], im[..., 1], im[..., 2] = RETICLE_RGB
    im[..., 3] = (a * 255).astype(np.uint8)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    Image.fromarray(im, "RGBA").save(path, optimize=True)


def utf(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def exported(rel, ext):
    p = os.path.join(EXPORT, *rel.split("/")) + ext
    if os.path.exists(p):
        return p
    if ext == ".psk" and os.path.exists(p + "x"):
        return p + "x"
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
    for pref in (lambda k, n: k == "B", lambda k, n: "basecolor" in n.lower(), lambda k, n: n.lower().endswith("_b"),
                 lambda k, n: "diffuse" in k.lower() or "albedo" in k.lower()):
        for k, n in names:
            if pref(k, n) and find_one(n + ".png"):
                return find_one(n + ".png")
    return None


class Textures:
    def __init__(self, pack_dir):
        self.pack_dir, self.written, self.reticles = pack_dir, {}, {}

    def for_material(self, mat, folder):
        if mat.startswith("#skip"):
            return f"{NS}:textures/ron/skip.png"
        if mat.startswith("#reticle"):
            return self.reticles[folder]
        src = material_texture(mat)
        if not src:
            print(f"  ! no base colour for material {mat}, using a grey placeholder")
            src = os.path.join(OUT, "_grey.png")
            Image.new("RGBA", (4, 4), (90, 90, 90, 255)).save(src)
        name = "".join(c if c.isalnum() or c in "_-." else "_" for c in os.path.splitext(os.path.basename(src))[0].lower())
        key = f"{folder}/{name}"
        if key not in self.written:
            im = Image.open(src).convert("RGBA")
            if max(im.size) > MAX_TEX:
                s = MAX_TEX / max(im.size)
                im = im.resize((max(1, round(im.width * s)), max(1, round(im.height * s))), Image.LANCZOS)
            im.putalpha(255)  # base colour alpha in RoN is not opacity
            dst = os.path.join(self.pack_dir, "assets", NS, "textures", "ron", folder, name + ".png")
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            im.save(dst, optimize=True)
            self.written[key] = True
        return f"{NS}:textures/ron/{folder}/{name}.png"


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
        # static mesh (magazine, rounds): one identity root bone so the renderer always has bone 0
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


def write_anim(anim, mesh, path, pick=None):
    names = {b.name.lower() for b in mesh.bones}
    keep = [i for i, n in enumerate(anim.bones) if n.lower() in names]
    frames = range(anim.frames)
    if pick == "first":
        frames = [0, 0]
    elif pick == "last":
        frames = [anim.frames - 1, anim.frames - 1]
    frames = list(frames)
    out = bytearray(b"RONA" + struct.pack(">i", 1))
    fps = anim.fps if pick is None else 30.0
    out += struct.pack(">fii", fps, len(frames), len(keep))
    for i in keep:
        out += utf(anim.bones[i])
    data = np.zeros((len(frames), len(keep), 7), dtype=">f4")
    for j, i in enumerate(keep):
        data[:, j, 0:3] = anim.pos[frames, i]
        q = anim.quat[frames, i].copy()
        if i != 0:
            q[:, 0:3] *= -1  # conjugate non-root
        q /= np.linalg.norm(q, axis=1, keepdims=True)
        data[:, j, 3:7] = q
    out += data.tobytes()
    with open(path, "wb") as fh:
        fh.write(out)
    return len(keep), len(frames) / max(fps, 1e-6)


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
    ron = os.path.join(pack_dir, "assets", NS, "ron")
    os.makedirs(ron)
    json.dump({"pack": {"pack_format": 34, "description": "RoN Weapons: PRIVATE, made from your own Ready or Not install. Do not share."}},
              open(os.path.join(pack_dir, "pack.mcmeta"), "w"), indent=2)
    tex = Textures(pack_dir)
    skip_png = os.path.join(pack_dir, "assets", NS, "textures", "ron", "skip.png")
    os.makedirs(os.path.dirname(skip_png), exist_ok=True)
    Image.new("RGBA", (1, 1), (0, 0, 0, 0)).save(skip_png)
    snd = os.path.join(OUT, "sound_pack")
    all_cues = json.load(open(os.path.join(snd, "cues.json"))) if os.path.isdir(snd) else {}
    if all_cues:
        shutil.copytree(os.path.join(snd, "assets"), os.path.join(pack_dir, "assets"), dirs_exist_ok=True)
        n = sum(len(fs) for _, _, fs in os.walk(os.path.join(snd, "assets", NS, "sounds")))
        print(f"sounds: {n} ogg files")
    else:
        print("  ! no private/sound_pack: run sound_pack.py for RoN sounds")

    arms = read_psk(exported(ARMS_MESH, ".psk"))
    print("arms (shared):", write_mesh(arms, os.path.join(ron, "arms.ronmesh"), lambda m: tex.for_material(m, "arms"), rigid=False))

    for wid, w in WEAPONS.items():
        src = exported(w["gun"], ".psk")
        if not src:
            print(f"== {wid}: gun mesh not exported, skipped")
            continue
        base = os.path.join(ron, wid)
        os.makedirs(os.path.join(base, "anims"))
        tf = lambda m, wid=wid: tex.for_material(m, wid)
        gun = read_psk(src)
        print(f"== {wid}: gun", write_mesh(gun, os.path.join(base, "gun.ronmesh"), tf, rigid=True))
        meta = {"ads_eye": w["ads_eye"], "attachments": []}
        for att in w.get("attachments", []):
            p = exported(att["mesh"], ".psk")
            if not p:
                print(f"  ! {att['file']}: {att['mesh']} not exported")
                continue
            print(f"   {att['file']}:", write_mesh(read_psk(p), os.path.join(base, att["file"] + ".ronmesh"), tf, rigid=True))
            entry = {"mesh": att["file"], "sockets": att["sockets"]}
            if "hide_y_above" in att:
                entry["hide_y_above"] = att["hide_y_above"]
            meta["attachments"].append(entry)
        if w.get("optics"):
            meta["optics"] = []
        for op in w.get("optics", []):
            p = exported(op["mesh"], ".psk")
            S = socket_matrix(gun, op["socket"])
            if not p or S is None:
                print(f"  ! optic {op['id']}: mesh or socket {op['socket']} missing")
                continue
            m = read_psk(p)
            name = f"optic_{op['id']}"
            folder = f"{wid}_{op['id']}"
            png = os.path.join(pack_dir, "assets", NS, "textures", "ron", folder, "reticle.png")
            tex.reticles[folder] = f"{NS}:textures/ron/{folder}/reticle.png"
            eye_y = w["ads_eye"][1]
            pic_y = (S @ np.array([0, 0, 0, 1.0]))[1]
            c = prepare_optic(m, S, op["style"], (eye_y if eye_y is not None else 30.0) - pic_y, png)
            if c is None:
                print(f"  ! optic {op['id']}: no reticle plane, iron-sight eye kept")
                eye = w["ads_eye"]
            else:
                eye = [round(float(c[0]), 3), eye_y, round(float(c[2]), 3)]
            print(f"   optic {op['id']:6s}:", write_mesh(m, os.path.join(base, name + ".ronmesh"), lambda mm, f=folder: tex.for_material(mm, f), rigid=True),
                  "eye", eye)
            meta["optics"].append({"id": op["id"], "mesh": name, "socket": op["socket"], "eye": eye, "hide": op.get("hide", [])})
        sp = w.get("suppressor")
        if sp:
            p = exported(sp["mesh"], ".psk")
            if p:
                m = read_psk(p)
                m.materials = ["#skip " + x if any(k in x.lower() for k in SKIP_MATERIAL) else x for x in m.materials]
                print("   suppressor:", write_mesh(m, os.path.join(base, "suppressor.ronmesh"), tf, rigid=True))
                meta["suppressor"] = {"mesh": "suppressor", "socket": sp["socket"]}
            else:
                print(f"  ! suppressor {sp['mesh']} not exported")
        if "tp" in w:
            meta["tp"] = w["tp"]
        if wid in all_cues:
            meta["cues"] = all_cues[wid]
        json.dump(meta, open(os.path.join(base, "rig.json"), "w"))
        for action, (a_rel, w_rel) in w["actions"].items():
            for kind, spec, mesh in (("arms", a_rel, arms), ("weapon", w_rel, gun)):
                if not spec:
                    continue
                rel, pick = (spec, None) if isinstance(spec, str) else spec
                psa = exported(rel, ".psa")
                if not psa:
                    print(f"  ! {action}/{kind}: {rel}.psa not exported, skipped")
                    continue
                tracks, dur = write_anim(read_psa(psa), mesh, os.path.join(base, "anims", f"{action}_{kind}.ronanim"), pick)
                print(f"   {action:13s} {kind:6s} {tracks:3d} tracks {dur:5.2f}s" + (f" ({pick} frame)" if pick else ""))

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
