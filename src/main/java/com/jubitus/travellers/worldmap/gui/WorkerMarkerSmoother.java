package com.jubitus.travellers.worldmap.gui;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Client-side smoothing of unit markers. The server sends positions about once a second; a marker
 * is drawn {@link #RENDER_DELAY_MS} in the past, on a smooth curve through the last received
 * samples (cubic Hermite, velocity continuous at every sample), so direction and speed never break
 * at an update. Pure logic; the clock is passed in.
 */
public final class WorkerMarkerSmoother {

    /** How far behind the newest data markers (and strike animations) are shown: a bit over one update. */
    public static final long RENDER_DELAY_MS = 1100L;
    /** A jump longer than this (teleport, dimension hop, long gap) is shown at once, not glided. */
    private static final float SNAP_BLOCKS = 48f;
    private static final int KEEP = 4;
    /** Past the newest sample (late update) a marker keeps its last velocity for this long, then stops. */
    private static final long MAX_EXTRAPOLATE_MS = 500L;
    /** How far before its arrival a sample may be dated: well under the render delay, so it is never already drawn past. */
    private static final long MAX_BEHIND_MS = 400L;
    /** How far after its arrival a sample may be dated (after a late update); the lead then shrinks slowly. */
    private static final long MAX_AHEAD_MS = RENDER_DELAY_MS;
    /** Share of the time between two updates by which a lead shrinks: the marker runs at most this much faster. */
    private static final float CATCH_UP_SHARE = 0.1f;

    private static final class Track {
        final float[] x = new float[KEEP];
        final float[] z = new float[KEEP];
        final long[] t = new long[KEEP];
        int n;

        void add(float px, float pz, long time) {
            if (n == KEEP) {
                System.arraycopy(x, 1, x, 0, KEEP - 1);
                System.arraycopy(z, 1, z, 0, KEEP - 1);
                System.arraycopy(t, 1, t, 0, KEEP - 1);
                n--;
            }
            x[n] = px;
            z[n] = pz;
            t[n] = time;
            n++;
        }

        void reset(float px, float pz, long time) {
            n = 0;
            add(px, pz, time);
        }

        /** Position on the curve at {@code at}; axis 0 = x, 1 = z. */
        float value(int axis, long at) {
            float[] v = axis == 0 ? x : z;
            if (n == 1 || at <= t[0]) return v[0];
            int last = n - 1;
            if (at >= t[last]) {
                float slope = (v[last] - v[last - 1]) / (t[last] - t[last - 1]);
                return v[last] + slope * Math.min(at - t[last], MAX_EXTRAPOLATE_MS);
            }
            int i = last - 1;
            while (i > 0 && t[i] > at) i--;
            float dt = t[i + 1] - t[i];
            float u = (at - t[i]) / dt;
            float segment = v[i + 1] - v[i];
            // Tangents (scaled by dt): the previous segment's slope at the start, this one's at the end.
            float m0 = i > 0 ? (v[i] - v[i - 1]) / (t[i] - t[i - 1]) * dt : segment;
            float m1 = segment;
            float u2 = u * u, u3 = u2 * u;
            return (2 * u3 - 3 * u2 + 1) * v[i] + (u3 - 2 * u2 + u) * m0
                    + (-2 * u3 + 3 * u2) * v[i + 1] + (u3 - u2) * m1;
        }
    }

    private final Map<UUID, Track> tracks = new HashMap<>();
    private long lastServerTick = -1L;
    private long lastSampleMs;

    /**
     * Time to give the samples of an update measured at {@code serverTick} and received at {@code arrivalMs}:
     * spaced by real game time since the previous update, so late or bunched packets never squeeze a move.
     */
    public long sampleTime(long serverTick, long arrivalMs) {
        long ms;
        if (lastServerTick < 0 || serverTick < lastServerTick) {
            ms = arrivalMs;
        } else {
            long gap = (serverTick - lastServerTick) * 50L;
            ms = lastSampleMs + gap;
            if (ms > arrivalMs) ms -= Math.min(ms - arrivalMs, (long) (gap * CATCH_UP_SHARE));
            ms = Math.max(arrivalMs - MAX_BEHIND_MS, Math.min(arrivalMs + MAX_AHEAD_MS, ms));
        }
        lastServerTick = serverTick;
        lastSampleMs = ms;
        return ms;
    }

    /** Records a freshly received position for a unit. */
    public void observe(UUID id, float x, float z, long nowMs) {
        Track t = tracks.get(id);
        if (t == null) {
            t = new Track();
            t.reset(x, z, nowMs);
            tracks.put(id, t);
            return;
        }
        float dx = x - t.x[t.n - 1];
        float dz = z - t.z[t.n - 1];
        if (dx * dx + dz * dz > SNAP_BLOCKS * SNAP_BLOCKS) {
            t.reset(x, z, nowMs);
            return;
        }
        if (nowMs <= t.t[t.n - 1]) return; // same instant: keep the earlier sample
        t.add(x, z, nowMs);
    }

    /** Forgets every unit that is no longer reported. */
    public void retainOnly(Set<UUID> present) {
        Iterator<UUID> it = tracks.keySet().iterator();
        while (it.hasNext()) {
            if (!present.contains(it.next())) it.remove();
        }
    }

    public float x(UUID id, float fallback, long nowMs) {
        Track t = id == null ? null : tracks.get(id);
        return t == null ? fallback : t.value(0, nowMs - RENDER_DELAY_MS);
    }

    public float z(UUID id, float fallback, long nowMs) {
        Track t = id == null ? null : tracks.get(id);
        return t == null ? fallback : t.value(1, nowMs - RENDER_DELAY_MS);
    }

    public float x(UUID id, float fallback) {
        return x(id, fallback, System.currentTimeMillis());
    }

    public float z(UUID id, float fallback) {
        return z(id, fallback, System.currentTimeMillis());
    }
}
