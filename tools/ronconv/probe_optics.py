import os, numpy as np
from actorx import read_psk
from build_pack import bind_data
EX = r"C:\Modding\ron-to-minecraft\private\export\ReadyOrNot\Content\ReadyOrNot\Assets\Weapons"
def P(rel):
    p = os.path.join(EX, *rel.split("/")) + ".psk"
    return p if os.path.exists(p) else p + "x"
GUN = {"m4a1": "M4_Rifle/Mesh/SK_WP_Rifle_M4", "g19": "G19/Meshes/SK_Pistol_G19", "python": "Python/Meshes/SK_Pistol_Python", "m590": "M590/Meshes/SK_Shotgun_M590_Assault"}
A = "Attachments/"
TESTS = [("m4a1", A+"Optic_EPXS3/Holosight_EXPS3", "EXPS3_Socket"), ("m4a1", A+"Optic_M5B/sk_optic_m5", "M5B_Socket"),
         ("m4a1", A+"Optic_MicroT2/SK_MicroT2_TallMount", "MicroT2Raised_Socket"), ("m4a1", A+"Optic_MicroT2/SK_MicroT2_TallMount", "SightSocket"),
         ("m4a1", A+"Optic_Holosun510C/Holosight_HS510CDot", "SightSocket"), ("m4a1", A+"Optic_SRS/SK_Optic_SRS", "SRSRaised_Socket"),
         ("m4a1", A+"Suppressor_SOCOM338/sk_suppressor_socom338", "socom338_socket"), ("m4a1", A+"Suppressor_Tundra/sk_suppressor_tundra", "supressor_socket"),
         ("m4a1", A+"Suppressor_Harvester/sk_suppressor_harvester", "supressor_socket"),
         ("g19", A+"Optic_RMR/sk_optic_rmr_pistol", "RMRPistol_Socket"), ("g19", A+"Optic_RMR/sk_optic_rmr_glock", "Reflex_Socket"),
         ("g19", A+"Optic_SRO/sk_optic_sro_glock", "SROPistol_Socket"), ("g19", A+"Optic_SRO/sk_optic_sro_glock", "Reflex_Socket"),
         ("g19", A+"Mesh/Pistol_SuppressorSmall", "supressor_socket"), ("g19", A+"Suppressor_GM9/SK_suppressor_gm9", "supressor_socket"),
         ("g19", A+"Suppressor_Osprey/sk_suppressor_osprey", "supressor_socket"),
         ("python", A+"Optic_MicroT2/V2/SK_T2_shortmount", "PythonT2_socket"), ("python", A+"Optic_MicroT2/SK_MicroT2", "PythonT2_socket"),
         ("python", A+"Optic_RMR/sk_optic_rmr_pistol", "Reflexaa2_Socket"),
         ("m590", A+"Optic_MicroT2/SK_MicroT2_TallMount", "MicroT2Raised_socket"), ("m590", A+"Optic_RMR/sk_optic_rmr_pistol", "Reflex_Socket"),
         ("m590", A+"Optic_EPXS3/Holosight_EXPS3", "Reflex_Socket")]
cache = {}
for gid, rel, sock in TESTS:
    if gid not in cache:
        g = read_psk(P(GUN[gid])); _, inv = bind_data(g)
        cache[gid] = (g, [np.linalg.inv(i) for i in inv], np.array(g.points), {b.name.lower(): i for i, b in enumerate(g.bones)})
    g, G, gp, idx = cache[gid]
    si = idx[sock.lower()]
    S = np.linalg.inv(G[0]) @ G[si]
    m = read_psk(P(rel)); pts = np.array(m.points)
    q = (S[:3, :3] @ pts.T).T + S[:3, 3]
    ax = {k: (S[:3, :3] @ v).round(2) for k, v in (("+X", [1, 0, 0]), ("+Y", [0, 1, 0]), ("+Z", [0, 0, 1]))}
    sel = gp[(gp[:, 1] >= q[:, 1].min()) & (gp[:, 1] <= q[:, 1].max()) & (np.abs(gp[:, 0]) < 1.0)]
    top = sel[:, 2].max() if len(sel) else float('nan')
    print(f"{gid:6s} {rel.split('/')[-1]:28s} @{sock:22s} axes {ax} | gun-space min {q.min(0).round(2)} max {q.max(0).round(2)} | gun top under {top:.2f}")
    fm = np.array(m.face_mat); wp = np.array(m.wedge_point); F = np.array(m.faces)
    for mi, mn in enumerate(m.materials):
        if 'picture' in mn.lower():
            vv = q[wp[F[fm == mi].flatten()]]
            c = (vv.min(0) + vv.max(0)) / 2
            print(f"        picture {mn}: centre {c.round(2)} extent {(vv.max(0) - vv.min(0)).round(2)}")
