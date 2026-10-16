package ronweapons.client;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ronweapons.RonWeapons;
import ronweapons.WeaponDef;
import ronweapons.net.FxPayload;

/**
 * Client-side world effects: bullet tracers, bullet holes on blocks, spent brass and empty magazines that tumble,
 * bounce and vanish after a few seconds. Shots come from the server (FxPayload) so every player sees them;
 * the local player's brass / mags come from the first-person rig sockets so they leave the real ejection port.
 */
public final class Fx {
    private static final Identifier HOLE_TEX = RonWeapons.id("textures/fx/bullet_hole.png");
    private static final float TRACER_SPEED = 320f, TRACER_LEN = 5f;
    private static final int CASING_TICKS = 70, MAG_TICKS = 76, MAX_DEBRIS = 240, MAX_HOLES = 320;
    private static final long HOLE_NS = 30_000_000_000L, HOLE_FADE_NS = 3_000_000_000L;
    /** psk -> camera axes (same as FirstPersonRenderer.C) as a rotation */
    private static final Quaternionf C_ROT = new Matrix4f(-1, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 1)
            .getUnnormalizedRotation(new Quaternionf()).normalize();

    private record Tracer(Vec3d a, Vec3d dir, double dist, long born, float width) {}
    private record Hole(Vec3d p, Direction face, float rot, float size, long born) {}
    /** where something leaves the gun: world position, look rotation (for velocities) and the mesh rotation (psk -> world) */
    private record Frame(Vec3d pos, Quaternionf look, Quaternionf mesh) {}

    private static final class Debris {
        final RonMesh mesh;
        final int life;
        final Identifier landSound;
        Vec3d pos, prev, vel;
        final Quaternionf rot;
        final Vector3f spin;
        int age;
        boolean landed;
        Debris(RonMesh mesh, int life, Identifier landSound, Vec3d pos, Vec3d vel, Quaternionf rot, Vector3f spin) {
            this.mesh = mesh; this.life = life; this.landSound = landSound;
            this.pos = pos; this.prev = pos; this.vel = vel; this.rot = rot; this.spin = spin;
        }
    }

    private record Pending(int at, Runnable run) {}

    private static final List<Tracer> tracers = new ArrayList<>();
    private static final List<Hole> holes = new ArrayList<>();
    private static final List<Debris> debris = new ArrayList<>();
    private static final List<Pending> pending = new ArrayList<>();
    private static final Map<RonMesh, float[][]> baked = new IdentityHashMap<>();
    private static final Random RNG = Random.create();
    private static ClientWorld lastWorld;
    private static int ticks;

    /** camera-space sockets of the first-person rig, refreshed every frame it is drawn */
    public static final class Fp {
        static final Matrix4f muzzle = new Matrix4f(), eject = new Matrix4f(), mag = new Matrix4f();
        static boolean hasMuzzle, hasEject, hasMag;
        static long ns;

        static void update(Matrix4f m, Matrix4f e, Matrix4f g) {
            hasMuzzle = m != null; if (m != null) muzzle.set(m);
            hasEject = e != null; if (e != null) eject.set(e);
            hasMag = g != null; if (g != null) mag.set(g);
            ns = System.nanoTime();
        }

        static boolean fresh() {
            MinecraftClient mc = MinecraftClient.getInstance();
            return System.nanoTime() - ns < 150_000_000L && mc.options.getPerspective().isFirstPerson();
        }
    }

    // ------------------------------------------------------------------ events

