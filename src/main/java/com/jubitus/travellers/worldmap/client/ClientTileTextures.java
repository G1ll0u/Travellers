package com.jubitus.travellers.worldmap.client;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.worldmap.pyramid.MapBiomeFamily;
import com.jubitus.travellers.worldmap.pyramid.MapTileGenerator;
import com.jubitus.travellers.worldmap.pyramid.PyramidDirtyPropagator;
import com.jubitus.travellers.worldmap.pyramid.TileKey;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * One GPU texture per cached map tile (level + tile), colored and fogged once when built, so drawing
 * the map is only textured quads. Rebuilt lazily when its tile data or the fog under it changes,
 * under a per-frame build budget; least recently drawn textures are freed past the cache size.
 * Client thread only.
 */
@SideOnly(Side.CLIENT)
public final class ClientTileTextures {

    public static final int FOG_RGB = 0x0B0B0B;
    private static final int UNKNOWN_ARGB = 0xFF2A2A2A;
    private static final int PX = MapTileGenerator.TILE_PX;

    private static final class Entry {
        int glId = -1;
        boolean dirty = true;
        long lastUsedFrame;
    }

    @SuppressWarnings("unchecked")
    private static final Long2ObjectOpenHashMap<Entry>[] LEVELS = new Long2ObjectOpenHashMap[PyramidDirtyPropagator.MAX_LEVEL + 1];
    private static int entryCount;
    private static long frame;
    private static int buildsLeft;

    private static final int[] PIXELS = new int[PX * PX];
    private static final byte[] LATTICE = new byte[(PX + 2) * (PX + 2)];
    private static int[] toneLut;
    // Biome tint per cell, with a one-cell border read from the neighbour tiles.
    private static final int TINT_STRIDE = MapTileGenerator.CELLS + 2;
    private static final float[] CELL_TINT = new float[TINT_STRIDE * TINT_STRIDE * 3];
    private static final float[][] BIOME_TINTS = new float[MapBiomeFamily.values().length][];
    private static int builtWithBiomeTint = -1;

    static {
        for (int i = 0; i < LEVELS.length; i++) LEVELS[i] = new Long2ObjectOpenHashMap<>();
        for (MapBiomeFamily f : MapBiomeFamily.values()) BIOME_TINTS[f.ordinal()] = tintOf(f);
    }

    private ClientTileTextures() {}

    /** Call once per map draw, before any {@link #textureFor}. */
    public static void beginFrame() {
        frame++;
        buildsLeft = TravellersConfig.WORLDMAP.mapTextureBuildsPerFrame;
        int tint = TravellersConfig.WORLDMAP.mapBiomeTint;
        if (tint != builtWithBiomeTint) {
            if (builtWithBiomeTint >= 0) invalidateAll();
            builtWithBiomeTint = tint;
        }
    }

    /** True if this tile already has a texture to draw (possibly stale). */
    public static boolean hasTexture(int level, long key) {
        return LEVELS[level].containsKey(key);
    }

    /** GL texture id of a tile, building it if needed and allowed by the frame budget; -1 if nothing to draw. */
    public static int textureFor(int level, long key) {
        Entry e = LEVELS[level].get(key);
        if (e == null || e.dirty) {
            byte[] data = buildsLeft > 0 ? ClientTileCache.get(level, key) : null;
            if (data != null && data.length == MapTileGenerator.TILE_BYTES) {
                if (e == null) {
                    e = new Entry();
                    e.glId = TextureUtil.glGenTextures();
                    TextureUtil.allocateTexture(e.glId, PX, PX);
                    LEVELS[level].put(key, e);
                    entryCount++;
                }
                build(level, TileKey.unpackX(key), TileKey.unpackZ(key), data);
                TextureUtil.uploadTexture(e.glId, PIXELS, PX, PX);
                GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                e.dirty = false;
                e.lastUsedFrame = frame;
                buildsLeft--;
                if (entryCount > TravellersConfig.WORLDMAP.mapClientTextureCacheSize) evictOne();
            }
        }
        if (e == null) return -1;
        e.lastUsedFrame = frame;
        return e.glId;
    }

    public static void invalidate(int level, int tileX, int tileZ) {
        if (level < 0 || level >= LEVELS.length) return;
        Entry e = LEVELS[level].get(TileKey.pack(tileX, tileZ));
        if (e != null) e.dirty = true;
    }

