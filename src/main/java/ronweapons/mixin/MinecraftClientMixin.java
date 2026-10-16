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

    @Inject(method = "handleBlockBreaking", at = @At("HEAD"), cancellable = true)
    private void ronweapons$noMining(boolean breaking, CallbackInfo ci) {
        if (RonWeaponsClient.holdingGun()) ci.cancel();
    }
}
