package ronweapons.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import ronweapons.GunItem;
import ronweapons.RonWeapons;
import ronweapons.WeaponDef;
import ronweapons.net.ActionPayload;
import ronweapons.net.FirePayload;
import ronweapons.net.FxPayload;

public final class RonWeaponsClient implements ClientModInitializer {
    public static final GunAnimator ANIM = new GunAnimator();

    private static KeyBinding reloadKey, selectKey, magKey, suppKey, opticKey;
    private static boolean autoMode = true, wasAttack, wasHolding;
    private static String heldId;
    private static int heldSlot = -1, localAmmo = -1;
    private static long nextShotNs, lastShotNs;


    /** Non-positional sound for the local shooter (stereo 1P sounds from the private pack). */
    public static void playLocal(Identifier sound, float volume, float pitch) {
        MinecraftClient.getInstance().getSoundManager().play(new PositionedSoundInstance(sound, SoundCategory.PLAYERS,
                volume, pitch, SoundInstance.createRandom(), false, 0, SoundInstance.AttenuationType.NONE, 0, 0, 0, true));
    }

    /** The weapon in the main hand, or null. */
    public static WeaponDef heldDef() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player == null ? null : RonWeapons.def(mc.player.getMainHandStack());
    }

    public static boolean holdingGun() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null && mc.player.getMainHandStack().getItem() instanceof GunItem;
    }

    public static boolean aiming() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.currentScreen == null && mc.options.useKey.isPressed();
    }

    @Override
    public void onInitializeClient() {
        reloadKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.ronweapons.reload", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "category.ronweapons"));
        selectKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.ronweapons.fireselect", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B, "category.ronweapons"));
        magKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.ronweapons.magcheck", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.ronweapons"));
        suppKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.ronweapons.suppressor", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_N, "category.ronweapons"));
        opticKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.ronweapons.optic", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_M, "category.ronweapons"));
        ANIM.onShellInserted(() -> {
            WeaponDef d = heldDef();
            if (d != null) localAmmo = Math.min(d.magSize(), localAmmo + 1);
        });

        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public Identifier getFabricId() { return RonWeapons.id("rig"); }

            @Override
            public void reload(ResourceManager manager) { GunRig.invalidate(); }
        });

        ClientTickEvents.END_CLIENT_TICK.register(RonWeaponsClient::tick);
        ClientTickEvents.END_CLIENT_TICK.register(Fx::tick);
        ClientPlayNetworking.registerGlobalReceiver(FxPayload.ID, (payload, ctx) -> Fx.onFx(payload));
        WorldRenderEvents.AFTER_ENTITIES.register(Fx::render);
    }

    private static void tick(MinecraftClient mc) {
        ClientPlayerEntity player = mc.player;
        if (player == null) return;
        WeaponDef def = heldDef();
        boolean holding = def != null;
        int slot = player.getInventory().selectedSlot;
        ItemStack stack = player.getMainHandStack();
        if (holding && (!wasHolding || slot != heldSlot || !def.id().equals(heldId))) {
            heldId = def.id();
            ANIM.setRig(GunRig.get(mc.getResourceManager(), def.id()));
            ANIM.draw();
            localAmmo = RonWeapons.ammo(stack);
        }
        wasHolding = holding;
        heldSlot = slot;
        if (!holding) {
            while (reloadKey.wasPressed()) { }
            while (selectKey.wasPressed()) { }
            while (magKey.wasPressed()) { }
            while (suppKey.wasPressed()) { }
            while (opticKey.wasPressed()) { }
            wasAttack = false;
            return;
        }

        if (GunRig.get(mc.getResourceManager(), def.id()) == null) ANIM.update(0.05f); // no private pack: keep logic timing alive
        RonWeapons.Sounds snd = RonWeapons.SOUNDS.get(def.id());
        boolean auto = def.auto() && autoMode;
        long now = System.nanoTime();
        int serverAmmo = RonWeapons.ammo(stack);
        boolean shells = def.shellReload() && ANIM.reloading(); // the animator counts inserted shells itself
        if (localAmmo < 0 || (serverAmmo < localAmmo && !shells)
                || (serverAmmo > localAmmo && now - lastShotNs > 400_000_000L && !ANIM.reloading())) {
            localAmmo = serverAmmo;
        }
        ANIM.setEmpty(localAmmo <= 0);
        ANIM.setLocomotion(player.isOnGround() && player.getVelocity().horizontalLengthSquared() > 0.0025, player.isSprinting());

        while (reloadKey.wasPressed()) {
            if (localAmmo < def.magSize() && ANIM.canReload()) {
                boolean emptyMag = localAmmo <= 0;
                if (def.shellReload()) ANIM.reloadShells(emptyMag, def.magSize() - localAmmo);
                else ANIM.reload(emptyMag);
                if (!ANIM.hasCues(ANIM.current())) player.playSound(snd.reload(), 0.8f, 1.0f);
                ClientPlayNetworking.send(new ActionPayload(emptyMag ? ActionPayload.RELOAD_EMPTY : ActionPayload.RELOAD_TAC));
            }
        }
        while (selectKey.wasPressed()) {
            if (def.auto() && ANIM.canAct()) {
                autoMode = !autoMode;
                ANIM.action(autoMode ? "select_auto" : "select_semi");
                player.playSound(RonWeapons.SND_SELECT, 0.5f, 1.0f);
                player.sendMessage(Text.translatable(autoMode ? "ronweapons.mode.auto" : "ronweapons.mode.semi", def.name()), true);
            }
        }
        while (magKey.wasPressed()) {
            if (ANIM.canAct()) {
                ANIM.action("magcheck");
                player.sendMessage(Text.literal(localAmmo + " / " + def.magSize()), true);
            }
        }

        while (suppKey.wasPressed()) {
            if (def.suppressible() && ANIM.canAct()) {
                boolean on = !RonWeapons.suppressed(stack);
                ClientPlayNetworking.send(new ActionPayload(ActionPayload.TOGGLE_SUPPRESSOR));
                player.playSound(RonWeapons.SND_SELECT, 0.4f, on ? 0.8f : 1.2f);
                player.sendMessage(Text.translatable(on ? "ronweapons.suppressor.on" : "ronweapons.suppressor.off", def.name()), true);
            }
        }
        while (opticKey.wasPressed()) {
            if (def.opticCount() > 1 && ANIM.canAct()) {
                int next = (RonWeapons.optic(stack) + 1) % def.opticCount();
                ClientPlayNetworking.send(new ActionPayload(ActionPayload.CYCLE_OPTIC));
                player.playSound(RonWeapons.SND_SELECT, 0.4f, 0.9f);
                player.sendMessage(Text.translatable("ronweapons.optic", def.name(),
                        Text.translatable("ronweapons.optic." + def.optics()[next])), true);
            }
        }

        boolean attack = mc.currentScreen == null && mc.options.attackKey.isPressed();
        // shotguns: the trigger stops a shell reload after the shell in hand
        if (attack && !wasAttack && def.shellReload() && ANIM.shellReloading()) {
            ANIM.cancelShells();
            ClientPlayNetworking.send(new ActionPayload(ActionPayload.RELOAD_CANCEL));
        }
        if (attack && ANIM.canFire()) {
            boolean trigger = auto ? now >= nextShotNs : (!wasAttack && now >= nextShotNs);
            if (trigger) {
                if (localAmmo > 0) {
                    ClientPlayNetworking.send(new FirePayload());
                    localAmmo--;
                    lastShotNs = now;
                    nextShotNs = now + def.clientIntervalNs();
                    ANIM.fire(localAmmo == 0, aiming());
                    Fx.localFire(GunRig.get(mc.getResourceManager(), def.id()));
                    FirstPersonRenderer.kick(def.viewKick());
                    boolean supp = RonWeapons.suppressed(stack);
                    playLocal(RonWeapons.id(def.id() + (supp ? ".fire_1p_supp" : ".fire_1p")), supp ? 0.8f : 1.0f,
                            0.97f + player.getRandom().nextFloat() * 0.06f);
                    float kick = def.recoilPitch() * (aiming() ? 0.65f : 1f);
                    player.setPitch(player.getPitch() - kick);
                    player.setYaw(player.getYaw() + (player.getRandom().nextFloat() - 0.5f) * 0.4f);
                } else if (!wasAttack) {
                    player.playSound(snd.dry(), 0.6f, 1.0f);
                }
            }
        }
        wasAttack = attack;
    }
}
