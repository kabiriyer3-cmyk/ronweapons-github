package ronweapons.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws RoN arms + gun (+ the separate magazine mesh) in Minecraft camera space (X right, Y up, -Z forward, blocks).
 * view = C * 0.01 * Rbind(fp_camera) * inverse(G(fp_camera)), so the RoN camera bone's game-feel motion is kept.
 * Verified offline with tools/ronconv/diag.py: hands within ~5 cm of the gun, muzzle ~0.63 blocks ahead.
 *
 * ADS: one rigid correction D moves the whole rig (arms and gun together, so the hands stay on the rifle) from the
 * idle hip pose to a pose where the iron-sight line runs through the eye: Gads = C * 0.01 * T(-eye) in gun space.
 * D is computed once from the idle pose, so recoil / sway from the RoN clips still plays on top while aiming.
 */
public final class FirstPersonRenderer {
    /** psk -> Minecraft camera axes: x = -x, y = z, z = y (column-major constructor). */
    private static final Matrix4f C = new Matrix4f(-1, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 1);
    private static final float ADS_TIME = 0.18f;

    private static GunRig rigCache;
    private static float[] alp, alr, wlp, wlr, tap, tar, twp, twr;
    private static Matrix4f[] armsG, gunG, armsSkin, gunSkin;
    private static final Matrix4f[] magSkin = {new Matrix4f()};
    private static final Vector3f adsT = new Vector3f();
    private static final Quaternionf adsQ = new Quaternionf();
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

        resetToBind(rig);
        anim.pose(rig, alp, alr, wlp, wlr, tap, tar, twp, twr);
        rig.arms.globals(alp, alr, armsG);
        rig.gun.globals(wlp, wlr, gunG);

        Matrix4f view = viewOf(rig);
        Matrix4f attach = new Matrix4f(view).mul(armsG[rig.attachBone]);

        boolean wantAds = RonWeaponsClient.aiming() && !anim.reloading();
        ads += (wantAds ? 1 : -1) * dt / ADS_TIME;
        ads = Math.max(0, Math.min(1, ads));
        if (ads > 0) {
            float e = ads * ads * (3 - 2 * ads);
            Matrix4f d = new Matrix4f()
                    .translation(adsT.x * e, adsT.y * e, adsT.z * e)
                    .rotate(new Quaternionf().slerp(adsQ, e));
            view.mulLocal(d);
            attach.mulLocal(d);
        }

        for (int b = 0; b < armsG.length; b++) armsSkin[b].set(view).mul(armsG[b]).mul(rig.arms.invBind[b]);
        for (int b = 0; b < gunG.length; b++) gunSkin[b].set(attach).mul(gunG[b]).mul(rig.gun.invBind[b]);
        MatrixStack.Entry entry = matrices.peek();
        rig.arms.skin(armsSkin);
        rig.arms.emit(entry, consumers, light);
        rig.gun.skin(gunSkin);
        rig.gun.emit(entry, consumers, light);
        renderMags(rig, attach, entry, consumers, light);
        return true;
    }

    /** The magazine is its own static mesh in RoN. Both sockets are drawn unless mag 2 sits in the same spot. */
    private static void renderMags(GunRig rig, Matrix4f attach, MatrixStack.Entry entry, VertexConsumerProvider consumers, int light) {
        if (rig.mag == null) return;
        if (rig.mag1Socket >= 0) drawMag(rig, attach, rig.mag1Socket, entry, consumers, light);
        if (rig.mag2Socket >= 0) {
            Vector3f p1 = gunG[rig.mag1Socket >= 0 ? rig.mag1Socket : 0].getTranslation(new Vector3f());
            Vector3f p2 = gunG[rig.mag2Socket].getTranslation(new Vector3f());
            if (p1.distance(p2) > 0.5f) drawMag(rig, attach, rig.mag2Socket, entry, consumers, light);
        }
    }

    private static void drawMag(GunRig rig, Matrix4f attach, int socket, MatrixStack.Entry entry, VertexConsumerProvider consumers, int light) {
        magSkin[0].set(attach).mul(gunG[socket]).mul(rig.mag.invBind[0]);
        rig.mag.skin(magSkin);
        rig.mag.emit(entry, consumers, light);
    }

    private static Matrix4f viewOf(GunRig rig) {
        return new Matrix4f(C).scale(0.01f).mul(rig.cameraBindRot).mul(new Matrix4f(armsG[rig.cameraBone]).invert());
    }

    private static void resetToBind(GunRig rig) {
        System.arraycopy(rig.arms.bindPos, 0, alp, 0, alp.length);
        System.arraycopy(rig.arms.bindRot, 0, alr, 0, alr.length);
        System.arraycopy(rig.gun.bindPos, 0, wlp, 0, wlp.length);
        System.arraycopy(rig.gun.bindRot, 0, wlr, 0, wlr.length);
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

        // ADS correction from the idle pose at t = 0
        resetToBind(rig);
        GunAnimator.sample(rig, "idle", 0f, true, false, alp, alr, wlp, wlr);
        rig.arms.globals(alp, alr, armsG);
        rig.gun.globals(wlp, wlr, gunG);
        Matrix4f hip = viewOf(rig).mul(armsG[rig.attachBone]).mul(gunG[0]);
        Matrix4f aim = new Matrix4f(C).scale(0.01f).translate(-rig.adsEye[0], -rig.adsEye[1], -rig.adsEye[2]);
        Matrix4f d = aim.mul(hip.invert());
        d.getTranslation(adsT);
        d.getUnnormalizedRotation(adsQ).normalize();
    }
}
