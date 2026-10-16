"""Reference implementation of the first-person rig that the Fabric mod ports to Java.

Conventions verified on SK_Char_SWAT_Heavy_FP_Arms / SK_WP_Rifle_M4 (check_conventions.py):
- psk/psa space is right-handed, Z up, centimetres, character faces -Y, right hand at -X.
- Bone rotations: conjugate every non-root bone (classic ActorX).
- Gun root J_Gun hangs off the arms bone `weapon_right`.
- View: Minecraft camera space (X right, Y up, -Z forward, blocks) = C * Rbind_cam * inverse(G_cam(frame)).
"""
import numpy as np

from actorx import bind_globals, conj, globals_from_locals, trs

# psk -> Minecraft camera axes: x_mc = -x, y_mc = z, z_mc = y ; cm -> blocks
C = np.array([[-1, 0, 0, 0], [0, 0, 1, 0], [0, 1, 0, 0], [0, 0, 0, 1]], dtype=np.float64)
S = np.diag([0.01, 0.01, 0.01, 1.0])


class Skel:
    def __init__(self, mesh):
        self.mesh = mesh
        self.names = [b.name for b in mesh.bones]
        self.lower = {n.lower(): i for i, n in enumerate(self.names)}
        self.parents = [b.parent for b in mesh.bones]
        self.bind_local = [trs(b.pos, b.quat if b.parent < 0 else conj(b.quat)) for b in mesh.bones]
        self.bind_global = bind_globals(mesh.bones, "nonroot")
        self.inv_bind = [np.linalg.inv(g) for g in self.bind_global]

    def pose(self, anim, frame):
        """Local matrices for one frame; bones without a track keep their bind local."""
        loc = list(self.bind_local)
        if anim is not None:
            f = min(int(frame), anim.frames - 1)
            for ai, name in enumerate(anim.bones):
                mi = self.lower.get(name.lower())
                if mi is None:
                    continue
                q = anim.quat[f, ai]
                q = q if ai == 0 else conj(q)
                loc[mi] = trs(anim.pos[f, ai], q)
        return globals_from_locals(self.parents, loc)

    def index(self, name):
        return self.lower[name.lower()]


def skin(mesh, skel, globals_, extra=np.eye(4)):
    """Linear blend skinning of every point; returns (P,3) in the space `extra` maps to."""
    P = len(mesh.points)
    acc = np.zeros((P, 3))
    wsum = np.zeros(P)
    hom = np.c_[mesh.points, np.ones(P)]
    by_bone = {}
    for p, b, w in mesh.weights:
        by_bone.setdefault(b, ([], []))
        by_bone[b][0].append(p); by_bone[b][1].append(w)
    for b, (ps, ws) in by_bone.items():
        M = extra @ globals_[b] @ skel.inv_bind[b]
        ps = np.array(ps); ws = np.array(ws)
        acc[ps] += (hom[ps] @ M.T)[:, :3] * ws[:, None]
        wsum[ps] += ws
    unweighted = wsum == 0
    if unweighted.any():
        acc[unweighted] = (hom[unweighted] @ (extra @ globals_[0] @ skel.inv_bind[0]).T)[:, :3]
        wsum[unweighted] = 1
    return acc / wsum[:, None]


def view_matrix(arms_skel, arms_globals, follow_camera=True):
    cam = arms_skel.index("fp_camera")
    gb = arms_skel.bind_global[cam]
    g = arms_globals[cam] if follow_camera else gb
    rb = np.eye(4); rb[:3, :3] = gb[:3, :3]
    return C @ S @ rb @ np.linalg.inv(g)
