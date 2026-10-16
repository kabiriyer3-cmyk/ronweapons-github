package ronweapons.client;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;
import ronweapons.RonWeapons;
import ronweapons.WeaponDef;

/**
 * The RoN gun (bind pose + magazine / rounds) as the item model in the inventory, hotbar, item frames and on the ground.
 * Other contexts (third person, head) keep the flat icon; with no private pack everything falls back to it.
 * Item space here: origin at the slot / item centre, X right, Y up, viewer on +Z.
 */
public final class ItemModelRenderer {
    /** psk -> item axes: muzzle (-Y) to +X, up (Z) to +Y, the gun's right side (-X) faces the viewer. */
    private static final Matrix4f AXES = new Matrix4f(0, 0, -1, 0, -1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1);

    private record Baked(GunRig rig, float[][] gun, float[][][] parts, RonMesh[] partMeshes) {}

    private static final Map<String, Baked> CACHE = new HashMap<>();

    public static boolean render(ItemStack stack, ModelTransformationMode mode, MatrixStack matrices, VertexConsumerProvider consumers, int light, int overlay) {
        if (mode != ModelTransformationMode.GUI && mode != ModelTransformationMode.GROUND && mode != ModelTransformationMode.FIXED) return false;
        WeaponDef def = RonWeapons.def(stack);
        if (def == null) return false;
        GunRig rig = GunRig.get(MinecraftClient.getInstance().getResourceManager(), def.id());
        if (rig == null) return false;
        Baked b = CACHE.get(def.id());
        if (b == null || b.rig() != rig) CACHE.put(def.id(), b = bake(rig));

        matrices.push();
        switch (mode) {
            case GUI -> {
                matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(30f));
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-20f));
                matrices.scale(1.05f, 1.05f, 1.05f);
            }
            case GROUND -> {
                matrices.translate(0f, 0.05f, 0f);
                matrices.scale(0.6f, 0.6f, 0.6f);
            }
            case FIXED -> {
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f));
                matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(30f));
            }
            default -> { }
        }
        MatrixStack.Entry e = matrices.peek();
        rig.gun.emit(b.gun()[0], b.gun()[1], e, consumers, light, overlay);
        for (int i = 0; i < b.parts().length; i++) b.partMeshes()[i].emit(b.parts()[i][0], b.parts()[i][1], e, consumers, light, overlay);
        matrices.pop();
        return true;
    }

    /** Bakes once per rig and fits the gun + first socket of each attachment into one item unit. */
    private static Baked bake(GunRig rig) {
        int n = rig.attachments.size();
        Matrix4f[] partBase = new Matrix4f[n];
        float[][] gun = rig.gun.bake(AXES);
        float[][][] parts = new float[n][][];
        for (int i = 0; i < n; i++) {
            GunRig.Attachment at = rig.attachments.get(i);
            partBase[i] = rig.gun.bindGlobal[at.sockets()[0]];
            parts[i] = at.mesh().bake(new Matrix4f(AXES).mul(partBase[i]));
        }
        float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        bounds(gun[0], lo, hi);
        for (float[][] p : parts) bounds(p[0], lo, hi);
        float size = Math.max(hi[0] - lo[0], Math.max(hi[1] - lo[1], hi[2] - lo[2]));
        float s = 1f / Math.max(size, 1e-3f);
        Matrix4f fit = new Matrix4f().scale(s).translate(-(lo[0] + hi[0]) / 2, -(lo[1] + hi[1]) / 2, -(lo[2] + hi[2]) / 2);
        gun = rig.gun.bake(new Matrix4f(fit).mul(AXES));
        RonMesh[] meshes = new RonMesh[n];
        for (int i = 0; i < n; i++) {
            meshes[i] = rig.attachments.get(i).mesh();
            parts[i] = meshes[i].bake(new Matrix4f(fit).mul(AXES).mul(partBase[i]));
        }
        return new Baked(rig, gun, parts, meshes);
    }

    private static void bounds(float[] p, float[] lo, float[] hi) {
        for (int v = 0; v < p.length; v += 3) {
            for (int k = 0; k < 3; k++) {
                lo[k] = Math.min(lo[k], p[v + k]);
                hi[k] = Math.max(hi[k], p[v + k]);
            }
        }
    }
}
