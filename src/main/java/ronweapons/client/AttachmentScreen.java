package ronweapons.client;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.ComponentType;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import ronweapons.RonWeapons;
import ronweapons.WeaponDef;
import ronweapons.net.AttachPayload;

/**
 * Attachment menu: the held gun in 3D in the middle (drag to turn, wheel to zoom), a ring of options of the current
 * category around it (hover = live preview on the model, click = mount), category tabs on top (click, 1-3 or Tab).
 * Only RoN's own options for this weapon are offered (WeaponDef lists).
 */
public class AttachmentScreen extends Screen {
    private record Cat(byte slot, String label, String prefix, String[] ids, ComponentType<Integer> comp) {}

    private final List<Cat> cats = new ArrayList<>();
    private int cat;
    private int hovered = -1;
    private float yaw = -25f, pitch = 12f, zoom = 1f;
    private boolean dragged;
    private long openedNs = System.nanoTime();
    private final int[] chosen = new int[3];

    public AttachmentScreen() {
        super(Text.translatable("ronweapons.menu.title"));
        ItemStack stack = held();
        WeaponDef d = RonWeapons.def(stack);
        if (d == null) return;
        if (d.opticCount() > 1) cats.add(new Cat(AttachPayload.OPTIC, "ronweapons.menu.optic", "ronweapons.optic.", d.optics(), RonWeapons.OPTIC));
        if (d.muzzleCount() > 1) cats.add(new Cat(AttachPayload.MUZZLE, "ronweapons.menu.muzzle", "ronweapons.muzzle.", d.muzzles(), RonWeapons.MUZZLE));
        if (d.gripCount() > 1) cats.add(new Cat(AttachPayload.GRIP, "ronweapons.menu.grip", "ronweapons.grip.", d.grips(), RonWeapons.GRIP));
        chosen[AttachPayload.OPTIC] = RonWeapons.optic(stack);
        chosen[AttachPayload.MUZZLE] = RonWeapons.muzzle(stack);
        chosen[AttachPayload.GRIP] = RonWeapons.grip(stack);
    }

    private static ItemStack held() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player == null ? ItemStack.EMPTY : mc.player.getMainHandStack();
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void tick() {
        // weapon switched / dropped: nothing to configure any more
        if (RonWeapons.def(held()) == null || cats.isEmpty()) close();
    }

    // ---------------------------------------------------------------- layout
    private float cx() { return width / 2f; }
    private float cy() { return height / 2f + 2; }
    /** the whole ring, the tabs above it and the two text lines below it stay on screen */
    private float outerR() { return Math.max(40f, Math.min(width * 0.36f, height / 2f - 42f)); }
    private float innerR() { return outerR() * 0.64f; }

    private int sectorAt(double mx, double my) {
        if (cats.isEmpty()) return -1;
        double dx = mx - cx(), dy = my - cy(), r = Math.hypot(dx, dy);
        if (r < innerR() || r > outerR() + 6) return -1;
        int n = cats.get(cat).ids().length;
        double a = Math.toDegrees(Math.atan2(dy, dx)) + 90 + 180.0 / n;   // 0 = top, clockwise on screen
        a = ((a % 360) + 360) % 360;
        return (int) (a / (360.0 / n)) % n;
    }

    // ---------------------------------------------------------------- drawing
    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        if (cats.isEmpty()) return;
        Cat c = cats.get(cat);
        hovered = sectorAt(mouseX, mouseY);
        int n = c.ids().length;
        float span = 360f / n, gap = n > 1 ? Math.min(2.5f, span * 0.08f) : 0f;

