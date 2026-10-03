package com.jubitus.travellers.worldmap.gui;

import com.jubitus.travellers.worldmap.client.ClientTileTextures;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.worldmap.pyramid.MapPyramidStore;
import com.jubitus.travellers.worldmap.pyramid.MapTileGenerator;
import com.jubitus.travellers.worldmap.pyramid.PyramidDirtyPropagator;
import com.jubitus.travellers.worldmap.pyramid.TileKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

public class GuiWorldMapPanel {

    // Coarser levels drawn underneath while the current level is still streaming in.
    private static final int COARSE_FALLBACK_LEVELS = 3;
    // Below this many screen pixels per texel, magnified tiles are smoothed instead of drawn as hard squares.
    private static final float NEAREST_MIN_SCREEN_PX = 3f;

    public static class Marker {
        /** How a legend swatch is coloured: the viewer's own mark colour, a darker shade of it, or a fixed colour. */
        public enum LegendTint { OWNER, OWNER_DARK, FIXED }

        /**
         * Everything about a marker kind in one place. Declaration order is draw order (first = bottom);
         * the legend lists the kinds that have a legend key, topmost first.
         */
        public enum MarkerType {
            FOLLOWER("marker_follower", Size.WORKER, "follower", LegendTint.OWNER_DARK, 0),
            FOLLOWER_HIGHLIGHTED("marker_follower", Size.WORKER_HIGHLIGHTED, null, LegendTint.OWNER, 0),
            PALFRENIER("marker_palfrenier", Size.WORKER, "palfrenier", LegendTint.OWNER, 0),
            LEADER("marker_leader", Size.WORKER, "leader", LegendTint.OWNER, 0),
            HOME("marker_home", Size.WORKER, "home", LegendTint.OWNER, 0),
            STABLE("marker_stable", Size.WORKER, "stable", LegendTint.OWNER, 0),
            VILLAGE("marker_village", Size.VILLAGE, "village", LegendTint.FIXED, WorldMapMarkerFactory.COLOR_VILLAGE),
            CONTROLLED_VILLAGE("marker_controlled_village", Size.VILLAGE, "controlled_village", LegendTint.OWNER, 0),
            CAMP_SLOT("marker_camp_slot", Size.CAMP, "camp_slot", LegendTint.FIXED, WorldMapMarkerFactory.COLOR_CAMP_SLOT_BUILT),
            CAMP_FIREPIT("marker_camp_firepit", Size.CAMP, "camp_firepit", LegendTint.FIXED, WorldMapMarkerFactory.COLOR_CAMP_FIREPIT_LIT),
            POP_TRAVELLER("marker_follower", Size.POPULATION, null, LegendTint.FIXED, 0),
            POP_RAIDER("marker_follower", Size.POPULATION, null, LegendTint.FIXED, 0),
            POP_WORKER("marker_follower", Size.POPULATION, null, LegendTint.FIXED, 0),
            BATTLE("marker_destination_cross", Size.ALERT, null, LegendTint.FIXED, 0),
            PLAYER("marker_player", Size.PLAYER, "player", LegendTint.OWNER, 0),
            DESTINATION_CROSS("marker_destination_cross", Size.ALERT, null, LegendTint.FIXED, 0);

            public final ResourceLocation texture;
            final Size size;
            /** Lang key under travellers.map.legend., or null when the kind is never listed. */
            final String legendKey;
            final LegendTint legendTint;
            final int legendArgb;

            MarkerType(String texture, Size size, String legendKey, LegendTint legendTint, int legendArgb) {
                this.texture = new ResourceLocation("travellers", "textures/map/" + texture + ".png");
                this.size = size;
                this.legendKey = legendKey == null ? null : "travellers.map.legend." + legendKey;
                this.legendTint = legendTint;
                this.legendArgb = legendArgb;
            }

            /** The legend entry this kind counts as (a highlighted follower is still a follower). */
            MarkerType legendType() {
                return this == FOLLOWER_HIGHLIGHTED ? FOLLOWER : this;
            }
        }

        /** Size class, resolved against the worldmap config at draw time. */
        enum Size { PLAYER, VILLAGE, WORKER, WORKER_HIGHLIGHTED, ALERT, POPULATION, CAMP }

        public final BlockPos pos;
        public final int argb;
        public final MarkerType type;
        public final String label;
        /** Rotation in degrees, only meaningful for directional marker types (currently PLAYER). */
        public final float angleDeg;

        public Marker(BlockPos pos, int argb, MarkerType type, String label) {
            this(pos, argb, type, label, 0f);
        }

        public Marker(BlockPos pos, int argb, MarkerType type, String label, float angleDeg) {
            this.pos = pos;
            this.argb = argb;
            this.type = type;
            this.label = label;
            this.angleDeg = angleDeg;
        }
    }

    private final Minecraft mc;
    private final World world;
    private final int w, h;

