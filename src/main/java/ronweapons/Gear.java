package ronweapons;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.LightBlock;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/** Server side of the ballistic shield, the night vision goggles and the weapon lights. */
public final class Gear {
    /** shield: blocks what comes from within this half-angle of where the player looks (horizontal) */
    private static final double SHIELD_COS = Math.cos(Math.toRadians(70));
    private static final double LIGHT_RANGE = 36;

    private static final Map<UUID, Integer> bashReady = new HashMap<>();
    private static final Set<UUID> nvgGiven = new HashSet<>();
    /** weapon light: where each player's light block sits now */
    private record Lit(ServerWorld world, BlockPos pos) {}
    private static final Map<UUID, Lit> lights = new HashMap<>();
    private static int ticks;

    static void init() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(Gear::allowDamage);
        ServerTickEvents.END_SERVER_TICK.register(Gear::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> unlight(handler.player.getUuid()));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (UUID u : new HashSet<>(lights.keySet())) unlight(u);
        });
    }

    public static boolean hasShield(PlayerEntity p) {
        return p.getOffHandStack().getItem() instanceof ShieldItem || p.getMainHandStack().getItem() instanceof ShieldItem;
    }

    // ------------------------------------------------------------------ shield

    private static boolean allowDamage(LivingEntity e, DamageSource src, float amount) {
        if (!(e instanceof ServerPlayerEntity p) || !hasShield(p) || src.isIn(DamageTypeTags.BYPASSES_SHIELD)) return true;
        Vec3d from = src.getPosition();
        if (from == null) return true;
        Vec3d to = new Vec3d(from.x - p.getX(), 0, from.z - p.getZ());
        double yaw = Math.toRadians(p.getYaw());
        Vec3d look = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw));
        if (to.lengthSquared() < 1e-4 || to.normalize().dotProduct(look) < SHIELD_COS) return true;
        ServerWorld w = p.getServerWorld();
        w.playSound(null, p.getX(), p.getEyeY(), p.getZ(), RonWeapons.SND_SHIELD_HIT, SoundCategory.PLAYERS, 1.0f, 0.9f + w.random.nextFloat() * 0.2f);
        if (src.getAttacker() instanceof LivingEntity a && src.getSource() == a && a.squaredDistanceTo(p) < 9) {
            a.takeKnockback(0.4, p.getX() - a.getX(), p.getZ() - a.getZ()); // melee bounces off
        }
        return false;
    }

    static void bash(ServerPlayerEntity p) {
        if (!hasShield(p) || p.isSpectator()) return;
        UUID u = p.getUuid();
        if (bashReady.getOrDefault(u, 0) > ticks) return;
        bashReady.put(u, ticks + 22);
        ServerWorld w = p.getServerWorld();
        Vec3d start = p.getEyePos(), dir = p.getRotationVec(1f), end = start.add(dir.multiply(2.6));
        EntityHitResult hit = ProjectileUtil.raycast(p, start, end, p.getBoundingBox().stretch(dir.multiply(2.6)).expand(1.0),
                t -> t instanceof LivingEntity && t.isAlive() && !t.isSpectator() && t != p, 2.6 * 2.6);
        if (hit == null || !(hit.getEntity() instanceof LivingEntity t)) return;
        t.damage(w.getDamageSources().playerAttack(p), 2f);
        t.takeKnockback(1.1, -dir.x, -dir.z);
        if (t instanceof MobEntity m) Stun.stun(m, 40);
        w.playSound(null, t.getX(), t.getEyeY(), t.getZ(), RonWeapons.SND_SHIELD_BASH, SoundCategory.PLAYERS, 1.0f, 1.0f);
    }

    // ------------------------------------------------------------------ night vision

    static void toggleNvg(ServerPlayerEntity p) {
        ItemStack head = p.getEquippedStack(EquipmentSlot.HEAD);
        if (!(head.getItem() instanceof NvgItem)) {
            p.sendMessage(Text.translatable("ronweapons.nvg.none"), true);
            return;
        }
        boolean on = !head.getOrDefault(RonWeapons.NVG_ON, false);
        head.set(RonWeapons.NVG_ON, on);
        p.getServerWorld().playSound(null, p.getX(), p.getEyeY(), p.getZ(), on ? RonWeapons.SND_NVG_ON : RonWeapons.SND_NVG_OFF,
                SoundCategory.PLAYERS, 0.8f, 1.0f);
        refreshNvg(p);
    }

    public static boolean nvgOn(PlayerEntity p) {
        ItemStack head = p.getEquippedStack(EquipmentSlot.HEAD);
        return head.getItem() instanceof NvgItem && head.getOrDefault(RonWeapons.NVG_ON, false);
    }

    private static void refreshNvg(ServerPlayerEntity p) {
        UUID u = p.getUuid();
        if (nvgOn(p)) {
            StatusEffectInstance cur = p.getStatusEffect(StatusEffects.NIGHT_VISION);
            if (cur == null || (cur.isAmbient() && cur.getDuration() < 300)) {
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, 400, 0, true, false, false));
            }
            nvgGiven.add(u);
        } else if (nvgGiven.remove(u)) {
            StatusEffectInstance cur = p.getStatusEffect(StatusEffects.NIGHT_VISION);
            if (cur != null && cur.isAmbient()) p.removeStatusEffect(StatusEffects.NIGHT_VISION);
        }
    }

    // ------------------------------------------------------------------ weapon light

    static void toggleLight(ServerPlayerEntity p, ItemStack stack, WeaponDef d) {
        if (!d.hasLight()) return;
        boolean on = !stack.getOrDefault(RonWeapons.LIGHT, false);
        stack.set(RonWeapons.LIGHT, on);
        p.getServerWorld().playSound(null, p.getX(), p.getEyeY(), p.getZ(), RonWeapons.SND_LIGHT, SoundCategory.PLAYERS, 0.5f, on ? 1.1f : 0.9f);
    }

    public static boolean lightOn(ItemStack stack) {
        WeaponDef d = RonWeapons.def(stack);
        return d != null && d.hasLight() && stack.getOrDefault(RonWeapons.LIGHT, false);
    }

    private static void tick(MinecraftServer server) {
        ticks++;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (ticks % 20 == 0) refreshNvg(p);
            if (lightOn(p.getMainHandStack()) && !p.isSpectator()) light(p);
            else unlight(p.getUuid());
        }
    }

    /** a light block (level by distance) in the air cell in front of whatever the beam hits; moved as the player aims */
    private static void light(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        Vec3d start = p.getEyePos(), end = start.add(p.getRotationVec(1f).multiply(LIGHT_RANGE));
        BlockHitResult hit = w.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
        Vec3d at = hit.getType() == HitResult.Type.MISS ? null : hit.getPos();
        EntityHitResult eh = ProjectileUtil.raycast(p, start, at == null ? end : at, new Box(start, at == null ? end : at).expand(1),
                t -> !t.isSpectator() && t != p, LIGHT_RANGE * LIGHT_RANGE);
        if (eh != null) at = eh.getPos();
        Lit old = lights.get(p.getUuid());
        if (at == null) { unlight(p.getUuid()); return; }
        double dist = at.distanceTo(start);
        Vec3d back = at.subtract(p.getRotationVec(1f).multiply(0.6));
        BlockPos pos = BlockPos.ofFloored(back);
        int level = (int) Math.max(6, Math.min(15, 15 - dist / 4.0));
        if (old != null && old.world() == w && old.pos().equals(pos) && w.getBlockState(pos).isOf(Blocks.LIGHT)
                && w.getBlockState(pos).get(LightBlock.LEVEL_15) == level) return;
        if (!free(w, pos, old)) {
            // no free cell right there (e.g. tall grass): try one cell closer
            pos = BlockPos.ofFloored(at.subtract(p.getRotationVec(1f).multiply(1.4)));
            if (!free(w, pos, old)) { unlight(p.getUuid()); return; }
        }
        if (old != null && !(old.world() == w && old.pos().equals(pos))) unlight(p.getUuid());
        w.setBlockState(pos, Blocks.LIGHT.getDefaultState().with(LightBlock.LEVEL_15, level), Block.NOTIFY_LISTENERS);
        lights.put(p.getUuid(), new Lit(w, pos));
    }

    /** air, or our own light block (never touch light blocks someone placed by hand) */
    private static boolean free(ServerWorld w, BlockPos pos, Lit old) {
        if (w.getBlockState(pos).isAir()) return true;
        for (Lit l : lights.values()) if (l.world() == w && l.pos().equals(pos) && w.getBlockState(pos).isOf(Blocks.LIGHT)) return true;
        return false;
    }

    private static void unlight(UUID u) {
        Lit old = lights.remove(u);
        if (old == null) return;
        for (Lit other : lights.values()) if (other.world() == old.world() && other.pos().equals(old.pos())) return; // shared cell
        if (old.world().getBlockState(old.pos()).isOf(Blocks.LIGHT)) old.world().setBlockState(old.pos(), Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
    }
}
