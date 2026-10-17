package ronweapons.mixin;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ronweapons.client.FirstPersonRenderer;

/** Magnified optics (Specter DR, ATACR, MRO + magnifier): the world FOV narrows while aiming; the hands / gun keep theirs. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void ronweapons$opticZoom(Camera camera, float tickDelta, boolean changingFov, CallbackInfoReturnable<Double> cir) {
        if (!changingFov) return;
        float z = FirstPersonRenderer.zoom();
        if (z > 1.001f) cir.setReturnValue(cir.getReturnValue() / z);
    }
}
