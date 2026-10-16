package ronweapons;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.ElderGuardianEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.mob.WardenEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import ronweapons.net.CuffPayload;
import ronweapons.net.CuffStatePayload;

/**
 * Server side of the zipcuffs. Restraining takes as long as RoN's restrain clip; the mob is held still meanwhile.
 * A cuffed mob keeps the command tag {@link #TAG} (saved with the entity): no AI, no targets, no despawn.
 * Hostile mobs have to be worn down first (RoN: only compliant suspects can be cuffed).
 */
public final class Cuffs {
    public static final String TAG = "ronweapons.cuffed";
    /** fp_zipcuffs_add_restrain_downed: 1.33 s */
    public static final int CUFF_TICKS = 26;
    public static final double REACH = 3.5;

    private record Pending(int entity, int endTick, int slot) {}

    private static final Map<UUID, Pending> pending = new HashMap<>();
    private static final Set<Integer> held = new HashSet<>();
    private static int ticks;

    public static boolean cuffed(Entity e) { return e.getCommandTags().contains(TAG); }

    /** no AI this tick: cuffed, or being cuffed right now */
    public static boolean frozen(MobEntity m) { return cuffed(m) || held.contains(m.getId()); }

    public static boolean canCuff(Entity e) {
        return e instanceof MobEntity m && m.isAlive() && !(e instanceof EnderDragonEntity) && !(e instanceof WitherEntity)
                && !(e instanceof ElderGuardianEntity) && !(e instanceof WardenEntity);
    }

    public static boolean compliant(LivingEntity e) {
        return !(e instanceof Monster) || e.getHealth() <= e.getMaxHealth() * 0.5f;
    }

    static void init() {
        PayloadTypeRegistry.playC2S().register(CuffPayload.ID, CuffPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(CuffStatePayload.ID, CuffStatePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(CuffPayload.ID, (payload, ctx) -> handle(ctx.player(), payload));
        ServerTickEvents.END_SERVER_TICK.register(Cuffs::tick);
        EntityTrackingEvents.START_TRACKING.register((entity, player) -> {
            if (cuffed(entity)) ServerPlayNetworking.send(player, new CuffStatePayload(entity.getId(), CuffStatePayload.CUFFED, -1));
        });
    }

    private static boolean holdingCuffs(ServerPlayerEntity p) { return p.getMainHandStack().getItem() instanceof ZipcuffsItem; }

    private static void handle(ServerPlayerEntity p, CuffPayload msg) {
        Entity e = p.getServerWorld().getEntityById(msg.entity());
        switch (msg.op()) {
            case CuffPayload.START -> {
                if (!holdingCuffs(p) || pending.containsKey(p.getUuid())) return;
                if (e == null || !canCuff(e) || cuffed(e) || held.contains(e.getId()) || p.squaredDistanceTo(e) > REACH * REACH) {
                    ServerPlayNetworking.send(p, new CuffStatePayload(msg.entity(), CuffStatePayload.FREE, p.getId()));
                    return;
                }
                if (!compliant((LivingEntity) e)) {
                    p.sendMessage(Text.translatable("ronweapons.cuffs.not_compliant"), true);
                    ServerPlayNetworking.send(p, new CuffStatePayload(msg.entity(), CuffStatePayload.FREE, p.getId()));
                    return;
                }
                MobEntity m = (MobEntity) e;
                hold(m);
                held.add(m.getId());
                pending.put(p.getUuid(), new Pending(m.getId(), ticks + CUFF_TICKS, p.getInventory().selectedSlot));
                broadcast(m, new CuffStatePayload(m.getId(), CuffStatePayload.CUFFING, p.getId()));
            }
            case CuffPayload.CANCEL -> {
                Pending pd = pending.remove(p.getUuid());
                if (pd != null) release(p.getServer(), pd);
            }
            case CuffPayload.RELEASE -> {
                if (e == null || !cuffed(e) || !holdingCuffs(p) || p.squaredDistanceTo(e) > REACH * REACH) return;
                e.removeCommandTag(TAG);
                e.getWorld().playSound(null, e.getX(), e.getY(), e.getZ(), RonWeapons.SND_CUT, SoundCategory.PLAYERS, 0.8f, 1.0f);
                p.sendMessage(Text.translatable("ronweapons.cuffs.released", e.getDisplayName()), true);
                broadcast(e, new CuffStatePayload(e.getId(), CuffStatePayload.FREE, -1));
            }
            default -> { }
        }
    }

    private static void tick(MinecraftServer server) {
        ticks++;
        for (Iterator<Map.Entry<UUID, Pending>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Pending> en = it.next();
            Pending pd = en.getValue();
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(en.getKey());
            Entity e = p == null ? null : p.getServerWorld().getEntityById(pd.entity());
            boolean ok = p != null && e != null && e.isAlive() && holdingCuffs(p) && p.getInventory().selectedSlot == pd.slot()
                    && p.squaredDistanceTo(e) <= (REACH + 1) * (REACH + 1);
            if (!ok) {
                it.remove();
                release(server, pd);
                continue;
            }
            if (ticks < pd.endTick()) continue;
            it.remove();
            held.remove(pd.entity());
            MobEntity m = (MobEntity) e;
            m.addCommandTag(TAG);
            m.setPersistent();
            hold(m);
            if (!p.getAbilities().creativeMode) p.getMainHandStack().decrement(1);
            m.getWorld().playSound(null, m.getX(), m.getY(), m.getZ(), RonWeapons.SND_CUFF, SoundCategory.PLAYERS, 0.9f, 1.0f);
            p.sendMessage(Text.translatable("ronweapons.cuffs.done", m.getDisplayName()), true);
            broadcast(m, new CuffStatePayload(m.getId(), CuffStatePayload.CUFFED, p.getId()));
        }
    }

    /** cancelled restrain: let the mob go again */
    private static void release(MinecraftServer server, Pending pd) {
        held.remove(pd.entity());
        for (var w : server.getWorlds()) {
            Entity e = w.getEntityById(pd.entity());
            if (e != null && !cuffed(e)) { broadcast(e, new CuffStatePayload(e.getId(), CuffStatePayload.FREE, -1)); return; }
        }
    }

    /** stop whatever the mob was doing: walking, chasing, swelling */
    public static void hold(MobEntity m) {
        m.getNavigation().stop();
        m.setTarget(null);
        m.setAttacking(false);
        m.setForwardSpeed(0);
        m.setSidewaysSpeed(0);
        m.setUpwardSpeed(0);
        m.setJumping(false);
        if (m instanceof CreeperEntity c) c.setFuseSpeed(-1);
    }

    private static void broadcast(Entity e, CuffStatePayload msg) {
        for (ServerPlayerEntity p : PlayerLookup.tracking(e)) ServerPlayNetworking.send(p, msg);
    }
}