        // ring of options
        VertexConsumer vc = ctx.getVertexConsumers().getBuffer(RenderLayer.getGui());
        Matrix4f m = ctx.getMatrices().peek().getPositionMatrix();
        ring(vc, m, innerR() - 3, innerR() - 1, 0, 360, 0x60FFFFFF);
        for (int i = 0; i < n; i++) {
            float a0 = -90 - span / 2 + i * span + gap / 2, a1 = a0 + span - gap;
            boolean cur = chosen[c.slot()] == i, hov = hovered == i;
            int col = hov ? 0xD0F2F2F2 : cur ? 0xC8C8862A : 0xA01A1C20;
            float grow = hov ? 5 : 0;
            ring(vc, m, innerR(), outerR() + grow, a0, a1, col);
            if (cur) ring(vc, m, outerR() + grow + 1, outerR() + grow + 4, a0, a1, 0xFFFFB040);
        }
        ctx.draw();

        // labels on the ring
        for (int i = 0; i < n; i++) {
            double a = Math.toRadians(-90 + i * span);
            float r = (innerR() + outerR()) / 2f;
            float x = cx() + (float) Math.cos(a) * r, y = cy() + (float) Math.sin(a) * r;
            Text t = Text.translatable(c.prefix() + c.ids()[i]);
            float maxW = (float) (Math.toRadians(span) * r * 0.9), w = textRenderer.getWidth(t);
            float s = Math.max(0.5f, Math.min(1f, w > maxW ? maxW / w : 1f));
            int color = hovered == i ? 0xFF101010 : chosen[c.slot()] == i ? 0xFFFFFFFF : 0xFFD8D8D8;
            MatrixStack ms = ctx.getMatrices();
            ms.push();
            ms.translate(x, y, 0);
            ms.scale(s, s, 1);
            ctx.drawText(textRenderer, t, (int) (-w / 2), -4, color, hovered != i);
            ms.pop();
        }

