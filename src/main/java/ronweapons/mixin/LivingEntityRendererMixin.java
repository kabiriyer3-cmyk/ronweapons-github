package ronweapons.mixin;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.model.SinglePartEntityModel;
import net.minecraft.client.render.entity.model.VillagerResemblingModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ronweapons.client.CuffClient;

/**
 * Procedural arrest poses on vanilla models (RoN's arrest clips are on its own skeleton):
 * being cuffed: hands go up, then behind the back and the mob drops to its knees; cuffed: kneeling, hands behind the back,
 * a little wiggle. Players restraining someone reach forward with both hands. Runs after every model's own setAngles.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
    @Shadow protected EntityModel<?> model;

    @Inject(method = "render(Lnet/minecraft/entity/LivingEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At(value = "INVOKE", shift = At.Shift.AFTER,
                    target = "Lnet/minecraft/client/render/entity/model/EntityModel;setAngles(Lnet/minecraft/entity/Entity;FFFFF)V"))
    private void ronweapons$arrestPose(LivingEntity entity, float yaw, float tickDelta, MatrixStack matrices,
                                       VertexConsumerProvider consumers, int light, CallbackInfo ci) {
        float p = CuffClient.progress(entity);
        if (model instanceof BipedEntityModel<?> b) {
            if (p >= 0) biped(b, p, entity.age + tickDelta);
            else if (entity instanceof PlayerEntity) {
                float c = CuffClient.cuffing(entity);
                if (c >= 0) reach(b, c);
            }
            if (b instanceof PlayerEntityModel<?> pm) {
                pm.leftSleeve.copyTransform(pm.leftArm);
                pm.rightSleeve.copyTransform(pm.rightArm);
                pm.leftPants.copyTransform(pm.leftLeg);
                pm.rightPants.copyTransform(pm.rightLeg);
                pm.jacket.copyTransform(pm.body);
            }
            b.hat.copyTransform(b.head);
        } else if (model instanceof SinglePartEntityModel<?> sp) {
            // villagers / illagers: hide the folded arms ("behind the back"), the renderer lowers them onto their knees
            ModelPart root = sp.getPart();
            if (root.hasChild("arms")) {
                if (p >= 0.4f) root.getChild("arms").visible = false;
                else if (model instanceof VillagerResemblingModel<?>) root.getChild("arms").visible = true;
            }
            if (p >= 0.4f) {
                if (root.hasChild("right_arm")) root.getChild("right_arm").visible = false;
                if (root.hasChild("left_arm")) root.getChild("left_arm").visible = false;
            }
        }
    }

    /** non-biped mobs: sink the whole model a bit (kneeling); bipeds fold their legs instead */
    @Inject(method = "setupTransforms(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/client/util/math/MatrixStack;FFFF)V", at = @At("TAIL"))
    private void ronweapons$kneel(LivingEntity entity, MatrixStack matrices, float animationProgress, float bodyYaw, float tickDelta,
                                  float scale, CallbackInfo ci) {
        if (model instanceof BipedEntityModel<?>) return;
        float p = CuffClient.progress(entity);
        if (p < 0.5f) return;
        float k = smooth((p - 0.5f) / 0.5f);
        matrices.translate(0f, -Math.min(0.45f, entity.getHeight() * 0.2f) * k, 0f);
    }

    private static float smooth(float x) {
        x = MathHelper.clamp(x, 0f, 1f);
        return x * x * (3 - 2 * x);
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    /** p: 0-0.35 hands up, 0.35-0.75 hands behind the back, 0.5-1 down on the knees */
    private static void biped(BipedEntityModel<?> m, float p, float age) {
        float up = smooth(p / 0.3f), back = smooth((p - 0.35f) / 0.4f), kneel = smooth((p - 0.5f) / 0.5f);
        float wiggle = p >= 1f ? MathHelper.sin(age * 0.25f) * 0.06f : 0f;
        // hands up (surrender) -> behind the back
        float pitch = lerp(lerp(m.rightArm.pitch, -2.9f, up), 0.55f, back);
        m.rightArm.pitch = pitch;
        m.leftArm.pitch = pitch;
        m.rightArm.yaw = lerp(0f, 0.25f, back);
        m.leftArm.yaw = lerp(0f, -0.25f, back);
        m.rightArm.roll = lerp(0.25f * up, -0.38f, back) + wiggle;
        m.leftArm.roll = lerp(-0.25f * up, 0.38f, back) - wiggle;
        // kneel: the whole upper body drops by 10 px, legs fold backwards along the ground
        float dy = 10f * kneel;
        m.head.pivotY += dy;
        m.body.pivotY += dy;
        m.rightArm.pivotY += dy;
        m.leftArm.pivotY += dy;
        m.rightLeg.pivotY += dy;
        m.leftLeg.pivotY += dy;
        m.rightLeg.pitch = lerp(m.rightLeg.pitch, 1.45f, kneel);
        m.leftLeg.pitch = lerp(m.leftLeg.pitch, 1.45f, kneel);
        m.rightLeg.yaw = 0f;
        m.leftLeg.yaw = 0f;
        m.rightLeg.roll = lerp(0f, 0.08f, kneel);
        m.leftLeg.roll = lerp(0f, -0.08f, kneel);
        m.head.pitch = lerp(m.head.pitch, 0.45f, back);
        m.body.pitch = lerp(m.body.pitch, 0.12f, kneel);
    }

    /** the player doing the cuffing: both hands forward and down onto the wrists, small tug while pulling the tie */
    private static void reach(BipedEntityModel<?> m, float t) {
        float in = smooth(t / 0.25f);
        float tug = t > 0.6f && t < 1.0f ? MathHelper.sin((t - 0.6f) * 25f) * 0.08f : 0f;
        m.rightArm.pitch = lerp(m.rightArm.pitch, -1.05f + tug, in);
        m.leftArm.pitch = lerp(m.leftArm.pitch, -1.05f - tug, in);
        m.rightArm.yaw = lerp(m.rightArm.yaw, -0.4f, in);
        m.leftArm.yaw = lerp(m.leftArm.yaw, 0.4f, in);
        m.rightArm.roll = 0f;
        m.leftArm.roll = 0f;
    }
}
