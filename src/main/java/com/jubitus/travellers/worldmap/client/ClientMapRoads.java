package com.jubitus.travellers.worldmap.client;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The Ultreïa road network as last received from the server, for {@link MapRoadPainter}. Client thread only. */
@SideOnly(Side.CLIENT)
public final class ClientMapRoads {

    public static final int KIND_VILLAGE = 0;
    public static final int KIND_LONE = 1;

    public static final class Road {
        public final int kind;
        public final int width;
        public final boolean bridge;
        public final int[] xs, zs;
        /** Distance along the road from its first point, in blocks. */
        public final float[] arc;
        public final int minX, minZ, maxX, maxZ;

        Road(int[] seg) {
            kind = seg[0] == KIND_LONE ? KIND_LONE : KIND_VILLAGE;
            width = Math.max(1, seg[1]);
            bridge = seg[2] != 0;
            int n = (seg.length - 3) / 2;
            xs = new int[n];
            zs = new int[n];
            arc = new float[n];
            int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
            for (int i = 0; i < n; i++) {
                xs[i] = seg[3 + i * 2];
                zs[i] = seg[4 + i * 2];
                if (i > 0) arc[i] = arc[i - 1] + (float) Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
                x0 = Math.min(x0, xs[i]);
                z0 = Math.min(z0, zs[i]);
                x1 = Math.max(x1, xs[i]);
                z1 = Math.max(z1, zs[i]);
            }
            minX = x0;
            minZ = z0;
            maxX = x1;
            maxZ = z1;
        }
    }

    private static List<Road> roads = Collections.emptyList();
    private static List<Road> pending;
    private static long pendingRevision;
    private static boolean hasVillage, hasLone, hasBridge;

    private ClientMapRoads() {}

    public static List<Road> roads() {
        return roads;
    }

    public static boolean hasVillageRoads() { return hasVillage; }
    public static boolean hasLoneTrails() { return hasLone; }
    public static boolean hasBridges() { return hasBridge; }

    public static void accept(long revision, int part, int parts, List<int[]> segments) {
        if (part == 0) {
            pending = new ArrayList<>();
            pendingRevision = revision;
        }
        if (pending == null || revision != pendingRevision) return;
        for (int[] seg : segments) {
            if (seg.length >= 7) pending.add(new Road(seg));
        }
        if (part == parts - 1) {
            roads = pending;
            pending = null;
            hasVillage = hasLone = hasBridge = false;
            for (Road r : roads) {
                if (r.bridge) hasBridge = true;
                else if (r.kind == KIND_LONE) hasLone = true;
                else hasVillage = true;
            }
            ClientTileTextures.invalidateAll();
        }
    }

    public static void clear() {
        roads = Collections.emptyList();
        pending = null;
        hasVillage = hasLone = hasBridge = false;
    }
}
