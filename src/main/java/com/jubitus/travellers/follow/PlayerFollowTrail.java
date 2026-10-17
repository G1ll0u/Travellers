package com.jubitus.travellers.follow;

import java.util.ArrayDeque;

import javax.annotation.Nullable;

/**
 * Pure logic of the hands-off player follow: the breadcrumb trail of the followed unit and which
 * crumb the player walks to next. Oldest crumb first; the newest is always the unit's position.
 */
public final class PlayerFollowTrail {

    static final double MIN_SPACING = 0.35D;
    static final double TELEPORT_JUMP = 16.0D;
    static final double REACHED_HORIZ = 0.8D;
    static final double REACHED_VERT = 1.5D;
    static final int MAX_POINTS = 200;

    private final ArrayDeque<double[]> points = new ArrayDeque<>();

    /** Appends the unit's position; a teleport-sized hop drops the trail (a straight line through whatever lay between would mislead). */
    public void record(double x, double y, double z) {
        double[] last = points.peekLast();
        if (last != null) {
            double dx = x - last[0], dz = z - last[2];
            double distSq = dx * dx + dz * dz;
            if (distSq > TELEPORT_JUMP * TELEPORT_JUMP) {
                points.clear();
            } else if (distSq < MIN_SPACING * MIN_SPACING && Math.abs(y - last[1]) < 0.5D) {
                return;
            }
        }
        points.addLast(new double[]{x, y, z});
        while (points.size() > MAX_POINTS) points.removeFirst();
    }

    /** The crumb to walk to from here, dropping the ones already reached or passed; null when the trail is empty. */
    @Nullable
    public double[] nextWaypoint(double px, double py, double pz) {
        while (points.size() > 0) {
            double[] head = points.peekFirst();
            double headDist = horizontalDist(head, px, pz);
            boolean reached = headDist < REACHED_HORIZ && Math.abs(head[1] - py) < REACHED_VERT;
            boolean passed = false;
            if (!reached && points.size() > 1 && headDist < 2.5D) {
                double[] second = nextAfterHead();
                passed = second != null && horizontalDist(second, px, pz) < headDist;
            }
            if (!reached && !passed) return head;
            if (points.size() == 1 && reached) return null;
            points.removeFirst();
        }
        return null;
    }

    /** The point about {@code distance} blocks of trail ahead of the player (the last crumb when the trail is shorter); null when empty. */
    @Nullable
    public double[] pointAhead(double px, double pz, double distance) {
        double[] prev = null;
        double acc = 0.0D;
        double[] last = null;
        for (double[] p : points) {
            double segment = prev == null ? horizontalDist(p, px, pz)
                    : Math.sqrt((p[0] - prev[0]) * (p[0] - prev[0]) + (p[2] - prev[2]) * (p[2] - prev[2]));
            if (acc + segment >= distance) {
                double f = segment > 1.0E-6D ? (distance - acc) / segment : 1.0D;
                double[] from = prev != null ? prev : new double[]{px, p[1], pz};
                return new double[]{from[0] + (p[0] - from[0]) * f, from[1] + (p[1] - from[1]) * f, from[2] + (p[2] - from[2]) * f};
            }
            acc += segment;
            prev = p;
            last = p;
        }
        return last;
    }

    public void clear() {
        points.clear();
    }

    public int size() {
        return points.size();
    }

    @Nullable
    private double[] nextAfterHead() {
        java.util.Iterator<double[]> it = points.iterator();
        it.next();
        return it.hasNext() ? it.next() : null;
    }

    private static double horizontalDist(double[] p, double x, double z) {
        double dx = p[0] - x, dz = p[2] - z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
