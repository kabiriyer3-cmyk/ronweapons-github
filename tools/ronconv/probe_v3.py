import os, sys, glob
import numpy as np
sys.path.insert(0, r"C:\Modding\ron-to-minecraft\repo\tools\ronconv")
from actorx import read_psk, read_psa
from build_pack import bind_data
EX = r"C:\Modding\ron-to-minecraft\private\export"
W = "ReadyOrNot/Content/ReadyOrNot/Assets/Weapons/"
def path(rel, ext):
    p = os.path.join(EX, *rel.split("/")) + ext
    return p if os.path.exists(p) else (p + "x" if os.path.exists(p + "x") else None)
GUNS = {"m4a1": W+"M4_Rifle/Mesh/SK_WP_Rifle_M4", "g19": W+"G19/Meshes/SK_Pistol_G19",
        "python": W+"Python/Meshes/SK_Pistol_Python", "m590": W+"M590/Meshes/SK_Shotgun_M590_Assault"}
for gid, rel in GUNS.items():
    p = path(rel, ".psk")
    if not p: print("==", gid, "missing"); continue
    m = read_psk(p)
    _, inv = bind_data(m)
    G = [np.linalg.inv(i) for i in inv]
    pts = np.array(m.points)
    print(f"== {gid}: {len(m.bones)} bones, mats {m.materials}, bounds {pts.min(0).round(2)} {pts.max(0).round(2)}")
    for i, b in enumerate(m.bones):
        if gid == "m590" or any(k in b.name.lower() for k in ("socket", "muzzle", "supp", "optic", "sight", "reflex")):
            print(f"   {i:3d} {b.name:34s} par {b.parent:3d} pos {G[i][:3,3].round(2)} fwd(-Y)->{(-G[i][:3,1]).round(2)}")
    if gid == "m590":
        for y0 in np.arange(np.floor(pts[:,1].min()), pts[:,1].max(), 4):
            sel = pts[(pts[:,1] >= y0) & (pts[:,1] < y0 + 4) & (np.abs(pts[:,0]) < 1.0)]
            if len(sel): print(f"   y[{y0:6.1f}] top z {sel[:,2].max():6.2f} n {len(sel)}")
ATT = ["Attachments/Suppressor_SOCOM338/sk_suppressor_socom338", "Attachments/Suppressor_Tundra/sk_suppressor_tundra",
       "Attachments/Suppressor_Harvester/sk_suppressor_harvester", "Attachments/Suppressor_GM9/SK_suppressor_gm9",
       "Attachments/Suppressor_Osprey/sk_suppressor_osprey", "Attachments/Mesh/Pistol_SuppressorSmall",
       "Attachments/Optic_EPXS3/Holosight_EXPS3", "Attachments/Optic_M5B/sk_optic_m5", "Attachments/Optic_MicroT2/SK_MicroT2_TallMount",
       "Attachments/Optic_MicroT2/SK_MicroT2", "Attachments/Optic_MicroT2/V2/SK_T2_shortmount", "Attachments/Optic_SRS/SK_Optic_SRS",
       "Attachments/Optic_Holosun510C/Holosight_HS510CDot", "Attachments/Optic_RMR/sk_optic_rmr_glock",
       "Attachments/Optic_RMR/sk_optic_rmr_pistol", "Attachments/Optic_SRO/sk_optic_sro_glock",
       "M590/Meshes/Shells_Scaled/m590_buckshot_static_shell"]
for rel in ATT:
    p = path(W + rel, ".psk")
    if not p: print("--", rel, "MISSING"); continue
    m = read_psk(p)
    pts = np.array(m.points)
    print(f"-- {rel.split('/')[-1]}: {len(m.bones)} bones {[b.name for b in m.bones][:6]}, pts {len(pts)}, bounds {pts.min(0).round(2)} {pts.max(0).round(2)}")
    fm = np.array(m.face_mat); wp = np.array(m.wedge_point); F = np.array(m.faces)
    for mi, mn in enumerate(m.materials):
        fs = F[fm == mi]
        if not len(fs): continue
        q = pts[wp[fs.flatten()]]
        print(f"     mat {mi} {mn:34s} tris {len(fs):5d} min {q.min(0).round(2)} max {q.max(0).round(2)}")
for d in ("Animations/FP/M590/base_male", "Animations/FP/M590/ron_m590"):
    for f in sorted(glob.glob(os.path.join(EX, "ReadyOrNot", "Content", "ReadyOrNot", *d.split("/"), "*.psa"))):
        a = read_psa(f)
        print(f"   anim {os.path.basename(f):48s} {a.frames:4d}f {a.fps:6.2f}fps {a.frames / a.fps:5.2f}s bones {len(a.bones)}")
