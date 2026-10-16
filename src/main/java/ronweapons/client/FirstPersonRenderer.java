package ronweapons.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.item.ItemStack;
import ronweapons.RonWeapons;
import ronweapons.WeaponDef;

/**
 * Draws RoN arms + gun (+ magazines / rounds) in Minecraft camera space (X right, Y up, -Z forward, blocks).
 * view = C * 0.01 * Rbind(fp_camera) * inverse(G(fp_camera)), so the RoN camera bone's game-feel motion is kept.
 *
 * ADS: one rigid correction D moves the whole rig (arms and gun together, so the hands stay on the gun) from the
 * idle hip pose to a pose where the sight line runs through the eye: Gads = C * 0.01 * T(-eye) in gun space.
 * D is computed once from the idle pose, so recoil / sway from the RoN clips still plays on top while aiming.
 * Pistols keep their hip distance to the eye (rig.adsKeepDistance) and only get centred on the sights.
 */
public final class FirstPersonRenderer {
    /** psk -> Minecraft camera axes: x = -x, y = z, z = y (column-major constructor). */
    private static final Matrix4f C = new Matrix4f(-1, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 1);
    private static final float ADS_TIME = 0.18f;

    private static GunRig rigCache;
    private static float[] alp, alr, wlp, wlr, tap, tar, twp, twr;
    private static Matrix4f[] armsG, gunG, armsSkin, gunSkin;
    private static final Matrix4f[] oneSkin = {new Matrix4f()};
    /** ADS correction per optic id ("iron" = iron sights): translation + rotation from the idle hip pose */
    private record Ads(Vector3f t, Quaternionf q) {}
    private static final Map<String, Ads> adsByOptic = new HashMap<>();
    private static long lastNs;
    private static float ads, kick, kickScale;
    private static String warnedFor;

    /** Procedural kick for weapons whose RoN fire clip is missing or mild (WeaponDef.viewKick). */
    public static void kick(float scale) {
        if (scale <= 0) return;
        kick = Math.min(1.6f, kick + 1f);
        kickScale = scale;
    }

    public static boolean render(MatrixStack matrices, VertexConsumerProvider consumers, int light) {
        MinecraftClient mc = MinecraftClient.getInstance();
        WeaponDef def = RonWeaponsClient.heldDef();
        GunRig rig = def == null ? null : GunRig.get(mc.getResourceManager(), def.id());
        RonWeaponsClient.ANIM.setRig(rig);
        if (rig == null) {
            if (def != null && !def.id().equals(warnedFor) && mc.player != null) {
                warnedFor = def.id();
                mc.player.sendMessage(Text.translatable("ronweapons.missing_pack"), false);
            }
            return false;
        }
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
        ItemStack stack = mc.player.getMainHandStack();
        GunRig.Optic optic = rig.optics.get(def.optics()[RonWeapons.optic(stack)]);
        Ads adsD = adsByOptic.getOrDefault(optic == null ? "iron" : optic.id(), adsByOptic.get("iron"));
        Vector3f adsT = adsD.t();
        Quaternionf adsQ = adsD.q();

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
        if (kick > 0.001f) {
            float k = kick * kickScale * (ads > 0.5f ? 0.6f : 1f);
            Matrix4f d = new Matrix4f().translation(0, 0.004f * k, 0.035f * k).rotateX((float) Math.toRadians(7.0 * k));
            view.mulLocal(d);
            attach.mulLocal(d);
            kick *= (float) Math.exp(-dt * 13.0);
        }

        // camera-space sockets for world effects (tracer start, brass, dropped mag)
        int muzzle = rig.suppressor != null && RonWeapons.suppressed(stack) ? rig.suppressor.socket() : rig.muzzleBone;
        Fx.Fp.update(muzzle < 0 ? null : new Matrix4f(attach).mul(gunG[muzzle]),
                rig.fx == null || rig.fx.ejectBone() < 0 ? null : new Matrix4f(attach).mul(gunG[rig.fx.ejectBone()]),
                rig.fx == null || rig.fx.magSocket() < 0 ? null : new Matrix4f(attach).mul(gunG[0])
                        .mul(new Matrix4f(rig.gun.bindGlobal[0]).invert()).mul(rig.gun.bindGlobal[rig.fx.magSocket()]));
        for (int b = 0; b < armsG.length; b++) armsSkin[b].set(view).mul(armsG[b]).mul(rig.arms.invBind[b]);
        for (int b = 0; b < gunG.length; b++) gunSkin[b].set(attach).mul(gunG[b]).mul(rig.gun.invBind[b]);
        MatrixStack.Entry entry = matrices.peek();
        rig.arms.skin(armsSkin);
        rig.arms.emit(entry, consumers, light);
        rig.gun.skin(gunSkin);
        rig.gun.emit(entry, consumers, light, optic == null ? null : optic.hideGun());
        renderAttachments(rig, attach, entry, consumers, light);
        if (optic != null) renderOnSocket(optic.mesh(), optic.socket(), attach, entry, consumers, light);
        if (rig.suppressor != null && RonWeapons.suppressed(stack)) {
            renderOnSocket(rig.suppressor.mesh(), rig.suppressor.socket(), attach, entry, consumers, light);
        }
        return true;
    }

