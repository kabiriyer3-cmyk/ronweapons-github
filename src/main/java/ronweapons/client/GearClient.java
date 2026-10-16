package ronweapons.client;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import ronweapons.Gear;
import ronweapons.RonWeapons;
import ronweapons.net.ActionPayload;
import ronweapons.net.FlashPayload;

/**
 * Client side of the gear: screen effects (flashbang white-out + ringing, stinger tunnel vision + shake, CS gas haze,
 * green night vision through two tubes), the NVG and shield-bash keys, and the light beams of weapon lights.
 */
public final class GearClient {
    private static final Identifier VIGNETTE = RonWeapons.id("textures/fx/vignette.png");
    private static final Identifier NVG_MASK = RonWeapons.id("textures/fx/nvg_mask.png");
    private static final double BEAM_RANGE = 24;

    private static KeyBinding nvgKey, bashKey;
    private static long flashStart, flashEnd, stingStart, stingEnd;
    private static float flashStrength, stingStrength, gas;
    private static long lastNs;
    /** first-person light lens (camera space, blocks) and beam direction, refreshed by FirstPersonRenderer */
    private static final Vector3f fpLens = new Vector3f(), fpDir = new Vector3f();
    private static long fpLightNs;

    static void init() {
        nvgKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.ronweapons.nvg", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, "category.ronweapons"));
        bashKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.ronweapons.bash", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_X, "category.ronweapons"));
        ClientPlayNetworking.registerGlobalReceiver(FlashPayload.ID, (payload, ctx) -> onFlash(payload));
        HudRenderCallback.EVENT.register(GearClient::hud);
        WorldRenderEvents.AFTER_ENTITIES.register(GearClient::beams);
    }

    static void fpLight(Vector3f lens, Vector3f dir) {
        fpLens.set(lens);
        fpDir.set(dir);
        fpLightNs = System.nanoTime();
    }

