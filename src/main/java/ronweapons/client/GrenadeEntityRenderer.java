package ronweapons.client;

import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;
import ronweapons.GrenadeEntity;
import ronweapons.RonWeapons;

/** A thrown grenade: the RoN mesh from the private pack (tumbling while it flies / rolls), or the item icon without it. */
public final class GrenadeEntityRenderer extends EntityRenderer<GrenadeEntity> {
    /** psk (Z up, cm) -> Minecraft (Y up, blocks): x = x, y = z, z = -y */
    private static final Matrix4f AXES = new Matrix4f(1, 0, 0, 0, 0, 0, -1, 0, 0, 1, 0, 0, 0, 0, 0, 1).scale(0.01f);
    private static final Map<RonMesh, float[][]> BAKED = new IdentityHashMap<>();

    public GrenadeEntityRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.08f;
    }

    @Override
    public void render(GrenadeEntity e, float yaw, float tickDelta, MatrixStack ms, VertexConsumerProvider consumers, int light) {
        GunRig rig = GunRig.get(MinecraftClient.getInstance().getResourceManager(), e.kind().id);
        ms.push();
        ms.translate(0f, 0.08f, 0f);
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-e.getYaw()));
        // lies on its side once it is down, tumbles end over end while moving
        ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(90f));
        ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(MathHelper.lerp(tickDelta, e.prevSpin, e.spin)));
        if (rig != null) {
            float[][] pn = BAKED.computeIfAbsent(rig.gun, m -> m.bakeRigid(AXES));
            rig.gun.emit(pn[0], pn[1], ms.peek(), consumers, light, OverlayTexture.DEFAULT_UV);
        } else {
            ms.scale(0.5f, 0.5f, 0.5f);
            MinecraftClient.getInstance().getItemRenderer().renderItem(new ItemStack(RonWeapons.GRENADES.get(e.kind())),
                    ModelTransformationMode.GROUND, light, OverlayTexture.DEFAULT_UV, ms, consumers, e.getWorld(), e.getId());
        }
        ms.pop();
        super.render(e, yaw, tickDelta, ms, consumers, light);
    }

    @Override
    public Identifier getTexture(GrenadeEntity e) { return RonWeapons.id("textures/item/" + e.kind().id + ".png"); }
}
