package ronweapons;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.component.ComponentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.component.type.AttributeModifierSlot;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.network.codec.PacketCodecs;
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
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import java.util.ArrayList;
import java.util.List;
import ronweapons.net.ActionPayload;
import ronweapons.net.FxPayload;
import ronweapons.net.FirePayload;

public final class RonWeapons implements ModInitializer {
    public static final String MODID = "ronweapons";
    public static final double RANGE = 160.0;
    public static final double SHOTGUN_RANGE = 48.0;

    public static Identifier id(String path) { return Identifier.of(MODID, path); }

    /** Attachments live on the stack, so they survive drops, chests and other players see them. */
    public static final ComponentType<Integer> OPTIC = Registry.register(Registries.DATA_COMPONENT_TYPE, id("optic"),
            ComponentType.<Integer>builder().codec(Codec.INT).packetCodec(PacketCodecs.VAR_INT).build());
    public static final ComponentType<Boolean> SUPPRESSOR = Registry.register(Registries.DATA_COMPONENT_TYPE, id("suppressor"),
            ComponentType.<Boolean>builder().codec(Codec.BOOL).packetCodec(PacketCodecs.BOOL).build());
    /** foregrip index into WeaponDef.grips (0 = none) */
    public static final ComponentType<Integer> GRIP = Registry.register(Registries.DATA_COMPONENT_TYPE, id("grip"),
            ComponentType.<Integer>builder().codec(Codec.INT).packetCodec(PacketCodecs.VAR_INT).build());
    /** weapon light switched on */
    public static final ComponentType<Boolean> LIGHT = Registry.register(Registries.DATA_COMPONENT_TYPE, id("light"),
            ComponentType.<Boolean>builder().codec(Codec.BOOL).packetCodec(PacketCodecs.BOOL).build());
    /** night vision goggles switched on */
    public static final ComponentType<Boolean> NVG_ON = Registry.register(Registries.DATA_COMPONENT_TYPE, id("nvg_on"),
            ComponentType.<Boolean>builder().codec(Codec.BOOL).packetCodec(PacketCodecs.BOOL).build());

    /** Sounds every weapon has (fireSupp only for suppressible ones). The shooter hears 1P versions client-side. */
    public record Sounds(SoundEvent fire, SoundEvent fireSupp, SoundEvent reload, SoundEvent dry) {}

    public static final Map<String, GunItem> ITEMS = new LinkedHashMap<>();
    public static final Map<String, Sounds> SOUNDS = new HashMap<>();
    public static final SoundEvent SND_SELECT = sound("fireselect");

    static {
        for (WeaponDef d : WeaponDef.ALL) {
            ITEMS.put(d.id(), Registry.register(Registries.ITEM, id(d.id()),
                    new GunItem(d, new Item.Settings().maxCount(1).maxDamage(d.magSize()))));
            SOUNDS.put(d.id(), new Sounds(sound(d.id() + ".fire"), d.suppressible() ? sound(d.id() + ".fire_supp") : null,
                    sound(d.id() + ".reload"), sound(d.id() + ".dryfire")));
        }
    }

    public static final Item M4A1 = ITEMS.get("m4a1");
    public static final Item ZIPCUFFS = Registry.register(Registries.ITEM, id("zipcuffs"), new ZipcuffsItem(new Item.Settings().maxCount(16)));
    public static final SoundEvent SND_CUFF = sound("zipcuffs.cuff_3p");
    public static final SoundEvent SND_CUT = sound("zipcuffs.cut");

    public static final Map<GrenadeItem.Kind, GrenadeItem> GRENADES = new LinkedHashMap<>();
    static {
        for (GrenadeItem.Kind k : GrenadeItem.Kind.values()) {
            GRENADES.put(k, Registry.register(Registries.ITEM, id(k.id), new GrenadeItem(k, new Item.Settings().maxCount(8))));
        }
    }
    public static final Item SHIELD = Registry.register(Registries.ITEM, id("ballistic_shield"), new ShieldItem(new Item.Settings().maxCount(1)
            .attributeModifiers(AttributeModifiersComponent.builder().add(EntityAttributes.GENERIC_MOVEMENT_SPEED,
                    new EntityAttributeModifier(id("shield_weight"), -0.15, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL),
                    AttributeModifierSlot.HAND).build())));
    public static final Item NVG = Registry.register(Registries.ITEM, id("nvg"), new NvgItem(new Item.Settings().maxCount(1)));

