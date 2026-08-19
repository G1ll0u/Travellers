package com.jubitus.travellers.client.debug;

import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;

/**
 * Draws the live trace panel and marks the traced travellers in the world.
 *
 * <p>The panel exists so what is seen and what is recorded can be compared while it is happening -
 * "it stopped right there" and the line saying the navigator has no path are much more useful
 * together than either is alone, and the person watching is the only one who can connect them.
 */
@SideOnly(Side.CLIENT)
@Mod.EventBusSubscriber(modid = "travellers", value = Side.CLIENT)
public final class TraceHudRenderer {

    private TraceHudRenderer() {
    }

    private static final int PANEL_X = 4;
    private static final int PANEL_Y = 4;
    private static final int PADDING = 3;
    private static final int BACKGROUND = 0xB0000000;
    private static final int BORDER = 0xFF4FA3D1;
    private static final int TEXT = 0xFFE0E0E0;
    private static final int HEADING = 0xFF7FD4FF;
    /** A marker any further than this is noise rather than help. */
    private static final double MARKER_MAX_DISTANCE = 64.0D;

    @SubscribeEvent
    public static void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
        if (!TravellersConfig.TRACE.showHud) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.gameSettings.showDebugInfo) return; // F3 already owns that corner

        List<String> lines = ClientTraceHud.getLines();
        if (lines.isEmpty()) return;

        FontRenderer font = mc.fontRenderer;
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, font.getStringWidth(line));
        }
        int height = lines.size() * (font.FONT_HEIGHT + 1);

        Gui.drawRect(PANEL_X, PANEL_Y,
                PANEL_X + width + PADDING * 2, PANEL_Y + height + PADDING * 2, BACKGROUND);
        Gui.drawRect(PANEL_X, PANEL_Y, PANEL_X + width + PADDING * 2, PANEL_Y + 1, BORDER);

        int y = PANEL_Y + PADDING;
        for (String line : lines) {
            // A line that doesn't start indented is an entity heading, so a panel holding several
            // traced travellers stays readable at a glance.
            font.drawStringWithShadow(line, PANEL_X + PADDING, y, line.startsWith(" ") ? TEXT : HEADING);
            y += font.FONT_HEIGHT + 1;
        }

        // Gui.drawRect leaves the GL colour set to whatever it last painted and never restores it -
        // anything drawn afterwards renders tinted until it is reset.
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    @SubscribeEvent
    public static void onRenderWorld(RenderWorldLastEvent event) {
        if (!TravellersConfig.TRACE.showHud) return;

        List<Entity> traced = ClientTraceHud.getTracedEntities();
        if (traced.isEmpty()) return;

        Minecraft mc = Minecraft.getMinecraft();
        Entity view = mc.getRenderViewEntity();
        if (view == null) return;

        float partial = event.getPartialTicks();
        double vx = view.lastTickPosX + (view.posX - view.lastTickPosX) * partial;
        double vy = view.lastTickPosY + (view.posY - view.lastTickPosY) * partial;
        double vz = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partial;

        for (Entity e : traced) {
            if (e.getDistanceSq(view) > MARKER_MAX_DISTANCE * MARKER_MAX_DISTANCE) continue;
            double ex = e.lastTickPosX + (e.posX - e.lastTickPosX) * partial - vx;
            double ey = e.lastTickPosY + (e.posY - e.lastTickPosY) * partial - vy + e.height + 0.6D;
            double ez = e.lastTickPosZ + (e.posZ - e.lastTickPosZ) * partial - vz;
            drawMarker(mc, ex, ey, ez);
        }
    }

    /** A small billboarded label above the entity, using the same camera-facing transform vanilla
     * uses for name tags. */
    private static void drawMarker(Minecraft mc, double x, double y, double z) {
        FontRenderer font = mc.fontRenderer;
        String label = "◆ TRACE";
        int half = font.getStringWidth(label) / 2;

        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);
        GlStateManager.glNormal3f(0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(-mc.getRenderManager().playerViewY, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(mc.getRenderManager().playerViewX, 1.0F, 0.0F, 0.0F);
        GlStateManager.scale(-0.025F, -0.025F, 0.025F);
        GlStateManager.disableLighting();
        GlStateManager.depthMask(false);
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();

        Gui.drawRect(-half - 2, -2, half + 2, font.FONT_HEIGHT, 0x80000000);
        font.drawString(label, -half, 0, 0xFF7FD4FF);

        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.enableLighting();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.popMatrix();
    }
}
