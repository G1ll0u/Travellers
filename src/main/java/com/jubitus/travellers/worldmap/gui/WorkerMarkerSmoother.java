package com.jubitus.travellers.worldmap.gui;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Client-side smoothing of unit markers. The server sends positions once every 20 ticks; samples are
 * timed in server time and a marker is drawn at {@link MapPlaybackClock} time, on a smooth curve through
 * the last received samples (cubic Hermite, velocity continuous at every sample), so direction and
 * speed never break at an update. Pure logic; the time is passed in.
 */
public final class WorkerMarkerSmoother {

    /** A jump longer than this (teleport, dimension hop, long gap) is shown at once, not glided. */
    private static final float SNAP_BLOCKS = 48f;
    private static final int KEEP = 4;
    /** Past the newest sample a marker keeps its last velocity for this long, then stops. */
    private static final long MAX_EXTRAPOLATE_MS = 500L;

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

    /** Records a position measured at {@code serverMs} (see {@link MapPlaybackClock#onUpdate}). */
    public void observe(UUID id, float x, float z, long serverMs) {
        Track t = tracks.get(id);
        if (t == null) {
            t = new Track();
            t.reset(x, z, serverMs);
            tracks.put(id, t);
            return;
        }
        float dx = x - t.x[t.n - 1];
        float dz = z - t.z[t.n - 1];
        if (dx * dx + dz * dz > SNAP_BLOCKS * SNAP_BLOCKS) {
            t.reset(x, z, serverMs);
            return;
        }
        if (serverMs <= t.t[t.n - 1]) return; // same instant: keep the earlier sample
        t.add(x, z, serverMs);
    }

    /** Forgets every unit that is no longer reported. */
    public void retainOnly(Set<UUID> present) {
        Iterator<UUID> it = tracks.keySet().iterator();
        while (it.hasNext()) {
            if (!present.contains(it.next())) it.remove();
        }
    }

    /** Drawn X at playback time {@code playMs}. */
    public float x(UUID id, float fallback, long playMs) {
        Track t = id == null ? null : tracks.get(id);
        return t == null ? fallback : t.value(0, playMs);
    }

    /** Drawn Z at playback time {@code playMs}. */
    public float z(UUID id, float fallback, long playMs) {
        Track t = id == null ? null : tracks.get(id);
        return t == null ? fallback : t.value(1, playMs);
    }

    public float x(UUID id, float fallback) {
        return x(id, fallback, MapPlaybackClock.CLIENT.now());
    }

    public float z(UUID id, float fallback) {
        return z(id, fallback, MapPlaybackClock.CLIENT.now());
    }
}
