package com.jubitus.travellers.worldmap.client;

import com.jubitus.travellers.worldmap.pyramid.MapTileGenerator;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;
import java.util.List;

/**
 * Paints Ultreïa roads into a tile's pixels before the fog, so they look drawn on the map at every
 * zoom: real width up close, a minimum cartographic width far away. Village roads are a pale band
 * between dark edges, lone trails a dotted line, bridges a darker deck with a bar at each end.
 * Each pass collects coverage over all its roads and blends once, so junctions never darken twice.
 * Client thread only.
 */
@SideOnly(Side.CLIENT)
public final class MapRoadPainter {

    private static final int PX = MapTileGenerator.TILE_PX;

    public static final int INK = 0x3E2814;
    public static final int PALE = 0xEEE0BA;
    public static final int DECK = 0x845C36;

    private static final float LONE_DASH_ON = 1.4f;
    private static final float LONE_DASH_OFF = 2.2f;
    // At one block per pixel the map is seen magnified, so the dots are drawn longer.
    private static final float LONE_DASH_ON_CLOSE = 3f;
    private static final float LONE_DASH_OFF_CLOSE = 2.6f;
    // Below this many pixels a village road keeps a one-pixel pale middle with thinner, lighter edges.
    private static final float VILLAGE_NARROW_PX = 1.6f;

    private static final float[] COV = new float[PX * PX];
    private static float dashOn, dashOff;
    private static int covMinX, covMinZ, covMaxX, covMaxZ;
    private static final List<ClientMapRoads.Road> VISIBLE = new ArrayList<>();

    private MapRoadPainter() {}

    public static void paint(int[] pixels, byte[] tones, int level, int tileX, int tileZ) {
        List<ClientMapRoads.Road> roads = ClientMapRoads.roads();
        if (roads.isEmpty()) return;
        float bpp = 1 << level;
        int tileBlocks = PX << level;
        int x0 = tileX * tileBlocks, z0 = tileZ * tileBlocks;

        VISIBLE.clear();
        for (ClientMapRoads.Road r : roads) {
            int margin = r.width + (int) Math.ceil(4 * bpp);
            if (r.maxX + margin < x0 || r.minX - margin > x0 + tileBlocks) continue;
            if (r.maxZ + margin < z0 || r.minZ - margin > z0 + tileBlocks) continue;
            VISIBLE.add(r);
        }
        if (VISIBLE.isEmpty()) return;
        resetBounds();

        // Lone trails fade a little once the map is zoomed far out.
        dashOn = level == 0 ? LONE_DASH_ON_CLOSE : LONE_DASH_ON;
        dashOff = level == 0 ? LONE_DASH_OFF_CLOSE : LONE_DASH_OFF;
        float loneAlpha = level <= 2 ? 0.85f : level == 3 ? 0.65f : 0.5f;
        for (ClientMapRoads.Road r : VISIBLE) {
            if (r.bridge || r.kind != ClientMapRoads.KIND_LONE) continue;
            stroke(r, Math.max(1.7f, r.width / bpp * 0.75f) / 2f, true, bpp, x0, z0);
        }
        blend(pixels, tones, INK, loneAlpha);

        // The pale middle never disappears, so a road keeps its colour at every zoom.
        for (ClientMapRoads.Road r : VISIBLE) {
            if (r.bridge || r.kind != ClientMapRoads.KIND_VILLAGE) continue;
            float core = r.width / bpp;
            stroke(r, (villageFillPx(core) + (core >= VILLAGE_NARROW_PX ? 1.6f : 1f)) / 2f, false, bpp, x0, z0);
        }
        blend(pixels, tones, INK, level <= 1 ? 0.85f : 0.6f);
        for (ClientMapRoads.Road r : VISIBLE) {
            if (r.bridge || r.kind != ClientMapRoads.KIND_VILLAGE) continue;
            stroke(r, villageFillPx(r.width / bpp) / 2f, false, bpp, x0, z0);
        }
        blend(pixels, tones, PALE, 0.95f);

        for (ClientMapRoads.Road r : VISIBLE) {
            if (r.bridge) stroke(r, bridgeOuterPx(r, bpp) / 2f, false, bpp, x0, z0);
        }
        blend(pixels, tones, INK, 0.95f);
        for (ClientMapRoads.Road r : VISIBLE) {
            if (r.bridge) stroke(r, Math.max(0.9f, r.width / bpp) / 2f, false, bpp, x0, z0);
        }
        blend(pixels, tones, DECK, 1f);
        for (ClientMapRoads.Road r : VISIBLE) {
            if (r.bridge) endBars(r, bpp, x0, z0);
        }
        blend(pixels, tones, INK, 1f);
        VISIBLE.clear();
    }

    private static float villageFillPx(float core) {
        return Math.max(1f, core - 0.2f);
    }

    private static float bridgeOuterPx(ClientMapRoads.Road r, float bpp) {
        return Math.max(2.4f, r.width / bpp + 1.8f);
    }

