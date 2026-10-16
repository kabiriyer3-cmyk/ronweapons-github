package ronweapons.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws RoN arms + gun in Minecraft camera space (X right, Y up, -Z forward, blocks).
 * view = C * 0.01 * Rbind(fp_camera) * inverse(G(fp_camera)), so the RoN camera bone's game-feel motion is kept.
 * Verified offline with tools/ronconv/diag.py: hands within ~5 cm of the gun, muzzle ~0.63 blocks ahead.
 */
public final class FirstPersonRenderer {
    /** psk -> Minecraft camera axes: x = -x, y = z, z = y (column-major constructor). */
    private static final Matrix4f C = new Matrix4f(-1, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 1);

    private static GunRig rigCache;
    private static float[] alp, alr, wlp, wlr, tap, tar, twp, twr;
    private static Matrix4f[] armsG, gunG, armsSkin, gunSkin;
    private static long lastNs;
    private static float ads;
    private static boolean warned;

    public static boolean render(MatrixStack matrices, VertexConsumerProvider consumers, int light) {
        MinecraftClient mc = MinecraftClient.getInstance();
        GunRig rig = GunRig.get(mc.getResourceManager());
        RonWeaponsClient.ANIM.setRig(rig);
        if (rig == null) {
            if (!warned && mc.player != null) {
                warned = true;
                mc.player.sendMessage(Text.translatable("ronweapons.missing_pack"), false);
            }
            return false;
        }
        warned = false;
        if (rig != rigCache) allocate(rig);

        long now = System.nanoTime();
        float dt = lastNs == 0 ? 0 : Math.min(0.1f, (now - lastNs) / 1e9f);
        lastNs = now;
        GunAnimator anim = RonWeaponsClient.ANIM;
        anim.update(dt);

        System.arraycopy(rig.arms.bindPos, 0, alp, 0, alp.length);
        System.arraycopy(rig.arms.bindRot, 0, alr, 0, alr.length);
        System.arraycopy(rig.gun.bindPos, 0, wlp, 0, wlp.length);
        System.arraycopy(rig.gun.bindRot, 0, wlr, 0, wlr.length);
        anim.pose(rig, alp, alr, wlp, wlr, tap, tar, twp, twr);
        rig.arms.globals(alp, alr, armsG);
        rig.gun.globals(wlp, wlr, gunG);

        Matrix4f view = new Matrix4f(C).scale(0.01f).mul(rig.cameraBindRot).mul(new Matrix4f(armsG[rig.cameraBone]).invert());
        Matrix4f attach = new Matrix4f(view).mul(armsG[rig.attachBone]);

        boolean wantAds = RonWeaponsClient.aiming() && !anim.reloading();
        ads += (wantAds ? 1 : -1) * dt / 0.15f;
        ads = Math.max(0, Math.min(1, ads));
        if (ads > 0 && rig.sightBone >= 0) {
            Vector3f s = new Matrix4f(attach).mul(gunG[rig.sightBone]).getTranslation(new Vector3f());
            float e = ads * ads * (3 - 2 * ads);
            Matrix4f shift = new Matrix4f().translation(-s.x * e, -s.y * e, 0.04f * e);
            view.mulLocal(shift);
            attach.mulLocal(shift);
        }

        for (int b = 0; b < armsG.length; b++) armsSkin[b].set(view).mul(armsG[b]).mul(rig.arms.invBind[b]);
        for (int b = 0; b < gunG.length; b++) gunSkin[b].set(attach).mul(gunG[b]).mul(rig.gun.invBind[b]);
        rig.arms.skin(armsSkin);
        rig.gun.skin(gunSkin);
        MatrixStack.Entry entry = matrices.peek();
        rig.arms.emit(entry, consumers, light);
        rig.gun.emit(entry, consumers, light);
        return true;
    }

    private static void allocate(GunRig rig) {
        rigCache = rig;
        int na = rig.arms.parents.length, ng = rig.gun.parents.length;
        alp = new float[na * 3]; alr = new float[na * 4]; tap = new float[na * 3]; tar = new float[na * 4];
        wlp = new float[ng * 3]; wlr = new float[ng * 4]; twp = new float[ng * 3]; twr = new float[ng * 4];
        armsG = new Matrix4f[na]; gunG = new Matrix4f[ng];
        armsSkin = new Matrix4f[na]; gunSkin = new Matrix4f[ng];
        for (int i = 0; i < na; i++) armsSkin[i] = new Matrix4f();
        for (int i = 0; i < ng; i++) gunSkin[i] = new Matrix4f();
    }
}
