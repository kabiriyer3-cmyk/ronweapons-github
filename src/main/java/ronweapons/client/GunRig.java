package ronweapons.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ronweapons.RonWeapons;

/**
 * One weapon's meshes and clips from the private resource pack (assets/ronweapons/ron/&lt;id&gt;/...).
 * The SWAT arms are shared by all weapons (assets/ronweapons/ron/arms.ronmesh).
 */
public final class GunRig {
    static final Logger LOG = LoggerFactory.getLogger("ronweapons");
    static final String[] ACTIONS = {
            "draw", "idle", "idle_empty", "walk", "run", "fire", "fire_last", "fire_ads",
            "reload_tac", "reload_empty", "magcheck", "select_auto", "select_semi",
            "reload_start", "reload_start_empty", "reload_loop", "reload_end", "dryfire", "restrain",
            // ballistic shield rigs: shield raised locomotion + transitions, reloads with the shield up, bashes
            "idle_up", "walk_up", "raise", "lower", "reload_tac_up", "reload_empty_up", "bash", "bash_up",
            // grenades: pull the pin and hold (held at the last frame), release
            "throw_start", "throw", "throw_under_start", "throw_under",
            // M4: flip the goggles down / up
            "nvg_on", "nvg_off",
            // RoN game-feel reactions (arms only): flashed, gassed, stung
            "aff_flash_start", "aff_flash_loop", "aff_flash_end", "aff_gas_start", "aff_gas_loop", "aff_gas_end",
            "aff_sting_start", "aff_sting_loop", "aff_sting_end"
    };

    /** One RoN action: an arms clip and (optionally) the matching weapon clip, mapped to mesh bones. */
    public static final class Action {
        public final RonClip arms, weapon;
        final int[] armsMap, weaponMap;
        Action(RonClip arms, RonClip weapon, RonMesh armsMesh, RonMesh gunMesh) {
            this.arms = arms;
            this.weapon = weapon;
            this.armsMap = arms == null ? null : arms.map(armsMesh);
            this.weaponMap = weapon == null ? null : weapon.map(gunMesh);
        }
        public float duration() {
            return Math.max(arms == null ? 0 : arms.duration(), weapon == null ? 0 : weapon.duration());
        }
    }

    /** A static mesh RoN hangs on gun sockets (magazines, rounds). Later sockets are skipped when they sit on the first. */
    /** hideAboveY: not drawn while the socket sits further back than this (gun space cm), e.g. a shell parked in the stock */
    public record Attachment(RonMesh mesh, int[] sockets, float hideAboveY) {}

    public record Cue(float t, Identifier sound, float volume) {}

    /** A swappable optic on a gun socket. eye: ADS eye in gun space (cm) on the reticle line; hideGun: gun materials it replaces. */
    public record Optic(String id, RonMesh mesh, int socket, float[] eye, boolean keepDistance, boolean[] hideGun, float zoom) {}

    /**
     * Spent brass / dropped mags. ejectOn: "fire" or the cue sound (e.g. "m590.pump_back") that ejects; count casings per eject;
     * vel: camera-space eject velocity (blocks/s); magCue: cue sound at which the empty mag leaves the gun.
     */
    public record FxConfig(RonMesh casing, int ejectBone, String ejectOn, int count, float[] vel, String magCue,
                           RonMesh mag, int magSocket, Identifier landSound) {}

    /** A muzzle device (suppressor, brake, compensator, choke) on a gun socket; hideBones: gun parts it replaces (RoN HideBoneOnMesh). */
    public record Muzzle(String id, RonMesh mesh, int socket, boolean suppressor, boolean[] hideBones) {}

    /** A mesh carried by an arms bone (the ballistic shield on ShieldAttachSocket). */
    public record ArmsAttachment(RonMesh mesh, int bone) {}

    /** Weapon light on a gun socket; lens: beam origin in socket space (cm). */
    public record Light(RonMesh mesh, int socket, float[] lens) {}

    /**
     * A foregrip on a gun socket. hand: where the left hand sits relative to the socket (arms model space, cm), from a RoN idle
     * pose made for that grip; fingers: arms bone index + local pos / rot of the hand's children in that pose (the grip hold).
     */
    public record Grip(String id, RonMesh mesh, int socket, int handSocket, Matrix4f hand, int[] fingerBones, float[] fingerPos, float[] fingerRot) {}

    /** Mag check read-out: socket the numbers ride on (gun space offset, cm) and the clip window the magazine is out (or null). */
    public record MagCheck(int socket, float[] offset, float[] window) {}

