package com.jubitus.travellers.client.gui.worldmap;

import com.jubitus.travellers.client.map.ClientMapDiscovery;
import com.jubitus.travellers.client.map.ClientMapTileCache;
import com.jubitus.travellers.server.map.WorldMapDiscovery;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

public class GuiWorldMapPanel {

    // ── Rendering tweaks ─────────────────────────────────────────────────────
    public static final class RenderSettings {
        public static int toneMin = 55;
        public static int toneMax = 200;
        public static float contrast = 1.25f;
        public static float smoothBlend = 0.35f;
        public static int shadowR = 24, shadowG = 18, shadowB = 10;
        public static int lightR = 248, lightG = 236, lightB = 200;
        public static int waterThreshold = 75;
        public static float waterTint = 0.12f;
        public static float waterLift = 0.17f;
    }
    // ─────────────────────────────────────────────────────────────────────────

    public static class Marker {
        public enum MarkerType { VILLAGE, HOME, LEADER, FOLLOWER, PLAYER }

        public final BlockPos pos;
        public final int argb;
        public final int size;
        public final MarkerType type;
        public final String label;

        public Marker(BlockPos pos, int argb, int size, MarkerType type, String label) {
            this.pos = pos;
            this.argb = argb;
            this.size = size;
            this.type = type;
            this.label = label;
        }

        /** Backward-compatible constructor (no type or label). */
        public Marker(BlockPos pos, int argb, int size) {
            this(pos, argb, size, MarkerType.VILLAGE, null);
        }
    }

    private final Minecraft mc;
    private final World world;

    private final int w, h;
    private final DynamicTexture dynTex;
    private final ResourceLocation texId;

    private BlockPos center = BlockPos.ORIGIN;
    private int blocksPerPixel = 4;

    private BlockPos selectedPos = null;

    private final List<Marker> markers = new ArrayList<>();

    private final int[] pixels;
    private boolean dirty = true;
    private long lastRebuildMs = 0L;

    public int getWidth()  { return w; }
    public int getHeight() { return h; }

    public GuiWorldMapPanel(Minecraft mc, World world, int width, int height, String textureKey) {
        this.mc = mc;
        this.world = world;
        this.w = width;
        this.h = height;
        this.pixels = new int[w * h];
        this.dynTex = new DynamicTexture(w, h);
        TextureManager tm = mc.getTextureManager();
        this.texId = tm.getDynamicTextureLocation(textureKey, dynTex);
    }

    // ── Navigation API ───────────────────────────────────────────────────────

    public void setCenter(BlockPos center) { this.center = center; this.dirty = true; }
    public BlockPos getCenter() { return center; }

    public void pan(int dxBlocks, int dzBlocks) {
        this.center = this.center.add(dxBlocks, 0, dzBlocks);
        this.dirty = true;
    }

    public void setZoomBlocksPerPixel(int bpp) {
        this.blocksPerPixel = Math.max(1, Math.min(96, bpp));
        this.dirty = true;
    }

    public int getZoomBlocksPerPixel() { return blocksPerPixel; }

    // ── Marker API ───────────────────────────────────────────────────────────

    public void clearMarkers() { markers.clear(); }
    public void addMarker(Marker m) { markers.add(m); }

    public void setSelectedPos(BlockPos pos) { this.selectedPos = pos; }
    public BlockPos getSelectedPos() { return selectedPos; }

    public void forceDirty() { this.dirty = true; }

    // ── Coordinate helpers ───────────────────────────────────────────────────

    /** Screen click → world BlockPos (Y = top-surface height). */
    public BlockPos screenToWorld(int mouseX, int mouseY, int mapX, int mapY) {
        int px = mouseX - mapX;
        int py = mouseY - mapY;
        int wx = center.getX() + (px - w / 2) * blocksPerPixel;
        int wz = center.getZ() + (py - h / 2) * blocksPerPixel;
        BlockPos top = world.getHeight(new BlockPos(wx, 0, wz));
        return new BlockPos(wx, top.getY(), wz);
    }

    private int worldToScreenX(int worldX, int mapX) {
        return mapX + (w / 2) + (worldX - center.getX()) / blocksPerPixel;
    }

    private int worldToScreenY(int worldZ, int mapY) {
        return mapY + (h / 2) + (worldZ - center.getZ()) / blocksPerPixel;
    }

    // ── Tooltip ──────────────────────────────────────────────────────────────