    /** server says someone shot or reloaded */
    public static void onFx(FxPayload p) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || p.weapon() < 0 || p.weapon() >= WeaponDef.ALL.length) return;
        WeaponDef d = WeaponDef.ALL[p.weapon()];
        GunRig rig = GunRig.get(mc.getResourceManager(), d.id());
        Entity shooter = mc.world.getEntityById(p.shooter());
        boolean self = mc.player != null && p.shooter() == mc.player.getId();
        if (p.kind() == FxPayload.SHOT) {
            Vec3d from = self && Fp.fresh() && Fp.hasMuzzle ? fpFrame(Fp.muzzle).pos()
                    : shooter != null ? approx(shooter, new Vector3f(0.1f, -0.12f, -0.8f)).pos() : p.start();
            float width = d.pellets() > 1 ? 0.012f : 0.022f;
            for (FxPayload.Hit h : p.hits()) {
                tracer(from, h.pos(), width);
                if (h.face() >= 0) hole(h.pos(), Direction.byId(h.face()), d.pellets() > 1 ? 0.05f : 0.075f);
            }
            if (!self && shooter != null && rig != null && rig.fx != null) {
                if (rig.fx.ejectOn().equals("fire")) eject(rig, approx(shooter, new Vector3f(0.1f, -0.1f, -0.35f)));
                else later(rig.cueTime("fire", rig.fx.ejectOn()), () -> eject(rig, approx(shooter, new Vector3f(0.1f, -0.1f, -0.35f))));
            }
        } else if (!self && shooter != null && rig != null && rig.fx != null && !d.shellReload()) {
            String act = p.kind() == FxPayload.RELOAD_EMPTY ? "reload_empty" : "reload_tac";
            if (rig.fx.magCue() != null) later(rig.cueTime(act, rig.fx.magCue()),
                    () -> dropMag(rig, approx(shooter, new Vector3f(0.1f, -0.3f, -0.35f))));
            if (!rig.fx.ejectOn().equals("fire")) later(rig.cueTime(act, rig.fx.ejectOn()),
                    () -> eject(rig, approx(shooter, new Vector3f(0.05f, -0.2f, -0.3f))));
        }
    }

    /** local shot: brass from the ejection port right away (no waiting for the server) */
    public static void localFire(GunRig rig) {
        if (rig != null && rig.fx != null && rig.fx.ejectOn().equals("fire")) eject(rig, localFrame(Fp.eject, Fp.hasEject, new Vector3f(0.12f, -0.1f, -0.35f)));
    }

    /** a sound cue of the local first-person clip fired: pump stroke, cylinder dump, mag release */
    public static void localCue(GunRig rig, String sound) {
        if (rig == null || rig.fx == null) return;
        if (sound.equals(rig.fx.ejectOn())) eject(rig, localFrame(Fp.eject, Fp.hasEject, new Vector3f(0.1f, -0.15f, -0.35f)));
        if (sound.equals(rig.fx.magCue())) dropMag(rig, localFrame(Fp.mag, Fp.hasMag, new Vector3f(0.1f, -0.3f, -0.35f)));
    }

    private static void later(float seconds, Runnable r) {
        if (seconds < 0) return;
        pending.add(new Pending(ticks + Math.round(seconds * 20f), r));
    }

    // ------------------------------------------------------------------ spawning

    private static void tracer(Vec3d a, Vec3d b, float width) {
        Vec3d d = b.subtract(a);
        double dist = d.length();
        if (dist < 0.5) return;
        tracers.add(new Tracer(a, d.multiply(1 / dist), dist, System.nanoTime(), width));
    }

    private static void hole(Vec3d p, Direction face, float size) {
        if (holes.size() >= MAX_HOLES) holes.remove(0);
        holes.add(new Hole(p, face, RNG.nextFloat() * MathHelper.TAU, size * (0.8f + RNG.nextFloat() * 0.4f), System.nanoTime()));
    }

    private static void eject(GunRig rig, Frame f) {
        if (rig.fx.casing() == null) return;
        float[] v = rig.fx.vel();
        for (int i = 0; i < rig.fx.count(); i++) {
            Vector3f vel = new Vector3f(v[0] + jitter(0.5f), v[1] + jitter(0.5f), v[2] + jitter(0.4f));
            Vec3d pos = f.pos();
            if (rig.fx.count() > 1) pos = pos.add(jitter(0.02f), jitter(0.02f), jitter(0.02f));
            spawn(rig.fx.casing(), CASING_TICKS, rig.fx.landSound(), pos, f.look().transform(vel).div(20f), new Quaternionf(f.mesh()), 25f);
        }
    }

    private static void dropMag(GunRig rig, Frame f) {
        if (rig.fx.mag() == null) return;
        Vector3f vel = new Vector3f(jitter(0.3f), -0.7f, -0.2f + jitter(0.2f));
        spawn(rig.fx.mag(), MAG_TICKS, null, f.pos(), f.look().transform(vel).div(20f), new Quaternionf(f.mesh()), 6f);
    }

    private static void spawn(RonMesh mesh, int life, Identifier land, Vec3d pos, Vector3f velPerTick, Quaternionf rot, float spinMax) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (debris.size() >= MAX_DEBRIS) debris.remove(0);
        Vec3d inherit = mc.player != null ? mc.player.getVelocity().multiply(0.8) : Vec3d.ZERO;
        Vector3f spin = new Vector3f(jitter(1f), jitter(1f), jitter(1f)).normalize().mul(spinMax * (0.5f + RNG.nextFloat() * 0.5f));
        debris.add(new Debris(mesh, life, land, pos, new Vec3d(velPerTick.x, velPerTick.y, velPerTick.z).add(inherit), rot, spin));
    }

    private static float jitter(float a) { return (RNG.nextFloat() * 2f - 1f) * a; }

    // ------------------------------------------------------------------ frames

    /** camera-space socket matrix -> world */
    private static Frame fpFrame(Matrix4f m) {
        Camera cam = MinecraftClient.getInstance().gameRenderer.getCamera();
        Quaternionf look = new Quaternionf(cam.getRotation());
        Vector3f t = m.getTranslation(new Vector3f());
        Vec3d pos = cam.getPos().add(new Vec3d(look.transform(new Vector3f(t))));
        Quaternionf r = m.getUnnormalizedRotation(new Quaternionf()).normalize();
        return new Frame(pos, look, new Quaternionf(look).mul(r));
    }

    private static Frame localFrame(Matrix4f m, boolean has, Vector3f fallback) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (has && Fp.fresh()) return fpFrame(m);
        return approx(mc.player, fallback);
    }

    /** a point in a player's view frame (X right, Y up, -Z forward), for players we only see from outside */
    private static Frame approx(Entity e, Vector3f local) {
        float td = MinecraftClient.getInstance().getRenderTickCounter().getTickDelta(false);
        Quaternionf look = new Quaternionf().rotationYXZ((float) Math.PI - e.getYaw(td) * MathHelper.RADIANS_PER_DEGREE,
                -e.getPitch(td) * MathHelper.RADIANS_PER_DEGREE, 0f);
        Vec3d pos = e.getCameraPosVec(td).add(new Vec3d(look.transform(new Vector3f(local))));
        return new Frame(pos, look, new Quaternionf(look).mul(C_ROT));
    }

    // ------------------------------------------------------------------ simulation

    public static void tick(MinecraftClient mc) {
        if (mc.world != lastWorld) {
            lastWorld = mc.world;
            tracers.clear(); holes.clear(); debris.clear(); pending.clear(); baked.clear();
        }
        if (mc.world == null || mc.isPaused()) return;
        ticks++;
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (ticks >= p.at()) { it.remove(); p.run().run(); }
        }
        ClientWorld w = mc.world;
        for (Iterator<Debris> it = debris.iterator(); it.hasNext(); ) {
            Debris d = it.next();
            if (++d.age > d.life) { it.remove(); continue; }
            d.prev = d.pos;
            Vec3d v = d.vel.add(0, -0.042, 0).multiply(0.985);
            double x = d.pos.x, y = d.pos.y, z = d.pos.z;
            double r = 0.012;
            if (solid(w, x, y + v.y - r, z) || solid(w, x, y + v.y + r, z)) {
                if (!d.landed && v.y < -0.08 && d.landSound != null) land(mc, d);
                if (v.y < 0) d.landed = true;
                v = new Vec3d(v.x * 0.55, Math.abs(v.y) < 0.06 ? 0 : -v.y * 0.3, v.z * 0.55);
                d.spin.mul(0.45f);
            } else y += v.y;
            if (solid(w, x + v.x, y + r, z)) v = new Vec3d(-v.x * 0.3, v.y, v.z); else x += v.x;
            if (solid(w, x, y + r, z + v.z)) v = new Vec3d(v.x, v.y, -v.z * 0.3); else z += v.z;
            if (d.landed && solid(w, x, y - 2 * r, z)) { v = new Vec3d(v.x * 0.8, v.y, v.z * 0.8); d.spin.mul(0.8f); }
            d.vel = v;
            d.pos = new Vec3d(x, y, z);
            float a = d.spin.length() / 20f;
            if (a > 1e-4f) d.rot.rotateAxis(a, d.spin.x / d.spin.length(), d.spin.y / d.spin.length(), d.spin.z / d.spin.length());
        }
    }

    private static void land(MinecraftClient mc, Debris d) {
        mc.getSoundManager().play(new PositionedSoundInstance(d.landSound, SoundCategory.PLAYERS, 0.35f, 0.9f + RNG.nextFloat() * 0.2f,
                SoundInstance.createRandom(), false, 0, SoundInstance.AttenuationType.LINEAR, d.pos.x, d.pos.y, d.pos.z, false));
    }

    private static boolean solid(ClientWorld w, double x, double y, double z) {
        BlockPos bp = BlockPos.ofFloored(x, y, z);
        VoxelShape s = w.getBlockState(bp).getCollisionShape(w, bp);
        if (s.isEmpty()) return false;
        double lx = x - bp.getX(), ly = y - bp.getY(), lz = z - bp.getZ();
        for (Box b : s.getBoundingBoxes()) if (b.contains(lx, ly, lz)) return true;
        return false;
    }

    // ------------------------------------------------------------------ rendering

    public static void render(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || (tracers.isEmpty() && holes.isEmpty() && debris.isEmpty())) return;
        Vec3d cam = ctx.camera().getPos();
        MatrixStack ms = ctx.matrixStack() != null ? ctx.matrixStack() : new MatrixStack();
        VertexConsumerProvider.Immediate imm = mc.getBufferBuilders().getEntityVertexConsumers();
        float td = ctx.tickCounter().getTickDelta(false);
        long now = System.nanoTime();

        ms.push();
        // debris (brass, mags): RoN meshes in cm, psk axes
        for (Debris d : debris) {
            float[][] pn = baked.computeIfAbsent(d.mesh, m -> m.bakeRigid(new Matrix4f()));
            Vec3d p = d.prev.lerp(d.pos, td);
            float shrink = Math.min(1f, (d.life - d.age - td) / 8f);
            if (shrink <= 0) continue;
            ms.push();
            ms.translate(p.x - cam.x, p.y - cam.y, p.z - cam.z);
            ms.multiply(d.rot);
            ms.scale(0.01f * shrink, 0.01f * shrink, 0.01f * shrink);
            d.mesh.emit(pn[0], pn[1], ms.peek(), imm, WorldRenderer.getLightmapCoordinates(mc.world, BlockPos.ofFloored(p)), OverlayTexture.DEFAULT_UV);
            ms.pop();
        }
        // bullet holes
        holes.removeIf(h -> now - h.born() > HOLE_NS);
        if (!holes.isEmpty()) {
            VertexConsumer vc = imm.getBuffer(RenderLayer.getEntityTranslucent(HOLE_TEX));
            MatrixStack.Entry e = ms.peek();
            for (Hole h : holes) {
                float age = now - h.born();
                int alpha = (int) (255 * MathHelper.clamp((HOLE_NS - age) / (float) HOLE_FADE_NS, 0f, 1f));
                Vector3f n = new Vector3f(h.face().getUnitVector());
                Vector3f u = Math.abs(n.y) > 0.9f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
                u = u.sub(new Vector3f(n).mul(u.dot(n))).normalize();
                Vector3f v = new Vector3f(n).cross(u);
                float c = MathHelper.cos(h.rot()) * h.size() / 2, s = MathHelper.sin(h.rot()) * h.size() / 2;
                Vector3f a1 = new Vector3f(u).mul(c).add(new Vector3f(v).mul(s));
                Vector3f a2 = new Vector3f(v).mul(c).sub(new Vector3f(u).mul(s));
                float ox = (float) (h.p().x - cam.x) + n.x * 0.004f, oy = (float) (h.p().y - cam.y) + n.y * 0.004f, oz = (float) (h.p().z - cam.z) + n.z * 0.004f;
                int light = WorldRenderer.getLightmapCoordinates(mc.world, BlockPos.ofFloored(h.p().add(n.x * 0.1, n.y * 0.1, n.z * 0.1)));
                holeVertex(vc, e, ox - a1.x - a2.x, oy - a1.y - a2.y, oz - a1.z - a2.z, 0, 1, alpha, light, n);
                holeVertex(vc, e, ox + a1.x - a2.x, oy + a1.y - a2.y, oz + a1.z - a2.z, 1, 1, alpha, light, n);
                holeVertex(vc, e, ox + a1.x + a2.x, oy + a1.y + a2.y, oz + a1.z + a2.z, 1, 0, alpha, light, n);
                holeVertex(vc, e, ox - a1.x + a2.x, oy - a1.y + a2.y, oz - a1.z + a2.z, 0, 0, alpha, light, n);
            }
        }
        // tracers: a short glowing streak flying from the muzzle to the impact
        tracers.removeIf(t -> (now - t.born()) / 1e9 * TRACER_SPEED - TRACER_LEN > t.dist());
        if (!tracers.isEmpty()) {
            VertexConsumer vc = imm.getBuffer(RenderLayer.getLightning());
            Matrix4f pm = ms.peek().getPositionMatrix();
            for (Tracer t : tracers) {
                double head = Math.min(t.dist(), (now - t.born()) / 1e9 * TRACER_SPEED);
                double tail = Math.max(0.3, head - TRACER_LEN * (t.width() < 0.02f ? 0.5 : 1));
                if (head <= tail) continue;
                Vec3d a = t.a().add(t.dir().multiply(tail)).subtract(cam), b = t.a().add(t.dir().multiply(head)).subtract(cam);
                Vec3d mid = a.add(b).multiply(0.5);
                Vec3d side = t.dir().crossProduct(mid).normalize().multiply(t.width());
                if (!Double.isFinite(side.x)) continue;
                streak(vc, pm, a, b, side, 255, 214, 140, 210);
                streak(vc, pm, a, b, side.multiply(0.4), 255, 250, 225, 255);
            }
        }
        ms.pop();
        imm.draw();
    }

    private static void holeVertex(VertexConsumer vc, MatrixStack.Entry e, float x, float y, float z, float u, float v, int alpha, int light, Vector3f n) {
        vc.vertex(e.getPositionMatrix(), x, y, z).color(255, 255, 255, alpha).texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV).light(light).normal(e, n.x, n.y, n.z);
    }

    private static void streak(VertexConsumer vc, Matrix4f pm, Vec3d a, Vec3d b, Vec3d side, int r, int g, int bl, int al) {
        vc.vertex(pm, (float) (a.x - side.x), (float) (a.y - side.y), (float) (a.z - side.z)).color(r, g, bl, 0);
        vc.vertex(pm, (float) (a.x + side.x), (float) (a.y + side.y), (float) (a.z + side.z)).color(r, g, bl, 0);
        vc.vertex(pm, (float) (b.x + side.x), (float) (b.y + side.y), (float) (b.z + side.z)).color(r, g, bl, al);
        vc.vertex(pm, (float) (b.x - side.x), (float) (b.y - side.y), (float) (b.z - side.z)).color(r, g, bl, al);
    }
}