    // Bars across the deck at both ends, reaching a little past its dark edges.
    private static void endBars(ClientMapRoads.Road r, float bpp, int x0, int z0) {
        int n = r.xs.length;
        if (n < 2) return;
        float reach = bridgeOuterPx(r, bpp) / 2f + Math.max(1.2f, 2f / bpp);
        float half = Math.max(0.6f, 1f / bpp);
        endBar(r, 0, Math.min(2, n - 1), reach, half, bpp, x0, z0);
        endBar(r, n - 1, Math.max(0, n - 3), reach, half, bpp, x0, z0);
    }

    private static void endBar(ClientMapRoads.Road r, int at, int toward, float reach, float half, float bpp, int x0, int z0) {
        float ax = texel(r.xs[at], x0, bpp), az = texel(r.zs[at], z0, bpp);
        float dx = texel(r.xs[toward], x0, bpp) - ax, dz = texel(r.zs[toward], z0, bpp) - az;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len <= 0f) return;
        float nx = -dz / len * reach, nz = dx / len * reach;
        segment(ax - nx, az - nz, ax + nx, az + nz, 0f, 0f, half, false);
    }

    private static float texel(int block, int origin, float bpp) {
        return (block + 0.5f - origin) / bpp;
    }

    // Points closer than half a pixel to the last kept one are skipped: far zooms hold many per pixel.
    private static void stroke(ClientMapRoads.Road r, float half, boolean dashed, float bpp, int x0, int z0) {
        int n = r.xs.length;
        float px = texel(r.xs[0], x0, bpp), pz = texel(r.zs[0], z0, bpp), ps = r.arc[0] / bpp;
        for (int i = 1; i < n; i++) {
            float qx = texel(r.xs[i], x0, bpp), qz = texel(r.zs[i], z0, bpp);
            float dx = qx - px, dz = qz - pz;
            if (i < n - 1 && dx * dx + dz * dz < 0.25f) continue;
            float qs = r.arc[i] / bpp;
            segment(px, pz, qx, qz, ps, qs, half, dashed);
            px = qx;
            pz = qz;
            ps = qs;
        }
    }

    private static void segment(float ax, float az, float bx, float bz, float sa, float sb, float half, boolean dashed) {
        float r = half + 1f;
        int i0 = Math.max(0, (int) Math.floor(Math.min(ax, bx) - r));
        int i1 = Math.min(PX - 1, (int) Math.ceil(Math.max(ax, bx) + r));
        int j0 = Math.max(0, (int) Math.floor(Math.min(az, bz) - r));
        int j1 = Math.min(PX - 1, (int) Math.ceil(Math.max(az, bz) + r));
        if (i0 > i1 || j0 > j1) return;
        float dx = bx - ax, dz = bz - az, len2 = dx * dx + dz * dz;
        float period = dashOn + dashOff;
        for (int j = j0; j <= j1; j++) {
            float cz = j + 0.5f;
            for (int i = i0; i <= i1; i++) {
                float cx = i + 0.5f;
                float t = len2 > 0f ? ((cx - ax) * dx + (cz - az) * dz) / len2 : 0f;
                if (t < 0f) t = 0f;
                else if (t > 1f) t = 1f;
                float ox = ax + dx * t - cx, oz = az + dz * t - cz;
                float c = half + 0.5f - (float) Math.sqrt(ox * ox + oz * oz);
                if (c <= 0f) continue;
                if (c > 1f) c = 1f;
                if (dashed) {
                    float phase = (sa + (sb - sa) * t) % period;
                    if (phase >= dashOn) continue;
                    c *= Math.min(1f, Math.min(phase, dashOn - phase) + 0.5f);
                }
                int o = j * PX + i;
                if (c > COV[o]) COV[o] = c;
            }
        }
        if (i0 < covMinX) covMinX = i0;
        if (j0 < covMinZ) covMinZ = j0;
        if (i1 > covMaxX) covMaxX = i1;
        if (j1 > covMaxZ) covMaxZ = j1;
    }

    // Blends the collected coverage in one colour, then clears it. Unknown pixels stay unknown.
    private static void blend(int[] pixels, byte[] tones, int rgb, float alpha) {
        int cr = (rgb >> 16) & 0xFF, cg = (rgb >> 8) & 0xFF, cb = rgb & 0xFF;
        for (int j = covMinZ; j <= covMaxZ; j++) {
            for (int i = covMinX; i <= covMaxX; i++) {
                int o = j * PX + i;
                float c = COV[o];
                if (c <= 0f) continue;
                COV[o] = 0f;
                if (tones[o] == 0) continue;
                float a = c * alpha;
                int p = pixels[o];
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                r += Math.round((cr - r) * a);
                g += Math.round((cg - g) * a);
                b += Math.round((cb - b) * a);
                pixels[o] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        resetBounds();
    }

    private static void resetBounds() {
        covMinX = covMinZ = PX;
        covMaxX = covMaxZ = -1;
    }
}
