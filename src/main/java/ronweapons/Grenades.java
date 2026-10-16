package ronweapons;

import java.util.List;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import ronweapons.net.FlashPayload;

/**
 * What the three RoN grenades do.
 * Flashbang: blinds + deafens everyone who can see it (less if they look away), mobs freeze with their hands up.
 * Stinger: rubber balls in a 6 m radius (damage + knockback), stuns. CS gas: a 15 s cloud, nausea / slowness, mobs give up.
 */
public final class Grenades {
    public static final double FLASH_RADIUS = 12, STING_RADIUS = 6, GAS_RADIUS = 5;

    /** player released the grenade (ActionPayload THROW_*); the client already played the throw clip */
    static void throwFrom(ServerPlayerEntity p, boolean under) {
        ItemStack stack = p.getMainHandStack();
        if (!(stack.getItem() instanceof GrenadeItem g) || p.isSpectator()) return;
        if (p.getItemCooldownManager().isCoolingDown(g)) return;
        p.getItemCooldownManager().set(g, 12);
        ServerWorld w = p.getServerWorld();
        GrenadeEntity e = new GrenadeEntity(RonWeapons.GRENADE_ENTITY, w);
        e.setKind(g.kind);
        e.setOwner(p);
        Vec3d look = p.getRotationVec(1f);
        Vec3d pos = p.getEyePos().add(look.multiply(0.45)).add(0, under ? -0.55 : -0.12, 0);
        e.setPosition(pos.x, pos.y, pos.z);
        Vec3d v = under ? new Vec3d(look.x, 0, look.z).normalize().multiply(0.48).add(0, 0.08, 0)
                : look.multiply(1.0).add(0, 0.14, 0);
        e.setVelocity(v.add(p.getVelocity().multiply(0.6, 0.2, 0.6)));
        w.spawnEntity(e);
        w.playSound(null, p.getX(), p.getY(), p.getZ(), RonWeapons.SND_GRENADE_THROW, SoundCategory.PLAYERS, 0.7f, 1.0f);
        w.playSound(null, pos.x, pos.y, pos.z, RonWeapons.SND_GRENADE_LEVER, SoundCategory.PLAYERS, 0.5f, 1.0f);
        if (!p.getAbilities().creativeMode) stack.decrement(1);
    }

