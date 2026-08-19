package com.jubitus.travellers.client.notify;

import com.jubitus.travellers.Tags;
import com.jubitus.travellers.client.gui.ITravellersGui;
import com.jubitus.travellers.client.gui.TravellersGuiTheme;
import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;

@SideOnly(Side.CLIENT)
@Mod.EventBusSubscriber(modid = Tags.MOD_ID, value = Side.CLIENT)
public class NotificationOverlayRenderer {

    private static final int PADDING = 4;
    private static final int GAP = 3;

    public static void draw(ScaledResolution res) {
        List<ActiveNotification> notifications = ClientNotificationCache.visible();
        if (notifications.isEmpty()) return;

        FontRenderer fr = Minecraft.getMinecraft().fontRenderer;
        float fontScale = TravellersConfig.NOTIFICATIONS.fontScale;
        int marginX = TravellersConfig.NOTIFICATIONS.marginX;
        int marginY = TravellersConfig.NOTIFICATIONS.marginY;
        boolean left = isLeft();
        boolean top = isTop();

        int screenW = res.getScaledWidth();
        int screenH = res.getScaledHeight();

        // Newest is at the end of the list and is always placed closest to the anchor edge;
        // older notifications stack away from the edge (upward for BOTTOM_*, downward for TOP_*).
        int cursorY = top ? marginY : screenH - marginY;

        for (int i = notifications.size() - 1; i >= 0; i--) {
            ActiveNotification n = notifications.get(i);
            int lineHeight = (int) (fr.FONT_HEIGHT * fontScale);
            int boxW = (int) (TravellersConfig.NOTIFICATIONS.widthPixels * fontScale) + PADDING * 2;
            int boxH = n.wrappedLines.size() * lineHeight + PADDING * 2;

            int boxX = left ? marginX : screenW - marginX - boxW;
            int boxY = top ? cursorY : cursorY - boxH;

            drawBox(n, fr, boxX, boxY, boxW, boxH, fontScale);

            cursorY = top ? (boxY + boxH + GAP) : (boxY - GAP);
        }
    }

    // Vanilla FontRenderer treats a color whose alpha byte is near 0 as "no alpha specified"
    // and silently forces it back to fully opaque (see FontRenderer#drawString's `(color &
    // 0xFC000000) == 0` check) - drawing text at that alpha would flash opaque right before
    // the notification is removed, instead of finishing the fade invisibly. Skip the box once
    // it's faded low enough that it should already read as gone.
    private static final int MIN_VISIBLE_ALPHA_BYTE = 8;

    private static void drawBox(ActiveNotification n, FontRenderer fr, int x, int y, int w, int h, float fontScale) {
        float alpha = n.alpha();
        int alphaByte = (int) (alpha * 255F);
        if (alphaByte < MIN_VISIBLE_ALPHA_BYTE) return;

        int bg = (TravellersGuiTheme.COL_BG & 0x00FFFFFF) | (alphaByte << 24);
        int border = (TravellersGuiTheme.COL_BORDER & 0x00FFFFFF) | (alphaByte << 24);
        net.minecraft.client.gui.Gui.drawRect(x, y, x + w, y + h, bg);
        TravellersGuiTheme.drawBorder(x, y, w, h, border);

        int textColor = (n.type.color & 0x00FFFFFF) | (alphaByte << 24);

        // Gui.drawRect leaves blending disabled when it returns, so the text's alpha channel
        // would otherwise be ignored and glyphs would pop in/out instead of fading with the box.
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO);

        GlStateManager.pushMatrix();
        GlStateManager.translate(x + PADDING, y + PADDING, 0.0F);
        GlStateManager.scale(fontScale, fontScale, 1.0F);
        for (int i = 0; i < n.wrappedLines.size(); i++) {
            fr.drawString(n.wrappedLines.get(i), 0, i * fr.FONT_HEIGHT, textColor);
        }
        GlStateManager.popMatrix();

        GlStateManager.disableBlend();
    }

    private static boolean isLeft() {
        String corner = TravellersConfig.NOTIFICATIONS.corner;
        return "TOP_LEFT".equals(corner) || "BOTTOM_LEFT".equals(corner);
    }

    private static boolean isTop() {
        String corner = TravellersConfig.NOTIFICATIONS.corner;
        return "TOP_LEFT".equals(corner) || "TOP_RIGHT".equals(corner);
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) return;

        GuiScreen cur = Minecraft.getMinecraft().currentScreen;
        if (cur == null) {
            draw(new ScaledResolution(Minecraft.getMinecraft()));
            return;
        }
        if (cur instanceof ITravellersGui) {
            return; // that GUI paints notifications itself via its own drawScreen call
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ClientNotificationCache.tick();
    }
}