    /** Returns the label of the marker under the mouse, or null. */
    public String getTooltipAt(int mouseX, int mouseY, int mapX, int mapY) {
        for (int i = markers.size() - 1; i >= 0; i--) {
            Marker m = markers.get(i);
            if (m.label == null || m.label.isEmpty()) continue;
            int sx = worldToScreenX(m.pos.getX(), mapX);
            int sy = worldToScreenY(m.pos.getZ(), mapY);
            int dx = mouseX - sx, dy = mouseY - sy;
            if (dx * dx + dy * dy <= 25) return m.label; // 5 px radius
        }
        return null;
    }

    // ── Draw ─────────────────────────────────────────────────────────────────

    public void draw(int mapX, int mapY) {
        rebuildIfNeeded();

        mc.getTextureManager().bindTexture(texId);
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();

        drawTexturedRect(mapX, mapY, w, h);

        net.minecraft.client.gui.ScaledResolution sr = new net.minecraft.client.gui.ScaledResolution(mc);
        int sf = sr.getScaleFactor();
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
        org.lwjgl.opengl.GL11.glScissor(mapX * sf, mc.displayHeight - (mapY + h) * sf, w * sf, h * sf);

        drawMarkers(mapX, mapY);

        if (selectedPos != null) {
            int sx = worldToScreenX(selectedPos.getX(), mapX);
            int sy = worldToScreenY(selectedPos.getZ(), mapY);
            drawOutlineSquare(sx, sy, 4, 0xFFFFE39A);
        }

        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
    }

    // ── Marker rendering ─────────────────────────────────────────────────────

    private void drawMarkers(int mapX, int mapY) {
        for (Marker m : markers) {
            int sx = worldToScreenX(m.pos.getX(), mapX);
            int sy = worldToScreenY(m.pos.getZ(), mapY);
            switch (m.type) {
                case PLAYER:
                    drawFilledSquare(sx, sy, m.size, m.argb);
                    drawOutlineSquare(sx, sy, m.size + 1, 0xFF000000);
                    // cross
                    net.minecraft.client.gui.Gui.drawRect(sx - m.size + 1, sy, sx + m.size, sy + 1, 0xFF000000);
                    net.minecraft.client.gui.Gui.drawRect(sx, sy - m.size + 1, sx + 1, sy + m.size, 0xFF000000);
                    break;
                case HOME:
                    drawHomeTriangle(sx, sy, m.size, m.argb);
                    break;
                default:
                    drawFilledSquare(sx, sy, m.size, m.argb);
                    drawOutlineSquare(sx, sy, m.size + 1, 0xFF000000);
            }
        }
    }

    /** Downward-pointing triangle (▽) with black outline for home markers. */
    private static void drawHomeTriangle(int cx, int cy, int half, int argb) {
        // black outline (slightly oversized triangle)
        for (int i = 0; i <= half + 1; i++) {
            int w = half + 1 - i + 1;
            int y = cy - half - 1 + i;
            net.minecraft.client.gui.Gui.drawRect(cx - w, y, cx + w + 1, y + 1, 0xFF000000);
        }
        // colored triangle: wide at top, narrows to single pixel at bottom
        for (int i = 0; i <= half; i++) {
            int w = half - i;
            int y = cy - half + i;
            net.minecraft.client.gui.Gui.drawRect(cx - w, y, cx + w + 1, y + 1, argb);
        }
    }

    // ── Draw primitives ──────────────────────────────────────────────────────

    private static void drawTexturedRect(int x, int y, int w, int h) {
        net.minecraft.client.gui.Gui.drawModalRectWithCustomSizedTexture(x, y, 0, 0, w, h, w, h);
    }

    private static void drawFilledSquare(int cx, int cy, int half, int argb) {
        net.minecraft.client.gui.Gui.drawRect(cx - half, cy - half, cx + half + 1, cy + half + 1, argb);
    }

    private static void drawOutlineSquare(int cx, int cy, int half, int argb) {
        net.minecraft.client.gui.Gui.drawRect(cx - half, cy - half, cx + half + 1, cy - half + 1, argb);
        net.minecraft.client.gui.Gui.drawRect(cx - half, cy + half, cx + half + 1, cy + half + 1, argb);
        net.minecraft.client.gui.Gui.drawRect(cx - half, cy - half, cx - half + 1, cy + half + 1, argb);
        net.minecraft.client.gui.Gui.drawRect(cx + half, cy - half, cx + half + 1, cy + half + 1, argb);
    }

    // ── Terrain texture rebuild ───────────────────────────────────────────────

