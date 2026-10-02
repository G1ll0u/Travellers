package com.jubitus.travellers.debug.client;

import com.jubitus.travellers.debug.net.MsgVillageDebugView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import java.util.Collections;
import java.util.List;

/** Draws the Millénaire buildings the server sent ({@link MsgVillageDebugView}): footprint boxes,
 * visit points and panels, with their names and tags written above. Visible through walls. */
@SideOnly(Side.CLIENT)
@Mod.EventBusSubscriber(modid = "travellers", value = Side.CLIENT)
public final class ClientVillageDebugOverlay {

    private static final float[] FOOTPRINT = {1.0F, 0.6F, 0.1F};
    private static final float[] TOWN_HALL = {1.0F, 0.2F, 0.2F};
    private static final float[][] POINT_COLORS = {
            {0.2F, 1.0F, 0.2F},  // entrance
            {0.2F, 0.9F, 1.0F},  // leisure
            {1.0F, 1.0F, 0.2F},  // selling
            {0.8F, 0.3F, 1.0F},  // bed
            {1.0F, 1.0F, 1.0F},  // panel
    };
    private static final String[] POINT_NAMES = {"entrance", "leisure", "selling", "bed", "panel"};

    private static volatile List<MsgVillageDebugView.Building> buildings = Collections.emptyList();
    private static volatile int labelDistance = 48;

    private ClientVillageDebugOverlay() {
    }

    public static void accept(MsgVillageDebugView msg) {
        buildings = msg.active ? msg.buildings : Collections.emptyList();
        labelDistance = msg.labelDistance;
    }

    @SubscribeEvent
    public static void onRenderWorld(RenderWorldLastEvent event) {
        List<MsgVillageDebugView.Building> list = buildings;
        if (list.isEmpty()) return;

        Minecraft mc = Minecraft.getMinecraft();
        Entity view = mc.getRenderViewEntity();
        if (view == null) return;
        float partial = event.getPartialTicks();
        double vx = view.lastTickPosX + (view.posX - view.lastTickPosX) * partial;
        double vy = view.lastTickPosY + (view.posY - view.lastTickPosY) * partial;
        double vz = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partial;

        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO);
        GL11.glLineWidth(2.0F);

        for (MsgVillageDebugView.Building b : list) {
            float[] c = b.townHall ? TOWN_HALL : FOOTPRINT;
            RenderGlobal.drawSelectionBoundingBox(new AxisAlignedBB(
                    b.minX, b.minY, b.minZ, b.maxX + 1, b.maxY + 1, b.maxZ + 1).offset(-vx, -vy, -vz),
                    c[0], c[1], c[2], 0.9F);
            for (int[] p : b.points) {
                float[] pc = POINT_COLORS[Math.max(0, Math.min(POINT_COLORS.length - 1, p[0]))];
                RenderGlobal.drawSelectionBoundingBox(new AxisAlignedBB(
                        p[1] + 0.25, p[2] + 0.25, p[3] + 0.25, p[1] + 0.75, p[2] + 0.75, p[3] + 0.75)
                        .offset(-vx, -vy, -vz), pc[0], pc[1], pc[2], 1.0F);
            }
        }

        GL11.glLineWidth(1.0F);
        GlStateManager.enableTexture2D();

        double max2 = (double) labelDistance * labelDistance;
        for (MsgVillageDebugView.Building b : list) {
            double cx = (b.minX + b.maxX + 1) * 0.5D;
            double cz = (b.minZ + b.maxZ + 1) * 0.5D;
            double dx = cx - vx, dz = cz - vz;
            if (dx * dx + dz * dz > max2) continue;
            String title = (b.townHall ? "TOWN HALL " + b.village + " - " : "") + b.planKey
                    + (b.name.isEmpty() ? "" : " (" + b.name + ")");
            double top = b.maxY + 1.8D;
            drawLabel(mc, cx - vx, top - vy + 0.3D, cz - vz, title, b.townHall ? 0xFFFF6060 : 0xFFFFB040);
            if (!b.tags.isEmpty()) {
                drawLabel(mc, cx - vx, top - vy, cz - vz, "tags: " + b.tags, 0xFFE0E0E0);
            }
            for (int[] p : b.points) {
                double px = p[1] + 0.5D - vx, pz = p[3] + 0.5D - vz;
                if (px * px + pz * pz > max2 * 0.25D) continue;
                int kind = Math.max(0, Math.min(POINT_NAMES.length - 1, p[0]));
                float[] pc = POINT_COLORS[kind];
                int color = 0xFF000000 | ((int) (pc[0] * 255) << 16) | ((int) (pc[1] * 255) << 8) | (int) (pc[2] * 255);
                drawLabel(mc, px, p[2] + 1.1D - vy, pz, POINT_NAMES[kind], color);
            }
        }

        GlStateManager.enableDepth();
        GlStateManager.disableBlend();
        GlStateManager.enableLighting();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.popMatrix();
    }

    private static void drawLabel(Minecraft mc, double x, double y, double z, String text, int color) {
        FontRenderer font = mc.fontRenderer;
        int half = font.getStringWidth(text) / 2;
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);
        GlStateManager.glNormal3f(0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(-mc.getRenderManager().playerViewY, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(mc.getRenderManager().playerViewX, 1.0F, 0.0F, 0.0F);
        GlStateManager.scale(-0.025F, -0.025F, 0.025F);
        GlStateManager.depthMask(false);
        Gui.drawRect(-half - 2, -2, half + 2, font.FONT_HEIGHT, 0x80000000);
        GlStateManager.enableTexture2D();
        font.drawString(text, -half, 0, color);
        GlStateManager.depthMask(true);
        GlStateManager.popMatrix();
    }
}
