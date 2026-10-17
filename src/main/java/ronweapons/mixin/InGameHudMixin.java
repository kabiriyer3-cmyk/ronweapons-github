package ronweapons.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ronweapons.client.FirstPersonRenderer;
import ronweapons.client.RonWeaponsClient;

/** No crosshair while aiming down the sights (the sights are the crosshair). */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void ronweapons$noCrosshairAds(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (RonWeaponsClient.holdingGun() && FirstPersonRenderer.adsAmount() > 0.4f) ci.cancel();
    }
}
