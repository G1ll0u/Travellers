package com.jubitus.travellers.worldmap.gui;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Client-side smoothing of worker markers: the server sends positions about once a second, so each
 * marker glides in a straight line from where it is drawn to the newly received spot, over the time
 * the last update took to arrive. Pure logic; the clock is passed in.
 */
public final class WorkerMarkerSmoother {

    private static final long DEFAULT_INTERVAL_MS = 1000L;
    private static final long MIN_INTERVAL_MS = 400L;
    private static final long MAX_INTERVAL_MS = 2000L;
    /** A jump longer than this (teleport, dimension hop, long gap) is shown at once, not glided. */
    private static final float SNAP_BLOCKS = 48f;

    private static final class Track {
        float fromX, fromZ, toX, toZ;
        long startMs;
        long durationMs = DEFAULT_INTERVAL_MS;
        long lastUpdateMs;

        float x(long nowMs) {
            return fromX + (toX - fromX) * progress(nowMs);
        }

        float z(long nowMs) {
            return fromZ + (toZ - fromZ) * progress(nowMs);
        }

        private float progress(long nowMs) {
            if (durationMs <= 0) return 1f;
            float t = (nowMs - startMs) / (float) durationMs;
            return t < 0f ? 0f : Math.min(t, 1f);
        }
    }

    private final Map<UUID, Track> tracks = new HashMap<>();

    /** Records a freshly received position for a worker. */
    public void observe(UUID id, float x, float z, long nowMs) {
        Track t = tracks.get(id);
        if (t == null) {
            t = new Track();
            t.fromX = t.toX = x;
            t.fromZ = t.toZ = z;
            t.startMs = t.lastUpdateMs = nowMs;
            tracks.put(id, t);
            return;
        }
        float curX = t.x(nowMs);
        float curZ = t.z(nowMs);
        float dx = x - curX;
        float dz = z - curZ;
        long interval = nowMs - t.lastUpdateMs;
        t.lastUpdateMs = nowMs;
        t.startMs = nowMs;
        t.toX = x;
        t.toZ = z;
        if (dx * dx + dz * dz > SNAP_BLOCKS * SNAP_BLOCKS) {
            t.fromX = x;
            t.fromZ = z;
            t.durationMs = DEFAULT_INTERVAL_MS;
            return;
        }
        t.fromX = curX;
        t.fromZ = curZ;
        t.durationMs = Math.max(MIN_INTERVAL_MS, Math.min(MAX_INTERVAL_MS, interval));
    }

    /** Forgets every worker that is no longer reported. */
    public void retainOnly(Set<UUID> present) {
        Iterator<UUID> it = tracks.keySet().iterator();
        while (it.hasNext()) {
            if (!present.contains(it.next())) it.remove();
        }
    }

    public float x(UUID id, float fallback, long nowMs) {
        Track t = id == null ? null : tracks.get(id);
        return t == null ? fallback : t.x(nowMs);
    }

    public float z(UUID id, float fallback, long nowMs) {
        Track t = id == null ? null : tracks.get(id);
        return t == null ? fallback : t.z(nowMs);
    }

    public float x(UUID id, float fallback) {
        return x(id, fallback, System.currentTimeMillis());
    }

    public float z(UUID id, float fallback) {
        return z(id, fallback, System.currentTimeMillis());
    }
}