    public static final EntityType<GrenadeEntity> GRENADE_ENTITY = Registry.register(Registries.ENTITY_TYPE, id("grenade"),
            EntityType.Builder.<GrenadeEntity>create(GrenadeEntity::new, SpawnGroup.MISC).dimensions(0.22f, 0.22f)
                    .maxTrackingRange(8).trackingTickInterval(1).build("grenade"));

    public static final SoundEvent SND_FLASHBANG = sound("flashbang.bang");
    public static final SoundEvent SND_STINGER = sound("stinger.bang");
    public static final SoundEvent SND_CSGAS_POP = sound("csgas.pop");
    public static final SoundEvent SND_CSGAS_HISS = sound("csgas.hiss");
    public static final SoundEvent SND_GRENADE_BOUNCE = sound("grenade.bounce");
    public static final SoundEvent SND_STINGER_BOUNCE = sound("stinger.bounce");
    public static final SoundEvent SND_CSGAS_BOUNCE = sound("csgas.bounce");
    public static final SoundEvent SND_GRENADE_THROW = sound("grenade.throw");
    public static final SoundEvent SND_GRENADE_LEVER = sound("grenade.lever");
    public static final SoundEvent SND_SHIELD_HIT = sound("shield.hit");
    public static final SoundEvent SND_SHIELD_BASH = sound("shield.bash");
    public static final SoundEvent SND_NVG_ON = sound("nvg.on");
    public static final SoundEvent SND_NVG_OFF = sound("nvg.off");
    public static final SoundEvent SND_LIGHT = sound("light.toggle");
    public static final SoundEvent SND_TINNITUS = sound("fx.tinnitus");

    private static SoundEvent sound(String path) {
        return Registry.register(Registries.SOUND_EVENT, id(path), SoundEvent.of(id(path)));
    }

    /** pending magazine reloads: refill the gun in this hotbar slot at this server tick (switching away cancels it) */
    private record Reload(int endTick, int slot) {}

    /** shell-by-shell reload, mirrors GunAnimator: start [+1 if empty], loops (+1 each), end (+1) */
    private static final class Shells {
        final int slot;
        int phaseEnd, remaining;
        byte phase; // 0 start, 1 empty start, 2 loop, 3 end
        boolean cancel;
        Shells(int slot, int phaseEnd, int remaining, byte phase) {
            this.slot = slot; this.phaseEnd = phaseEnd; this.remaining = remaining; this.phase = phase;
        }
    }

    private static final Map<UUID, Reload> reloadEnds = new HashMap<>();
    private static final Map<UUID, Shells> shellReloads = new HashMap<>();
    private static final Map<UUID, Long> lastShot = new HashMap<>();
    private static int serverTicks;

    public static int ammo(ItemStack stack) { return stack.getMaxDamage() - stack.getDamage(); }

    public static WeaponDef def(ItemStack stack) {
        return stack.getItem() instanceof GunItem g ? g.def : null;
    }

    public static int optic(ItemStack stack) {
        WeaponDef d = def(stack);
        int o = stack.getOrDefault(OPTIC, 0);
        return d == null || o < 0 || o >= d.opticCount() ? 0 : o;
    }

    public static int grip(ItemStack stack) {
        WeaponDef d = def(stack);
        int g = stack.getOrDefault(GRIP, 0);
        return d == null || g < 0 || g >= d.gripCount() ? 0 : g;
    }

    /** foregrip id on the stack ("none" without one) */
    public static String gripId(ItemStack stack) {
        WeaponDef d = def(stack);
        return d == null ? "none" : d.grips()[grip(stack)];
    }

    public static boolean suppressed(ItemStack stack) {
        WeaponDef d = def(stack);
        return d != null && d.suppressible() && stack.getOrDefault(SUPPRESSOR, false);
    }

    @Override
    public void onInitialize() {
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.COMBAT).register(entries -> {
            ITEMS.values().forEach(item -> entries.add(item));
            entries.add(ZIPCUFFS);
            GRENADES.values().forEach(item -> entries.add(item));
            entries.add(SHIELD);
            entries.add(NVG);
        });
        Cuffs.init();
        Stun.init();
        Gear.init();
        PayloadTypeRegistry.playS2C().register(ronweapons.net.FlashPayload.ID, ronweapons.net.FlashPayload.CODEC);