    public final String id;
    public final RonMesh arms, gun;
    public final List<Attachment> attachments = new ArrayList<>();
    public final Map<String, Action> actions = new HashMap<>();
    public final int attachBone, cameraBone, muzzleBone;
    /** gun bones whose clip changes must snap instead of blend (magazines teleport between hand, well and pouch) */
    public final boolean[] gunNoBlend;
    /** ADS eye position in gun space (cm) on the sight line. adsKeepDistance: y comes from the hip pose (pistols). */
    public final float[] adsEye = {0f, 11.0f, 9.42f};
    public boolean adsKeepDistance;
    public final Matrix4f cameraBindRot;
    /** sound cues per action: seconds into the clip -> sound event */
    public final Map<String, List<Cue>> cues = new HashMap<>();
    public final Map<String, Optic> optics = new HashMap<>();
    public Muzzle suppressor;
    /** RoN muzzle devices by id (WeaponDef.muzzles); packs before v10 only have the single suppressor above */
    public final Map<String, Muzzle> muzzles = new HashMap<>();
    public FxConfig fx;
    public ArmsAttachment armsAttachment;
    public Light light;
    public final Map<String, Grip> grips = new HashMap<>();
    public MagCheck magCheck;
    /** left arm bones moved as one piece to put the hand on a grip (upperarm_LE and everything below it), and the hand */
    public final int[] leftArm;
    public final int handLE;
    /** action -> seconds after which the gun mesh is not drawn (the grenade has left the hand) */
    public final Map<String, Float> hideAfter = new HashMap<>();
    /** ADS correction computed from this action's pose instead of idle (shield: from the raised pose); ADS blend time */
    public String adsFrom = "idle";
    public float adsTime = 0.18f;
    /** third person: how far (blocks) the gun sits back from the fist so the stock reaches the shoulder; yaw undoes the crossbow-pose inward angle */
    public float tpBack, tpYaw = -17.2f;
    /** third person: fist centre relative to the held-item frame (blocks); tweakable from rig.json "tp": {"offset": [x, y, z]} */
    public final float[] tpOffset = {-1f / 16f, -2f / 16f, 0f};

    private static final Map<String, GunRig> RIGS = new HashMap<>();
    private static final Map<String, String> ERRORS = new HashMap<>();
    private static RonMesh sharedArms;
    private static boolean armsTried;

