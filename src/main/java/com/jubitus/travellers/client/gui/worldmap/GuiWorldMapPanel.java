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
        // Tone range: server encodes terrain in roughly [55, 220].
        // Values outside this range are clamped to shadow/highlight color.
        public static int toneMin = 55;
        public static int toneMax = 200;

        // Contrast multiplier applied around the midpoint (1.0 = neutral, >1 = more contrast).
        public static float contrast = 1.25f;

        // How much of the S-curve (smoothstep) blends in vs pure linear (0 = off, 1 = full).
        public static float smoothBlend = 0.35f;

        // Shadow color (dark areas / deep water).
        public static int shadowR = 24, shadowG = 18, shadowB = 10;

        // Highlight color (high peaks / bright areas).
        public static int lightR = 248, lightG = 236, lightB = 200;

        // Tones at or below this threshold receive a subtle blue-grey tint (likely water).
        public static int waterThreshold = 75;

        // How strongly the blue-grey tint is applied at the darkest water tones (0–1).
        public static float waterTint = 0.12f;

        // Minimum brightness for water pixels before tinting (0=pure shadow, 1=full highlight).
        // Increase to make water lighter/more visible.
        public static float waterLift = 0.17f;
    }
    // ─────────────────────────────────────────────────────────────────────────

    private BlockPos playerPos = null;
    private int playerArgb = 0xFF55FF55;
    private int playerSize = 2;

    public int getWidth() { return w; }
    public int getHeight() { return h; }

    // Découverte côté client (remplie par les messages réseau)


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

    public void setPlayerMarker(BlockPos pos, int argb, int size) {
        this.playerPos = pos;
        this.playerArgb = argb;
        this.playerSize = size;
    }

    public void clearPlayerMarker() {
        this.playerPos = null;
    }

    public static class Marker {
        public final BlockPos pos;
        public final int argb;     // couleur du mark (overlay)
        public final int size;     // demi-taille en pixels (ex: 2)
        public Marker(BlockPos pos, int argb, int size) {
            this.pos = pos;
            this.argb = argb;
            this.size = size;
        }
    }

    private final Minecraft mc;
    private final World world;

    private final int w, h;
    private final DynamicTexture dynTex;
    private final ResourceLocation texId;

    // paramètres de navigation
    private BlockPos center = BlockPos.ORIGIN;
    private int blocksPerPixel = 4; // zoom: 1 = très zoomé, 6 = large

    // sélection
    private BlockPos selectedPos = null;

    // markers
    private final List<Marker> markers = new ArrayList<>();

    // cache pixels
    private final int[] pixels;
    private boolean dirty = true;
    private long lastRebuildMs = 0L;

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

    // ----------------- API Réutilisable -----------------

    public void setCenter(BlockPos center) {
        this.center = center;
        this.dirty = true;
    }

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

    public void clearMarkers() {
        this.markers.clear();
    }

    public void addMarker(Marker m) {
        this.markers.add(m);
    }

    public void setSelectedPos(BlockPos pos) {
        this.selectedPos = pos;
        // no need to rebuild texture, selection is overlay
    }


    public BlockPos getSelectedPos() {
        return selectedPos;
    }

    /**
     * Convertit un clic écran -> BlockPos monde (XZ, Y fixé au top height)
     */
    public BlockPos screenToWorld(int mouseX, int mouseY, int mapX, int mapY) {
        int px = mouseX - mapX;
        int py = mouseY - mapY;
        int wx = center.getX() + (px - w / 2) * blocksPerPixel;
        int wz = center.getZ() + (py - h / 2) * blocksPerPixel;

        BlockPos top = world.getHeight(new BlockPos(wx, 0, wz));
        return new BlockPos(wx, top.getY(), wz);
    }

    /**
     * Dessine la carte à l'écran.
     */
    public void draw(int mapX, int mapY) {
        rebuildIfNeeded();

        // texture
        mc.getTextureManager().bindTexture(texId);
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();

        // draw textured quad
        drawTexturedRect(mapX, mapY, w, h);

        // Clipper les marqueurs au cadre de la carte via GL scissor
        net.minecraft.client.gui.ScaledResolution sr = new net.minecraft.client.gui.ScaledResolution(mc);
        int sf = sr.getScaleFactor();
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
        org.lwjgl.opengl.GL11.glScissor(mapX * sf, mc.displayHeight - (mapY + h) * sf, w * sf, h * sf);

        // overlay markers
        drawMarkers(mapX, mapY);

        if (selectedPos != null) {
            int sx = worldToScreenX(selectedPos.getX(), mapX);
            int sy = worldToScreenY(selectedPos.getZ(), mapY);
            drawOutlineSquare(sx, sy, 4, 0xFFFFE39A);
        }

        drawPlayerMarker(mapX, mapY);

        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
    }
    private void drawPlayerMarker(int mapX, int mapY) {
        if (playerPos == null) return;

        int sx = worldToScreenX(playerPos.getX(), mapX);
        int sy = worldToScreenY(playerPos.getZ(), mapY);

        drawFilledSquare(sx, sy, playerSize, playerArgb);
        drawOutlineSquare(sx, sy, playerSize + 1, 0xFF000000);
    }
    // ----------------- Internals -----------------

    private void rebuildIfNeeded() {
        long now = System.currentTimeMillis();

// If nothing changed, do nothing (big FPS win)
        if (!dirty) return;

// Safety throttle: don't rebuild more often than ~20 FPS even if spammed dirty
        if ((now - lastRebuildMs) < 50) return;

        dirty = false;
        java.util.HashMap<Long, byte[]> localTileCache = new java.util.HashMap<>();

        lastRebuildMs = now;


        // Build heights first for hillshade
        // (on calcule hauteur aux pixels et voisins)
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {

                int wx = center.getX() + (px - w / 2) * blocksPerPixel;
                int wz = center.getZ() + (py - h / 2) * blocksPerPixel;

// 1) Fog-of-war
                if (!isTileDiscovered(wx, wz)) {
                    pixels[py * w + px] = 0xFF0B0B0B; // noir = pas découvert
                    continue;
                }

// 2) Cherche la tuile et son image
                int cx = wx >> 4;
                int cz = wz >> 4;
                int tileX = floorDiv(cx, WorldMapDiscovery.CHUNKS_PER_TILE);
                int tileZ = floorDiv(cz, WorldMapDiscovery.CHUNKS_PER_TILE);

                long tkey = (((long) tileX) << 32) ^ (tileZ & 0xFFFFFFFFL);

// Fetch tile bytes (use tiny local cache to reduce HashMap lookups)
                byte[] tile = localTileCache.get(tkey);
                if (tile == null) {
                    tile = ClientMapTileCache.get(tkey);
                    if (tile != null) localTileCache.put(tkey, tile);
                }

// If discovered but image not received yet (or corrupted), draw "missing" gray and continue
                if (tile == null || tile.length != 64 * 64) {
                    pixels[py * w + px] = 0xFF2A2A2A;
                    continue;
                }

// 3) Convertit (wx,wz) -> pixel dans la tuile 64x64
                final int tileBlocks = WorldMapDiscovery.CHUNKS_PER_TILE * 16; // 256 blocks
                final int step = tileBlocks / 64; // 4 blocks par pixel

                int baseX = tileX * tileBlocks;
                int baseZ = tileZ * tileBlocks;

                int localX = wx - baseX;
                int localZ = wz - baseZ;

// clamp sécurité
                if (localX < 0) localX = 0;
                if (localZ < 0) localZ = 0;
                if (localX > tileBlocks - 1) localX = tileBlocks - 1;
                if (localZ > tileBlocks - 1) localZ = tileBlocks - 1;

                int sx = localX / step; // 0..63
                int sz = localZ / step; // 0..63

                int tone = tile[sz * 64 + sx] & 0xFF;

// 0 = "unknown/unloaded" sentinel from server generator => draw as missing/gray
                if (tone == 0) {
                    pixels[py * w + px] = 0xFF2A2A2A;
                    continue;
                }

// 4) ton -> couleur
                pixels[py * w + px] = (0xFF << 24) | toneToArgbPacked(tone);


            }
        }

        dynTex.getTextureData(); // assure alloué
        System.arraycopy(pixels, 0, dynTex.getTextureData(), 0, pixels.length);
        dynTex.updateDynamicTexture();
    }

    private void drawMarkers(int mapX, int mapY) {
        for (Marker m : markers) {
            int sx = worldToScreenX(m.pos.getX(), mapX);
            int sy = worldToScreenY(m.pos.getZ(), mapY);

            // petit carré
            int s = m.size;
            drawFilledSquare(sx, sy, s, m.argb);
            drawOutlineSquare(sx, sy, s + 1, 0xFF000000);
        }
    }

    private int worldToScreenX(int worldX, int mapX) {
        int dx = (worldX - center.getX()) / blocksPerPixel;
        return mapX + (w / 2) + dx;
    }

    private int worldToScreenY(int worldZ, int mapY) {
        int dz = (worldZ - center.getZ()) / blocksPerPixel;
        return mapY + (h / 2) + dz;
    }

    private static void drawTexturedRect(int x, int y, int w, int h) {
        // Gui.drawModalRectWithCustomSizedTexture équivalent “manuel”
        net.minecraft.client.gui.Gui.drawModalRectWithCustomSizedTexture(
                x, y, 0, 0, w, h, w, h
        );
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

    private static int toneToArgbPacked(int tone) {
        float t = (tone - RenderSettings.toneMin) / (float)(RenderSettings.toneMax - RenderSettings.toneMin);
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;

        // S-curve blend
        float s = t * t * (3f - 2f * t);
        t = RenderSettings.smoothBlend * s + (1f - RenderSettings.smoothBlend) * t;

        // Linear contrast around midpoint
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

        // Subtle cool tint for probable water (low tones)
        if (tone <= RenderSettings.waterThreshold) {
            // Lift water brightness before tinting so it never collapses to near-black.
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

    private static int clamp(int v, int lo, int hi) {
        return (v < lo) ? lo : (v > hi) ? hi : v;
    }
    public void forceDirty() {
        this.dirty = true;
    }

}
