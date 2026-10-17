package ronweapons.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
 * The RoN gun (bind pose + magazine / rounds + mounted optic / suppressor) as the item model: inventory, hotbar,
 * item frames, on the ground and in the hands of players seen from outside. With no private pack everything falls back to the icon.
 */
public final class ItemModelRenderer {
    /** psk -> item axes: muzzle (-Y) to +X, up (Z) to +Y, the gun's right side (-X) faces the viewer. */
    private static final Matrix4f AXES = new Matrix4f(0, 0, -1, 0, -1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1);
    /**
     * Third person: HeldItemFeatureRenderer leaves us in a frame at the fist that looks like camera space
     * (X right, Y up along the arm's front, -Z where the arm points), so psk maps like first person: x = -x, y = z, z = y.
     */
    private static final Matrix4f TP_AXES = new Matrix4f(-1, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 1).scale(0.01f);
    /** shield in the off hand: its height runs along the arm (+Z), its face (-Y) to the front (+Y): x = -x, y = -y, z = z */
    private static final Matrix4f SHIELD_TP_AXES = new Matrix4f(-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1).scale(0.01f);

    /** pack folder of the RoN mesh drawn for this item, or null (vanilla model) */
    public static String rigId(ItemStack stack) {
        WeaponDef def = RonWeapons.def(stack);
        if (def != null) return def.id();
        if (stack.getItem() instanceof ronweapons.GrenadeItem g) return g.kind.id;
        if (stack.getItem() instanceof ronweapons.ShieldItem) return "shield";
        if (stack.getItem() instanceof ronweapons.NvgItem) return "nvg";
        return null;
    }

    public static boolean handles(ItemStack stack) { return rigId(stack) != null; }

    private record Part(RonMesh mesh, float[][] pn, boolean[] hide) {}
    private record Baked(GunRig rig, List<Part> parts) {}

    private static final Map<String, Baked> CACHE = new HashMap<>();

    public static boolean render(ItemStack stack, ModelTransformationMode mode, boolean leftHanded, MatrixStack matrices,
                                 VertexConsumerProvider consumers, int light, int overlay) {
        boolean tp = mode == ModelTransformationMode.THIRD_PERSON_RIGHT_HAND || mode == ModelTransformationMode.THIRD_PERSON_LEFT_HAND;
        String rigId = rigId(stack);
        if (rigId == null) return false;
        // goggles on the head: RoN's mesh sits on its own head bone, no clean place on the vanilla head -> not drawn
        if (mode == ModelTransformationMode.HEAD && rigId.equals("nvg")) return true;
        if (!tp && mode != ModelTransformationMode.GUI && mode != ModelTransformationMode.GROUND && mode != ModelTransformationMode.FIXED) return false;
        WeaponDef def = RonWeapons.def(stack);
        GunRig rig = GunRig.get(MinecraftClient.getInstance().getResourceManager(), rigId);
        if (rig == null) return false;
        String opticId = def == null ? "iron" : def.optics()[RonWeapons.optic(stack)];
        GunRig.Optic optic = rig.optics.get(opticId);
        GunRig.Muzzle device = rig.muzzle(RonWeapons.muzzleId(stack));
        GunRig.Grip grip = rig.grips.get(RonWeapons.gripId(stack));
        String key = rigId + "|" + (optic == null ? "iron" : opticId) + "|" + (device == null ? "-" : device.id()) + "|" + tp + "|" + (grip == null ? "-" : grip.id());
        Baked b = CACHE.get(key);
        if (b == null || b.rig() != rig) CACHE.put(key, b = bake(rig, optic, device, tp, rigId.equals("shield"), grip));

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
            default -> {
                // fist centre is 1 px left and 2 px below the frame origin; undo the crossbow pose's inward yaw; slide back to the shoulder
                matrices.translate(leftHanded ? -rig.tpOffset[0] : rig.tpOffset[0], rig.tpOffset[1], rig.tpOffset[2]);
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(leftHanded ? -rig.tpYaw : rig.tpYaw));
                matrices.translate(0f, 0f, rig.tpBack);
            }
        }
        MatrixStack.Entry e = matrices.peek();
        for (Part p : b.parts()) p.mesh().emit(p.pn()[0], p.pn()[1], e, consumers, light, overlay, p.hide());
        matrices.pop();
        return true;
    }

    /** Bakes once per rig + attachment state. Icons fit the whole silhouette into one item unit; third person keeps real size. */
    private static boolean[] hiddenBones;

    private static Baked bake(GunRig rig, GunRig.Optic optic, GunRig.Muzzle device, boolean tp, boolean shield, GunRig.Grip grip) {
        List<RonMesh> meshes = new ArrayList<>();
        List<Matrix4f> bases = new ArrayList<>();
        List<boolean[]> hides = new ArrayList<>();
        meshes.add(rig.gun); bases.add(null); hides.add(optic == null ? null : optic.hideGun());
        for (GunRig.Attachment at : rig.attachments) {
            meshes.add(at.mesh()); bases.add(rig.gun.bindGlobal[at.sockets()[0]]); hides.add(null);
        }
        if (optic != null) { meshes.add(optic.mesh()); bases.add(rig.gun.bindGlobal[optic.socket()]); hides.add(null); }
        if (device != null) { meshes.add(device.mesh()); bases.add(rig.gun.bindGlobal[device.socket()]); hides.add(null); }
        hiddenBones = device == null ? null : device.hideBones();
        if (rig.light != null) { meshes.add(rig.light.mesh()); bases.add(rig.gun.bindGlobal[rig.light.socket()]); hides.add(null); }
        if (grip != null) { meshes.add(grip.mesh()); bases.add(rig.gun.bindGlobal[grip.socket()]); hides.add(null); }

        Matrix4f axes = tp ? (shield ? SHIELD_TP_AXES : TP_AXES) : AXES;
        if (!tp) {
            float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (int i = 0; i < meshes.size(); i++) bounds(bakeOne(meshes.get(i), AXES, bases.get(i))[0], lo, hi);
            float size = Math.max(hi[0] - lo[0], Math.max(hi[1] - lo[1], hi[2] - lo[2]));
            float s = 1f / Math.max(size, 1e-3f);
            axes = new Matrix4f().scale(s).translate(-(lo[0] + hi[0]) / 2, -(lo[1] + hi[1]) / 2, -(lo[2] + hi[2]) / 2).mul(AXES);
        }
        List<Part> parts = new ArrayList<>();
        for (int i = 0; i < meshes.size(); i++) parts.add(new Part(meshes.get(i), bakeOne(meshes.get(i), axes, bases.get(i)), hides.get(i)));
        return new Baked(rig, parts);
    }

    /** base null = the gun itself (skinned bind pose); otherwise an attachment placed rigidly at a socket. */
    private static float[][] bakeOne(RonMesh mesh, Matrix4f axes, Matrix4f base) {
        return base == null ? mesh.bake(axes, hiddenBones) : mesh.bakeRigid(new Matrix4f(axes).mul(base));
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
