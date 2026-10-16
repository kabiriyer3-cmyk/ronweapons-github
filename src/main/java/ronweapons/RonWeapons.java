package ronweapons;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import ronweapons.net.ActionPayload;
import ronweapons.net.FirePayload;

public final class RonWeapons implements ModInitializer {
    public static final String MODID = "ronweapons";
    public static final double RANGE = 160.0;

    public static Identifier id(String path) { return Identifier.of(MODID, path); }

    /** Sounds every weapon has. The shooter hears 1P versions client-side right away; others get these from the server. */
    public record Sounds(SoundEvent fire, SoundEvent reload, SoundEvent dry) {}

    public static final Map<String, GunItem> ITEMS = new LinkedHashMap<>();
    public static final Map<String, Sounds> SOUNDS = new HashMap<>();
    public static final SoundEvent SND_SELECT = sound("fireselect");

    static {
        for (WeaponDef d : WeaponDef.ALL) {
            ITEMS.put(d.id(), Registry.register(Registries.ITEM, id(d.id()),
                    new GunItem(d, new Item.Settings().maxCount(1).maxDamage(d.magSize()))));
            SOUNDS.put(d.id(), new Sounds(sound(d.id() + ".fire"), sound(d.id() + ".reload"), sound(d.id() + ".dryfire")));
        }
    }

    public static final Item M4A1 = ITEMS.get("m4a1");

    private static SoundEvent sound(String path) {
        return Registry.register(Registries.SOUND_EVENT, id(path), SoundEvent.of(id(path)));
    }

    /** pending reloads: refill the gun in this hotbar slot at this server tick (switching away cancels it) */
    private record Reload(int endTick, int slot) {}
    private static final Map<UUID, Reload> reloadEnds = new HashMap<>();
    private static final Map<UUID, Long> lastShot = new HashMap<>();
    private static int serverTicks;

    public static int ammo(ItemStack stack) { return stack.getMaxDamage() - stack.getDamage(); }

    public static WeaponDef def(ItemStack stack) {
        return stack.getItem() instanceof GunItem g ? g.def : null;
    }

    @Override
    public void onInitialize() {
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.COMBAT).register(entries -> ITEMS.values().forEach(item -> entries.add(item)));

        PayloadTypeRegistry.playC2S().register(FirePayload.ID, FirePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ActionPayload.ID, ActionPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(FirePayload.ID, (payload, ctx) -> fire(ctx.player()));
        ServerPlayNetworking.registerGlobalReceiver(ActionPayload.ID, (payload, ctx) -> action(ctx.player(), payload.action()));

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            serverTicks++;
            reloadEnds.entrySet().removeIf(e -> {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
                if (p == null || p.getInventory().selectedSlot != e.getValue().slot()) return true;
                if (serverTicks < e.getValue().endTick()) return false;
                if (p.getMainHandStack().getItem() instanceof GunItem) p.getMainHandStack().setDamage(0);
                return true;
            });
        });
    }

    private static void action(ServerPlayerEntity player, byte action) {
        ItemStack stack = player.getMainHandStack();
        WeaponDef d = def(stack);
        if (d == null) return;
        if (action == ActionPayload.RELOAD_TAC || action == ActionPayload.RELOAD_EMPTY) {
            if (reloadEnds.containsKey(player.getUuid()) || ammo(stack) >= d.magSize()) return;
            int ticks = ammo(stack) > 0 ? d.reloadTacTicks() : d.reloadEmptyTicks();
            reloadEnds.put(player.getUuid(), new Reload(serverTicks + ticks, player.getInventory().selectedSlot));
            player.getWorld().playSound(player, player.getX(), player.getY(), player.getZ(), SOUNDS.get(d.id()).reload(),
                    SoundCategory.PLAYERS, 0.8f, 1.0f);
        }
    }

    private static void fire(ServerPlayerEntity player) {
        ItemStack stack = player.getMainHandStack();
        WeaponDef d = def(stack);
        if (d == null || player.isSpectator()) return;
        if (reloadEnds.containsKey(player.getUuid())) return;
        long now = System.nanoTime();
        Long last = lastShot.get(player.getUuid());
        if (last != null && now - last < d.serverIntervalNs()) return;
        ServerWorld world = player.getServerWorld();
        Sounds snd = SOUNDS.get(d.id());
        if (ammo(stack) <= 0) {
            world.playSound(player, player.getX(), player.getY(), player.getZ(), snd.dry(), SoundCategory.PLAYERS, 0.6f, 1.0f);
            return;
        }
        lastShot.put(player.getUuid(), now);
        // rounds are spent in creative too: otherwise the client counter resyncs back to a full mag
        stack.setDamage(Math.min(d.magSize(), stack.getDamage() + 1));
        world.playSound(player, player.getX(), player.getY(), player.getZ(), snd.fire(), SoundCategory.PLAYERS, 2.0f,
                0.95f + world.random.nextFloat() * 0.1f);

        Vec3d start = player.getEyePos();
        Vec3d dir = player.getRotationVec(1.0f);
        Vec3d end = start.add(dir.multiply(RANGE));
        BlockHitResult block = world.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, player));
        Vec3d stop = block.getType() == HitResult.Type.MISS ? end : block.getPos();
        Box box = player.getBoundingBox().stretch(dir.multiply(RANGE)).expand(1.0);
        EntityHitResult hit = ProjectileUtil.raycast(player, start, stop, box,
                e -> !e.isSpectator() && e.canHit() && e != player, start.squaredDistanceTo(stop));

        if (hit != null) {
            Entity target = hit.getEntity();
            float dmg = d.damage();
            if (target instanceof LivingEntity living && hit.getPos().y > living.getEyeY() - 0.25) dmg *= 2.0f;
            target.timeUntilRegen = 0;
            target.damage(player.getDamageSources().playerAttack(player), dmg);
            Vec3d p = hit.getPos();
            world.spawnParticles(ParticleTypes.DAMAGE_INDICATOR, p.x, p.y, p.z, 3, 0.1, 0.1, 0.1, 0.05);
        } else if (block.getType() == HitResult.Type.BLOCK) {
            BlockState state = world.getBlockState(block.getBlockPos());
            Vec3d p = block.getPos();
            world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, state), p.x, p.y, p.z, 8, 0.05, 0.05, 0.05, 0.15);
            world.spawnParticles(ParticleTypes.SMOKE, p.x, p.y, p.z, 2, 0.02, 0.02, 0.02, 0.01);
        }
    }
}