    static void detonate(GrenadeEntity g) {
        if (!(g.getWorld() instanceof ServerWorld w)) return;
        Vec3d c = g.getPos().add(0, 0.15, 0);
        switch (g.kind()) {
            case FLASH -> {
                w.playSound(null, c.x, c.y, c.z, RonWeapons.SND_FLASHBANG, SoundCategory.PLAYERS, 6.0f, 0.95f + w.random.nextFloat() * 0.1f);
                w.spawnParticles(ParticleTypes.FLASH, c.x, c.y, c.z, 1, 0, 0, 0, 0);
                w.spawnParticles(ParticleTypes.EXPLOSION, c.x, c.y, c.z, 1, 0, 0, 0, 0);
                w.spawnParticles(ParticleTypes.SMOKE, c.x, c.y, c.z, 30, 0.3, 0.2, 0.3, 0.04);
                for (LivingEntity e : around(w, c, FLASH_RADIUS)) {
                    double d = e.getEyePos().distanceTo(c);
                    if (!sees(w, c, e.getEyePos(), g)) {
                        // behind a wall: still deafened a bit when close
                        if (d < 4 && e instanceof ServerPlayerEntity sp) ServerPlayNetworking.send(sp, new FlashPayload(FlashPayload.FLASH, 0.15f, 2f));
                        continue;
                    }
                    Vec3d to = c.subtract(e.getEyePos()).normalize();
                    float facing = (float) MathHelper.clamp(e.getRotationVec(1f).dotProduct(to), -1, 1);
                    float s = (float) (1 - d / FLASH_RADIUS) * (facing > 0.2f ? 1f : 0.45f);
                    s = MathHelper.clamp(s * 1.25f, 0.1f, 1f);
                    if (e instanceof ServerPlayerEntity sp) ServerPlayNetworking.send(sp, new FlashPayload(FlashPayload.FLASH, s, 2.5f + 6f * s));
                    else if (e instanceof MobEntity m) {
                        Stun.stun(m, (int) (60 + 140 * s));
                        m.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, (int) (60 + 140 * s), 0));
                    }
                    if (d < 2) e.damage(w.getDamageSources().explosion(g, g.getOwner()), 2f);
                }
            }
            case STING -> {
                w.playSound(null, c.x, c.y, c.z, RonWeapons.SND_STINGER, SoundCategory.PLAYERS, 5.0f, 0.95f + w.random.nextFloat() * 0.1f);
                w.spawnParticles(ParticleTypes.EXPLOSION, c.x, c.y, c.z, 1, 0, 0, 0, 0);
                w.spawnParticles(ParticleTypes.CRIT, c.x, c.y, c.z, 90, 0.1, 0.1, 0.1, 1.1);
                w.spawnParticles(ParticleTypes.SMOKE, c.x, c.y, c.z, 12, 0.2, 0.1, 0.2, 0.03);
                for (LivingEntity e : around(w, c, STING_RADIUS)) {
                    Vec3d body = e.getPos().add(0, e.getHeight() * 0.5, 0);
                    if (!sees(w, c, body, g)) continue;
                    double d = body.distanceTo(c);
                    float s = (float) MathHelper.clamp(1 - d / STING_RADIUS, 0.1, 1);
                    e.timeUntilRegen = 0;
                    e.damage(w.getDamageSources().thrown(g, g.getOwner()), 1.5f + 5f * s);
                    Vec3d away = body.subtract(c).normalize();
                    e.takeKnockback(0.4 + 0.8 * s, -away.x, -away.z);
                    if (e instanceof ServerPlayerEntity sp) ServerPlayNetworking.send(sp, new FlashPayload(FlashPayload.STING, s, 2f + 3f * s));
                    else if (e instanceof MobEntity m) Stun.stun(m, (int) (40 + 80 * s));
                }
            }
            case GAS -> {
                w.playSound(null, c.x, c.y, c.z, RonWeapons.SND_CSGAS_POP, SoundCategory.PLAYERS, 1.5f, 1.0f);
                w.spawnParticles(ParticleTypes.POOF, c.x, c.y, c.z, 10, 0.1, 0.1, 0.1, 0.05);
            }
        }
    }

    /** CS gas venting: the cloud grows to GAS_RADIUS over 3 s; everyone inside is gassed every half second */
    static void gasTick(GrenadeEntity g) {
        if (!(g.getWorld() instanceof ServerWorld w)) return;
        int t = g.stateTicks();
        if (t % 40 == 1) w.playSound(null, g.getX(), g.getY(), g.getZ(), RonWeapons.SND_CSGAS_HISS, SoundCategory.PLAYERS, 1.0f, 1.0f);
        if (t % 10 != 0) return;
        double r = gasRadius(t);
        Vec3d c = g.getPos().add(0, 0.6, 0);
        for (LivingEntity e : around(w, c, r + 0.5)) {
            if (e.getPos().add(0, e.getHeight() * 0.5, 0).distanceTo(c) > r + 0.6) continue;
            if (e instanceof ServerPlayerEntity sp) {
                if (sp.isCreative() || sp.isSpectator()) continue;
                ServerPlayNetworking.send(sp, new FlashPayload(FlashPayload.GAS, 1f, 3f));
                sp.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 160, 0, false, false, true));
                sp.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 1, false, false, true));
                sp.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 40, 1, false, false, true));
            } else if (e instanceof MobEntity m) {
                Stun.stun(m, 50);
            }
        }
    }

    public static double gasRadius(int ventTicks) { return 1.2 + (GAS_RADIUS - 1.2) * Math.min(1.0, ventTicks / 60.0); }

    /** client: the white cloud (both sides call tick, only the client draws) */
    static void gasParticles(GrenadeEntity g) {
        World w = g.getWorld();
        int t = g.stateTicks();
        double r = gasRadius(t);
        for (int i = 0; i < 4; i++) {
            double a = w.random.nextDouble() * Math.PI * 2, s = 0.05 + w.random.nextDouble() * 0.12 * (r / GAS_RADIUS + 0.4);
            w.addParticle(i == 0 ? ParticleTypes.WHITE_SMOKE : ParticleTypes.CLOUD, g.getX(), g.getY() + 0.1, g.getZ(),
                    Math.cos(a) * s, 0.01 + w.random.nextDouble() * 0.03, Math.sin(a) * s);
        }
        if (t > 20 && w.random.nextInt(2) == 0) {
            double a = w.random.nextDouble() * Math.PI * 2, d = Math.sqrt(w.random.nextDouble()) * r;
            w.addParticle(ParticleTypes.CLOUD, g.getX() + Math.cos(a) * d, g.getY() + 0.2 + w.random.nextDouble() * 1.6, g.getZ() + Math.sin(a) * d,
                    0, 0.005, 0);
        }
    }

    private static List<LivingEntity> around(ServerWorld w, Vec3d c, double r) {
        return w.getEntitiesByClass(LivingEntity.class, new Box(c, c).expand(r), e -> e.isAlive() && !e.isSpectator()
                && e.getEyePos().squaredDistanceTo(c) <= r * r * 1.1);
    }

    private static boolean sees(World w, Vec3d from, Vec3d to, Entity g) {
        return w.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, g))
                .getType() == HitResult.Type.MISS;
    }
}
