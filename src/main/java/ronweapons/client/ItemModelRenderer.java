package ronweapons.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;

/**
 * The RoN rifle (bind pose + magazine) as the item model in the inventory, hotbar, item frames and on the ground.
 * Other contexts (third person, head) keep the flat icon; with no private pack everything falls back to it.
 * Item space here: origin at the slot / item centre, X right, Y up, viewer on +Z.
 */
public final class ItemModelRenderer {
    /** psk -> item axes: muzzle (-Y) to +X, up (Z) to +Y, the gun's right side (-X) faces the viewer. */
    private static final Matrix4f AXES = new Matrix4f(0, 0, -1, 0, -1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1);
    /** gun + magazine bounding box centre in psk cm, and a scale that fits its ~78 cm length into one item unit */
    private static final float CY = -11.85f, CZ = -3.0f, SCALE = 1f / 74f;

    private static GunRig cached;
    private static float[][] gun, mag;

    public static boolean render(ModelTransformationMode mode, MatrixStack matrices, VertexConsumerProvider consumers, int light, int overlay) {
        if (mode != ModelTransformationMode.GUI && mode != ModelTransformationMode.GROUND && mode != ModelTransformationMode.FIXED) return false;
        GunRig rig = GunRig.get(MinecraftClient.getInstance().getResourceManager());
        if (rig == null) return false;
        if (rig != cached) bake(rig);

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
        rig.gun.emit(gun[0], gun[1], e, consumers, light, overlay);
        if (mag != null) rig.mag.emit(mag[0], mag[1], e, consumers, light, overlay);
        matrices.pop();
        return true;
    }

    private static void bake(GunRig rig) {
        cached = rig;
        Matrix4f base = new Matrix4f().scale(SCALE).mul(AXES).translate(0f, -CY, -CZ);
        gun = rig.gun.bake(base);
        mag = null;
        if (rig.mag != null && rig.mag1Socket >= 0) {
            mag = rig.mag.bake(new Matrix4f(base).mul(rig.gun.bindGlobal[rig.mag1Socket]));
        }
    }
}
