package ronweapons.client;

/**
 * Client-side action state machine with cross-fades. RoN clips are absolute poses, so blending is a plain
 * per-bone lerp/nlerp between the outgoing and incoming clip.
 */
public final class GunAnimator {
    private static final float FADE_DEFAULT = 0.2f;
    /** guns without an exportable draw clip get a procedural raise from below (seconds) */
    private static final float PROC_DRAW = 0.45f;
    private float procDraw;

    private String cur = "draw", prev = null;
    private float curT, prevT, fade, fadeDur = 0f;
    private boolean curLoop, prevLoop, curEmpty, prevEmpty;
    private boolean empty, moving, sprinting;
    private GunRig rig;
    private int cueIdx;
    /** shell-by-shell reload (shotguns): shells still to insert, stop after the shell in hand, callback per inserted shell */
    private int shellsLeft;
    private boolean shellCancel;
    private Runnable onShell;

    public void onShellInserted(Runnable r) { this.onShell = r; }

    public void setRig(GunRig rig) { this.rig = rig; }
    public void setEmpty(boolean empty) { this.empty = empty; }
    public void setLocomotion(boolean moving, boolean sprinting) { this.moving = moving; this.sprinting = sprinting; }

    private static boolean locomotion(String a) { return a.equals("idle") || a.equals("walk") || a.equals("run"); }

    private float duration(String a) {
        if (rig == null) return a.startsWith("reload") ? 2.5f : 0.3f;
        GunRig.Action act = rig.actions.get(a);
        return act == null ? 0f : act.duration();
    }

    private boolean has(String a) { return rig == null || rig.actions.containsKey(a); }

    public void play(String action, boolean loop, float fadeTime) {
        if (!has(action)) return;
        prev = cur; prevT = curT; prevLoop = curLoop; prevEmpty = curEmpty;
        cur = action; curT = 0; curLoop = loop; curEmpty = empty;
        fade = 0; fadeDur = fadeTime;
        cueIdx = 0;
    }

    public String current() { return cur; }

    public boolean hasCues(String a) { return rig != null && rig.cues.containsKey(a); }

    /** RoN plays reload / mag check foley on its FMOD timeline; here the cues follow the clip time. */
    private void fireCues() {
        if (rig == null) return;
        java.util.List<GunRig.Cue> list = rig.cues.get(cur);
        while (list != null && cueIdx < list.size() && list.get(cueIdx).t() <= curT) {
            GunRig.Cue c = list.get(cueIdx++);
            RonWeaponsClient.playLocal(c.sound(), c.volume(), 1f);
            Fx.localCue(rig, c.sound().getPath());
        }
    }

    /** On equip: the draw clip, or straight to idle for guns without one (also cancels a reload of the previous gun). */
    public void draw() {
        procDraw = 0;
        if (has("draw")) play("draw", false, 0.05f);
        else {
            play("idle", true, 0.1f);
            procDraw = PROC_DRAW;
            java.util.List<GunRig.Cue> list = rig == null ? null : rig.cues.get("draw");
            if (list != null) for (GunRig.Cue c : list) RonWeaponsClient.playLocal(c.sound(), c.volume(), 1f);
        }
    }

    /** 1 at the start of a procedural draw, 0 when the gun is up */
    public float procDraw() { return procDraw / PROC_DRAW; }

    /** empty trigger pull (revolver: the hammer still cocks and falls) */
    public void dryfire() {
        if (rig != null && rig.actions.containsKey("dryfire") && canAct()) play("dryfire", false, 0.05f);
    }

    /** back to the locomotion pose (cancelled restrain) */
    public void toIdle() { play("idle", true, 0.2f); }
    public void fire(boolean last, boolean ads) {
        String a = last && has("fire_last") ? "fire_last" : (ads && rig != null && rig.actions.containsKey("fire_ads") ? "fire_ads" : "fire");
        play(a, false, 0.05f);
    }

    /** RoN shotgun reload: start (grab a shell) or empty start (chamber one), loops (insert + grab next), end (insert the last). */
    public void reloadShells(boolean emptyGun, int need) {
        shellsLeft = need;
        shellCancel = false;
        play(emptyGun && has("reload_start_empty") ? "reload_start_empty" : "reload_start", false, 0.15f);
    }

    public boolean shellReloading() { return cur.equals("reload_start") || cur.equals("reload_start_empty") || cur.equals("reload_loop"); }

    public void cancelShells() { shellCancel = true; }

    private void inserted() {
        shellsLeft--;
        if (onShell != null) onShell.run();
    }
    public void reload(boolean emptyMag) { play(emptyMag && has("reload_empty") ? "reload_empty" : "reload_tac", false, 0.15f); }
    public void action(String a) { play(a, false, 0.15f); }