        PayloadTypeRegistry.playC2S().register(FirePayload.ID, FirePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ActionPayload.ID, ActionPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(FxPayload.ID, FxPayload.CODEC);
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
            shellReloads.entrySet().removeIf(e -> {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
                if (p == null || p.getInventory().selectedSlot != e.getValue().slot) return true;
                return !stepShells(p, e.getValue());
            });
        });
    }

    /** @return false when the shell reload is over */
    private static boolean stepShells(ServerPlayerEntity p, Shells s) {
        ItemStack stack = p.getMainHandStack();
        WeaponDef d = def(stack);
        if (d == null || !d.shellReload()) return false;
        if (serverTicks < s.phaseEnd) return true;
        WeaponDef.ShellReload t = d.shell();
        boolean insert = s.phase != 0; // empty start chambers one, loops and the end insert one, a plain start only grabs
        if (insert) {
            stack.setDamage(Math.max(0, stack.getDamage() - 1));
            s.remaining--;
        }
        if (s.phase == 3 || s.remaining <= 0 || ammo(stack) >= d.magSize()) return false;
        if (s.remaining == 1 || s.cancel) {
            s.phase = 3;
            s.phaseEnd = serverTicks + t.endInsertTicks();
        } else {
            s.phase = 2;
            s.phaseEnd = serverTicks + t.loopTicks();
        }
        return true;
    }

    private static void action(ServerPlayerEntity player, byte action) {
        switch (action) {
            case ActionPayload.TOGGLE_NVG -> { Gear.toggleNvg(player); return; }
            case ActionPayload.SHIELD_BASH -> { Gear.bash(player); return; }
            case ActionPayload.THROW_OVER, ActionPayload.THROW_UNDER -> { Grenades.throwFrom(player, action == ActionPayload.THROW_UNDER); return; }
            default -> { }
        }
        ItemStack stack = player.getMainHandStack();
        WeaponDef d = def(stack);
        if (d == null) return;
        UUID u = player.getUuid();
        boolean busy = reloadEnds.containsKey(u) || shellReloads.containsKey(u);
        switch (action) {
            case ActionPayload.RELOAD_TAC, ActionPayload.RELOAD_EMPTY -> {
                if (busy || ammo(stack) >= d.magSize()) return;
                boolean empty = ammo(stack) <= 0;
                if (d.shellReload()) {
                    WeaponDef.ShellReload t = d.shell();
                    shellReloads.put(u, new Shells(player.getInventory().selectedSlot,
                            serverTicks + (empty ? t.emptyStartTicks() : t.startTicks()), d.magSize() - ammo(stack), (byte) (empty ? 1 : 0)));
                } else {
                    int ticks = empty ? d.reloadEmptyTicks() : d.reloadTacTicks();
                    reloadEnds.put(u, new Reload(serverTicks + ticks, player.getInventory().selectedSlot));
                }
                player.getWorld().playSound(player, player.getX(), player.getY(), player.getZ(), SOUNDS.get(d.id()).reload(),
                        SoundCategory.PLAYERS, 0.8f, 1.0f);
                broadcast(player, new FxPayload(empty ? FxPayload.RELOAD_EMPTY : FxPayload.RELOAD_TAC, player.getId(),
                        weaponIndex(d), player.getEyePos(), List.of()));
            }
            case ActionPayload.RELOAD_CANCEL -> {
                Shells s = shellReloads.get(u);
                if (s == null) return;
                // still grabbing the first shell of a tactical reload: the client goes straight to the end clip, which inserts it
                s.cancel = true;
            }
            case ActionPayload.TOGGLE_SUPPRESSOR -> {
                if (busy || !d.suppressible()) return;
                stack.set(SUPPRESSOR, !suppressed(stack));
            }
            case ActionPayload.TOGGLE_LIGHT -> Gear.toggleLight(player, stack, d);
            case ActionPayload.CYCLE_OPTIC -> {
                if (busy || d.opticCount() < 2) return;
                stack.set(OPTIC, (optic(stack) + 1) % d.opticCount());
            }
            case ActionPayload.CYCLE_GRIP -> {
                if (busy || d.gripCount() < 2) return;
                stack.set(GRIP, (grip(stack) + 1) % d.gripCount());
            }
            default -> { }
        }
    }

    private static void fire(ServerPlayerEntity player) {
        ItemStack stack = player.getMainHandStack();
        WeaponDef d = def(stack);
        if (d == null || player.isSpectator()) return;
        UUID u = player.getUuid();
        if (reloadEnds.containsKey(u) || shellReloads.containsKey(u)) return;
        long now = System.nanoTime();
        Long last = lastShot.get(u);
        if (last != null && now - last < d.serverIntervalNs()) return;
        ServerWorld world = player.getServerWorld();
        Sounds snd = SOUNDS.get(d.id());
        if (ammo(stack) <= 0) {
            world.playSound(player, player.getX(), player.getY(), player.getZ(), snd.dry(), SoundCategory.PLAYERS, 0.6f, 1.0f);
            return;
        }
        lastShot.put(u, now);
        // rounds are spent in creative too: otherwise the client counter resyncs back to a full mag
        stack.setDamage(Math.min(d.magSize(), stack.getDamage() + 1));
        boolean supp = suppressed(stack) && snd.fireSupp() != null;
        world.playSound(player, player.getX(), player.getY(), player.getZ(), supp ? snd.fireSupp() : snd.fire(), SoundCategory.PLAYERS,
                supp ? 0.9f : 2.0f, 0.95f + world.random.nextFloat() * 0.1f);

        Vec3d start = player.getEyePos();
        Vec3d aim = player.getRotationVec(1.0f);
        double range = d.pellets() > 1 ? SHOTGUN_RANGE : RANGE;
        List<FxPayload.Hit> fx = new ArrayList<>();
        Map<Entity, Float> damage = new HashMap<>();
        Map<Entity, Vec3d> where = new HashMap<>();
        int blockFx = 0;
        for (int i = 0; i < d.pellets(); i++) {
            Vec3d dir = d.pellets() > 1 ? spread(aim, d.spreadDeg(), world) : aim;
            Vec3d end = start.add(dir.multiply(range));
            BlockHitResult block = world.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE, player));
            Vec3d stop = block.getType() == HitResult.Type.MISS ? end : block.getPos();
            Box box = player.getBoundingBox().stretch(dir.multiply(range)).expand(1.0);
            EntityHitResult hit = ProjectileUtil.raycast(player, start, stop, box,
                    e -> !e.isSpectator() && e.canHit() && e != player, start.squaredDistanceTo(stop));
            if (hit != null) {
                Entity target = hit.getEntity();
                float dmg = d.damage();
                if (target instanceof LivingEntity living && hit.getPos().y > living.getEyeY() - 0.25) dmg *= 2.0f;
                damage.merge(target, dmg, Float::sum);
                where.putIfAbsent(target, hit.getPos());
                fx.add(new FxPayload.Hit(hit.getPos(), (byte) -1));
            } else {
                boolean onBlock = block.getType() == HitResult.Type.BLOCK;
                fx.add(new FxPayload.Hit(stop, onBlock ? (byte) block.getSide().getId() : (byte) -1));
            }
            if (hit == null && block.getType() == HitResult.Type.BLOCK && blockFx++ < 4) {
                BlockState state = world.getBlockState(block.getBlockPos());
                Vec3d p = block.getPos();
                world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, state), p.x, p.y, p.z,
                        d.pellets() > 1 ? 4 : 8, 0.05, 0.05, 0.05, 0.15);
                world.spawnParticles(ParticleTypes.SMOKE, p.x, p.y, p.z, 2, 0.02, 0.02, 0.02, 0.01);
            }
        }
        broadcast(player, new FxPayload(FxPayload.SHOT, player.getId(), weaponIndex(d), start, fx));
        // one damage call per target: pellets add up instead of being eaten by the hurt cooldown
        damage.forEach((target, dmg) -> {
            target.timeUntilRegen = 0;
            target.damage(player.getDamageSources().playerAttack(player), dmg);
            Vec3d p = where.get(target);
            world.spawnParticles(ParticleTypes.DAMAGE_INDICATOR, p.x, p.y, p.z, 3, 0.1, 0.1, 0.1, 0.05);
        });
    }

    private static byte weaponIndex(WeaponDef d) {
        for (int i = 0; i < WeaponDef.ALL.length; i++) if (WeaponDef.ALL[i] == d) return (byte) i;
        return 0;
    }

    /** tracers / holes / casings for everyone who can see it (the shooter too: its tracers and holes come from here) */
    private static void broadcast(ServerPlayerEntity player, FxPayload fx) {
        for (ServerPlayerEntity p : PlayerLookup.around(player.getServerWorld(), player.getPos(), 128)) {
            ServerPlayNetworking.send(p, fx);
        }
    }

    /** uniform direction inside a cone around aim */
    private static Vec3d spread(Vec3d aim, float halfAngleDeg, ServerWorld world) {
        Vec3d up = Math.abs(aim.y) > 0.99 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
        Vec3d right = aim.crossProduct(up).normalize();
        Vec3d up2 = right.crossProduct(aim).normalize();
        double cosMax = Math.cos(Math.toRadians(halfAngleDeg));
        double cos = 1 - world.random.nextDouble() * (1 - cosMax);
        double sin = Math.sqrt(Math.max(0, 1 - cos * cos));
        double phi = world.random.nextDouble() * MathHelper.TAU;
        return aim.multiply(cos).add(right.multiply(sin * Math.cos(phi))).add(up2.multiply(sin * Math.sin(phi))).normalize();
    }
}
