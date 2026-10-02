package com.jubitus.travellers.pathing.itinerary;

import net.minecraft.nbt.NBTTagCompound;

/**
 * A unit's position along its {@link Itinerary}: a segment index plus the distance travelled along
 * the polyline. Never moves backwards. Allocation-free - this runs per sim step for every virtual unit.
 */
public final class ItineraryCursor {

    // Segments scanned ahead of the current one on each advance.
    static final int SCAN_SEGMENTS = 8;

    private int seg;
    private double along;
    private double offSq;

    // Output of computeCarrot, read right after the call.
    public double carrotX;
    public double carrotZ;
    // Height hint and segment kind at the carrot (the road surface on a ROAD run).
    public double carrotY;
    public byte carrotKind;

    public int segment() {
        return seg;
    }

    public double along() {
        return along;
    }

    public double offRouteDistance() {
        return Math.sqrt(offSq);
    }

    public double remaining(Itinerary it) {
        return Math.max(0.0, it.totalLength() - along);
    }

    /** Places the cursor on the nearest point of the whole polyline (new itinerary, no history). */
    public void reset(Itinerary it, double x, double z) {
        seg = 0;
        along = 0.0;
        offSq = Double.MAX_VALUE;
        project(it, x, z, 0, it.size() - 2, true);
    }

    /** Moves forward to the nearest point within the next few segments. Never backwards. */
    public void advance(Itinerary it, double x, double z) {
        int last = it.size() - 2;
        if (seg > last) seg = last;
        project(it, x, z, seg, Math.min(last, seg + SCAN_SEGMENTS), false);
    }

    private void project(Itinerary it, double px, double pz, int fromSeg, int toSeg, boolean absolute) {
        double bestD = Double.MAX_VALUE;
        double bestAlong = along;
        for (int k = fromSeg; k <= toSeg; k++) {
            double ax = it.x(k) + 0.5, az = it.z(k) + 0.5;
            double sx = it.x(k + 1) + 0.5 - ax, sz = it.z(k + 1) + 0.5 - az;
            double len2 = sx * sx + sz * sz;
            double t = len2 < 1e-9 ? 0.0 : ((px - ax) * sx + (pz - az) * sz) / len2;
            if (t < 0.0) t = 0.0;
            else if (t > 1.0) t = 1.0;
            double qx = ax + sx * t, qz = az + sz * t;
            double d = (px - qx) * (px - qx) + (pz - qz) * (pz - qz);
            if (d < bestD) {
                bestD = d;
                bestAlong = it.cum(k) + (it.cum(k + 1) - it.cum(k)) * t;
            }
        }
        along = absolute ? bestAlong : Math.max(along, bestAlong);
        seg = segmentAt(it, along, absolute ? 0 : seg);
        if (along == bestAlong) {
            offSq = bestD;
        } else {
            // Pushed back behind the cursor: off-route is measured to the cursor's own point.
            double segLen = it.cum(seg + 1) - it.cum(seg);
            double t = segLen < 1e-9 ? 0.0 : Math.min(1.0, (along - it.cum(seg)) / segLen);
            double cx = it.x(seg) + 0.5 + (it.x(seg + 1) - it.x(seg)) * t;
            double cz = it.z(seg) + 0.5 + (it.z(seg + 1) - it.z(seg)) * t;
            offSq = (px - cx) * (px - cx) + (pz - cz) * (pz - cz);
        }
    }

    private static int segmentAt(Itinerary it, double a, int from) {
        int last = it.size() - 2;
        int k = Math.max(0, Math.min(from, last));
        while (k < last && it.cum(k + 1) <= a) k++;
        return k;
    }

    /** Writes the point {@code dist} blocks further along the polyline into carrotX/carrotZ (block centres). */
    public void computeCarrot(Itinerary it, double dist) {
        double target = Math.min(it.totalLength(), along + Math.max(0.0, dist));
        int k = segmentAt(it, target, seg);
        double segLen = it.cum(k + 1) - it.cum(k);
        double t = segLen < 1e-9 ? 1.0 : (target - it.cum(k)) / segLen;
        if (t > 1.0) t = 1.0;
        carrotX = it.x(k) + 0.5 + (it.x(k + 1) - it.x(k)) * t;
        carrotZ = it.z(k) + 0.5 + (it.z(k + 1) - it.z(k)) * t;
        carrotY = it.y(k) + (it.y(k + 1) - it.y(k)) * t;
        carrotKind = it.kind(k);
    }

    /** Kind of the segment the cursor is on. */
    public byte kind(Itinerary it) {
        return it.kind(Math.max(0, Math.min(seg, it.size() - 2)));
    }

    /**
     * Distance along the polyline to the first segment of {@code kind} starting within
     * {@code maxAhead} blocks: 0 when the cursor is already on one, -1 when there is none.
     */
    public double distanceToKind(Itinerary it, byte kind, double maxAhead) {
        int last = it.size() - 2;
        for (int k = Math.max(0, Math.min(seg, last)); k <= last; k++) {
            double start = it.cum(k) - along;
            if (start > maxAhead) return -1.0;
            if (it.kind(k) == kind) return Math.max(0.0, start);
        }
        return -1.0;
    }

    public void writeNbt(NBTTagCompound tag) {
        tag.setInteger("CSeg", seg);
        tag.setDouble("CAl", along);
    }

    public void readNbt(NBTTagCompound tag, Itinerary it) {
        seg = Math.max(0, Math.min(tag.getInteger("CSeg"), it.size() - 2));
        along = Math.max(0.0, Math.min(tag.getDouble("CAl"), it.totalLength()));
        seg = segmentAt(it, along, 0);
        offSq = 0.0;
    }
}
