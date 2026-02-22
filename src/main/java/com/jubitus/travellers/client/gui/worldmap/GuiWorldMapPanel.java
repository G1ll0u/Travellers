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

/**
 * Mini-carte top-down ultra simple, rendue en DynamicTexture.
 * - Nuances gris/beige
 * - Relief via hillshade
 * - Eau / lave
 * - Markers (villages, homes, etc.)
 *
 * Réutilisable dans d'autres GUI : tu peux l'instancier et pousser des markers.
 */

public class GuiWorldMapPanel {


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

    public static class Marker {
        public final BlockPos pos;
        public final int argb;     // couleur du marker (overlay)
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

        // overlay markers
        drawMarkers(mapX, mapY);

        // overlay sélection
        if (selectedPos != null) {
            int sx = worldToScreenX(selectedPos.getX(), mapX);
            int sy = worldToScreenY(selectedPos.getZ(), mapY);
            drawOutlineSquare(sx, sy, 4, 0xFFFFE39A); // beige clair
        }
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

// 4) ton -> couleur beige
                int r = clamp(tone + 10, 0, 255);
                int g = clamp(tone + 5, 0, 255);
                int b = clamp(tone, 0, 255);

                pixels[py * w + px] = (0xFF << 24) | (r << 16) | (g << 8) | b;


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

    private static int clamp(int v, int lo, int hi) {
        return (v < lo) ? lo : (v > hi) ? hi : v;
    }
    public void forceDirty() {
        this.dirty = true;
    }

}
