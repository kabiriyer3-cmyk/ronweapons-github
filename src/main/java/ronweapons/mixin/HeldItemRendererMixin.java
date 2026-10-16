package ronweapons.mixin;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ronweapons.client.FirstPersonRenderer;
import ronweapons.client.RonWeaponsClient;

@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {
    @Inject(method = "renderFirstPersonItem", at = @At("HEAD"), cancellable = true)
    private void ronweapons$renderGun(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand,
                                      float swingProgress, ItemStack item, float equipProgress, MatrixStack matrices,
                                      VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
        if (!RonWeaponsClient.holdingRig()) return;
        if (hand == Hand.MAIN_HAND) {
            if (FirstPersonRenderer.render(matrices, vertexConsumers, light)) ci.cancel();
        } else {
            ci.cancel(); // both RoN hands are busy (rifle / zipcuffs)
        }
    }
}