    private GunRig(ResourceManager rm, String id) throws IOException {
        this.id = id;
        String base = "ron/" + id + "/";
        arms = arms(rm, base);
        gun = RonMesh.read(open(rm, base + "gun.ronmesh"));
        Optional<Resource> meta = rm.getResource(RonWeapons.id(base + "rig.json"));
        JsonObject o = new JsonObject();
        if (meta.isPresent()) {
            try (InputStreamReader r = new InputStreamReader(meta.get().getInputStream(), StandardCharsets.UTF_8)) {
                o = JsonParser.parseReader(r).getAsJsonObject();
            }
        }
        if (o.has("ads_eye")) {
            JsonArray a = o.getAsJsonArray("ads_eye");
            for (int k = 0; k < 3; k++) {
                JsonElement e = a.get(k);
                if (e.isJsonNull()) { if (k == 1) adsKeepDistance = true; }
                else adsEye[k] = e.getAsFloat();
            }
        }
        if (o.has("cues")) {
            JsonObject c = o.getAsJsonObject("cues");
            for (String action : c.keySet()) {
                List<Cue> list = new ArrayList<>();
                for (JsonElement el : c.getAsJsonArray(action)) {
                    JsonArray q = el.getAsJsonArray();
                    list.add(new Cue(q.get(0).getAsFloat(), RonWeapons.id(q.get(1).getAsString()),
                            q.size() > 2 ? q.get(2).getAsFloat() : 1f));
                }
                list.sort(Comparator.comparingDouble(Cue::t));
                cues.put(action, list);
            }
        }
        if (o.has("attachments")) {
            for (JsonElement el : o.getAsJsonArray("attachments")) {
                JsonObject at = el.getAsJsonObject();
                addAttachment(rm, base + at.get("mesh").getAsString() + ".ronmesh", at.getAsJsonArray("sockets"),
                        at.has("hide_y_above") ? at.get("hide_y_above").getAsFloat() : Float.NaN);
            }
        } else {
            // packs from before multi-weapon support: M4 magazine on its two sockets
            JsonArray s = new JsonArray();
            s.add("mag_01_socket");
            s.add("mag_02_socket");
            addAttachment(rm, base + "mag.ronmesh", s, Float.NaN);
        }
        if (o.has("optics")) {
            for (JsonElement el : o.getAsJsonArray("optics")) {
                JsonObject op = el.getAsJsonObject();
                Optional<Resource> r = rm.getResource(RonWeapons.id(base + op.get("mesh").getAsString() + ".ronmesh"));
                int socket = gun.bone(op.get("socket").getAsString());
                if (r.isEmpty() || socket < 0) continue;
                float[] eye = adsEye.clone();
                boolean keep = adsKeepDistance;
                JsonArray e = op.getAsJsonArray("eye");
                for (int k = 0; k < 3; k++) {
                    if (e.get(k).isJsonNull()) { if (k == 1) keep = true; }
                    else eye[k] = e.get(k).getAsFloat();
                }
                boolean[] hide = new boolean[gun.materialNames.length];
                if (op.has("hide")) for (JsonElement h : op.getAsJsonArray("hide")) {
                    int m = gun.material(h.getAsString());
                    if (m >= 0) hide[m] = true;
                }
                String oid = op.get("id").getAsString();
                optics.put(oid, new Optic(oid, RonMesh.read(r.get().getInputStream()), socket, eye, keep, hide,
                        op.has("zoom") ? Math.max(1f, op.get("zoom").getAsFloat()) : 1f));
            }
        }
        if (o.has("suppressor")) {
            JsonObject sp = o.getAsJsonObject("suppressor");
            Optional<Resource> r = rm.getResource(RonWeapons.id(base + sp.get("mesh").getAsString() + ".ronmesh"));
            int socket = gun.bone(sp.get("socket").getAsString());
            if (r.isPresent() && socket >= 0) suppressor = new Muzzle("supp", RonMesh.read(r.get().getInputStream()), socket, true, null);
        }
        if (o.has("muzzles")) {
            for (JsonElement el : o.getAsJsonArray("muzzles")) {
                JsonObject mz = el.getAsJsonObject();
                Optional<Resource> r = rm.getResource(RonWeapons.id(base + mz.get("mesh").getAsString() + ".ronmesh"));
                int socket = gun.bone(mz.get("socket").getAsString());
                if (r.isEmpty() || socket < 0) continue;
                boolean[] hide = null;
                if (mz.has("hide_bone") && !mz.get("hide_bone").isJsonNull()) {
                    int hb = gun.bone(mz.get("hide_bone").getAsString());
                    if (hb >= 0) {
                        hide = new boolean[gun.parents.length];
                        hide[hb] = true;
                        for (int b = hb + 1; b < hide.length; b++) if (gun.parents[b] >= 0 && hide[gun.parents[b]]) hide[b] = true;
                    }
                }
                String mid = mz.get("id").getAsString();
                muzzles.put(mid, new Muzzle(mid, RonMesh.read(r.get().getInputStream()), socket,
                        mz.has("suppressor") && mz.get("suppressor").getAsBoolean(), hide));
            }
        }
        if (o.has("fx")) {
            JsonObject f = o.getAsJsonObject("fx");
            RonMesh casing = null;
            if (f.has("casing")) {
                Optional<Resource> r = rm.getResource(RonWeapons.id(base + f.get("casing").getAsString() + ".ronmesh"));
                if (r.isPresent()) casing = RonMesh.read(r.get().getInputStream());
            }
            float[] vel = {2.4f, 1.8f, 0.5f};
            if (f.has("vel")) for (int k = 0; k < 3; k++) vel[k] = f.getAsJsonArray("vel").get(k).getAsFloat();
            int magSocket = f.has("mag_socket") ? gun.bone(f.get("mag_socket").getAsString()) : -1;
            RonMesh mag = null;
            if (magSocket >= 0 && !attachments.isEmpty()) mag = attachments.get(0).mesh();
            fx = new FxConfig(casing, f.has("eject_bone") ? gun.bone(f.get("eject_bone").getAsString()) : -1,
                    f.has("eject_on") ? f.get("eject_on").getAsString() : "fire", f.has("count") ? f.get("count").getAsInt() : 1, vel,
                    f.has("mag_cue") ? f.get("mag_cue").getAsString() : null, mag, magSocket,
                    f.has("land_sound") ? RonWeapons.id(f.get("land_sound").getAsString()) : null);
        }
        if (o.has("arms_attachment")) {
            JsonObject aa = o.getAsJsonObject("arms_attachment");
            Optional<Resource> r = rm.getResource(RonWeapons.id(base + aa.get("mesh").getAsString() + ".ronmesh"));
            int bone = arms.bone(aa.get("bone").getAsString());
            if (r.isPresent() && bone >= 0) armsAttachment = new ArmsAttachment(RonMesh.read(r.get().getInputStream()), bone);
        }
        if (o.has("light")) {
            JsonObject l = o.getAsJsonObject("light");
            Optional<Resource> r = rm.getResource(RonWeapons.id(base + l.get("mesh").getAsString() + ".ronmesh"));
            int socket = gun.bone(l.get("socket").getAsString());
            float[] lens = new float[3];
            if (l.has("lens")) for (int k = 0; k < 3; k++) lens[k] = l.getAsJsonArray("lens").get(k).getAsFloat();
            if (r.isPresent() && socket >= 0) light = new Light(RonMesh.read(r.get().getInputStream()), socket, lens);
        }
        if (o.has("grips")) {
            JsonObject gs = o.getAsJsonObject("grips");
            for (String gid : gs.keySet()) {
                JsonObject g = gs.getAsJsonObject(gid);
                Optional<Resource> r = rm.getResource(RonWeapons.id(base + g.get("mesh").getAsString() + ".ronmesh"));
                int socket = gun.bone(g.get("socket").getAsString());
                if (r.isEmpty() || socket < 0) continue;
                float[] hm = new float[16];
                JsonArray ha = g.getAsJsonArray("hand");
                for (int k = 0; k < 16; k++) hm[k] = ha.get(k).getAsFloat();
                List<Integer> fb = new ArrayList<>();
                List<float[]> fv = new ArrayList<>();
                if (g.has("fingers")) for (JsonElement el : g.getAsJsonArray("fingers")) {
                    JsonArray f = el.getAsJsonArray();
                    int b = arms.bone(f.get(0).getAsString());
                    if (b < 0) continue;
                    float[] v = new float[7];
                    for (int k = 0; k < 7; k++) v[k] = f.get(k + 1).getAsFloat();
                    fb.add(b);
                    fv.add(v);
                }
                float[] fp = new float[fb.size() * 3], fr = new float[fb.size() * 4];
                for (int i = 0; i < fb.size(); i++) {
                    System.arraycopy(fv.get(i), 0, fp, i * 3, 3);
                    System.arraycopy(fv.get(i), 3, fr, i * 4, 4);
                }
                int handSocket = g.has("hand_socket") ? gun.bone(g.get("hand_socket").getAsString()) : socket;
                grips.put(gid, new Grip(gid, RonMesh.read(r.get().getInputStream()), socket, handSocket < 0 ? socket : handSocket, new Matrix4f().set(hm),
                        fb.stream().mapToInt(Integer::intValue).toArray(), fp, fr));
            }
        }
        if (o.has("magcheck")) {
            JsonObject mc = o.getAsJsonObject("magcheck");
            int socket = gun.bone(mc.get("socket").getAsString());
            float[] off = {0f, 0f, 1.4f};
            if (mc.has("offset")) for (int k = 0; k < 3; k++) off[k] = mc.getAsJsonArray("offset").get(k).getAsFloat();
            float[] win = null;
            if (mc.has("window") && mc.get("window").isJsonArray()) {
                JsonArray w = mc.getAsJsonArray("window");
                win = new float[] {w.get(0).getAsFloat(), w.get(1).getAsFloat()};
            }
            if (socket >= 0) magCheck = new MagCheck(socket, off, win);
        }
        if (o.has("hide_after")) {
            JsonObject h = o.getAsJsonObject("hide_after");
            for (String k : h.keySet()) hideAfter.put(k, h.get(k).getAsFloat());
        }
        if (o.has("ads_from")) adsFrom = o.get("ads_from").getAsString();
        if (o.has("ads_time")) adsTime = o.get("ads_time").getAsFloat();
        if (o.has("tp")) {
            JsonObject tp = o.getAsJsonObject("tp");
            if (tp.has("back")) tpBack = tp.get("back").getAsFloat();
            if (tp.has("yaw")) tpYaw = tp.get("yaw").getAsFloat();
            if (tp.has("offset")) for (int k = 0; k < 3; k++) tpOffset[k] = tp.getAsJsonArray("offset").get(k).getAsFloat();
        }
        for (String a : ACTIONS) {
            RonClip ac = clip(rm, base + "anims/" + a + "_arms.ronanim");
            RonClip wc = clip(rm, base + "anims/" + a + "_weapon.ronanim");
            if (ac != null || wc != null) actions.put(a, new Action(ac, wc, arms, gun));
        }
        if (!actions.containsKey("idle")) throw new IOException("idle clip missing");
        attachBone = require(arms, "weapon_right");
        cameraBone = require(arms, "fp_camera");
        muzzleBone = gun.bone("tag_muzzle");
        gunNoBlend = new boolean[gun.parents.length];
        for (int b = 0; b < gun.parents.length; b++) {
            String n = gun.boneNames[b].toLowerCase(Locale.ROOT);
            gunNoBlend[b] = n.startsWith("tag_mag_") || n.startsWith("tag_bullet"); // shotgun shell teleports hand -> tube
        }
        cameraBindRot = new Matrix4f(arms.bindGlobal[cameraBone]).setTranslation(0, 0, 0);
        handLE = arms.bone("hand_LE");
        int upper = arms.bone("upperarm_LE");
        List<Integer> chain = new ArrayList<>();
        if (upper >= 0) {
            boolean[] in = new boolean[arms.parents.length];
            in[upper] = true;
            chain.add(upper);
            for (int b = upper + 1; b < arms.parents.length; b++) {
                if (arms.parents[b] >= 0 && in[arms.parents[b]]) { in[b] = true; chain.add(b); }
            }
        }
        leftArm = chain.stream().mapToInt(Integer::intValue).toArray();
    }