    /** Optics / suppressors: skeletal attachment meshes placed rigidly in socket space, like UE attaches the component. */
    private static void renderOnSocket(RonMesh mesh, int socket, Matrix4f attach, MatrixStack.Entry entry, VertexConsumerProvider consumers, int light) {
        mesh.skinRigid(new Matrix4f(attach).mul(gunG[socket]));
        mesh.emit(entry, consumers, light);
    }

    /** Magazines / rounds are separate static meshes in RoN. Later sockets are skipped while they sit on the first. */
    private static void renderAttachments(GunRig rig, Matrix4f attach, MatrixStack.Entry entry, VertexConsumerProvider consumers, int light) {
        Vector3f p0 = new Vector3f(), pi = new Vector3f();
        for (GunRig.Attachment at : rig.attachments) {
            int[] s = at.sockets();
            gunG[s[0]].getTranslation(p0);
            for (int i = 0; i < s.length; i++) {
                if (i > 0 && gunG[s[i]].getTranslation(pi).distance(p0) <= 0.5f) continue;
                if (!Float.isNaN(at.hideAboveY()) && gunG[s[i]].getTranslation(pi).y > at.hideAboveY()) continue;
                oneSkin[0].set(attach).mul(gunG[s[i]]).mul(at.mesh().invBind[0]);
                at.mesh().skin(oneSkin);
                at.mesh().emit(entry, consumers, light);
            }
        }
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

        // ADS corrections from the idle pose at t = 0: iron sights + every optic of this rig
        resetToBind(rig);
        GunAnimator.sample(rig, "idle", 0f, true, false, alp, alr, wlp, wlr);
        rig.arms.globals(alp, alr, armsG);
        rig.gun.globals(wlp, wlr, gunG);
        Matrix4f hip = viewOf(rig).mul(armsG[rig.attachBone]).mul(gunG[0]);
        adsByOptic.clear();
        adsByOptic.put("iron", adsFor(hip, rig.adsEye, rig.adsKeepDistance));
        for (GunRig.Optic o : rig.optics.values()) adsByOptic.put(o.id(), adsFor(hip, o.eye(), o.keepDistance()));
        ads = 0;
        kick = 0;
    }

    private static Ads adsFor(Matrix4f hip, float[] eye, boolean keepDistance) {
        float ex = eye[0], ey = eye[1], ez = eye[2];
        if (keepDistance) {
            Vector3f cam = new Matrix4f(hip).invert().transformPosition(new Vector3f()); // camera in gun space, cm
            ey = Math.max(12f, cam.y);
        }
        Matrix4f aim = new Matrix4f(C).scale(0.01f).translate(-ex, -ey, -ez);
        Matrix4f d = aim.mul(new Matrix4f(hip).invert());
        Ads a = new Ads(new Vector3f(), new Quaternionf());
        d.getTranslation(a.t());
        d.getUnnormalizedRotation(a.q()).normalize();
        return a;
    }
}
