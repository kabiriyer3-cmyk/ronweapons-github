package ronweapons.client;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import ronweapons.Gear;
import ronweapons.RonWeapons;
import ronweapons.WeaponDef;

/**
 * Small RoN-style weapon panel bottom right: weapon, fire mode, what is mounted. No ammo counter (RoN has none: check the
 * magazine, G). Attachment / mode changes show as a short toast above it instead of the vanilla action bar.
 */
public final class WeaponHud {
    private static final int ACCENT = 0xFF8FB9CC, BG = 0x99101418, DIM = 0xFF9AA4AA, ON = 0xFFE6EEF2;
    private static Text toast;
    private static long toastNs;

    static void init() {
        HudRenderCallback.EVENT.register(WeaponHud::render);
    }

    public static void notify(Text t) {
        toast = t;
        toastNs = System.nanoTime();
    }

    private static void render(DrawContext ctx, net.minecraft.client.render.RenderTickCounter tc) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;
        TextRenderer tr = mc.textRenderer;
        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
        int right = w - 8, bottom = h - 8;
        WeaponDef def = RonWeaponsClient.heldDef();
        int panelTop = bottom;
        if (def != null) {
            ItemStack stack = mc.player.getMainHandStack();
            String mode = !def.auto() ? "ronweapons.hud.semi" : RonWeaponsClient.autoMode() ? "ronweapons.hud.auto" : "ronweapons.hud.single";
            StringBuilder parts = new StringBuilder();
            String optic = def.optics()[RonWeapons.optic(stack)];
            if (!optic.equals("iron")) parts.append(Text.translatable("ronweapons.optic." + optic).getString());
            String muzzle = RonWeapons.muzzleId(stack);
            if (!muzzle.equals("none")) sep(parts).append(Text.translatable("ronweapons.muzzle." + muzzle).getString());
            String grip = RonWeapons.gripId(stack);
            if (!grip.equals("none")) sep(parts).append(Text.translatable("ronweapons.grip." + grip).getString());
            Text line1 = Text.literal(def.name());
            Text line2 = Text.translatable(mode);
            String line3 = parts.toString();
            boolean lightOn = Gear.lightOn(stack);
            String lightTxt = def.hasLight() ? Text.translatable(lightOn ? "ronweapons.hud.light_on" : "ronweapons.hud.light_off").getString() : "";
            int tw = Math.max(tr.getWidth(line1), Math.max(tr.getWidth(line2) + (lightTxt.isEmpty() ? 0 : tr.getWidth(lightTxt) + 10),
                    (int) (tr.getWidth(line3) * 0.75f)));
            int pw = tw + 14, ph = line3.isEmpty() ? 26 : 34;
            int x0 = right - pw, y0 = bottom - ph;
            ctx.fill(x0, y0, right, bottom, BG);
            ctx.fill(x0, y0, x0 + 2, bottom, ACCENT);
            ctx.drawText(tr, line1, x0 + 8, y0 + 4, ON, false);
            ctx.drawText(tr, line2, x0 + 8, y0 + 15, DIM, false);
            if (!lightTxt.isEmpty()) ctx.drawText(tr, lightTxt, right - 6 - tr.getWidth(lightTxt), y0 + 15, lightOn ? 0xFFFFE9A8 : DIM, false);
            if (!line3.isEmpty()) {
                ctx.getMatrices().push();
                ctx.getMatrices().translate(x0 + 8, y0 + 25, 0);
                ctx.getMatrices().scale(0.75f, 0.75f, 1f);
                ctx.drawText(tr, line3, 0, 0, DIM, false);
                ctx.getMatrices().pop();
            }
            panelTop = y0;
        }
        if (toast != null) {
            float age = (System.nanoTime() - toastNs) / 1e9f;
            if (age > 2.2f) { toast = null; return; }
            float a = Math.min(1f, Math.min(age / 0.12f, (2.2f - age) / 0.4f));
            int alpha = Math.max(8, (int) (a * 255));
            int tw = tr.getWidth(toast);
            int x1 = right - tw - 10, y1 = panelTop - 16;
            ctx.fill(x1, y1, right, y1 + 13, (int) (a * 0x99) << 24 | 0x101418);
            ctx.fill(right - 2, y1, right, y1 + 13, (alpha << 24) | (ACCENT & 0xFFFFFF));
            ctx.drawText(tr, toast, x1 + 5, y1 + 3, (alpha << 24) | 0xE6EEF2, false);
        }
    }

    private static StringBuilder sep(StringBuilder b) {
        if (b.length() > 0) b.append("  \u00b7  ");
        return b;
    }
}