    private float centerX = 0, centerZ = 0;
    private float blocksPerPixel = 1f;

    // Debug: optional drawer invoked after the worldmap background and before the markers, so an
    // overlay (e.g. the scanned-terrain map) can replace the background while keeping markers on top.
    public Runnable debugBackgroundOverlay;

    private BlockPos selectedPos = null;

    // RGB (no alpha) used to colorize player-dependent legend entries
    private int legendOwnerColor = 0xFFFFFF;

    private final List<Marker> markers = new ArrayList<>();
    // Markers standing here are drawn larger and brighter (hover / menu target).
    private java.util.Set<BlockPos> highlighted = java.util.Collections.emptySet();

    public int getWidth()  { return w; }
    public int getHeight() { return h; }

    public GuiWorldMapPanel(Minecraft mc, World world, int width, int height, String textureKey) {
        this.mc = mc;
        this.world = world;
        this.w = width;
        this.h = height;
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
    }

    public float getZoomBlocksPerPixel() { return blocksPerPixel; }

    /** True when the map has something to show here (it only covers the overworld). */
    public boolean isMappedDimension() {
        return mc.world != null && mc.world.provider.getDimension() == MapPyramidStore.MAP_DIMENSION;
    }

    // ── Marker API ───────────────────────────────────────────────────────────

    public void clearMarkers() { markers.clear(); }
    public void addMarker(Marker m) { markers.add(m); }

    public void setSelectedPos(BlockPos pos) { this.selectedPos = pos; }

    public void setHighlighted(java.util.Set<BlockPos> positions) {
        this.highlighted = positions != null ? positions : java.util.Collections.emptySet();
    }

    /** Pyramid level matching the current zoom: level L = 2^L blocks/pixel. */
    public int currentPyramidLevel() {
        int lvl = Math.round((float) (Math.log(blocksPerPixel) / Math.log(2)));
        if (lvl < 0) lvl = 0;
        if (lvl > PyramidDirtyPropagator.MAX_LEVEL) lvl = PyramidDirtyPropagator.MAX_LEVEL;
        return lvl;
    }

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

    /** Screen X of a world column, for a map drawn at mapX. */
    public float worldToScreenX(double worldX, int mapX) {
        return (float) (mapX + w / 2f + (worldX - centerX) / blocksPerPixel);
    }

    public float worldToScreenY(double worldZ, int mapY) {
        return (float) (mapY + h / 2f + (worldZ - centerZ) / blocksPerPixel);
    }

    // ── Tooltip ──────────────────────────────────────────────────────────────

    /** Returns the label of the marker under the mouse, or null. */
    public String getTooltipAt(int mouseX, int mouseY, int mapX, int mapY) {
        if (!isMappedDimension()) return null;
        for (int i = markers.size() - 1; i >= 0; i--) {
            Marker m = markers.get(i);
            if (m.label == null || m.label.isEmpty()) continue;
            float dx = mouseX - worldToScreenX(m.pos.getX(), mapX);
            float dy = mouseY - worldToScreenY(m.pos.getZ(), mapY);
            if (dx * dx + dy * dy <= 25) return m.label;
        }
        return null;
    }

    // ── Draw ─────────────────────────────────────────────────────────────────

    public void draw(int mapX, int mapY) {
        ScaledResolution sr = new ScaledResolution(mc);
        int sf = sr.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(mapX * sf, mc.displayHeight - (mapY + h) * sf, w * sf, h * sf);

        Gui.drawRect(mapX, mapY, mapX + w, mapY + h, 0xFF000000 | ClientTileTextures.FOG_RGB);

        if (!isMappedDimension()) {
            String msg = I18n.format("travellers.map.unavailable_dimension");
            mc.fontRenderer.drawStringWithShadow(msg, mapX + (w - mc.fontRenderer.getStringWidth(msg)) / 2f,
                    mapY + h / 2f - 4, 0xEEDDBB);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            return;
        }

        drawTerrain(mapX, mapY, sf);

        // Debug terrain overlay (opaque; drawn over the background, under the markers).
        if (debugBackgroundOverlay != null) debugBackgroundOverlay.run();

        drawMarkers(mapX, mapY);

        if (selectedPos != null) {
            int sx = Math.round(worldToScreenX(selectedPos.getX(), mapX));
            int sy = Math.round(worldToScreenY(selectedPos.getZ(), mapY));
            drawOutlineSquare(sx, sy, 4, 0xFFFFE39A);
        }

        drawLegend(mapX, mapY);

        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    // ── Terrain ──────────────────────────────────────────────────────────────

    private void drawTerrain(int mapX, int mapY, int sf) {
        ClientTileTextures.beginFrame();
        int level = currentPyramidLevel();

        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.color(1f, 1f, 1f, 1f);

        boolean complete = allVisibleHaveTextures(level);
        if (!complete) {
            int top = Math.min(PyramidDirtyPropagator.MAX_LEVEL, level + COARSE_FALLBACK_LEVELS);
            for (int lv = top; lv > level; lv--) drawLevel(lv, mapX, mapY, sf, false);
        }
        drawLevel(level, mapX, mapY, sf, false);
        if (!complete && level > 0) drawLevel(level - 1, mapX, mapY, sf, true);

        GlStateManager.enableBlend();
    }

    private boolean allVisibleHaveTextures(int level) {
        int tileBlocks = MapTileGenerator.TILE_PX << level;
        int tx0 = visibleMin(centerX, w, tileBlocks), tx1 = visibleMax(centerX, w, tileBlocks);
        int tz0 = visibleMin(centerZ, h, tileBlocks), tz1 = visibleMax(centerZ, h, tileBlocks);
        for (int tz = tz0; tz <= tz1; tz++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                if (!ClientTileTextures.hasTexture(level, TileKey.pack(tx, tz))) return false;
            }
        }
        return true;
    }