    private void addAttachment(ResourceManager rm, String path, JsonArray socketNames, float hideAboveY) throws IOException {
        Optional<Resource> r = rm.getResource(RonWeapons.id(path));
        if (r.isEmpty()) return;
        List<Integer> idx = new ArrayList<>();
        for (JsonElement s : socketNames) {
            int b = gun.bone(s.getAsString());
            if (b >= 0) idx.add(b);
        }
        if (idx.isEmpty()) return;
        attachments.add(new Attachment(RonMesh.read(r.get().getInputStream()), idx.stream().mapToInt(Integer::intValue).toArray(), hideAboveY));
    }

    private static RonMesh arms(ResourceManager rm, String base) throws IOException {
        Optional<Resource> own = rm.getResource(RonWeapons.id(base + "arms.ronmesh"));
        if (own.isPresent()) return RonMesh.read(own.get().getInputStream());
        if (!armsTried) {
            armsTried = true;
            sharedArms = RonMesh.read(open(rm, "ron/arms.ronmesh"));
        }
        if (sharedArms == null) throw new IOException("missing ron/arms.ronmesh");
        return sharedArms;
    }

    private static int require(RonMesh mesh, String bone) throws IOException {
        int b = mesh.bone(bone);
        if (b < 0) throw new IOException("bone " + bone + " missing");
        return b;
    }

