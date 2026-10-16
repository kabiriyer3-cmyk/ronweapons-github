package ronweapons.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ronweapons.RonWeapons;

/** Arms + gun meshes and their clips, loaded from the private resource pack (assets/ronweapons/ron/m4a1/...). */
public final class GunRig {
    static final Logger LOG = LoggerFactory.getLogger("ronweapons");
    static final String BASE = "ron/m4a1/";
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

    public final RonMesh arms, gun, mag;
    public final Map<String, Action> actions = new HashMap<>();
    public final int attachBone, cameraBone, sightBone, muzzleBone;
    /** sockets the separate magazine mesh hangs on; -1 if absent */
    public final int mag1Socket, mag2Socket, mag1Bone, mag2Bone;
    /** gun bones whose clip changes must snap instead of blend (magazines teleport between hand, well and pouch) */
    public final boolean[] gunNoBlend;
    /** ADS eye position in gun space (cm): on the iron sight line, behind the rear aperture */
    public final float[] adsEye = {0f, 11.0f, 9.42f};
    public final Matrix4f cameraBindRot;

    private static GunRig instance;
    private static boolean tried;
    private static String error;

    private GunRig(ResourceManager rm) throws IOException {
        arms = RonMesh.read(open(rm, BASE + "arms.ronmesh"));
        gun = RonMesh.read(open(rm, BASE + "gun.ronmesh"));
        Optional<Resource> magRes = rm.getResource(RonWeapons.id(BASE + "mag.ronmesh"));
        mag = magRes.isEmpty() ? null : RonMesh.read(magRes.get().getInputStream());
        Optional<Resource> meta = rm.getResource(RonWeapons.id(BASE + "rig.json"));
        if (meta.isPresent()) {
            try (InputStreamReader r = new InputStreamReader(meta.get().getInputStream(), StandardCharsets.UTF_8)) {
                JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
                if (o.has("ads_eye")) {
                    JsonArray a = o.getAsJsonArray("ads_eye");
                    for (int k = 0; k < 3; k++) adsEye[k] = a.get(k).getAsFloat();
                }
            }
        }
        for (String a : ACTIONS) {
            RonClip ac = clip(rm, BASE + "anims/" + a + "_arms.ronanim");
            RonClip wc = clip(rm, BASE + "anims/" + a + "_weapon.ronanim");
            if (ac != null || wc != null) actions.put(a, new Action(ac, wc, arms, gun));
        }
        if (!actions.containsKey("idle")) throw new IOException("idle clip missing");
        attachBone = require(arms, "weapon_right");
        cameraBone = require(arms, "fp_camera");
        sightBone = gun.bone("SightSocket");
        muzzleBone = gun.bone("tag_muzzle");
        mag1Socket = gun.bone("mag_01_socket");
        mag2Socket = gun.bone("mag_02_socket");
        mag1Bone = gun.bone("tag_mag_01");
        mag2Bone = gun.bone("tag_mag_02");
        gunNoBlend = new boolean[gun.parents.length];
        for (int b = 0; b < gun.parents.length; b++) {
            String n = gun.boneNames[b].toLowerCase(java.util.Locale.ROOT);
            gunNoBlend[b] = n.startsWith("tag_mag_");
        }
        cameraBindRot = new Matrix4f(arms.bindGlobal[cameraBone]).setTranslation(0, 0, 0);
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
        Identifier id = RonWeapons.id(path);
        Optional<Resource> r = rm.getResource(id);
        return r.isEmpty() ? null : RonClip.read(r.get().getInputStream());
    }

    public static GunRig get(ResourceManager rm) {
        if (!tried) {
            tried = true;
            try {
                instance = new GunRig(rm);
                error = null;
                LOG.info("RoN rig loaded: arms {} verts, gun {} verts, {} actions",
                        instance.arms.vertexCount, instance.gun.vertexCount, instance.actions.size());
            } catch (Exception e) {
                instance = null;
                error = e.getMessage();
                LOG.warn("RoN rig not available: {}", error);
            }
        }
        return instance;
    }

    public static String error() { return error; }

    public static void invalidate() {
        tried = false;
        instance = null;
    }
}