    public boolean reloading() { return cur.startsWith("reload"); }

    public boolean canFire() {
        if (procDraw > PROC_DRAW * 0.3f) return false;
        if (locomotion(cur) || cur.startsWith("fire") || cur.equals("dryfire")) return true;
        return cur.equals("draw") && curT > duration("draw") * 0.75f;
    }

    public boolean canReload() { return procDraw <= PROC_DRAW * 0.3f && (locomotion(cur) || cur.startsWith("fire") || cur.equals("magcheck") || cur.equals("dryfire")); }
    public boolean canAct() { return procDraw <= PROC_DRAW * 0.3f && (locomotion(cur) || cur.startsWith("fire") || cur.equals("dryfire")); }

    public void update(float dt) {
        curT += dt;
        prevT += dt;
        procDraw = Math.max(0f, procDraw - dt);
        fade += dt;
        fireCues();
        String loco = sprinting && has("run") ? "run" : (moving && has("walk") ? "walk" : "idle");
        if (locomotion(cur)) {
            if (!cur.equals(loco)) play(loco, true, 0.3f);
        } else if (curT >= duration(cur)) {
            if (shellReloading()) {
                // same decisions as the server (RonWeapons.stepShells)
                if (!cur.equals("reload_start")) inserted();
                if (shellsLeft <= 0) play(loco, true, FADE_DEFAULT);
                else if (shellsLeft == 1 || shellCancel) play("reload_end", false, 0.04f);
                else play("reload_loop", false, 0.04f);
            } else {
                if (cur.equals("reload_end")) inserted();
                play(loco, true, cur.startsWith("fire") ? 0.25f : FADE_DEFAULT);
            }
        }
        // the weapon idle pose depends on the bolt state, follow it while idling
        if (locomotion(cur)) curEmpty = empty;
    }

    public float fadeWeight() { return fadeDur <= 0 ? 1f : Math.min(1f, fade / fadeDur); }

    /** Fills local pose arrays (pre-initialised by the caller with bind poses) for the current blend. */
    public void pose(GunRig rig, float[] alp, float[] alr, float[] wlp, float[] wlr,
                     float[] tmpAP, float[] tmpAR, float[] tmpWP, float[] tmpWR) {
        sample(rig, cur, curT, curLoop, curEmpty, alp, alr, wlp, wlr);
        float w = fadeWeight();
        if (prev != null && w < 1f) {
            System.arraycopy(rig.arms.bindPos, 0, tmpAP, 0, tmpAP.length);
            System.arraycopy(rig.arms.bindRot, 0, tmpAR, 0, tmpAR.length);
            System.arraycopy(rig.gun.bindPos, 0, tmpWP, 0, tmpWP.length);
            System.arraycopy(rig.gun.bindRot, 0, tmpWR, 0, tmpWR.length);
            sample(rig, prev, prevT, prevLoop, prevEmpty, tmpAP, tmpAR, tmpWP, tmpWR);
            blend(tmpAP, tmpAR, alp, alr, w, null);
            blend(tmpWP, tmpWR, wlp, wlr, w, rig.gunNoBlend);
        }
    }

    static void sample(GunRig rig, String name, float t, boolean loop, boolean emptyBolt,
                               float[] alp, float[] alr, float[] wlp, float[] wlr) {
        GunRig.Action a = rig.actions.get(name);
        GunRig.Action idle = rig.actions.get("idle");
        GunRig.Action base = a != null && a.arms != null ? a : idle;
        base.arms.sample(base == a ? t : t, loop || base != a, base.armsMap, alp, alr);
        GunRig.Action wsrc = a != null && a.weapon != null ? a : null;
        if (wsrc == null) {
            GunRig.Action ie = rig.actions.get("idle_empty");
            wsrc = emptyBolt && ie != null && ie.weapon != null ? ie : idle;
            if (wsrc.weapon != null) wsrc.weapon.sample(0, true, wsrc.weaponMap, wlp, wlr);
        } else {
            wsrc.weapon.sample(t, loop, wsrc.weaponMap, wlp, wlr);
        }
    }

    /** to = lerp(from, to, w); bones flagged in skip keep the incoming pose */
    private static void blend(float[] fp, float[] fr, float[] tp, float[] tr, float w, boolean[] skip) {
        for (int i = 0; i < tp.length; i++) {
            if (skip != null && skip[i / 3]) continue;
            tp[i] = fp[i] + (tp[i] - fp[i]) * w;
        }
        float[] tmp = new float[4];
        for (int b = 0; b < tr.length / 4; b++) {
            if (skip != null && skip[b]) continue;
            RonClip.nlerp(fr, b * 4, tr, b * 4, w, tmp, 0);
            System.arraycopy(tmp, 0, tr, b * 4, 4);
        }
    }
}
