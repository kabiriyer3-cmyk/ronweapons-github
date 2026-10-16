package ronweapons.mixin;

import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ronweapons.GunItem;
import ronweapons.client.ItemModelRenderer;

/** Every item render path (GUI, item frames, dropped items) ends here, before the model's display transform. */
@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {
    @Inject(method = "renderItem(Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/render/model/json/ModelTransformationMode;ZLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;IILnet/minecraft/client/render/model/BakedModel;)V",
            at = @At("HEAD"), cancellable = true)
    private void ronweapons$model3d(ItemStack stack, ModelTransformationMode mode, boolean leftHanded, MatrixStack matrices,
                                    VertexConsumerProvider consumers, int light, int overlay, BakedModel model, CallbackInfo ci) {
        if (stack.isEmpty() || !(stack.getItem() instanceof GunItem)) return;
        if (ItemModelRenderer.render(stack, mode, matrices, consumers, light, overlay)) ci.cancel();
    }
}