    private void rebuildIfNeeded() {
        long now = System.currentTimeMillis();
        if (!dirty) return;
        if ((now - lastRebuildMs) < 50) return;
        dirty = false;
        lastRebuildMs = now;

        java.util.HashMap<Long, byte[]> localTileCache = new java.util.HashMap<>();

        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                int wx = center.getX() + (px - w / 2) * blocksPerPixel;
                int wz = center.getZ() + (py - h / 2) * blocksPerPixel;

                if (!isTileDiscovered(wx, wz)) {
                    pixels[py * w + px] = 0xFF0B0B0B;
                    continue;
                }

                int cx = wx >> 4;
                int cz = wz >> 4;
                int tileX = floorDiv(cx, WorldMapDiscovery.CHUNKS_PER_TILE);
                int tileZ = floorDiv(cz, WorldMapDiscovery.CHUNKS_PER_TILE);
                long tkey = (((long) tileX) << 32) ^ (tileZ & 0xFFFFFFFFL);

                byte[] tile = localTileCache.get(tkey);
                if (tile == null) {
                    tile = ClientMapTileCache.get(tkey);
                    if (tile != null) localTileCache.put(tkey, tile);
                }

                if (tile == null || tile.length != 64 * 64) {
                    pixels[py * w + px] = 0xFF2A2A2A;
                    continue;
                }

                final int tileBlocks = WorldMapDiscovery.CHUNKS_PER_TILE * 16;
                final int step = tileBlocks / 64;

                int baseX = tileX * tileBlocks;
                int baseZ = tileZ * tileBlocks;
                int localX = wx - baseX;
                int localZ = wz - baseZ;
                if (localX < 0) localX = 0;
                if (localZ < 0) localZ = 0;
                if (localX > tileBlocks - 1) localX = tileBlocks - 1;
                if (localZ > tileBlocks - 1) localZ = tileBlocks - 1;

                int sx = localX / step;
                int sz = localZ / step;
                int tone = tile[sz * 64 + sx] & 0xFF;

                if (tone == 0) {
                    pixels[py * w + px] = 0xFF2A2A2A;
                    continue;
                }

                pixels[py * w + px] = (0xFF << 24) | toneToArgbPacked(tone);
            }
        }

        dynTex.getTextureData();
        System.arraycopy(pixels, 0, dynTex.getTextureData(), 0, pixels.length);
        dynTex.updateDynamicTexture();
    }

    private boolean isTileDiscovered(int worldX, int worldZ) {
        int cx = worldX >> 4;
        int cz = worldZ >> 4;
        int tileX = floorDiv(cx, WorldMapDiscovery.CHUNKS_PER_TILE);
        int tileZ = floorDiv(cz, WorldMapDiscovery.CHUNKS_PER_TILE);
        long key = (((long) tileX) << 32) ^ (tileZ & 0xFFFFFFFFL);
        return ClientMapDiscovery.getTilesView().contains(key);
    }

    private static int floorDiv(int a, int b) {
        int r = a / b;
        if ((a ^ b) < 0 && (r * b != a)) r--;
        return r;
    }

    private static int toneToArgbPacked(int tone) {
        float t = (tone - RenderSettings.toneMin) / (float)(RenderSettings.toneMax - RenderSettings.toneMin);
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;

        float s = t * t * (3f - 2f * t);
        t = RenderSettings.smoothBlend * s + (1f - RenderSettings.smoothBlend) * t;

        float c = t - 0.5f;
        t = 0.5f + c * RenderSettings.contrast;
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;

        int dr = RenderSettings.lightR - RenderSettings.shadowR;
        int dg = RenderSettings.lightG - RenderSettings.shadowG;
        int db = RenderSettings.lightB - RenderSettings.shadowB;

        int r = (int)(RenderSettings.shadowR + t * dr);
        int g = (int)(RenderSettings.shadowG + t * dg);
        int b = (int)(RenderSettings.shadowB + t * db);

        if (tone <= RenderSettings.waterThreshold) {
            if (t < RenderSettings.waterLift) t = RenderSettings.waterLift;
            r = (int)(RenderSettings.shadowR + t * dr);
            g = (int)(RenderSettings.shadowG + t * dg);
            b = (int)(RenderSettings.shadowB + t * db);

            float wf = (RenderSettings.waterThreshold - tone) / (float) Math.max(1, RenderSettings.waterThreshold - 40);
            if (wf > 1f) wf = 1f;
            float ws = RenderSettings.waterTint * wf;
            r = (int)(r * (1f - ws * 1.5f));
            g = (int)(g * (1f - ws * 0.7f));
            b = (int)(b + (255 - b) * ws);
        }

        r = r < 0 ? 0 : (r > 255 ? 255 : r);
        g = g < 0 ? 0 : (g > 255 ? 255 : g);
        b = b < 0 ? 0 : (b > 255 ? 255 : b);

        return (r << 16) | (g << 8) | b;
    }
}
