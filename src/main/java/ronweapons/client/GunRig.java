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
            "draw", "idle", "idle_empty", "walk", "run", "fire", "fire_last",
            "reload_tac", "reload_empty", "magcheck", "select_auto", "select_semi"
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
    public record Attachment(RonMesh mesh, int[] sockets) {}

    public record Cue(float t, Identifier sound, float volume) {}

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
                addAttachment(rm, base + at.get("mesh").getAsString() + ".ronmesh", at.getAsJsonArray("sockets"));
            }
        } else {
            // packs from before multi-weapon support: M4 magazine on its two sockets
            JsonArray s = new JsonArray();
            s.add("mag_01_socket");
            s.add("mag_02_socket");
            addAttachment(rm, base + "mag.ronmesh", s);
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
            gunNoBlend[b] = gun.boneNames[b].toLowerCase(Locale.ROOT).startsWith("tag_mag_");
        }
        cameraBindRot = new Matrix4f(arms.bindGlobal[cameraBone]).setTranslation(0, 0, 0);
    }

    private void addAttachment(ResourceManager rm, String path, JsonArray socketNames) throws IOException {
        Optional<Resource> r = rm.getResource(RonWeapons.id(path));
        if (r.isEmpty()) return;
        List<Integer> idx = new ArrayList<>();
        for (JsonElement s : socketNames) {
            int b = gun.bone(s.getAsString());
            if (b >= 0) idx.add(b);
        }
        if (idx.isEmpty()) return;
        attachments.add(new Attachment(RonMesh.read(r.get().getInputStream()), idx.stream().mapToInt(Integer::intValue).toArray()));
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
            LOG.info("RoN rig {} loaded: gun {} verts, {} attachments, {} actions",
                    id, rig.gun.vertexCount, rig.attachments.size(), rig.actions.size());
        } catch (Exception e) {
            ERRORS.put(id, e.getMessage());
            LOG.warn("RoN rig {} not available: {}", id, e.getMessage());
        }
        RIGS.put(id, rig);
        return rig;
    }

    public static String error(String id) { return ERRORS.get(id); }

    public static void invalidate() {
        RIGS.clear();
        ERRORS.clear();
        sharedArms = null;
        armsTried = false;
    }
}
