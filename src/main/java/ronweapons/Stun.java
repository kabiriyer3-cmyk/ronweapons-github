package ronweapons;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import ronweapons.net.CuffStatePayload;

/**
 * Less-lethal effect on mobs (flashbang, stinger, CS gas, shield bash): no AI for a while, hands up (RoN suspects comply),
 * and they count as compliant for the zipcuffs whatever their health.
 */
public final class Stun {
    private record Entry(ServerWorld world, int until) {}

    private static final Map<Integer, Entry> STUNNED = new HashMap<>();
    private static int ticks;

    static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            ticks++;
            for (Iterator<Map.Entry<Integer, Entry>> it = STUNNED.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Integer, Entry> e = it.next();
                if (ticks < e.getValue().until()) continue;
                it.remove();
                Entity m = e.getValue().world().getEntityById(e.getKey());
                if (m instanceof MobEntity mm && !Cuffs.frozen(mm)) {
                    broadcast(m, new CuffStatePayload(m.getId(), CuffStatePayload.FREE, -1));
                }
            }
        });
    }

    public static boolean stunned(Entity e) {
        Entry s = STUNNED.get(e.getId());
        return s != null && ticks < s.until();
    }

    /** stun for at least this many ticks (longer stuns are kept) */
    public static void stun(MobEntity m, int forTicks) {
        if (!Cuffs.canCuff(m) || !(m.getWorld() instanceof ServerWorld w)) return;
        boolean was = stunned(m);
        Entry old = STUNNED.get(m.getId());
        int until = Math.max(old == null ? 0 : old.until(), ticks + forTicks);
        STUNNED.put(m.getId(), new Entry(w, until));
        Cuffs.hold(m);
        if (!was && !Cuffs.cuffed(m)) broadcast(m, new CuffStatePayload(m.getId(), CuffStatePayload.STUNNED, -1));
    }

    private static void broadcast(Entity e, CuffStatePayload msg) {
        for (ServerPlayerEntity p : PlayerLookup.tracking(e)) ServerPlayNetworking.send(p, msg);
    }
}
