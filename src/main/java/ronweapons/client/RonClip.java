package ronweapons.client;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Absolute per-bone local keyframes (CUE4Parse bakes additive RoN anims onto their reference pose). */
public final class RonClip {
    public final float fps;
    public final int frames;
    public final String[] tracks;
    private final float[] data; // frames * tracks * 7 (px,py,pz,qx,qy,qz,qw)

    private RonClip(DataInputStream d) throws IOException {
        if (d.readInt() != 0x524F4E41) throw new IOException("not a RONA file");
        if (d.readInt() != 1) throw new IOException("unsupported RONA version");
        fps = d.readFloat();
        frames = d.readInt();
        int nt = d.readInt();
        tracks = new String[nt];
        for (int t = 0; t < nt; t++) tracks[t] = d.readUTF();
        data = new float[frames * nt * 7];
        for (int i = 0; i < data.length; i++) data[i] = d.readFloat();
    }

    public static RonClip read(InputStream in) throws IOException {
        try (DataInputStream d = new DataInputStream(new BufferedInputStream(in, 1 << 16))) {
            return new RonClip(d);
        }
    }

    public float duration() { return frames > 1 ? (frames - 1) / fps : 0f; }

    public int[] map(RonMesh mesh) {
        int[] m = new int[tracks.length];
        for (int t = 0; t < tracks.length; t++) m[t] = mesh.bone(tracks[t]);
        return m;
    }

    /** Writes the pose at time t (seconds) into the local arrays for the mapped bones only. */
    public void sample(float t, boolean loop, int[] map, float[] lp, float[] lr) {
        float f;
        if (frames <= 1) f = 0;
        else if (loop) { float dur = duration(); f = ((t % dur + dur) % dur) * fps; }
        else f = Math.max(0, Math.min(t * fps, frames - 1));
        int f0 = (int) Math.floor(f);
        int f1 = Math.min(f0 + 1, frames - 1);
        float a = f - f0;
        int nt = tracks.length;
        for (int tr = 0; tr < nt; tr++) {
            int b = map[tr];
            if (b < 0) continue;
            int o0 = (f0 * nt + tr) * 7, o1 = (f1 * nt + tr) * 7;
            lp[b * 3] = data[o0] + (data[o1] - data[o0]) * a;
            lp[b * 3 + 1] = data[o0 + 1] + (data[o1 + 1] - data[o0 + 1]) * a;
            lp[b * 3 + 2] = data[o0 + 2] + (data[o1 + 2] - data[o0 + 2]) * a;
            nlerp(data, o0 + 3, data, o1 + 3, a, lr, b * 4);
        }
    }

    static void nlerp(float[] qa, int ia, float[] qb, int ib, float t, float[] out, int io) {
        float dot = qa[ia] * qb[ib] + qa[ia + 1] * qb[ib + 1] + qa[ia + 2] * qb[ib + 2] + qa[ia + 3] * qb[ib + 3];
        float s = dot < 0 ? -1f : 1f;
        float x = qa[ia] + (s * qb[ib] - qa[ia]) * t;
        float y = qa[ia + 1] + (s * qb[ib + 1] - qa[ia + 1]) * t;
        float z = qa[ia + 2] + (s * qb[ib + 2] - qa[ia + 2]) * t;
        float w = qa[ia + 3] + (s * qb[ib + 3] - qa[ia + 3]) * t;
        float len = (float) Math.sqrt(x * x + y * y + z * z + w * w);
        if (len < 1e-8f) len = 1f;
        out[io] = x / len; out[io + 1] = y / len; out[io + 2] = z / len; out[io + 3] = w / len;
    }
}