        // category tabs
        int tabW = 92, tabH = 16, gapT = 6, total = cats.size() * tabW + (cats.size() - 1) * gapT;
        int tx = (int) (cx() - total / 2f), ty = (int) Math.max(2, cy() - outerR() - 22);
        for (int i = 0; i < cats.size(); i++) {
            int x = tx + i * (tabW + gapT);
            boolean on = i == cat, hov = mouseX >= x && mouseX < x + tabW && mouseY >= ty && mouseY < ty + tabH;
            ctx.fill(x, ty, x + tabW, ty + tabH, on ? 0xE0C8862A : hov ? 0xC0404448 : 0xA01A1C20);
            if (on) ctx.fill(x, ty + tabH - 2, x + tabW, ty + tabH, 0xFFFFB040);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal((i + 1) + "  ").append(Text.translatable(cats.get(i).label())),
                    x + tabW / 2, ty + 4, 0xFFFFFFFF);
        }

        // the gun, live: hovered option previewed on the model
        drawGun(ctx, c, delta);

        // name of the hovered / mounted option + hints
        int show = hovered >= 0 ? hovered : chosen[c.slot()];
        Text name = Text.translatable(c.prefix() + c.ids()[show]);
        int by = (int) Math.min(height - 24, cy() + outerR() + 8);
        WeaponDef d = RonWeapons.def(held());
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(d == null ? "" : d.name() + "  \u2022  ").append(name),
                (int) cx(), by, hovered >= 0 ? 0xFFFFE08A : 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.translatable("ronweapons.menu.hint"), (int) cx(), by + 12, 0xFF9A9A9A);
    }

    private void drawGun(DrawContext ctx, Cat c, float delta) {
        ItemStack preview = held().copy();
        if (preview.isEmpty()) return;
        if (hovered >= 0) preview.set(c.comp(), hovered);
        if (!dragged) yaw += delta * 0.6f;   // slow turntable until the player grabs it
        float s = innerR() * 2.35f * zoom;
        MatrixStack ms = ctx.getMatrices();
        ms.push();
        ms.translate(cx(), cy(), 300);
        ms.scale(s, -s, s);
        ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(pitch));
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw));
        DiffuseLighting.enableGuiDepthLighting();
        boolean ok = ItemModelRenderer.render(preview, ModelTransformationMode.GROUND, false, ms, ctx.getVertexConsumers(),
                0xF000F0, OverlayTexture.DEFAULT_UV);
        if (!ok) {
            client.getItemRenderer().renderItem(preview, ModelTransformationMode.GUI, 0xF000F0, OverlayTexture.DEFAULT_UV, ms,
                    ctx.getVertexConsumers(), client.world, 0);
        }
        ctx.draw();
        DiffuseLighting.disableGuiDepthLighting();
        ms.pop();
    }

    /** annular sector between radii r0..r1 and angles a0..a1 (degrees, screen space, clockwise) as GUI quads */
    private void ring(VertexConsumer vc, Matrix4f m, float r0, float r1, float a0, float a1, int argb) {
        int steps = Math.max(2, (int) Math.ceil((a1 - a0) / 4f));
        float x = cx(), y = cy();
        for (int k = 0; k < steps; k++) {
            double b0 = Math.toRadians(a0 + (a1 - a0) * k / steps), b1 = Math.toRadians(a0 + (a1 - a0) * (k + 1) / steps);
            float c0 = (float) Math.cos(b0), s0 = (float) Math.sin(b0), c1 = (float) Math.cos(b1), s1 = (float) Math.sin(b1);
            vc.vertex(m, x + c0 * r0, y + s0 * r0, 0).color(argb);
            vc.vertex(m, x + c1 * r0, y + s1 * r0, 0).color(argb);
            vc.vertex(m, x + c1 * r1, y + s1 * r1, 0).color(argb);
            vc.vertex(m, x + c0 * r1, y + s0 * r1, 0).color(argb);
        }
    }

    // ---------------------------------------------------------------- input
    private void apply(int index) {
        Cat c = cats.get(cat);
        if (index < 0 || index >= c.ids().length) return;
        chosen[c.slot()] = index;
        ClientPlayNetworking.send(new AttachPayload(c.slot(), (byte) index));
        if (client.player != null) client.player.playSound(RonWeapons.SND_SELECT, 0.5f, 0.7f + 0.1f * c.slot());
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (cats.isEmpty()) return super.mouseClicked(mx, my, button);
        int tabW = 92, tabH = 16, gapT = 6, total = cats.size() * tabW + (cats.size() - 1) * gapT;
        int tx = (int) (cx() - total / 2f), ty = (int) Math.max(2, cy() - outerR() - 22);
        for (int i = 0; i < cats.size(); i++) {
            int x = tx + i * (tabW + gapT);
            if (mx >= x && mx < x + tabW && my >= ty && my < ty + tabH) { cat = i; return true; }
        }
        int s = sectorAt(mx, my);
        if (s >= 0 && button == 0) { apply(s); return true; }
        if (button == 1) { cat = (cat + 1) % cats.size(); return true; }   // right click on empty space: next category
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (Math.hypot(mx - cx(), my - cy()) < innerR() || button == 2) {
            dragged = true;
            yaw += (float) dx * 1.2f;
            pitch = Math.max(-80f, Math.min(80f, pitch + (float) dy * 1.2f));
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        zoom = Math.max(0.6f, Math.min(2.6f, zoom * (v > 0 ? 1.12f : 1 / 1.12f)));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (RonWeaponsClient.menuKey != null && RonWeaponsClient.menuKey.matchesKey(key, scan)
                && System.nanoTime() - openedNs > 150_000_000L) { close(); return true; }
        if (!cats.isEmpty()) {
            if (key == GLFW.GLFW_KEY_TAB) { cat = (cat + 1) % cats.size(); return true; }
            if (key >= GLFW.GLFW_KEY_1 && key < GLFW.GLFW_KEY_1 + cats.size()) { cat = key - GLFW.GLFW_KEY_1; return true; }
            if (key == GLFW.GLFW_KEY_R) { yaw = -25f; pitch = 12f; zoom = 1f; dragged = false; return true; }
        }
        return super.keyPressed(key, scan, mods);
    }
}
