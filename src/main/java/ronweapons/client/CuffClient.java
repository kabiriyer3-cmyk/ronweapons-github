package ronweapons.client;

import java.util.HashMap;
import java.util.Map;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.text.Text;
import net.minecraft.util.hit.EntityHitResult;
import ronweapons.Cuffs;
import ronweapons.ZipcuffsItem;
import ronweapons.net.CuffPayload;
import ronweapons.net.CuffStatePayload;

/**
 * Client side of the zipcuffs: hold right click on a mob to play RoN's restrain clip while the server times the cuffing,
 * and the cuff state of every mob in view for the procedural poses (hands up, hands behind the back, kneeling).
 */
public final class CuffClient {
    /** seconds of the mob transition: hands up, then behind the back and down on the knees */
    public static final float POSE_TIME = 1.3f;
    private static final long CUFF_NS = (long) (Cuffs.CUFF_TICKS / 20.0 * 1e9);

    private record State(byte state, long startNs, int cuffer) {}

    private static final Map<Integer, State> STATES = new HashMap<>();
    /** players restraining someone right now -> start time */
    private static final Map<Integer, Long> CUFFERS = new HashMap<>();
    private static ClientWorld lastWorld;
    private static int target = -1;
    private static long targetNs;
    private static boolean wasUse;

    public static void onState(CuffStatePayload p) {
        long now = System.nanoTime();
        State old = STATES.get(p.entity());
        if (old != null && old.cuffer() >= 0) CUFFERS.remove(old.cuffer());
        switch (p.state()) {
            case CuffStatePayload.CUFFING -> {
                STATES.put(p.entity(), new State(p.state(), now, p.cuffer()));
                CUFFERS.put(p.cuffer(), now);
            }
            case CuffStatePayload.CUFFED -> STATES.put(p.entity(), new State(p.state(),
                    old != null ? old.startNs() : now - 10_000_000_000L, -1));
            default -> {
                STATES.remove(p.entity());
                if (p.entity() == target) abort(false);
            }
        }
    }

    /** -1 free, 0..1 being cuffed (pose transition), 1 cuffed */
    public static float progress(Entity e) {
        State s = STATES.get(e.getId());
        if (s == null) return -1f;
        float t = (System.nanoTime() - s.startNs()) / 1e9f / POSE_TIME;
        return s.state() == CuffStatePayload.CUFFED ? Math.min(1f, Math.max(t, 0.75f)) : Math.min(0.95f, t);
    }

    public static boolean cuffed(Entity e) {
        State s = STATES.get(e.getId());
        return s != null && s.state() == CuffStatePayload.CUFFED;
    }

    /** seconds since this player started restraining someone, or -1 */
    public static float cuffing(Entity player) {
        Long t = CUFFERS.get(player.getId());
        return t == null ? -1f : (System.nanoTime() - t) / 1e9f;
    }

    public static boolean holdingCuffs() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null && mc.player.getMainHandStack().getItem() instanceof ZipcuffsItem;
    }

    private static void abort(boolean tellServer) {
        if (target >= 0 && tellServer) ClientPlayNetworking.send(new CuffPayload(CuffPayload.CANCEL, target));
        target = -1;
        if (RonWeaponsClient.ANIM.current().equals("restrain")) RonWeaponsClient.ANIM.toIdle();
    }

    public static void tick(MinecraftClient mc) {
        if (mc.world != lastWorld) {
            lastWorld = mc.world;
            STATES.clear();
            CUFFERS.clear();
            target = -1;
        }
        ClientPlayerEntity pl = mc.player;
        if (pl == null) return;
        boolean use = mc.currentScreen == null && mc.options.useKey.isPressed();
        if (!holdingCuffs()) {
            if (target >= 0) abort(true);
            wasUse = false;
            return;
        }
        long now = System.nanoTime();
        if (target >= 0) {
            Entity e = mc.world.getEntityById(target);
            boolean done = now - targetNs >= CUFF_NS;
            if (e == null || e.squaredDistanceTo(pl) > (Cuffs.REACH + 1) * (Cuffs.REACH + 1) || (!use && !done)) abort(true);
            else if (done) target = -1; // the server finishes it on its own clock
        } else if (use && !wasUse && RonWeaponsClient.ANIM.canAct()) {
            Entity e = mc.crosshairTarget instanceof EntityHitResult h ? h.getEntity() : null;
            if (e instanceof MobEntity m && e.squaredDistanceTo(pl) <= Cuffs.REACH * Cuffs.REACH) {
                if (cuffed(e)) {
                    if (pl.isSneaking()) ClientPlayNetworking.send(new CuffPayload(CuffPayload.RELEASE, e.getId()));
                    else pl.sendMessage(Text.translatable("ronweapons.cuffs.already"), true);
                } else if (Cuffs.canCuff(e)) {
                    if (m instanceof Monster && ((LivingEntity) m).getHealth() > m.getMaxHealth() * 0.5f) {
                        pl.sendMessage(Text.translatable("ronweapons.cuffs.not_compliant"), true);
                    } else {
                        target = e.getId();
                        targetNs = now;
                        RonWeaponsClient.ANIM.action("restrain");
                        ClientPlayNetworking.send(new CuffPayload(CuffPayload.START, target));
                    }
                }
            }
        }
        wasUse = use;
    }
}
