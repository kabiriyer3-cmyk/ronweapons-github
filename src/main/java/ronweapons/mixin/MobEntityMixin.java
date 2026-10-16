package ronweapons.mixin;

import net.minecraft.entity.mob.MobEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ronweapons.Cuffs;

/** Cuffed (or being cuffed) mobs skip goals, brain, navigation and look/move controls; physics still runs. */
@Mixin(MobEntity.class)
public abstract class MobEntityMixin {
    @Inject(method = "tickNewAi", at = @At("HEAD"), cancellable = true)
    private void ronweapons$cuffed(CallbackInfo ci) {
        MobEntity self = (MobEntity) (Object) this;
        if (!self.getWorld().isClient && Cuffs.frozen(self)) {
            Cuffs.hold(self);
            ci.cancel();
        }
    }
}
