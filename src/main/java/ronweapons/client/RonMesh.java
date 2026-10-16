package ronweapons.client;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Skinned mesh converted from an ActorX psk by tools/ronconv/build_pack.py.
 * Coordinates are the psk's own (right-handed, Z up, cm); rotations are already conjugation-fixed.
 */
public final class RonMesh {
    public final String[] boneNames;
    public final int[] parents;
    public final float[] bindPos;   // 3 per bone, local
    public final float[] bindRot;   // 4 per bone (x,y,z,w), local
    public final Matrix4f[] invBind;
    public final Matrix4f[] bindGlobal;
    public final Map<String, Integer> boneIndex = new HashMap<>();

    public final Identifier[] textures;
    public final int vertexCount;
    final float[] pos, nrm, uv;
    final short[] bIdx;
    final float[] bW;
    final int[] sectionMaterial;
    final int[][] sectionIndices;

    final float[] outPos, outNrm;
    private final float[] mats;

    private RonMesh(DataInputStream d) throws IOException {
        if (d.readInt() != 0x524F4E4D) throw new IOException("not a RONM file");
        int version = d.readInt();
        if (version != 1) throw new IOException("unsupported RONM version " + version);
        int nb = d.readInt();
        boneNames = new String[nb];
        parents = new int[nb];
        bindPos = new float[nb * 3];
        bindRot = new float[nb * 4];
        invBind = new Matrix4f[nb];
        bindGlobal = new Matrix4f[nb];
        float[] tmp = new float[16];
        for (int b = 0; b < nb; b++) {
            boneNames[b] = d.readUTF();
            boneIndex.put(boneNames[b].toLowerCase(Locale.ROOT), b);
            parents[b] = d.readInt();
            for (int k = 0; k < 3; k++) bindPos[b * 3 + k] = d.readFloat();
            for (int k = 0; k < 4; k++) bindRot[b * 4 + k] = d.readFloat();
            for (int k = 0; k < 16; k++) tmp[k] = d.readFloat();
            invBind[b] = new Matrix4f().set(tmp);
        }
        for (int b = 0; b < nb; b++) {
            Matrix4f local = local(bindPos, bindRot, b, new Matrix4f());
            bindGlobal[b] = parents[b] < 0 ? local : new Matrix4f(bindGlobal[parents[b]]).mul(local);
        }
        int nm = d.readInt();
        textures = new Identifier[nm];
        for (int m = 0; m < nm; m++) {
            d.readUTF(); // material name, informational
            textures[m] = Identifier.of(d.readUTF());
        }
        vertexCount = d.readInt();
        pos = new float[vertexCount * 3];
        nrm = new float[vertexCount * 3];
        uv = new float[vertexCount * 2];
        bIdx = new short[vertexCount * 4];
        bW = new float[vertexCount * 4];
        for (int v = 0; v < vertexCount; v++) {
            for (int k = 0; k < 3; k++) pos[v * 3 + k] = d.readFloat();
            for (int k = 0; k < 3; k++) nrm[v * 3 + k] = d.readFloat();
            uv[v * 2] = d.readFloat();
            uv[v * 2 + 1] = d.readFloat();
            for (int k = 0; k < 4; k++) {
                bIdx[v * 4 + k] = d.readShort();
                bW[v * 4 + k] = d.readFloat();
            }
        }
        int ns = d.readInt();
        sectionMaterial = new int[ns];
        sectionIndices = new int[ns][];
        for (int s = 0; s < ns; s++) {
            sectionMaterial[s] = d.readInt();
            int n = d.readInt() * 3;
            int[] idx = new int[n];
            for (int i = 0; i < n; i++) idx[i] = d.readInt();
            sectionIndices[s] = idx;
        }
        outPos = new float[vertexCount * 3];
        outNrm = new float[vertexCount * 3];
        mats = new float[nb * 16];
    }

    public static RonMesh read(InputStream in) throws IOException {
        try (DataInputStream d = new DataInputStream(new BufferedInputStream(in, 1 << 16))) {
            return new RonMesh(d);
        }
    }