    // onlyUnderMissingParent: finer fallback, drawn only where the level above has nothing yet.
    private void drawLevel(int level, int mapX, int mapY, int sf, boolean onlyUnderMissingParent) {
        int tileBlocks = MapTileGenerator.TILE_PX << level;
        int tx0 = visibleMin(centerX, w, tileBlocks), tx1 = visibleMax(centerX, w, tileBlocks);
        int tz0 = visibleMin(centerZ, h, tileBlocks), tz1 = visibleMax(centerZ, h, tileBlocks);
        float texelScreenPx = sf * (1 << level) / blocksPerPixel;
        int magFilter = texelScreenPx >= NEAREST_MIN_SCREEN_PX ? GL11.GL_NEAREST : GL11.GL_LINEAR;

        Tessellator t = Tessellator.getInstance();
        BufferBuilder b = t.getBuffer();
        for (int tz = tz0; tz <= tz1; tz++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                if (onlyUnderMissingParent
                        && ClientTileTextures.hasData(level + 1, TileKey.pack(Math.floorDiv(tx, 2), Math.floorDiv(tz, 2)))) {
                    continue;
                }
                int id = ClientTileTextures.textureFor(level, TileKey.pack(tx, tz));
                if (id < 0) continue;
                GlStateManager.bindTexture(id);
                GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, magFilter);
                // Both edges come from the same formula, so neighbouring tiles meet without gaps.
                float x0 = worldToScreenX((double) tx * tileBlocks, mapX);
                float x1 = worldToScreenX((double) (tx + 1) * tileBlocks, mapX);
                float y0 = worldToScreenY((double) tz * tileBlocks, mapY);
                float y1 = worldToScreenY((double) (tz + 1) * tileBlocks, mapY);
                b.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
                b.pos(x0, y1, 0).tex(0, 1).endVertex();
                b.pos(x1, y1, 0).tex(1, 1).endVertex();
                b.pos(x1, y0, 0).tex(1, 0).endVertex();
                b.pos(x0, y0, 0).tex(0, 0).endVertex();
                t.draw();
            }
        }
    }

    private int visibleMin(float center, int sizePx, int tileBlocks) {
        return Math.floorDiv((int) Math.floor(center - sizePx / 2f * blocksPerPixel), tileBlocks);
    }

    private int visibleMax(float center, int sizePx, int tileBlocks) {
        return Math.floorDiv((int) Math.ceil(center + sizePx / 2f * blocksPerPixel), tileBlocks);
    }

    // ── Marker rendering ─────────────────────────────────────────────────────

    private void drawMarkers(int mapX, int mapY) {
        float zoomFloor = Math.max(1f, Math.round(1f / blocksPerPixel));
        float scale = zoomFloor * (float) TravellersConfig.WORLDMAP.markerScaleGlobal;
        boolean directional = TravellersConfig.WORLDMAP.playerMarkerDirectional;
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        for (Marker.MarkerType pass : Marker.MarkerType.values()) {
            for (Marker m : markers) {
                if (m.type != pass) continue;
                float sx = worldToScreenX(m.pos.getX(), mapX);
                float sy = worldToScreenY(m.pos.getZ(), mapY);
                boolean hl = highlighted.contains(m.pos);
                int size = Math.round(baseSize(m.type.size) * scale * (hl ? HIGHLIGHT_SCALE : 1f));
                int argb = hl ? (m.argb & 0xFF000000) | WorldMapMarkerFactory.lighten(m.argb & 0xFFFFFF, 0.45f) : m.argb;
                GlStateManager.pushMatrix();
                GlStateManager.translate(sx, sy, 0f);
                if (m.type == Marker.MarkerType.PLAYER && directional) {
                    // Base texture points north (up); yaw 0 = facing south (down on screen).
                    GlStateManager.rotate(m.angleDeg + 180f, 0f, 0f, 1f);
                }
                drawMarkerPng(m.type.texture, 0, 0, argb, size);
                GlStateManager.popMatrix();
            }
        }
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    private static final float HIGHLIGHT_SCALE = 1.4f;

    /** Base display size in screen pixels, from config — independent of PNG resolution. */
    private static int baseSize(Marker.Size size) {
        TravellersConfig.Worldmap cfg = TravellersConfig.WORLDMAP;
        switch (size) {
            case PLAYER:             return cfg.playerMarkerSize;
            case VILLAGE:            return cfg.villageMarkerSize;
            case WORKER_HIGHLIGHTED: return cfg.workerMarkerSize + cfg.followerHighlightedSizeBonus;
            case ALERT:              return cfg.alertMarkerSize;
            case POPULATION:         return cfg.populationMarkerSize;
            case CAMP:               return cfg.campMarkerSize;
            case WORKER:
            default:                 return cfg.workerMarkerSize;
        }
    }

    /** Draws the marker PNG tinted with argb, with a soft shadow offset by 1px. */
    private void drawMarkerPng(ResourceLocation tex, int cx, int cy, int argb, int size) {
        int half = size / 2;
        GlStateManager.enableTexture2D();
        mc.getTextureManager().bindTexture(tex);
        // Pass size as texture dimensions so UV always spans [0,1] regardless of PNG resolution.
        GlStateManager.color(0f, 0f, 0f, 0.45f);
        Gui.drawModalRectWithCustomSizedTexture(cx - half + 1, cy - half + 1, 0, 0, size, size, size, size);
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >>  8) & 0xFF) / 255f;
        float b = ( argb        & 0xFF) / 255f;
        float a = ((argb >> 24) & 0xFF) / 255f;
        GlStateManager.color(r, g, b, a);
        Gui.drawModalRectWithCustomSizedTexture(cx - half, cy - half, 0, 0, size, size, size, size);
    }

    // ── Legend ────────────────────────────────────────────────────────────────

    private void drawLegend(int mapX, int mapY) {
        net.minecraft.client.gui.FontRenderer fr = mc.fontRenderer;

        // Only kinds actually on the map are listed: each GUI decides which markers it adds.
        java.util.EnumSet<Marker.MarkerType> present = java.util.EnumSet.noneOf(Marker.MarkerType.class);
        for (Marker m : markers) {
            if (m.type.legendType().legendKey != null) present.add(m.type.legendType());
        }
        if (present.isEmpty()) return;

        // Topmost kind first.
        List<Marker.MarkerType> rows = new ArrayList<>(present);
        java.util.Collections.reverse(rows);

        int entryH    = 9;
        int shapeArea = 12; // pixels reserved for the shape
        int shapeGap  = 3;
        int padX = 4, padY = 3;

        int maxTextW = 0;
        String[] labels = new String[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            labels[i] = I18n.format(rows.get(i).legendKey);
            maxTextW = Math.max(maxTextW, fr.getStringWidth(labels[i]));
        }

        int legendW = padX + shapeArea + shapeGap + maxTextW + padX;
        int legendH = padY + rows.size() * entryH + padY;
        int lx = mapX + 3;
        int ly = mapY + h - legendH - 3;

        // Semi-transparent dark background — very subtle
        Gui.drawRect(lx, ly, lx + legendW, ly + legendH, 0x99090909);

        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        for (int i = 0; i < rows.size(); i++) {
            Marker.MarkerType t = rows.get(i);
            int ey = ly + padY + i * entryH;
            drawMarkerPng(t.texture, lx + padX + shapeArea / 2, ey + entryH / 2, legendColor(t), 8);
            GlStateManager.color(1f, 1f, 1f, 1f);
            fr.drawString(labels[i], lx + padX + shapeArea + shapeGap, ey + (entryH - 8) / 2, 0xEEDDBB);
        }
    }

    private int legendColor(Marker.MarkerType t) {
        switch (t.legendTint) {
            case OWNER:      return 0xFF000000 | legendOwnerColor;
            case OWNER_DARK: return 0xFF000000 | WorldMapMarkerFactory.darken(legendOwnerColor, 0.55f);
            case FIXED:
            default:         return t.legendArgb;
        }
    }

    // ── Draw primitives ──────────────────────────────────────────────────────

    private static void drawOutlineSquare(int cx, int cy, int half, int argb) {
        Gui.drawRect(cx - half, cy - half, cx + half + 1, cy - half + 1, argb);
        Gui.drawRect(cx - half, cy + half, cx + half + 1, cy + half + 1, argb);
        Gui.drawRect(cx - half, cy - half, cx - half + 1, cy + half + 1, argb);
        Gui.drawRect(cx + half, cy - half, cx + half + 1, cy + half + 1, argb);
    }
}
