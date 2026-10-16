package ronweapons;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * A thrown RoN grenade: bounces and rolls (own physics, both sides, server authoritative), goes off after the fuse.
 * Flashbang / stinger leave the spent body for two seconds; CS gas vents for 15 s (Grenades.gasTick) and then lies there.
 */
public class GrenadeEntity extends ProjectileEntity {
    private static final TrackedData<Byte> KIND = DataTracker.registerData(GrenadeEntity.class, TrackedDataHandlerRegistry.BYTE);
    /** 0 armed, 1 venting (CS gas), 2 spent */
    private static final TrackedData<Byte> STATE = DataTracker.registerData(GrenadeEntity.class, TrackedDataHandlerRegistry.BYTE);
    public static final int GAS_TICKS = 300;

    private int ticks, stateTicks, bounceCooldown;
    /** client: tumble angle for the renderer (degrees) */
    public float spin, prevSpin;

    public GrenadeEntity(EntityType<? extends GrenadeEntity> type, World world) {
        super(type, world);
    }

    public GrenadeItem.Kind kind() { return GrenadeItem.Kind.of(dataTracker.get(KIND)); }
    public void setKind(GrenadeItem.Kind k) { dataTracker.set(KIND, (byte) k.ordinal()); }
    public byte state() { return dataTracker.get(STATE); }
    private void setState(int s) { dataTracker.set(STATE, (byte) s); stateTicks = 0; }
    public int stateTicks() { return stateTicks; }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        builder.add(KIND, (byte) 0);
        builder.add(STATE, (byte) 0);
    }

    @Override
    public void tick() {
        super.tick();
        ticks++;
        stateTicks++;
        if (bounceCooldown > 0) bounceCooldown--;
        physics();
        World w = getWorld();
        if (w.isClient) {
            if (state() == 1) Grenades.gasParticles(this);
            return;
        }
        byte st = state();
        if (st == 0 && ticks >= kind().fuse) {
            Grenades.detonate(this);
            setState(kind() == GrenadeItem.Kind.GAS ? 1 : 2);
        } else if (st == 1) {
            Grenades.gasTick(this);
            if (stateTicks >= GAS_TICKS) setState(2);
        } else if (st == 2 && stateTicks >= (kind() == GrenadeItem.Kind.GAS ? 200 : 40)) {
            discard();
        }
        if (getY() < w.getBottomY() - 16) discard();
    }

    /** gravity, drag, and a bounce off every face it hits (Entity.move zeroes the blocked axes, we reflect them instead) */
    private void physics() {
        Vec3d v = getVelocity().add(0, -0.045, 0);
        Vec3d before = getPos();
        move(MovementType.SELF, v);
        Vec3d moved = getPos().subtract(before);
        boolean hx = Math.abs(moved.x - v.x) > 1e-4, hy = Math.abs(moved.y - v.y) > 1e-4, hz = Math.abs(moved.z - v.z) > 1e-4;
        double impact = 0;
        double vx = v.x, vy = v.y, vz = v.z;
        if (hx) { impact = Math.max(impact, Math.abs(vx)); vx = -vx * 0.3; }
        if (hz) { impact = Math.max(impact, Math.abs(vz)); vz = -vz * 0.3; }
        if (hy) {
            impact = Math.max(impact, Math.abs(vy));
            vy = Math.abs(vy) < 0.09 ? 0 : -vy * 0.3;
            if (v.y < 0) { vx *= 0.7; vz *= 0.7; }
        }
        boolean ground = isOnGround() || (hy && v.y < 0);
        if (ground && vy == 0) { vx *= 0.86; vz *= 0.86; }
        vx *= 0.99; vy *= 0.99; vz *= 0.99;
        if (Math.abs(vx) < 0.003) vx = 0;
        if (Math.abs(vz) < 0.003) vz = 0;
        setVelocity(vx, vy, vz);
        prevSpin = spin;
        double speed = Math.sqrt(vx * vx + vz * vz) + (ground ? 0 : Math.abs(vy) * 0.5);
        spin += (float) (speed * (ground ? 260 : 120));
        if (impact > 0.1 && bounceCooldown == 0 && !getWorld().isClient) {
            bounceCooldown = 3;
            SoundEvent s = kind() == GrenadeItem.Kind.STING ? RonWeapons.SND_STINGER_BOUNCE
                    : kind() == GrenadeItem.Kind.GAS ? RonWeapons.SND_CSGAS_BOUNCE : RonWeapons.SND_GRENADE_BOUNCE;
            getWorld().playSound(null, getX(), getY(), getZ(), s, SoundCategory.PLAYERS,
                    (float) MathHelper.clamp(impact * 2.2, 0.2, 1.0), 0.92f + random.nextFloat() * 0.16f);
            if (impact > 0.3 && getWorld() instanceof net.minecraft.server.world.ServerWorld sw) {
                sw.spawnParticles(ParticleTypes.POOF, getX(), getY(), getZ(), 1, 0.02, 0.02, 0.02, 0.0);
            }
        }
    }

    @Override
    public boolean canHit() { return false; }

    @Override
    public boolean shouldRender(double distance) { return distance < 64 * 64; }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putByte("Kind", dataTracker.get(KIND));
        nbt.putByte("State", state());
        nbt.putInt("Ticks", ticks);
        nbt.putInt("StateTicks", stateTicks);
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        dataTracker.set(KIND, nbt.getByte("Kind"));
        dataTracker.set(STATE, nbt.getByte("State"));
        ticks = nbt.getInt("Ticks");
        stateTicks = nbt.getInt("StateTicks");
    }
}