    public int bone(String name) {
        Integer i = boneIndex.get(name.toLowerCase(Locale.ROOT));
        return i == null ? -1 : i;
    }

    static Matrix4f local(float[] p, float[] r, int b, Matrix4f dest) {
        return dest.translation(p[b * 3], p[b * 3 + 1], p[b * 3 + 2])
                .rotate(new Quaternionf(r[b * 4], r[b * 4 + 1], r[b * 4 + 2], r[b * 4 + 3]).normalize());
    }

    /** Globals from local pose arrays (same layout as bindPos/bindRot). Parents always precede children. */
    public Matrix4f[] globals(float[] lp, float[] lr, Matrix4f[] out) {
        for (int b = 0; b < parents.length; b++) {
            Matrix4f m = out[b] == null ? (out[b] = new Matrix4f()) : out[b];
            local(lp, lr, b, m);
            if (parents[b] >= 0) m.mulLocal(out[parents[b]]);
        }
        return out;
    }

    /** CPU linear blend skinning. skinMats[b] = view * global[b] * invBind[b]. */
    public void skin(Matrix4f[] skinMats) {
        float[] m = mats;
        for (int b = 0; b < skinMats.length; b++) skinMats[b].get(m, b * 16);
        for (int v = 0; v < vertexCount; v++) {
            float px = pos[v * 3], py = pos[v * 3 + 1], pz = pos[v * 3 + 2];
            float nx = nrm[v * 3], ny = nrm[v * 3 + 1], nz = nrm[v * 3 + 2];
            float ox = 0, oy = 0, oz = 0, qx = 0, qy = 0, qz = 0;
            for (int k = 0; k < 4; k++) {
                int b = bIdx[v * 4 + k];
                if (b < 0) break;
                float w = bW[v * 4 + k];
                int o = b * 16;
                ox += w * (m[o] * px + m[o + 4] * py + m[o + 8] * pz + m[o + 12]);
                oy += w * (m[o + 1] * px + m[o + 5] * py + m[o + 9] * pz + m[o + 13]);
                oz += w * (m[o + 2] * px + m[o + 6] * py + m[o + 10] * pz + m[o + 14]);
                qx += w * (m[o] * nx + m[o + 4] * ny + m[o + 8] * nz);
                qy += w * (m[o + 1] * nx + m[o + 5] * ny + m[o + 9] * nz);
                qz += w * (m[o + 2] * nx + m[o + 6] * ny + m[o + 10] * nz);
            }
            float len = (float) Math.sqrt(qx * qx + qy * qy + qz * qz);
            if (len > 1e-6f) { qx /= len; qy /= len; qz /= len; }
            outPos[v * 3] = ox; outPos[v * 3 + 1] = oy; outPos[v * 3 + 2] = oz;
            outNrm[v * 3] = qx; outNrm[v * 3 + 1] = qy; outNrm[v * 3 + 2] = qz;
        }
    }

    /** Emits the last skinned result. Entity layers draw quads, so each triangle is a quad with a repeated corner. */
    public void emit(MatrixStack.Entry entry, VertexConsumerProvider consumers, int light) {
        Matrix4f pm = entry.getPositionMatrix();
        for (int s = 0; s < sectionIndices.length; s++) {
            VertexConsumer vc = consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(textures[sectionMaterial[s]]));
            int[] idx = sectionIndices[s];
            for (int i = 0; i < idx.length; i += 3) {
                corner(vc, pm, entry, idx[i], light);
                corner(vc, pm, entry, idx[i + 1], light);
                corner(vc, pm, entry, idx[i + 2], light);
                corner(vc, pm, entry, idx[i + 2], light);
            }
        }
    }

    private void corner(VertexConsumer vc, Matrix4f pm, MatrixStack.Entry entry, int v, int light) {
        vc.vertex(pm, outPos[v * 3], outPos[v * 3 + 1], outPos[v * 3 + 2])
                .color(255, 255, 255, 255)
                .texture(uv[v * 2], uv[v * 2 + 1])
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal(entry, outNrm[v * 3], outNrm[v * 3 + 1], outNrm[v * 3 + 2]);
    }
}
