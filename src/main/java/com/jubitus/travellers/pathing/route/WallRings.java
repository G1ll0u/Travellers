package com.jubitus.travellers.pathing.route;

/**
 * Immutable set of walled-village rings a route search must not walk through (roads may: they pass
 * through the gates). Handed to a planner worker, so no world references.
 */
public final class WallRings {

    public static final WallRings EMPTY = new WallRings(new int[0], new int[0], new double[0]);

    private final int[] cx;
    private final int[] cz;
    private final double[] r2;

    public WallRings(int[] cx, int[] cz, double[] radius) {
        this.cx = cx;
        this.cz = cz;
        this.r2 = new double[radius.length];
        for (int i = 0; i < radius.length; i++) r2[i] = radius[i] * radius[i];
    }

    public boolean isEmpty() {
        return cx.length == 0;
    }

    /** True when the point lies inside any ring. */
    public boolean contains(double x, double z) {
        for (int i = 0; i < cx.length; i++) {
            double dx = x - cx[i], dz = z - cz[i];
            if (dx * dx + dz * dz <= r2[i]) return true;
        }
        return false;
    }
}
