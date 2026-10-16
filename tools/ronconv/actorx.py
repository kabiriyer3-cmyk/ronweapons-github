"""Readers for ActorX .psk/.pskx meshes and .psa animations as written by CUE4Parse-Conversion.

Everything stays in the file's own coordinate space. Quaternions are (x, y, z, w).
The psk/psa convention stores non-root bone rotations conjugated; `fix_quat` handles that and is
selected by the `conj` mode so callers can test hypotheses against the data.
"""
import struct
from dataclasses import dataclass, field

import numpy as np


def read_chunks(path):
    b = open(path, "rb").read()
    o, out = 0, {}
    while o + 32 <= len(b):
        cid = b[o:o + 20].split(b"\0")[0].decode("latin1")
        _flags, size, count = struct.unpack_from("<iii", b, o + 20)
        o += 32
        out[cid] = (size, count, b[o:o + size * count])
        o += size * count
    return out


@dataclass
class Bone:
    name: str
    parent: int
    quat: np.ndarray  # raw from file
    pos: np.ndarray


def _bones(size, count, data):
    bones = []
    for i in range(count):
        o = i * size
        name = data[o:o + 64].split(b"\0")[0].decode("latin1")
        _flags, _nchild, parent = struct.unpack_from("<Iii", data, o + 64)
        q = np.array(struct.unpack_from("<4f", data, o + 76), dtype=np.float64)
        p = np.array(struct.unpack_from("<3f", data, o + 92), dtype=np.float64)
        bones.append(Bone(name, parent if i else -1, q, p))
    return bones


@dataclass
class Mesh:
    points: np.ndarray            # (P,3)
    wedge_point: np.ndarray       # (W,)
    wedge_uv: np.ndarray          # (W,2)
    wedge_mat: np.ndarray         # (W,)
    faces: np.ndarray             # (F,3) wedge indices
    face_mat: np.ndarray          # (F,)
    materials: list
    bones: list
    weights: list                 # (pointIndex, boneIndex, weight)
    normals: np.ndarray = None    # per wedge or point, (N,3)


def read_psk(path):
    c = read_chunks(path)
    size, n, d = c["PNTS0000"]
    points = np.frombuffer(d, dtype="<f4").reshape(n, 3).astype(np.float64)
    size, n, d = c["VTXW0000"]
    wp, uv, wm = [], [], []
    big = path.lower().endswith(".pskx") or len(points) > 65536
    for i in range(n):
        o = i * size
        pi = struct.unpack_from("<I" if big else "<H", d, o)[0]
        u, v = struct.unpack_from("<2f", d, o + 4)
        m = d[o + 12]
        wp.append(pi); uv.append((u, v)); wm.append(m)
    if "FACE3200" in c:
        size, n, d = c["FACE3200"]; fmt = "<IIIBB"
    else:
        size, n, d = c["FACE0000"]; fmt = "<HHHBB"
    faces, fmat = [], []
    for i in range(n):
        a, b_, cc, m, _aux = struct.unpack_from(fmt, d, i * size)
        faces.append((a, b_, cc)); fmat.append(m)
    size, n, d = c["MATT0000"]
    mats = [d[i * size:i * size + 64].split(b"\0")[0].decode("latin1") for i in range(n)]
    bones = _bones(*c["REFSKELT"]) if "REFSKELT" in c else [Bone("root", -1, np.array([0, 0, 0, 1.0]), np.zeros(3))]
    weights = []
    if "RAWWEIGHTS" in c:
        size, n, d = c["RAWWEIGHTS"]
        for i in range(n):
            w, p, bi = struct.unpack_from("<fii", d, i * size)
            weights.append((p, bi, w))
    normals = None
    if "VTXNORMS" in c:
        size, n, d = c["VTXNORMS"]
        normals = np.frombuffer(d, dtype="<f4").reshape(n, 3).astype(np.float64)
    return Mesh(points, np.array(wp), np.array(uv, dtype=np.float64), np.array(wm), np.array(faces), np.array(fmat),
                mats, bones, weights, normals)


@dataclass
class Anim:
    name: str
    fps: float
    frames: int
    bones: list          # names, file order
    parents: list
    pos: np.ndarray      # (frames, bones, 3)
    quat: np.ndarray     # (frames, bones, 4) raw


def read_psa(path):
    c = read_chunks(path)
    bones = _bones(*c["BONENAMES"])
    size, n, d = c["ANIMINFO"]
    name = d[0:64].split(b"\0")[0].decode("latin1")
    total_bones, _root, _style, _quotum = struct.unpack_from("<4i", d, 128)
    _red, _track_time, rate = struct.unpack_from("<3f", d, 144)
    _start, _first, nframes = struct.unpack_from("<3i", d, 156)
    size, n, d = c["ANIMKEYS"]
    k = np.frombuffer(d, dtype="<f4").reshape(nframes, total_bones, 8).astype(np.float64)
    return Anim(name, rate, nframes, [b.name for b in bones], [b.parent for b in bones], k[:, :, 0:3], k[:, :, 3:7])


# ---------- math ----------

def conj(q):
    return np.array([-q[0], -q[1], -q[2], q[3]])


def quat_to_mat(q):
    x, y, z, w = q / np.linalg.norm(q)
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
    ])


def trs(pos, q):
    m = np.eye(4)
    m[:3, :3] = quat_to_mat(q)
    m[:3, 3] = pos
    return m


def fix_quat(q, is_root, mode):
    """mode: 'nonroot' = conjugate all but root (classic psk/psa), 'all' = conjugate every bone, 'none'."""
    if mode == "all" or (mode == "nonroot" and not is_root):
        return conj(q)
    return q


def globals_from_locals(parents, locals_):
    g = [None] * len(parents)
    for i, p in enumerate(parents):
        g[i] = locals_[i] if p < 0 else g[p] @ locals_[i]
    return g


def bind_globals(bones, mode):
    parents = [b.parent for b in bones]
    loc = [trs(b.pos, fix_quat(b.quat, b.parent < 0, mode)) for b in bones]
    return globals_from_locals(parents, loc)
