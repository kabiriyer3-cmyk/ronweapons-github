package ronweapons.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import ronweapons.GrenadeItem;
import ronweapons.net.ActionPayload;

/**
 * Grenade handling like RoN: hold left click = pin pulled, arm cocked overarm; let go = throw.
 * Right click does the same underarm (rolls it along the floor). The server spawns the grenade at the release frame.
 */
public final class GrenadeClient {
    /** the pin is out at 0.52 s in every RoN pin-pull clip (tag_pin motion, diag_v7c.py) */
    private static final float PIN_TIME = 0.55f;
    /** seconds into the throw clip at which the grenade leaves the hand */
    private static final float RELEASE_OVER = 0.22f, RELEASE_UNDER = 0.32f;

    private enum State { READY, COOKING, THROWING, RECOVER }

    private static State state = State.READY;
    private static boolean under, wasAttack, wasUse, sent;
    private static int slot = -1;

    public static String heldKind() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null && mc.player.getMainHandStack().getItem() instanceof GrenadeItem g ? g.kind.id : null;
    }

    public static boolean holdingGrenade() { return heldKind() != null; }

    static void tick(MinecraftClient mc) {
        if (mc.player == null) return;
        boolean attack = mc.currentScreen == null && mc.options.attackKey.isPressed();
        boolean use = mc.currentScreen == null && mc.options.useKey.isPressed();
        GunAnimator a = RonWeaponsClient.ANIM;
        int sel = mc.player.getInventory().selectedSlot;
        if (!holdingGrenade() || sel != slot) {
            state = State.READY;
            slot = sel;
            wasAttack = attack;
            wasUse = use;
            return;
        }
        switch (state) {
            case READY -> {
                if (a.canAct()) {
                    if (attack && !wasAttack) start(a, false);
                    else if (use && !wasUse) start(a, true);
                }
            }
            case COOKING -> {
                boolean held = under ? use : attack;
                String clip = under ? "throw_under_start" : "throw_start";
                boolean pinOut = !a.current().equals(clip) || a.time() >= PIN_TIME;
                if (!held && pinOut) {
                    a.action(under ? "throw_under" : "throw");
                    state = State.THROWING;
                    sent = false;
                }
            }
            case THROWING -> {
                String clip = under ? "throw_under" : "throw";
                if (!sent && (!a.current().equals(clip) || a.time() >= (under ? RELEASE_UNDER : RELEASE_OVER))) {
                    ClientPlayNetworking.send(new ActionPayload(under ? ActionPayload.THROW_UNDER : ActionPayload.THROW_OVER));
                    sent = true;
                }
                if (sent && !a.current().equals(clip)) state = State.RECOVER;
            }
            case RECOVER -> {
                // next grenade from the pouch (the stack count came back from the server already)
                if (mc.player.getMainHandStack().getCount() > 0) a.draw();
                state = State.READY;
            }
        }
        wasAttack = attack;
        wasUse = use;
    }

    private static void start(GunAnimator a, boolean underarm) {
        under = underarm;
        a.action(underarm ? "throw_under_start" : "throw_start");
        state = State.COOKING;
    }
}
