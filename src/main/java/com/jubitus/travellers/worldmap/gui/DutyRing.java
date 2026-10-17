package com.jubitus.travellers.worldmap.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Where on-duty workers standing at their zone are drawn: evenly around the zone's marker at a fixed
 * screen distance, so they stay visible over the zone icon at every zoom. Pure logic; the zoom is passed in.
 */
public final class DutyRing {

    /** A worker closer than this to its zone's centre is drawn on the ring; farther, at its real position. */
    static final double AT_ZONE_BLOCKS = 32.0;

    private static final class Slot {
        final int zoneX, zoneZ;
        final int index, count;

        Slot(int zoneX, int zoneZ, int index, int count) {
            this.zoneX = zoneX;
            this.zoneZ = zoneZ;
            this.index = index;
            this.count = count;
        }
    }

    private final Map<UUID, Slot> slots = new HashMap<>();

    private final Map<Long, List<UUID>> pending = new HashMap<>();
    private float markerPx = 8f, blocksPerPixel = 1f;

    /** Starts a new layout; add every on-duty worker standing at its zone, then call {@link #layout()}. */
    public void clear() {
        slots.clear();
        pending.clear();
    }

    public void add(UUID uuid, int zoneX, int zoneZ) {
        pending.computeIfAbsent(((long) zoneX << 32) | (zoneZ & 0xFFFFFFFFL), k -> new ArrayList<>()).add(uuid);
    }

    /** Spreads each zone's workers evenly, in a stable order so nobody swaps places between updates. */
    public void layout() {
        for (Map.Entry<Long, List<UUID>> e : pending.entrySet()) {
            int zoneX = (int) (e.getKey() >> 32);
            int zoneZ = (int) e.getKey().longValue();
            List<UUID> zone = e.getValue();
            zone.sort(null);
            for (int i = 0; i < zone.size(); i++) slots.put(zone.get(i), new Slot(zoneX, zoneZ, i, zone.size()));
        }
        pending.clear();
    }

    public boolean covers(UUID uuid) {
        return uuid != null && slots.containsKey(uuid);
    }

    /** {@code markerPx}: spacing wanted between neighbours and from the zone icon; the ring widens when crowded. */
    public void setScale(float markerPx, float blocksPerPixel) {
        this.markerPx = markerPx;
        this.blocksPerPixel = blocksPerPixel;
    }

    /** Drawn position (block X, block Z) of a covered worker. */
    public float[] position(UUID uuid) {
        Slot s = slots.get(uuid);
        float radiusPx = Math.max(markerPx * 2f, s.count * markerPx * 2f / (float) (2 * Math.PI));
        double angle = -Math.PI / 2 + s.index * 2 * Math.PI / s.count;
        float r = radiusPx * blocksPerPixel;
        return new float[]{
                s.zoneX + 0.5f + (float) Math.cos(angle) * r,
                s.zoneZ + 0.5f + (float) Math.sin(angle) * r};
    }
}
