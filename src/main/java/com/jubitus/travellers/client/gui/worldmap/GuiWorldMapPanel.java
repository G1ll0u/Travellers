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

    /**
     * Global marker size multiplier — increase to make all markers larger.
     * Effective scale = max(MARKER_SCALE, round(1 / blocksPerPixel)) so that
     * markers never appear smaller than 1 detail pixel (1 block) on screen.
     */
    public static int MARKER_SCALE = 1;

    // ── Marker textures ───────────────────────────────────────────────────────
    /** Source PNG resolution — display size is set independently via getMarkerBaseSize(). */
    private static final int MARKER_PNG_SIZE = 16;

    private static final ResourceLocation TEX_PLAYER =
        new ResourceLocation("travellers", "textures/map/marker_player.png");
    private static final ResourceLocation TEX_VILLAGE =
        new ResourceLocation("travellers", "textures/map/marker_village.png");
    private static final ResourceLocation TEX_CTRL_VILLAGE =
        new ResourceLocation("travellers", "textures/map/marker_controlled_village.png");
    private static final ResourceLocation TEX_HOME =
        new ResourceLocation("travellers", "textures/map/marker_home.png");
    private static final ResourceLocation TEX_LEADER =
        new ResourceLocation("travellers", "textures/map/marker_leader.png");
    private static final ResourceLocation TEX_FOLLOWER =
        new ResourceLocation("travellers", "textures/map/marker_follower.png");
    private static final ResourceLocation TEX_DESTINATION_CROSS =
        new ResourceLocation("travellers", "textures/map/marker_destination_cross.png");
    private static final ResourceLocation TEX_CAMP_FIREPIT =
        new ResourceLocation("travellers", "textures/map/marker_camp_firepit.png");
    private static final ResourceLocation TEX_CAMP_SLOT =
        new ResourceLocation("travellers", "textures/map/marker_camp_slot.png");

    /**
     * Extra pixels added on each side of the terrain texture beyond the viewport.
     * Pan within ±MARGIN pixels uses GL translate only (no rebuild).
     * Rebuild fires only when the offset exceeds this boundary.
     */
    private static final int MARGIN = 16;

    public static class Marker {
        public enum MarkerType { VILLAGE, CONTROLLED_VILLAGE, HOME, LEADER, FOLLOWER, FOLLOWER_HIGHLIGHTED, PLAYER, DESTINATION_CROSS, POP_TRAVELLER, POP_RAIDER, POP_WORKER, CAMP_FIREPIT, CAMP_SLOT }

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
    }

    private final Minecraft mc;
    private final World world;

    private final int w, h;
    // Oversized texture: viewport + MARGIN on each side, for smooth GL-translate panning.
    private final int tw, th;
    private final DynamicTexture dynTex;
    private final ResourceLocation texId;

    // Float center for sub-block precision — eliminates discrete jumps when zoomed in.
    private float centerX = 0, centerZ = 0;
    private float blocksPerPixel = 1f;

    // World coordinates that the center of the oversized texture was built around.
    private float textureAnchorX = 0, textureAnchorZ = 0;

    private BlockPos selectedPos = null;

    // RGB (no alpha) used to colorize player-dependent legend entries
    private int legendOwnerColor = 0xFFFFFF;

    private final List<Marker> markers = new ArrayList<>();

    private final int[] pixels;
    // dirty: forces a rebuild regardless of offset (zoom change, tile data arrived, etc.)
    private boolean dirty = true;

    public int getWidth()  { return w; }
    public int getHeight() { return h; }

    public GuiWorldMapPanel(Minecraft mc, World world, int width, int height, String textureKey) {
        this.mc = mc;
        this.world = world;
        this.w = width;
        this.h = height;
        this.tw = width  + 2 * MARGIN;
        this.th = height + 2 * MARGIN;
        this.pixels = new int[tw * th];
        this.dynTex = new DynamicTexture(tw, th);
        TextureManager tm = mc.getTextureManager();
        this.texId = tm.getDynamicTextureLocation(textureKey, dynTex);
    }

    // ── Navigation API ───────────────────────────────────────────────────────

    public void setCenter(BlockPos pos) { this.centerX = pos.getX(); this.centerZ = pos.getZ(); }
    public void setCenterF(float x, float z) { this.centerX = x; this.centerZ = z; }
    public BlockPos getCenter() { return new BlockPos(Math.round(centerX), 0, Math.round(centerZ)); }
    public float getCenterXf() { return centerX; }
    public float getCenterZf() { return centerZ; }

    public void pan(int dxBlocks, int dzBlocks) {
        this.centerX += dxBlocks;
        this.centerZ += dzBlocks;
    }

    public void setZoomBlocksPerPixel(float bpp) {
        this.blocksPerPixel = Math.max(0.0625f, bpp);
        this.dirty = true;
    }

    public float getZoomBlocksPerPixel() { return blocksPerPixel; }

    // ── Marker API ───────────────────────────────────────────────────────────

    public void clearMarkers() { markers.clear(); }
    public void addMarker(Marker m) { markers.add(m); }

    public void setSelectedPos(BlockPos pos) { this.selectedPos = pos; }

    public void forceDirty() { this.dirty = true; }

    /** Sets the owner color (0xRRGGBB) used to colorize player-dependent legend entries. */
    public void setLegendOwnerColor(int ownerRgb) { this.legendOwnerColor = ownerRgb & 0xFFFFFF; }

    // ── Coordinate helpers ───────────────────────────────────────────────────

    /** Screen click → world BlockPos (Y = top-surface height). */
    public BlockPos screenToWorld(int mouseX, int mouseY, int mapX, int mapY) {
        int wx = Math.round(centerX + (mouseX - mapX - w / 2f) * blocksPerPixel);
        int wz = Math.round(centerZ + (mouseY - mapY - h / 2f) * blocksPerPixel);
        BlockPos top = world.getHeight(new BlockPos(wx, 0, wz));
        return new BlockPos(wx, top.getY(), wz);
    }

    private int worldToScreenX(int worldX, int mapX) {
        return mapX + (w / 2) + Math.round((worldX - centerX) / blocksPerPixel);
    }

    private int worldToScreenY(int worldZ, int mapY) {
        return mapY + (h / 2) + Math.round((worldZ - centerZ) / blocksPerPixel);
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
            if (dx * dx + dy * dy <= 25) return m.label;
        }
        return null;
    }

    // ── Draw ─────────────────────────────────────────────────────────────────

    public void draw(int mapX, int mapY) {
        // Sub-pixel offset of the current view center relative to what the texture was built for.
        float pixelOffsetX = (textureAnchorX - centerX) / blocksPerPixel;
        float pixelOffsetZ = (textureAnchorZ - centerZ) / blocksPerPixel;

        // Rebuild when forced (zoom, tile data) or when pan has drifted beyond the margin buffer.
        if (dirty || Math.abs(pixelOffsetX) > MARGIN - 1 || Math.abs(pixelOffsetZ) > MARGIN - 1) {
            rebuildTexture();
            textureAnchorX = centerX;
            textureAnchorZ = centerZ;
            dirty = false;
            pixelOffsetX = 0;
            pixelOffsetZ = 0;
        }

        mc.getTextureManager().bindTexture(texId);
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();

        // Scissor to the visible viewport before drawing (clips the oversized texture margins).
        net.minecraft.client.gui.ScaledResolution sr = new net.minecraft.client.gui.ScaledResolution(mc);
        int sf = sr.getScaleFactor();
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
        org.lwjgl.opengl.GL11.glScissor(mapX * sf, mc.displayHeight - (mapY + h) * sf, w * sf, h * sf);

        // Draw oversized terrain texture with sub-pixel GL translation.
        GlStateManager.pushMatrix();
        GlStateManager.translate(pixelOffsetX, pixelOffsetZ, 0f);
        drawTexturedRect(mapX - MARGIN, mapY - MARGIN, tw, th);
        GlStateManager.popMatrix();

        // Markers and overlay use viewport screen coords — unaffected by GL translate.
        drawMarkers(mapX, mapY);

        if (selectedPos != null) {
            int sx = worldToScreenX(selectedPos.getX(), mapX);
            int sy = worldToScreenY(selectedPos.getZ(), mapY);
            drawOutlineSquare(sx, sy, 4, 0xFFFFE39A);
        }

        drawLegend(mapX, mapY);

        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
    }

    // ── Marker rendering ─────────────────────────────────────────────────────

    /**
     * Draw order: first = background, last = foreground.
     *   FOLLOWER → FOLLOWER_HIGHLIGHTED → LEADER → HOME → VILLAGE / CONTROLLED_VILLAGE → PLAYER
     */
    private static final Marker.MarkerType[] DRAW_ORDER = {
        Marker.MarkerType.FOLLOWER,
        Marker.MarkerType.FOLLOWER_HIGHLIGHTED,
        Marker.MarkerType.LEADER,
        Marker.MarkerType.HOME,
        Marker.MarkerType.VILLAGE,
        Marker.MarkerType.CONTROLLED_VILLAGE,
        Marker.MarkerType.CAMP_SLOT,
        Marker.MarkerType.CAMP_FIREPIT,
        Marker.MarkerType.POP_TRAVELLER,
        Marker.MarkerType.POP_RAIDER,
        Marker.MarkerType.POP_WORKER,
        Marker.MarkerType.PLAYER,
        Marker.MarkerType.DESTINATION_CROSS,
    };

    private void drawMarkers(int mapX, int mapY) {
        int scale = Math.max(MARKER_SCALE, Math.round(1f / blocksPerPixel));
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        for (Marker.MarkerType pass : DRAW_ORDER) {
            for (Marker m : markers) {
                if (m.type != pass) continue;
                int sx = worldToScreenX(m.pos.getX(), mapX);
                int sy = worldToScreenY(m.pos.getZ(), mapY);
                drawMarkerPng(getMarkerTexture(m.type), sx, sy, m.argb, getMarkerBaseSize(m.type) * scale);
            }
        }
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    private static ResourceLocation getMarkerTexture(Marker.MarkerType type) {
        switch (type) {
            case PLAYER:             return TEX_PLAYER;
            case VILLAGE:            return TEX_VILLAGE;
            case CONTROLLED_VILLAGE: return TEX_CTRL_VILLAGE;
            case HOME:               return TEX_HOME;
            case LEADER:             return TEX_LEADER;
            case FOLLOWER:
            case FOLLOWER_HIGHLIGHTED: return TEX_FOLLOWER;
            case DESTINATION_CROSS:  return TEX_DESTINATION_CROSS;
            case POP_TRAVELLER:
            case POP_RAIDER:
            case POP_WORKER:         return TEX_FOLLOWER;
            case CAMP_FIREPIT:       return TEX_CAMP_FIREPIT;
            case CAMP_SLOT:          return TEX_CAMP_SLOT;
            default:                 return TEX_VILLAGE;
        }
    }

    /** Base display size in screen pixels — independent of PNG resolution. */
    private static int getMarkerBaseSize(Marker.MarkerType type) {
        switch (type) {
            case PLAYER:             return 4;
            case VILLAGE:
            case CONTROLLED_VILLAGE: return 6;
            case HOME:
            case LEADER:             return 4;
            case FOLLOWER:           return 4;
            case FOLLOWER_HIGHLIGHTED: return 7;
            case DESTINATION_CROSS:  return 8;
            case POP_TRAVELLER:
            case POP_RAIDER:
            case POP_WORKER:         return 4;
            case CAMP_FIREPIT:       return 5;
            case CAMP_SLOT:          return 4;
            default:                 return 4;
        }
    }

    /** Draws the marker PNG tinted with argb, with a soft shadow offset by 1px. */
    private void drawMarkerPng(ResourceLocation tex, int cx, int cy, int argb, int size) {
        int half = size / 2;
        GlStateManager.enableTexture2D();
        mc.getTextureManager().bindTexture(tex);
        // Pass size as texture dimensions so UV always spans [0,1] regardless of PNG resolution.
        GlStateManager.color(0f, 0f, 0f, 0.45f);
        net.minecraft.client.gui.Gui.drawModalRectWithCustomSizedTexture(
            cx - half + 1, cy - half + 1, 0, 0, size, size, size, size);
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >>  8) & 0xFF) / 255f;
        float b = ( argb        & 0xFF) / 255f;
        float a = ((argb >> 24) & 0xFF) / 255f;
        GlStateManager.color(r, g, b, a);
        net.minecraft.client.gui.Gui.drawModalRectWithCustomSizedTexture(
            cx - half, cy - half, 0, 0, size, size, size, size);
    }

    // ── Legend ────────────────────────────────────────────────────────────────

    private static final Marker.MarkerType[] LEGEND_TYPES = {
        Marker.MarkerType.PLAYER,
        Marker.MarkerType.VILLAGE,
        Marker.MarkerType.CONTROLLED_VILLAGE,
        Marker.MarkerType.HOME,
        Marker.MarkerType.LEADER,
        Marker.MarkerType.FOLLOWER,
        Marker.MarkerType.CAMP_FIREPIT,
        Marker.MarkerType.CAMP_SLOT,
    };

    private static final String[] LEGEND_KEYS = {
        "travellers.map.legend.player",
        "travellers.map.legend.village",
        "travellers.map.legend.controlled_village",
        "travellers.map.legend.home",
        "travellers.map.legend.leader",
        "travellers.map.legend.follower",
        "travellers.map.legend.camp_firepit",
        "travellers.map.legend.camp_slot",
    };

    private void drawLegend(int mapX, int mapY) {
        net.minecraft.client.gui.FontRenderer fr = mc.fontRenderer;

        int[] colors = buildLegendColors();

        // Only show legend entries for marker types actually present on the map.
        // Each GUI decides which markers it adds, so the legend follows automatically.
        boolean[] present = presentMarkerTypes();

        int entryH    = 9;
        int shapeArea = 12; // pixels reserved for the shape
        int shapeGap  = 3;
        int padX = 4, padY = 3;

        // Measure text width and count visible entries to size the background.
        int maxTextW = 0;
        int visibleCount = 0;
        String[] labels = new String[LEGEND_KEYS.length];
        for (int i = 0; i < LEGEND_KEYS.length; i++) {
            if (!present[i]) continue;
            labels[i] = net.minecraft.client.resources.I18n.format(LEGEND_KEYS[i]);
            int tw = fr.getStringWidth(labels[i]);
            if (tw > maxTextW) maxTextW = tw;
            visibleCount++;
        }

        if (visibleCount == 0) return;

        int legendW = padX + shapeArea + shapeGap + maxTextW + padX;
        int legendH = padY + visibleCount * entryH + padY;

        int lx = mapX + 3;
        int ly = mapY + h - legendH - 3;

        // Semi-transparent dark background — very subtle
        net.minecraft.client.gui.Gui.drawRect(lx, ly, lx + legendW, ly + legendH, 0x99090909);

        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        int row = 0;
        for (int i = 0; i < LEGEND_TYPES.length; i++) {
            if (!present[i]) continue;
            int ey = ly + padY + row * entryH;
            int shapeCx = lx + padX + shapeArea / 2;
            int shapeCy = ey + entryH / 2;
            drawMarkerPng(getMarkerTexture(LEGEND_TYPES[i]), shapeCx, shapeCy, colors[i], 8);
            GlStateManager.color(1f, 1f, 1f, 1f);
            fr.drawString(labels[i], lx + padX + shapeArea + shapeGap, ey + (entryH - 8) / 2, 0xEEDDBB);
            row++;
        }
    }

    /** Flags, parallel to LEGEND_TYPES, marking which marker types are currently on the map. */
    private boolean[] presentMarkerTypes() {
        boolean[] present = new boolean[LEGEND_TYPES.length];
        for (Marker m : markers) {
            // Treat FOLLOWER_HIGHLIGHTED as FOLLOWER for legend purposes.
            Marker.MarkerType effectiveType = (m.type == Marker.MarkerType.FOLLOWER_HIGHLIGHTED)
                    ? Marker.MarkerType.FOLLOWER : m.type;
            for (int i = 0; i < LEGEND_TYPES.length; i++) {
                if (LEGEND_TYPES[i] == effectiveType) {
                    present[i] = true;
                    break;
                }
            }
        }
        return present;
    }

    private int[] buildLegendColors() {
        int ownerArgb    = 0xFF000000 | (legendOwnerColor & 0xFFFFFF);
        int followerArgb = 0xFF000000 | WorldMapMarkerFactory.darken(legendOwnerColor & 0xFFFFFF, 0.55f);
        return new int[] {
            ownerArgb,                              // PLAYER
            WorldMapMarkerFactory.COLOR_VILLAGE,    // VILLAGE
            ownerArgb,                              // CONTROLLED_VILLAGE
            ownerArgb,                              // HOME
            ownerArgb,                              // LEADER
            followerArgb,                           // FOLLOWER
            WorldMapMarkerFactory.COLOR_CAMP_FIREPIT_LIT, // CAMP_FIREPIT (lit color swatch)
            WorldMapMarkerFactory.COLOR_CAMP_SLOT_BUILT,  // CAMP_SLOT (built color swatch)
        };
    }

    // ── Draw primitives ──────────────────────────────────────────────────────

    private static void drawTexturedRect(int x, int y, int w, int h) {
        net.minecraft.client.gui.Gui.drawModalRectWithCustomSizedTexture(x, y, 0, 0, w, h, w, h);
    }

    private static void rect(int x1, int y1, int x2, int y2, int color) {
        net.minecraft.client.gui.Gui.drawRect(x1, y1, x2, y2, color);
    }

    private static void drawOutlineSquare(int cx, int cy, int half, int argb) {
        net.minecraft.client.gui.Gui.drawRect(cx - half, cy - half, cx + half + 1, cy - half + 1, argb);
        net.minecraft.client.gui.Gui.drawRect(cx - half, cy + half, cx + half + 1, cy + half + 1, argb);
        net.minecraft.client.gui.Gui.drawRect(cx - half, cy - half, cx - half + 1, cy + half + 1, argb);
        net.minecraft.client.gui.Gui.drawRect(cx + half, cy - half, cx + half + 1, cy + half + 1, argb);
    }

    // ── Terrain texture rebuild ───────────────────────────────────────────────

    private void rebuildTexture() {
        java.util.HashMap<Long, byte[]> localTileCache = new java.util.HashMap<>();
        java.util.HashMap<Long, byte[]> localDetailCache = new java.util.HashMap<>();
        boolean useDetail = blocksPerPixel < 4f;

        // Build the oversized texture centered on the current centerX/Z.
        for (int py = 0; py < th; py++) {
            for (int px = 0; px < tw; px++) {
                int wx = Math.round(centerX + (px - tw / 2f) * blocksPerPixel);
                int wz = Math.round(centerZ + (py - th / 2f) * blocksPerPixel);

                if (!isTileDiscovered(wx, wz)) {
                    pixels[py * tw + px] = 0xFF0B0B0B;
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
                    pixels[py * tw + px] = 0xFF2A2A2A;
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

                // Use detail tile (1px=1bloc) when zoomed in, fall back to normal tile for missing pixels.
                int sx = localX / step;
                int sz = localZ / step;
                int tone = tile[sz * 64 + sx] & 0xFF;
                if (useDetail) {
                    byte[] detailTile = localDetailCache.get(tkey);
                    if (detailTile == null) {
                        detailTile = com.jubitus.travellers.client.map.ClientDetailTileCache.get(tkey);
                        if (detailTile != null) localDetailCache.put(tkey, detailTile);
                    }
                    if (detailTile != null && detailTile.length == com.jubitus.travellers.server.map.MapTileGenerator.DETAIL_BYTES) {
                        int detailTone = detailTile[localZ * com.jubitus.travellers.server.map.MapTileGenerator.DETAIL_PX + localX] & 0xFF;
                        if (detailTone != 0) tone = detailTone;
                    }
                }

                if (tone == 0) {
                    pixels[py * tw + px] = 0xFF2A2A2A;
                    continue;
                }

                pixels[py * tw + px] = (0xFF << 24) | toneToArgbPacked(tone);
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