    static void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null) return;
        while (nvgKey.wasPressed()) {
            if (!(p.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD).getItem() instanceof ronweapons.NvgItem)) {
                p.sendMessage(Text.translatable("ronweapons.nvg.none"), true);
                continue;
            }
            boolean on = !Gear.nvgOn(p);
            ClientPlayNetworking.send(new ActionPayload(ActionPayload.TOGGLE_NVG));
            GunAnimator a = RonWeaponsClient.ANIM;
            if (RonWeaponsClient.holdingRig() && a.canAct()) a.action(on ? "nvg_on" : "nvg_off");
        }
        while (bashKey.wasPressed()) {
            if (!Gear.hasShield(p)) continue;
            GunAnimator a = RonWeaponsClient.ANIM;
            if (RonWeaponsClient.shieldRig()) {
                if (!a.canAct() && !a.current().startsWith("fire")) continue;
                a.action(RonWeaponsClient.aiming() ? "bash_up" : "bash");
            }
            ClientPlayNetworking.send(new ActionPayload(ActionPayload.SHIELD_BASH));
        }
        // stinger: the view kicks around a bit
        float s = stingLeft();
        if (s > 0 && !mc.isPaused()) {
            float amp = s * stingStrength * 1.6f;
            p.setPitch(p.getPitch() + (p.getRandom().nextFloat() - 0.5f) * amp);
            p.setYaw(p.getYaw() + (p.getRandom().nextFloat() - 0.5f) * amp);
        }
    }

    private static void onFlash(FlashPayload f) {
        MinecraftClient mc = MinecraftClient.getInstance();
        long now = System.nanoTime(), dur = (long) (f.seconds() * 1e9);
        switch (f.kind()) {
            case FlashPayload.FLASH -> {
                if (f.strength() >= flashLevel(now)) {
                    flashStrength = f.strength();
                    flashStart = now;
                    flashEnd = now + dur;
                }
                RonWeaponsClient.playLocal(RonWeapons.id("fx.tinnitus"), Math.min(1f, 0.25f + f.strength()), 1f);
                if (f.strength() > 0.3f) RonWeaponsClient.ANIM.affect("flash", f.seconds() * 0.6f);
            }
            case FlashPayload.STING -> {
                stingStrength = Math.max(stingStrength * stingLeft(), f.strength());
                stingStart = now;
                stingEnd = now + dur;
                RonWeaponsClient.ANIM.affect("sting", Math.min(2.5f, f.seconds()));
            }
            case FlashPayload.GAS -> {
                gas = Math.min(1f, gas + 0.35f);
                RonWeaponsClient.ANIM.affect("gas", 2.5f);
            }
            default -> { }
        }
        if (mc.player == null) return;
    }

    /** current white-out 0..1: full for a moment (longer when stronger), then a slow fade */
    private static float flashLevel(long now) {
        if (now >= flashEnd) return 0f;
        float t = (now - flashStart) / 1e9f, total = (flashEnd - flashStart) / 1e9f;
        float hold = 0.4f + 1.6f * flashStrength;
        if (t < hold) return flashStrength;
        float k = 1f - (t - hold) / Math.max(0.1f, total - hold);
        return flashStrength * k * k;
    }

    private static float stingLeft() {
        long now = System.nanoTime();
        if (now >= stingEnd) return 0f;
        return 1f - (now - stingStart) / (float) Math.max(1, stingEnd - stingStart);
    }

    // ------------------------------------------------------------------ HUD

    private static void hud(DrawContext ctx, net.minecraft.client.render.RenderTickCounter tc) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 0 : Math.min(0.1f, (now - lastNs) / 1e9f);
        lastNs = now;
        gas = Math.max(0f, gas - dt * 0.12f);
        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();

        if (Gear.nvgOn(mc.player)) {
            // phosphor green over the night-vision lit world, two tubes, a touch of noise
            ctx.fill(0, 0, w, h, 0x5530FF50);
            for (int i = 0; i < 90; i++) {
                int x = mc.player.getRandom().nextInt(w), y = mc.player.getRandom().nextInt(h);
                ctx.fill(x, y, x + 1, y + 1, 0x40C8FFC8);
            }
            texture(ctx, NVG_MASK, w, h, 1f, 1f, 1f, 1f);
        }
        if (gas > 0.01f) {
            ctx.fill(0, 0, w, h, argb(Math.min(0.55f, gas * 0.6f), 0.93f, 0.92f, 0.8f));
            texture(ctx, VIGNETTE, w, h, 0.85f, 0.8f, 0.55f, Math.min(1f, gas));
        }
        float s = stingLeft();
        if (s > 0) {
            float pulse = 0.75f + 0.25f * MathHelper.sin((now % 1_000_000_000L) / 1e9f * MathHelper.TAU * 2f);
            texture(ctx, VIGNETTE, w, h, 0.45f, 0.02f, 0.02f, Math.min(1f, s * stingStrength * 1.4f * pulse));
        }
        float f = flashLevel(now);
        if (f > 0.003f) ctx.fill(0, 0, w, h, argb(Math.min(1f, f), 1f, 1f, 0.98f));
    }

    private static int argb(float a, float r, float g, float b) {
        return ((int) (a * 255) << 24) | ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }

    private static void texture(DrawContext ctx, Identifier tex, int w, int h, float r, float g, float b, float a) {
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        ctx.setShaderColor(r, g, b, a);
        ctx.drawTexture(tex, 0, 0, w, h, 0f, 0f, 256, 144, 256, 144);
        ctx.setShaderColor(1f, 1f, 1f, 1f);
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
    }

    // ------------------------------------------------------------------ light beams

    private static void beams(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;
        Camera cam = ctx.camera();
        Vec3d cp = cam.getPos();
        float td = ctx.tickCounter().getTickDelta(false);
        VertexConsumerProvider.Immediate imm = mc.getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer vc = null;
        MatrixStack ms = ctx.matrixStack() != null ? ctx.matrixStack() : new MatrixStack();
        Matrix4f pm = ms.peek().getPositionMatrix();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (!Gear.lightOn(p.getMainHandStack()) || p.isSpectator()) continue;
            Vec3d from, dir;
            boolean self = p == mc.player && mc.options.getPerspective().isFirstPerson();
            if (self && System.nanoTime() - fpLightNs < 150_000_000L) {
                Quaternionf look = new Quaternionf(cam.getRotation());
                from = cp.add(new Vec3d(look.transform(new Vector3f(fpLens))));
                dir = new Vec3d(look.transform(new Vector3f(fpDir))).normalize();
            } else {
                Quaternionf look = new Quaternionf().rotationYXZ((float) Math.PI - p.getYaw(td) * MathHelper.RADIANS_PER_DEGREE,
                        -p.getPitch(td) * MathHelper.RADIANS_PER_DEGREE, 0f);
                from = p.getCameraPosVec(td).add(new Vec3d(look.transform(new Vector3f(0.18f, -0.18f, -0.75f))));
                dir = p.getRotationVec(td);
            }
            BlockHitResult hit = mc.world.raycast(new RaycastContext(from, from.add(dir.multiply(BEAM_RANGE)),
                    RaycastContext.ShapeType.VISUAL, RaycastContext.FluidHandling.NONE, p));
            double len = hit.getType() == HitResult.Type.MISS ? BEAM_RANGE : Math.max(0.3, hit.getPos().distanceTo(from));
            if (vc == null) vc = imm.getBuffer(RenderLayer.getLightning());
            cone(vc, pm, from.subtract(cp), dir, len, self ? 0.012 : 0.03, Math.tan(Math.toRadians(11)), self ? 26 : 40);
        }
        if (vc != null) imm.draw();
    }

    /** a soft additive cone, both windings so it is visible from inside and outside */
    private static void cone(VertexConsumer vc, Matrix4f pm, Vec3d o, Vec3d d, double len, double r0, double spread, int alpha) {
        Vec3d up = Math.abs(d.y) > 0.95 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
        Vec3d u = d.crossProduct(up).normalize(), v = d.crossProduct(u).normalize();
        Vec3d e = o.add(d.multiply(len));
        double r1 = r0 + len * spread;
        int n = 10;
        for (int i = 0; i < n; i++) {
            double a0 = i * Math.PI * 2 / n, a1 = (i + 1) * Math.PI * 2 / n;
            Vec3d c0 = u.multiply(Math.cos(a0)).add(v.multiply(Math.sin(a0))), c1 = u.multiply(Math.cos(a1)).add(v.multiply(Math.sin(a1)));
            Vec3d p0 = o.add(c0.multiply(r0)), p1 = o.add(c1.multiply(r0)), q1 = e.add(c1.multiply(r1)), q0 = e.add(c0.multiply(r1));
            quad(vc, pm, p0, p1, q1, q0, alpha);
            quad(vc, pm, p1, p0, q0, q1, alpha);
        }
    }

    private static void quad(VertexConsumer vc, Matrix4f pm, Vec3d a, Vec3d b, Vec3d c, Vec3d d, int alpha) {
        vc.vertex(pm, (float) a.x, (float) a.y, (float) a.z).color(255, 246, 220, alpha);
        vc.vertex(pm, (float) b.x, (float) b.y, (float) b.z).color(255, 246, 220, alpha);
        vc.vertex(pm, (float) c.x, (float) c.y, (float) c.z).color(255, 246, 220, 0);
        vc.vertex(pm, (float) d.x, (float) d.y, (float) d.z).color(255, 246, 220, 0);
    }
}
