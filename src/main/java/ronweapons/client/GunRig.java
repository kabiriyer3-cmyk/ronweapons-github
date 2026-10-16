package ronweapons.client;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

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

    public final RonMesh arms, gun;
    public final Map<String, Action> actions = new HashMap<>();
    public final int attachBone, cameraBone, sightBone, muzzleBone;
    public final Matrix4f cameraBindRot;

    private static GunRig instance;
    private static boolean tried;
    private static String error;

    private GunRig(ResourceManager rm) throws IOException {
        arms = RonMesh.read(open(rm, BASE + "arms.ronmesh"));
        gun = RonMesh.read(open(rm, BASE + "gun.ronmesh"));
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
