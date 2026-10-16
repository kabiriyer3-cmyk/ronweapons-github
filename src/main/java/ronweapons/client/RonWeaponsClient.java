package ronweapons.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import ronweapons.GunItem;
import ronweapons.RonWeapons;
import ronweapons.net.ActionPayload;
import ronweapons.net.FirePayload;

public final class RonWeaponsClient implements ClientModInitializer {
    public static final GunAnimator ANIM = new GunAnimator();
    private static final long AUTO_INTERVAL_NS = 75_000_000L; // ~800 rpm

    private static KeyBinding reloadKey, selectKey, magKey;
    private static boolean auto = true, wasAttack, wasHolding;
    private static int heldSlot = -1, localAmmo = -1;
    private static long nextShotNs, lastShotNs;

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

        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public Identifier getFabricId() { return RonWeapons.id("rig"); }

            @Override
            public void reload(ResourceManager manager) { GunRig.invalidate(); }
        });

        ClientTickEvents.END_CLIENT_TICK.register(RonWeaponsClient::tick);
    }

    private static void tick(MinecraftClient mc) {
        ClientPlayerEntity player = mc.player;
        if (player == null) return;
        boolean holding = holdingGun();
        int slot = player.getInventory().selectedSlot;
        ItemStack stack = player.getMainHandStack();
        if (holding && (!wasHolding || slot != heldSlot)) {
            ANIM.draw();
            localAmmo = RonWeapons.ammo(stack);
        }
        wasHolding = holding;
        heldSlot = slot;
        if (!holding) {
            while (reloadKey.wasPressed()) { }
            while (selectKey.wasPressed()) { }
            while (magKey.wasPressed()) { }
            wasAttack = false;
            return;
        }

        if (GunRig.get(mc.getResourceManager()) == null) ANIM.update(0.05f); // no private pack: keep logic timing alive
        long now = System.nanoTime();
        int serverAmmo = RonWeapons.ammo(stack);
        if (localAmmo < 0 || serverAmmo < localAmmo
                || (serverAmmo > localAmmo && now - lastShotNs > 400_000_000L && !ANIM.reloading())) {
            localAmmo = serverAmmo;
        }
        ANIM.setEmpty(localAmmo <= 0);
        ANIM.setLocomotion(player.isOnGround() && player.getVelocity().horizontalLengthSquared() > 0.0025, player.isSprinting());

        while (reloadKey.wasPressed()) {
            if (localAmmo < RonWeapons.MAG_SIZE && ANIM.canReload()) {
                boolean emptyMag = localAmmo <= 0;
                ANIM.reload(emptyMag);
                ClientPlayNetworking.send(new ActionPayload(emptyMag ? ActionPayload.RELOAD_EMPTY : ActionPayload.RELOAD_TAC));
            }
        }
        while (selectKey.wasPressed()) {
            if (ANIM.canAct()) {
                auto = !auto;
                ANIM.action(auto ? "select_auto" : "select_semi");
                player.playSound(RonWeapons.SND_SELECT, 0.5f, 1.0f);
                player.sendMessage(Text.translatable(auto ? "ronweapons.mode.auto" : "ronweapons.mode.semi"), true);
            }
        }
        while (magKey.wasPressed()) {
            if (ANIM.canAct()) {
                ANIM.action("magcheck");
                player.sendMessage(Text.literal(localAmmo + " / " + RonWeapons.MAG_SIZE), true);
            }
        }

        boolean attack = mc.currentScreen == null && mc.options.attackKey.isPressed();
        if (attack && ANIM.canFire()) {
            boolean trigger = auto ? now >= nextShotNs : !wasAttack;
            if (trigger) {
                if (localAmmo > 0) {
                    ClientPlayNetworking.send(new FirePayload());
                    localAmmo--;
                    lastShotNs = now;
                    nextShotNs = now + AUTO_INTERVAL_NS;
                    ANIM.fire(localAmmo == 0);
                    float kick = aiming() ? 0.45f : 0.7f;
                    player.setPitch(player.getPitch() - kick);
                    player.setYaw(player.getYaw() + (player.getRandom().nextFloat() - 0.5f) * 0.4f);
                } else if (!wasAttack) {
                    player.playSound(RonWeapons.SND_DRY, 0.6f, 1.0f);
                }
            }
        }
        wasAttack = attack;
    }
}
