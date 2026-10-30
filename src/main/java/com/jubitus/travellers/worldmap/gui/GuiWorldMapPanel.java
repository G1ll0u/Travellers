package com.jubitus.travellers.worldmap.gui;

import com.jubitus.travellers.worldmap.client.ClientMapRoads;
import com.jubitus.travellers.worldmap.client.ClientTileTextures;
import com.jubitus.travellers.worldmap.client.MapRoadPainter;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.gui.GuiClip;
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

    // A Millénaire settlement with no icon anywhere in its culture.
    private static final String SETTLEMENT_TEXTURE = "marker_settlement";
    // The tinted dot under a settlement's icon (controlled, enemy, a recruiting trip's stop).
    private static final ResourceLocation DOT_TEXTURE = new ResourceLocation("travellers", "textures/map/marker_settlement_dot.png");

    // Coarser levels drawn underneath while the current level is still streaming in.
    private static final int COARSE_FALLBACK_LEVELS = 3;
    // Below this many screen pixels per texel, magnified tiles are smoothed instead of drawn as hard squares.
    private static final float NEAREST_MIN_SCREEN_PX = 3f;

    public static class Marker {
        /** How a legend swatch is coloured: the viewer's own mark colour, a darker shade of it, or a fixed colour. */
        public enum LegendTint { OWNER, OWNER_DARK, FIXED }

        /**
         * How a kind is drawn: its texture tinted with the marker's colour; a settlement (its item icon, else the
         * untinted texture), optionally with a dot of the marker's colour under it; or only that dot.
         */
        enum Look { TINTED, SETTLEMENT, SETTLEMENT_DOT, DOT }

        /**
         * Everything about a marker kind in one place. Declaration order is draw order (first = bottom);
         * the legend lists the kinds that have a legend key, topmost first.
         */
        public enum MarkerType {
            POP_TRAVELLER("marker_follower", Size.POPULATION, null, LegendTint.FIXED, 0),
            POP_RAIDER("marker_follower", Size.POPULATION, null, LegendTint.FIXED, 0),
            POP_WORKER("marker_follower", Size.POPULATION, null, LegendTint.FIXED, 0),
            FOLLOWER("marker_follower", Size.WORKER, "follower", LegendTint.OWNER_DARK, 0),
            FOLLOWER_HIGHLIGHTED("marker_follower", Size.WORKER_HIGHLIGHTED, null, LegendTint.OWNER, 0),
            LEADER("marker_leader", Size.WORKER, "leader", LegendTint.OWNER, 0),
            CAMP_SLOT("marker_camp_slot", Size.CAMP, "camp_slot", LegendTint.FIXED, WorldMapMarkerFactory.COLOR_CAMP_SLOT_BUILT),
            CAMP_FIREPIT("marker_camp_firepit", Size.CAMP, "camp_firepit", LegendTint.FIXED, WorldMapMarkerFactory.COLOR_CAMP_FIREPIT_LIT),
            HOME("marker_home", Size.WORKER, "home", LegendTint.OWNER, 0),
            STABLE("marker_stable", Size.WORKER, "stable", LegendTint.OWNER, 0),
            LONE_BUILDING(SETTLEMENT_TEXTURE, Look.SETTLEMENT, Size.VILLAGE, null, LegendTint.FIXED, 0),
            VILLAGE(SETTLEMENT_TEXTURE, Look.SETTLEMENT, Size.VILLAGE, null, LegendTint.FIXED, 0),
            ENEMY_VILLAGE(SETTLEMENT_TEXTURE, Look.SETTLEMENT_DOT, Size.VILLAGE, "enemy_village", LegendTint.FIXED, WorldMapMarkerFactory.COLOR_ENEMY_VILLAGE),
            CONTROLLED_VILLAGE(SETTLEMENT_TEXTURE, Look.SETTLEMENT_DOT, Size.VILLAGE, "controlled_village", LegendTint.OWNER, 0),
            /** A screen's dot under a settlement's icon (a recruiting trip's stop). */
            SETTLEMENT_DOT(SETTLEMENT_TEXTURE, Look.DOT, Size.VILLAGE, null, LegendTint.FIXED, 0),
            BATTLE_SKIRMISH("marker_destination_cross", Size.SKIRMISH, null, LegendTint.FIXED, 0),
            BATTLE("marker_destination_cross", Size.ALERT, null, LegendTint.FIXED, 0),
            DESTINATION_CROSS("marker_destination_cross", Size.ALERT, null, LegendTint.FIXED, 0),
            PLAYER("marker_player", Size.PLAYER, "player", LegendTint.OWNER, 0);

            public final ResourceLocation texture;
            final Look look;
            final Size size;
            /** Lang key under travellers.map.legend., or null when the kind is never listed. */
            final String legendKey;
            final LegendTint legendTint;
            final int legendArgb;

            MarkerType(String texture, Size size, String legendKey, LegendTint legendTint, int legendArgb) {
                this(texture, Look.TINTED, size, legendKey, legendTint, legendArgb);
            }

            MarkerType(String texture, Look look, Size size, String legendKey, LegendTint legendTint, int legendArgb) {
                this.texture = new ResourceLocation("travellers", "textures/map/" + texture + ".png");
                this.look = look;
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
        public enum Size { PLAYER, VILLAGE, WORKER, WORKER_HIGHLIGHTED, ALERT, SKIRMISH, POPULATION, CAMP }

        public final BlockPos pos;
        public final int argb;
        public final MarkerType type;
        public final String label;
        /** Rotation in degrees, only meaningful for directional marker types (currently PLAYER). */
        public final float angleDeg;
        /** Where the marker is drawn (block coordinates): its position, or a smoothed one for a moving unit. */
        public final float drawX, drawZ;
        /** The unit this marker stands for, when it is one (lets the strike animation find it). */
        public final java.util.UUID unitId;
        /** Force-balance bar under the marker: share of the left colour (0..1), or negative for no bar. */
        public final float balance;
        public final int balanceLeftRgb, balanceRightRgb;
        /** A battle marker's drawn tooltip, in place of {@link #label}. */
        @javax.annotation.Nullable public final com.jubitus.travellers.battle.client.BattleTooltip battle;
        /** Item drawn in place of the texture (a settlement's Millénaire icon). */
        @javax.annotation.Nullable public final net.minecraft.item.ItemStack icon;

        public Marker(BlockPos pos, int argb, MarkerType type, String label) {
            this(pos, argb, type, label, 0f);
        }

        public Marker(BlockPos pos, int argb, MarkerType type, String label, float angleDeg) {
            this(pos, argb, type, label, angleDeg, pos.getX(), pos.getZ(), null, -1f, 0, 0, null, null);
        }

        private Marker(BlockPos pos, int argb, MarkerType type, String label, float angleDeg, float drawX, float drawZ,
                       java.util.UUID unitId, float balance, int balanceLeftRgb, int balanceRightRgb,
                       @javax.annotation.Nullable com.jubitus.travellers.battle.client.BattleTooltip battle,
                       @javax.annotation.Nullable net.minecraft.item.ItemStack icon) {
            this.pos = pos;
            this.argb = argb;
            this.type = type;
            this.label = label;
            this.angleDeg = angleDeg;
            this.drawX = drawX;
            this.drawZ = drawZ;
            this.unitId = unitId;
            this.balance = balance;
            this.balanceLeftRgb = balanceLeftRgb;
            this.balanceRightRgb = balanceRightRgb;
            this.battle = battle;
            this.icon = icon;
        }

        /** The same marker drawn at another spot; {@link #pos} stays its identity. */
        public Marker at(float x, float z) {
            return new Marker(pos, argb, type, label, angleDeg, x, z, unitId, balance, balanceLeftRgb, balanceRightRgb, battle, icon);
        }

        public Marker forUnit(java.util.UUID id) {
            return new Marker(pos, argb, type, label, angleDeg, drawX, drawZ, id, balance, balanceLeftRgb, balanceRightRgb, battle, icon);
        }

        public Marker withBalance(float share, int leftRgb, int rightRgb) {
            return new Marker(pos, argb, type, label, angleDeg, drawX, drawZ, unitId, share, leftRgb, rightRgb, battle, icon);
        }

        public Marker withBattle(com.jubitus.travellers.battle.client.BattleTooltip tooltip) {
            return new Marker(pos, argb, type, label, angleDeg, drawX, drawZ, unitId, balance, balanceLeftRgb, balanceRightRgb, tooltip, icon);
        }

        public Marker withIcon(@javax.annotation.Nullable net.minecraft.item.ItemStack stack) {
            return new Marker(pos, argb, type, label, angleDeg, drawX, drawZ, unitId, balance, balanceLeftRgb, balanceRightRgb, battle, stack);
        }
    }

    private final Minecraft mc;
    private final World world;
    private final int w, h;

    private float centerX = 0, centerZ = 0;
    private float blocksPerPixel = 1f;

    // Optional drawer invoked after the worldmap background and before the markers (debug terrain,
    // a planned route), so markers stay on top.
    public Runnable underMarkersOverlay;

    // RGB (no alpha) used to colorize player-dependent legend entries
    private int legendOwnerColor = 0xFFFFFF;

    private final List<Marker> markers = new ArrayList<>();
    private final BattleStrikeAnimator strikes = new BattleStrikeAnimator();
    private static final int STRIKE_FALLBACK_ARGB = 0xFFCCCCCC;
    // Arrows would be sub-pixel specks beyond this zoom-out.
    private static final float ARROW_MAX_BLOCKS_PER_PIXEL = 4f;
    private static final float LUNGE_PX = 2f;
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

    /** Starts the animation of the strikes the server reported for this map's dimension. */
    public void receiveStrikes(int dim, List<com.jubitus.travellers.worldmap.net.MsgMapBattleStrikes.Entry> entries) {
        if (mc.world == null || mc.world.provider.getDimension() != dim || entries == null || entries.isEmpty()) return;
        java.util.Set<java.util.UUID> archers = new java.util.HashSet<>();
        for (com.jubitus.travellers.worldmap.net.MsgMapBattleStrikes.Entry e : entries) {
            if (e.kind == com.jubitus.travellers.worldmap.stream.MapStrikeLog.ARROW) archers.add(e.attacker);
        }
        java.util.Map<java.util.UUID, Integer> colours = new java.util.HashMap<>();
        if (!archers.isEmpty()) {
            for (Marker m : markers) {
                if (m.unitId != null && archers.contains(m.unitId)) colours.put(m.unitId, m.argb);
            }
        }
        if (!MapPlaybackClock.CLIENT.isRunning()) return;
        for (com.jubitus.travellers.worldmap.net.MsgMapBattleStrikes.Entry e : entries) {
            // On the markers' clock, so a shot leaves from where its archer is drawn.
            long start = e.tick * 50L;
            if (e.kind == com.jubitus.travellers.worldmap.stream.MapStrikeLog.ARROW) {
                int argb = 0xFF000000 | colours.getOrDefault(e.attacker, STRIKE_FALLBACK_ARGB);
                strikes.addArrow(e.attackerX, e.attackerZ, e.victimX, e.victimZ, argb, start);
            } else {
                strikes.addLunge(e.attacker, e.attackerX, e.attackerZ, e.victimX, e.victimZ, start);
            }
        }
    }

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

    /** The marker drawn on top under the mouse that has a tooltip, or null. */
    @javax.annotation.Nullable
    public Marker tooltipMarkerAt(int mouseX, int mouseY, int mapX, int mapY) {
        if (!isMappedDimension()) return null;
        Marker.MarkerType[] types = Marker.MarkerType.values();
        for (int t = types.length - 1; t >= 0; t--) {
            for (int i = markers.size() - 1; i >= 0; i--) {
                Marker m = markers.get(i);
                if (m.type != types[t] || m.battle == null && (m.label == null || m.label.isEmpty())) continue;
                float dx = mouseX - worldToScreenX(m.drawX, mapX);
                float dy = mouseY - worldToScreenY(m.drawZ, mapY);
                float r = hitRadiusPx(m.type.size);
                if (dx * dx + dy * dy <= r * r) return m;
            }
        }
        return null;
    }

    // ── Draw ─────────────────────────────────────────────────────────────────

    public void draw(int mapX, int mapY) {
        int sf = new ScaledResolution(mc).getScaleFactor();
        GuiClip.begin(mapX, mapY, w, h);

        Gui.drawRect(mapX, mapY, mapX + w, mapY + h, 0xFF000000 | ClientTileTextures.FOG_RGB);

        if (!isMappedDimension()) {
            String msg = I18n.format("travellers.map.unavailable_dimension");
            mc.fontRenderer.drawStringWithShadow(msg, mapX + (w - mc.fontRenderer.getStringWidth(msg)) / 2f,
                    mapY + h / 2f - 4, 0xEEDDBB);
            GuiClip.end();
            return;
        }

        drawTerrain(mapX, mapY, sf);

        // Overlays drawn over the terrain, under the markers (debug terrain, a planned route).
        if (underMarkersOverlay != null) underMarkersOverlay.run();

        drawMarkers(mapX, mapY);

        drawLegend(mapX, mapY);

        GuiClip.end();
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
        float scale = markerScale();
        boolean directional = TravellersConfig.WORLDMAP.playerMarkerDirectional;
        long now = MapPlaybackClock.CLIENT.now();
        float[] lungeBuf = new float[3];
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        for (Marker.MarkerType pass : Marker.MarkerType.values()) {
            for (Marker m : markers) {
                if (m.type != pass) continue;
                float sx = worldToScreenX(m.drawX, mapX);
                float sy = worldToScreenY(m.drawZ, mapY);
                if (m.unitId != null && strikes.lunge(m.unitId, now, lungeBuf)) {
                    float reach = lungeBuf[2] * LUNGE_PX * scale;
                    sx += lungeBuf[0] * reach;
                    sy += lungeBuf[1] * reach;
                }
                boolean hl = highlighted.contains(m.pos);
                int size = Math.round(baseSize(m.type.size) * scale * (hl ? HIGHLIGHT_SCALE : 1f));
                int argb = hl ? (m.argb & 0xFF000000) | WorldMapMarkerFactory.lighten(m.argb & 0xFFFFFF, 0.45f) : m.argb;
                GlStateManager.pushMatrix();
                GlStateManager.translate(sx, sy, 0f);
                if (m.type == Marker.MarkerType.PLAYER && directional) {
                    // Base texture points north (up); yaw 0 = facing south (down on screen).
                    GlStateManager.rotate(m.angleDeg + 180f, 0f, 0f, 1f);
                }
                drawLook(m, argb, size);
                if (m.balance >= 0f) drawBalanceBar(m, size);
                GlStateManager.popMatrix();
            }
        }
        GlStateManager.color(1f, 1f, 1f, 1f);
        drawArrows(mapX, mapY, now);
    }

    /** Two-colour bar just under a marker (already translated to its centre). */
    private static void drawBalanceBar(Marker m, int size) {
        int w = Math.max(8, size);
        int x0 = -w / 2, y0 = size / 2 + 1;
        int split = x0 + Math.round(w * Math.min(1f, m.balance));
        Gui.drawRect(x0 - 1, y0 - 1, x0 + w + 1, y0 + 3, 0xC0000000);
        if (split > x0) Gui.drawRect(x0, y0, split, y0 + 2, 0xFF000000 | m.balanceLeftRgb);
        if (split < x0 + w) Gui.drawRect(split, y0, x0 + w, y0 + 2, 0xFF000000 | m.balanceRightRgb);
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
    }

    private void drawArrows(int mapX, int mapY, long now) {
        if (strikes.isIdle()) return;
        if (blocksPerPixel > ARROW_MAX_BLOCKS_PER_PIXEL) {
            strikes.forEachArrow(now, (argb, hx, hz, tx, tz) -> { }); // keep expiring
            return;
        }
        strikes.forEachArrow(now, (argb, hx, hz, tx, tz) -> {
            float hsx = worldToScreenX(hx, mapX), hsy = worldToScreenY(hz, mapY);
            float tsx = worldToScreenX(tx, mapX), tsy = worldToScreenY(tz, mapY);
            // Trail: three fading dots from the tail toward the head, then the head itself.
            for (int i = 0; i < 3; i++) {
                float t = i / 3f;
                int a = 0x30 + i * 0x30;
                int x = Math.round(tsx + (hsx - tsx) * t), y = Math.round(tsy + (hsy - tsy) * t);
                Gui.drawRect(x, y, x + 1, y + 1, (a << 24) | (argb & 0xFFFFFF));
            }
            int x = Math.round(hsx), y = Math.round(hsy);
            Gui.drawRect(x - 1, y - 1, x + 1, y + 1, 0xFF000000 | (argb & 0xFFFFFF));
        });
    }

    private static final float HIGHLIGHT_SCALE = 1.4f;
    // A settlement's dot: this share of the icon's size, overlapping the icon's bottom edge by a third of itself.
    private static final float DOT_SHARE = 0.5f;
    private static final int DOT_MIN_PX = 4;
    // A skirmish's marker is this share of a battle's.
    private static final float SKIRMISH_SIZE_SHARE = 0.65f;
    private static final float HIT_MARGIN_PX = 2f;
    private static final float MIN_HIT_RADIUS_PX = 5f;

    private float markerScale() {
        float zoomFloor = Math.max(1f, Math.round(1f / blocksPerPixel));
        return zoomFloor * (float) TravellersConfig.WORLDMAP.markerScaleGlobal;
    }

    /** Click radius (screen pixels) of a marker of this size class: its drawn half-size plus a small margin. */
    public float hitRadiusPx(Marker.Size size) {
        float drawn = Math.round(baseSize(size) * markerScale());
        return Math.max(MIN_HIT_RADIUS_PX, drawn / 2f + HIT_MARGIN_PX);
    }

    /** Base display size in screen pixels, from config — independent of PNG resolution. */
    private static int baseSize(Marker.Size size) {
        TravellersConfig.Worldmap cfg = TravellersConfig.WORLDMAP;
        switch (size) {
            case PLAYER:             return cfg.playerMarkerSize;
            case VILLAGE:            return cfg.villageMarkerSize;
            case WORKER_HIGHLIGHTED: return cfg.workerMarkerSize + cfg.followerHighlightedSizeBonus;
            case ALERT:              return cfg.alertMarkerSize;
            case SKIRMISH:           return Math.max(4, Math.round(cfg.alertMarkerSize * SKIRMISH_SIZE_SHARE));
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

    /** Draws a marker centred on the current origin. */
    private void drawLook(Marker m, int argb, int size) {
        Marker.Look look = m.type.look;
        if (look == Marker.Look.TINTED) {
            drawMarkerPng(m.type.texture, 0, 0, argb, size);
            return;
        }
        if (look != Marker.Look.DOT) {
            if (m.icon != null) {
                com.jubitus.travellers.gui.ItemIcon.draw(m.icon, -size / 2, -size / 2, size);
                GlStateManager.enableBlend();
                GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            } else {
                drawMarkerPng(m.type.texture, 0, 0, 0xFFFFFFFF, size);
            }
        }
        if (look != Marker.Look.SETTLEMENT) {
            int dot = Math.max(DOT_MIN_PX, Math.round(size * DOT_SHARE));
            drawMarkerPng(DOT_TEXTURE, 0, size / 2 + dot / 6, argb, dot);
        }
    }

    // ── Legend ────────────────────────────────────────────────────────────────

    private void drawLegend(int mapX, int mapY) {
        net.minecraft.client.gui.FontRenderer fr = mc.fontRenderer;

        // Only kinds actually on the map are listed: each GUI decides which markers it adds.
        java.util.EnumSet<Marker.MarkerType> present = java.util.EnumSet.noneOf(Marker.MarkerType.class);
        for (Marker m : markers) {
            if (m.type.legendType().legendKey != null) present.add(m.type.legendType());
        }
        List<RoadLegend> roadRows = new ArrayList<>();
        if (ClientMapRoads.hasVillageRoads()) roadRows.add(RoadLegend.ROAD);
        if (ClientMapRoads.hasLoneTrails()) roadRows.add(RoadLegend.TRAIL);
        if (ClientMapRoads.hasBridges()) roadRows.add(RoadLegend.BRIDGE);
        if (present.isEmpty() && roadRows.isEmpty()) return;

        // Topmost kind first, roads last.
        List<Marker.MarkerType> rows = new ArrayList<>(present);
        java.util.Collections.reverse(rows);

        int entryH    = 9;
        int shapeArea = 12; // pixels reserved for the shape
        int shapeGap  = 3;
        int padX = 4, padY = 3;

        int maxTextW = 0;
        int rowCount = rows.size() + roadRows.size();
        String[] labels = new String[rowCount];
        for (int i = 0; i < rowCount; i++) {
            labels[i] = I18n.format(i < rows.size() ? rows.get(i).legendKey : roadRows.get(i - rows.size()).langKey);
            maxTextW = Math.max(maxTextW, fr.getStringWidth(labels[i]));
        }

        int legendW = padX + shapeArea + shapeGap + maxTextW + padX;
        int legendH = padY + rowCount * entryH + padY;
        int lx = mapX + 3;
        int ly = mapY + h - legendH - 3;

        // Semi-transparent dark background — very subtle
        Gui.drawRect(lx, ly, lx + legendW, ly + legendH, 0x99090909);

        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        for (int i = 0; i < rows.size(); i++) {
            Marker.MarkerType t = rows.get(i);
            int ey = ly + padY + i * entryH;
            drawMarkerPng(t.look == Marker.Look.SETTLEMENT_DOT ? DOT_TEXTURE : t.texture, lx + padX + shapeArea / 2, ey + entryH / 2, legendColor(t), 8);
            GlStateManager.color(1f, 1f, 1f, 1f);
            fr.drawString(labels[i], lx + padX + shapeArea + shapeGap, ey + (entryH - 8) / 2, 0xEEDDBB);
        }
        for (int i = 0; i < roadRows.size(); i++) {
            int ey = ly + padY + (rows.size() + i) * entryH;
            drawRoadSwatch(roadRows.get(i), lx + padX, ey + entryH / 2);
            fr.drawString(labels[rows.size() + i], lx + padX + shapeArea + shapeGap, ey + (entryH - 8) / 2, 0xEEDDBB);
        }
    }

    private enum RoadLegend {
        ROAD("travellers.map.legend.road"), TRAIL("travellers.map.legend.trail"), BRIDGE("travellers.map.legend.bridge");

        final String langKey;

        RoadLegend(String langKey) { this.langKey = langKey; }
    }

    // A 12x7 scrap of map with the road drawn on it, so dark ink stays readable on the legend's dark ground.
    private static void drawRoadSwatch(RoadLegend kind, int x, int cy) {
        int ink = 0xFF000000 | MapRoadPainter.INK;
        Gui.drawRect(x, cy - 3, x + 12, cy + 4, kind == RoadLegend.BRIDGE ? 0xFF6E6A5E : 0xFFCDB993);
        switch (kind) {
            case ROAD:
                Gui.drawRect(x + 1, cy - 1, x + 11, cy + 2, ink);
                Gui.drawRect(x + 1, cy, x + 11, cy + 1, 0xFF000000 | MapRoadPainter.PALE);
                break;
            case TRAIL:
                for (int dx = 1; dx < 11; dx += 3) Gui.drawRect(x + dx, cy, x + dx + 2, cy + 1, ink);
                break;
            case BRIDGE:
                Gui.drawRect(x + 3, cy - 1, x + 9, cy + 2, ink);
                Gui.drawRect(x + 3, cy, x + 9, cy + 1, 0xFF000000 | MapRoadPainter.DECK);
                Gui.drawRect(x + 2, cy - 2, x + 3, cy + 3, ink);
                Gui.drawRect(x + 9, cy - 2, x + 10, cy + 3, ink);
                break;
        }
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
    }

    private int legendColor(Marker.MarkerType t) {
        switch (t.legendTint) {
            case OWNER:      return 0xFF000000 | legendOwnerColor;
            case OWNER_DARK: return 0xFF000000 | WorldMapMarkerFactory.darken(legendOwnerColor, 0.55f);
            case FIXED:
            default:         return t.legendArgb;
        }
    }

}