    /** A tile's data arrived or changed: rebuild it, and its built neighbours whose biome blend reads its edge. */
    public static void invalidateWithNeighbours(int level, int tileX, int tileZ) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) invalidate(level, tileX + dx, tileZ + dz);
        }
    }

    /** Marks dirty every texture, at every level, touching this block rectangle (max exclusive). */
    public static void invalidateBlockArea(int minX, int minZ, int maxX, int maxZ) {
        for (int level = 0; level < LEVELS.length; level++) {
            if (LEVELS[level].isEmpty()) continue;
            int tileBlocks = PX << level;
            int tx0 = Math.floorDiv(minX, tileBlocks), tx1 = Math.floorDiv(maxX - 1, tileBlocks);
            int tz0 = Math.floorDiv(minZ, tileBlocks), tz1 = Math.floorDiv(maxZ - 1, tileBlocks);
            for (int tz = tz0; tz <= tz1; tz++) {
                for (int tx = tx0; tx <= tx1; tx++) {
                    Entry e = LEVELS[level].get(TileKey.pack(tx, tz));
                    if (e != null) e.dirty = true;
                }
            }
        }
    }

    public static void invalidateAll() {
        for (Long2ObjectOpenHashMap<Entry> m : LEVELS) {
            for (Entry e : m.values()) e.dirty = true;
        }
        toneLut = null;
    }

    public static void clear() {
        for (Long2ObjectOpenHashMap<Entry> m : LEVELS) {
            for (Entry e : m.values()) TextureUtil.deleteTexture(e.glId);
            m.clear();
        }
        entryCount = 0;
    }

    // Frees the least recently drawn texture, never one drawn this frame or the last (still on screen): the cap may be exceeded meanwhile.
    private static void evictOne() {
        int bestLevel = -1;
        long bestKey = 0, bestFrame = frame - 1;
        for (int level = 0; level < LEVELS.length; level++) {
            for (Long2ObjectMap.Entry<Entry> me : LEVELS[level].long2ObjectEntrySet()) {
                if (me.getValue().lastUsedFrame < bestFrame) {
                    bestFrame = me.getValue().lastUsedFrame;
                    bestLevel = level;
                    bestKey = me.getLongKey();
                }
            }
        }
        if (bestLevel < 0) return;
        TextureUtil.deleteTexture(LEVELS[bestLevel].remove(bestKey).glId);
        entryCount--;
    }

    // ── Tile colouring ─────────────────────────────────────────────────────────

    private static void build(int level, int tileX, int tileZ, byte[] tones) {
        int[] lut = lut();
        for (int i = 0; i < PIXELS.length; i++) PIXELS[i] = lut[tones[i] & 0xFF];
        tintBiomes(level, tileX, tileZ, tones);
        MapRoadPainter.paint(PIXELS, tones, level, tileX, tileZ);
        if (ClientMapDiscovery.isFullMap()) return;
        int pb = 1 << level;
        int tileBlocks = PX * pb;
        int x0 = tileX * tileBlocks;
        int z0 = tileZ * tileBlocks;

        // Discovery sampled on a lattice of cell centres (chunks, or pixels when a pixel is bigger than a chunk),
        // with a one-cell border so the edge blends across tile seams.
        int d = Math.max(16, pb);
        int n = tileBlocks / d;
        int stride = n + 2;
        for (int j = 0; j < stride; j++) {
            int wz = z0 + (j - 1) * d + d / 2;
            for (int i = 0; i < stride; i++) {
                int wx = x0 + (i - 1) * d + d / 2;
                LATTICE[j * stride + i] = (byte) (ClientMapDiscovery.isChunkDiscovered(wx >> 4, wz >> 4) ? 1 : 0);
            }
        }

        float soft = TravellersConfig.WORLDMAP.mapFogEdgeSoftness;
        float noiseAmp = TravellersConfig.WORLDMAP.mapFogEdgeNoise;
        float cellPerPixel = pb / (float) d;
        for (int pz = 0; pz < PX; pz++) {
            float gz = (pz + 0.5f) * cellPerPixel + 0.5f;
            int iz = (int) gz;
            float fz = gz - iz;
            for (int px = 0; px < PX; px++) {
                float gx = (px + 0.5f) * cellPerPixel + 0.5f;
                int ix = (int) gx;
                float fx = gx - ix;
                int o = iz * stride + ix;
                int a00 = LATTICE[o], a10 = LATTICE[o + 1], a01 = LATTICE[o + stride], a11 = LATTICE[o + stride + 1];
                int terrain = PIXELS[pz * PX + px];
                int sum = a00 + a10 + a01 + a11;
                if (sum == 4) {
                    PIXELS[pz * PX + px] = terrain;
                    continue;
                }
                if (sum == 0) {
                    PIXELS[pz * PX + px] = 0xFF000000 | FOG_RGB;
                    continue;
                }
                float v = (a00 * (1 - fx) + a10 * fx) * (1 - fz) + (a01 * (1 - fx) + a11 * fx) * fz;
                float wx = x0 + (px + 0.5f) * pb;
                float wz = z0 + (pz + 0.5f) * pb;
                // Noise only bends the edge band; fully clear or fully fogged ground stays exact.
                float e = v + (edgeNoise(wx, wz, pb) - 0.5f) * noiseAmp * 4f * v * (1 - v);
                float alpha = soft <= 0f ? (e >= 0.5f ? 1f : 0f) : smoothstep(0.5f - soft, 0.5f + soft, e);
                PIXELS[pz * PX + px] = blend(FOG_RGB, terrain, alpha);
            }
        }
    }

    // Each land pixel takes the bilinear blend of its 4 nearest cells' tints, across tile edges too.
    private static void tintBiomes(int level, int tileX, int tileZ, byte[] tile) {
        float strength = builtWithBiomeTint / 100f;
        if (strength <= 0f) return;
        boolean any = false;
        for (int j = 0; j < TINT_STRIDE; j++) {
            for (int i = 0; i < TINT_STRIDE; i++) {
                float[] f = BIOME_TINTS[cellFamily(level, tileX, tileZ, tile, i - 1, j - 1)];
                int o = (j * TINT_STRIDE + i) * 3;
                for (int k = 0; k < 3; k++) {
                    CELL_TINT[o + k] = 1f + (f[k] - 1f) * strength;
                    any |= f[k] != 1f;
                }
            }
        }
        if (!any) return;
        float cellPx = MapTileGenerator.CELL_PX;
        for (int pz = 0; pz < PX; pz++) {
            float gz = (pz + 0.5f) / cellPx + 0.5f;
            int iz = (int) gz;
            float fz = gz - iz;
            for (int px = 0; px < PX; px++) {
                int i = pz * PX + px;
                int tone = tile[i] & 0xFF;
                if (tone == 0 || tone <= RenderSettings.waterThreshold) continue;
                float gx = (px + 0.5f) / cellPx + 0.5f;
                int ix = (int) gx;
                float fx = gx - ix;
                int a = (iz * TINT_STRIDE + ix) * 3, b = a + 3, c = a + TINT_STRIDE * 3, d = c + 3;
                int rgb = PIXELS[i];
                int out = 0xFF000000;
                for (int k = 0, shift = 16; k < 3; k++, shift -= 8) {
                    float t = (CELL_TINT[a + k] * (1 - fx) + CELL_TINT[b + k] * fx) * (1 - fz)
                            + (CELL_TINT[c + k] * (1 - fx) + CELL_TINT[d + k] * fx) * fz;
                    int v = (int) (((rgb >> shift) & 0xFF) * t);
                    out |= (v > 255 ? 255 : v) << shift;
                }
                PIXELS[i] = out;
            }
        }
    }

    // Family of a cell, possibly one cell outside this tile (read from the neighbour, else this tile's edge).
    private static int cellFamily(int level, int tileX, int tileZ, byte[] tile, int cx, int cz) {
        int cells = MapTileGenerator.CELLS;
        int dx = cx < 0 ? -1 : cx >= cells ? 1 : 0;
        int dz = cz < 0 ? -1 : cz >= cells ? 1 : 0;
        byte[] src = tile;
        if (dx != 0 || dz != 0) {
            byte[] n = ClientTileCache.get(level, TileKey.pack(tileX + dx, tileZ + dz));
            if (n != null && n.length == MapTileGenerator.TILE_BYTES) {
                src = n;
                cx -= dx * cells;
                cz -= dz * cells;
            }
        }
        cx = Math.max(0, Math.min(cells - 1, cx));
        cz = Math.max(0, Math.min(cells - 1, cz));
        return MapBiomeFamily.byId(src[MapTileGenerator.cellIndex(cx, cz)] & 0xFF).ordinal();
    }

    // RGB multipliers at full strength; light ground stays readable, dark relief keeps its shading.
    private static float[] tintOf(MapBiomeFamily f) {
        switch (f) {
            case PLAINS:   return new float[]{0.92f, 1.06f, 0.80f};
            case FOREST:   return new float[]{0.78f, 1.02f, 0.70f};
            case CONIFER:  return new float[]{0.72f, 0.95f, 0.80f};
            case JUNGLE:   return new float[]{0.70f, 1.08f, 0.62f};
            case SWAMP:    return new float[]{0.78f, 0.90f, 0.70f};
            case DESERT:   return new float[]{1.08f, 1.02f, 0.82f};
            case SAVANNA:  return new float[]{1.05f, 1.00f, 0.70f};
            case MESA:     return new float[]{1.15f, 0.85f, 0.70f};
            case SNOW:     return new float[]{0.98f, 1.04f, 1.22f};
            case MOUNTAIN: return new float[]{0.92f, 0.95f, 1.02f};
            case MUSHROOM: return new float[]{1.05f, 0.88f, 1.05f};
            default:       return new float[]{1f, 1f, 1f};
        }
    }

    private static int blend(int fogRgb, int argb, float a) {
        int r = (int) (((fogRgb >> 16) & 0xFF) + ((((argb >> 16) & 0xFF) - ((fogRgb >> 16) & 0xFF)) * a));
        int g = (int) (((fogRgb >> 8) & 0xFF) + ((((argb >> 8) & 0xFF) - ((fogRgb >> 8) & 0xFF)) * a));
        int b = (int) ((fogRgb & 0xFF) + (((argb & 0xFF) - (fogRgb & 0xFF)) * a));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static float smoothstep(float lo, float hi, float x) {
        float t = (x - lo) / (hi - lo);
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        return t * t * (3f - 2f * t);
    }

    // Two octaves of value noise in world space; the fine one is dropped when a pixel is too coarse to show it.
    private static float edgeNoise(float wx, float wz, int pixelBlocks) {
        float n = valueNoise(wx / 40f, wz / 40f) * 0.65f;
        return pixelBlocks <= 4 ? n + valueNoise(wx / 11f, wz / 11f) * 0.35f : n + 0.175f;
    }

    private static float valueNoise(float x, float z) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        float fx = x - ix, fz = z - iz;
        fx = fx * fx * (3f - 2f * fx);
        fz = fz * fz * (3f - 2f * fz);
        float a = hash(ix, iz), b = hash(ix + 1, iz), c = hash(ix, iz + 1), d = hash(ix + 1, iz + 1);
        return (a + (b - a) * fx) * (1 - fz) + (c + (d - c) * fx) * fz;
    }

    private static float hash(int x, int z) {
        int h = x * 374761393 + z * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / 16777216f;
    }

    // ── Tone -> colour ─────────────────────────────────────────────────────────

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

    private static int[] lut() {
        if (toneLut == null) {
            int[] lut = new int[256];
            lut[0] = UNKNOWN_ARGB;
            for (int t = 1; t < 256; t++) lut[t] = 0xFF000000 | toneToRgb(t);
            toneLut = lut;
        }
        return toneLut;
    }

    private static int toneToRgb(int tone) {
        float t = (tone - RenderSettings.toneMin) / (float) (RenderSettings.toneMax - RenderSettings.toneMin);
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

        int r = (int) (RenderSettings.shadowR + t * dr);
        int g = (int) (RenderSettings.shadowG + t * dg);
        int b = (int) (RenderSettings.shadowB + t * db);

        if (tone <= RenderSettings.waterThreshold) {
            if (t < RenderSettings.waterLift) t = RenderSettings.waterLift;
            r = (int) (RenderSettings.shadowR + t * dr);
            g = (int) (RenderSettings.shadowG + t * dg);
            b = (int) (RenderSettings.shadowB + t * db);

            float wf = (RenderSettings.waterThreshold - tone) / (float) Math.max(1, RenderSettings.waterThreshold - 40);
            if (wf > 1f) wf = 1f;
            float ws = RenderSettings.waterTint * wf;
            r = (int) (r * (1f - ws * 1.5f));
            g = (int) (g * (1f - ws * 0.7f));
            b = (int) (b + (255 - b) * ws);
        }

        r = r < 0 ? 0 : (r > 255 ? 255 : r);
        g = g < 0 ? 0 : (g > 255 ? 255 : g);
        b = b < 0 ? 0 : (b > 255 ? 255 : b);

        return (r << 16) | (g << 8) | b;
    }
}
