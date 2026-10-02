package com.jubitus.travellers.client.map;

import com.jubitus.travellers.server.net.message.map.MsgItineraryDebugMap;
import com.jubitus.travellers.pathing.itinerary.Itinerary;
import com.jubitus.travellers.pathing.route.TravelPlan;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Debug: the selected unit's itinerary drawn on {@code GuiTravellerPopulationMap} - roads orange,
 * walked stretches cyan, the cursor white, the unit yellow, trip stops magenta, the leg's target red.
 */
@SideOnly(Side.CLIENT)
public final class ClientItineraryDebugOverlay {

    private static final int ROAD_COLOR = 0xFFFFA030;
    private static final int WALK_COLOR = 0xFF40D0FF;
    private static final int BOAT_COLOR = 0xFFFF40FF;
    private static final int GATE_COLOR = 0xFF40FF40;

    @Nullable
    private static MsgItineraryDebugMap data;
    @Nullable
    private static UUID selected;

    private ClientItineraryDebugOverlay() {
    }

    public static void accept(MsgItineraryDebugMap msg) {
        if (!msg.found) {
            data = null;
            selected = null;
            return;
        }
        data = msg;
        selected = msg.id;
    }

    @Nullable
    public static UUID selected() {
        return selected;
    }

    public static boolean isActive() {
        return data != null;
    }

    public static void clear() {
        data = null;
        selected = null;
    }

    /** Lines and points inside the map viewport; scissor is assumed already set. */
    public static void draw(int mapX, int mapY, int mapW, int mapH, float centerX, float centerZ, float bpp) {
        MsgItineraryDebugMap d = data;
        if (d == null) return;
        float ox = mapX + mapW / 2f;
        float oy = mapY + mapH / 2f;

        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.glLineWidth(2.0F);
        Tessellator t = Tessellator.getInstance();
        BufferBuilder b = t.getBuffer();
        if (d.xs.length >= 2) {
            b.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
            for (int i = 0; i < d.xs.length - 1; i++) {
                int c = d.kinds[i] == Itinerary.ROAD ? ROAD_COLOR : d.kinds[i] == Itinerary.BOAT ? BOAT_COLOR
                        : d.kinds[i] == Itinerary.GATE ? GATE_COLOR : WALK_COLOR;
                vertex(b, ox + (d.xs[i] + 0.5f - centerX) / bpp, oy + (d.zs[i] + 0.5f - centerZ) / bpp, c);
                vertex(b, ox + (d.xs[i + 1] + 0.5f - centerX) / bpp, oy + (d.zs[i + 1] + 0.5f - centerZ) / bpp, c);
            }
            t.draw();
        }
        GlStateManager.glLineWidth(1.0F);
        GlStateManager.enableTexture2D();

        for (int i = 0; i < d.tripStops.size(); i++) {
            BlockPos p = d.tripStops.get(i);
            dot(ox + (p.getX() + 0.5f - centerX) / bpp, oy + (p.getZ() + 0.5f - centerZ) / bpp,
                    i == d.tripIndex ? 3 : 2, 0xFFFF40FF);
        }
        if (d.target != null) {
            dot(ox + (d.target.getX() + 0.5f - centerX) / bpp, oy + (d.target.getZ() + 0.5f - centerZ) / bpp, 2, 0xFFFF3030);
        }
        if (d.xs.length >= 2) {
            dot(ox + ((float) d.cursorX - centerX) / bpp, oy + ((float) d.cursorZ - centerZ) / bpp, 2, 0xFFFFFFFF);
        }
        dot(ox + ((float) d.unitX - centerX) / bpp, oy + ((float) d.unitZ - centerZ) / bpp, 2, 0xFFFFFF40);
    }

    /** One-line summary drawn at the bottom of the map. */
    public static void drawInfo(int mapX, int mapY, int mapW, int mapH) {
        MsgItineraryDebugMap d = data;
        if (d == null) return;
        String line;
        if (d.xs.length < 2) {
            line = d.name + (d.virtual ? " [virtual]" : " [loaded]") + " - no itinerary";
        } else {
            line = String.format(java.util.Locale.ROOT, "%s %s  seed=%s best=%s  %.0f/%.0f blocks  off %.1f%s%s",
                    d.name, d.virtual ? "[virtual]" : "[loaded]", d.source, TravelPlan.qualityName(d.quality),
                    d.along, d.length, d.offRoute, d.bridgesLeft ? "  planning..." : "",
                    d.forTarget ? "" : "  (not for current target!)");
        }
        FontRenderer fr = Minecraft.getMinecraft().fontRenderer;
        String shown = fr.trimStringToWidth(line, mapW - 8);
        int y = mapY + mapH - 12;
        Gui.drawRect(mapX + 2, y - 2, mapX + 6 + fr.getStringWidth(shown), y + 10, 0xB0000000);
        fr.drawString(shown, mapX + 4, y, 0xFFFFFFFF);
    }

    private static void vertex(BufferBuilder b, float x, float y, int argb) {
        b.pos(x, y, 0).color((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, (argb >>> 24) & 255).endVertex();
    }

    private static void dot(float x, float y, int r, int argb) {
        int ix = Math.round(x), iy = Math.round(y);
        Gui.drawRect(ix - r - 1, iy - r - 1, ix + r + 1, iy + r + 1, 0xFF000000);
        Gui.drawRect(ix - r, iy - r, ix + r, iy + r, argb);
    }
}