    private static InputStream open(ResourceManager rm, String path) throws IOException {
        Optional<Resource> r = rm.getResource(RonWeapons.id(path));
        if (r.isEmpty()) throw new IOException("missing " + path);
        return r.get().getInputStream();
    }

    private static RonClip clip(ResourceManager rm, String path) throws IOException {
        Optional<Resource> r = rm.getResource(RonWeapons.id(path));
        return r.isEmpty() ? null : RonClip.read(r.get().getInputStream());
    }

    /** The rig of one weapon, or null when the private pack does not have it (the error is logged once). */
    public static GunRig get(ResourceManager rm, String id) {
        if (id == null) return null;
        if (RIGS.containsKey(id)) return RIGS.get(id);
        GunRig rig = null;
        try {
            rig = new GunRig(rm, id);
            ERRORS.remove(id);
            LOG.info("RoN rig {} loaded: gun {} verts, {} attachments, {} optics, suppressor {}, {} actions",
                    id, rig.gun.vertexCount, rig.attachments.size(), rig.optics.size(), rig.suppressor != null, rig.actions.size());
        } catch (Exception e) {
            ERRORS.put(id, e.getMessage());
            LOG.warn("RoN rig {} not available: {}", id, e.getMessage());
        }
        RIGS.put(id, rig);
        return rig;
    }

    public static String error(String id) { return ERRORS.get(id); }

    /** the muzzle device for a stack's muzzle id (old packs: the one suppressor for any suppressor id) */
    public Muzzle muzzle(String id) {
        if (id == null || id.equals("none")) return null;
        Muzzle m = muzzles.get(id);
        if (m == null && muzzles.isEmpty() && ronweapons.WeaponDef.isSuppressor(id)) m = suppressor;
        return m;
    }

    /** seconds into the action at which a cue sound plays (ronweapons:&lt;sound&gt;), or -1 */
    public float cueTime(String action, String sound) {
        List<Cue> list = cues.get(action);
        if (list == null) return -1f;
        for (Cue c : list) if (c.sound().getPath().equals(sound)) return c.t();
        return -1f;
    }

    public static void invalidate() {
        RIGS.clear();
        ERRORS.clear();
        sharedArms = null;
        armsTried = false;
    }
}
