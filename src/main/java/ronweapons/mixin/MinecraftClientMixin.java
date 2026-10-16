package ronweapons.mixin;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ronweapons.client.RonWeaponsClient;

/** With the rifle out, the attack key shoots instead of punching or mining. */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    private void ronweapons$noMelee(CallbackInfoReturnable<Boolean> cir) {
        if (RonWeaponsClient.holdingGun()) cir.setReturnValue(false);
    }

    /** With the zipcuffs out, right click restrains (CuffClient) instead of trading with / using the mob. */
    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void ronweapons$cuffUse(CallbackInfo ci) {
        if (ronweapons.client.CuffClient.holdingCuffs()) ci.cancel();
    }

    @Inject(method = "handleBlockBreaking", at = @At("HEAD"), cancellable = true)
    private void ronweapons$noMining(boolean breaking, CallbackInfo ci) {
        if (RonWeaponsClient.holdingGun()) ci.cancel();
    }
}
