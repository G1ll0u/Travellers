package com.jubitus.travellers.follow;

/** Small angle and distance helpers shared by the follow AI. */
final class FollowMath {

    private FollowMath() {
    }

    static double wrapToPi(double angle) {
        while (angle > Math.PI) angle -= Math.PI * 2.0D;
        while (angle < -Math.PI) angle += Math.PI * 2.0D;
        return angle;
    }

    static double distanceSq(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return dx * dx + dy * dy + dz * dz;
    }

    static double distanceSq2D(double ax, double az, double bx, double bz) {
        double dx = ax - bx;
        double dz = az - bz;
        return dx * dx + dz * dz;
    }

    /** A hired worker leads with the worker formation spacing; any other unit leads like a traveller group. */
    static boolean isHiredWorker(net.minecraft.entity.Entity e) {
        return e instanceof com.jubitus.travellers.unit.EntityTravellerWorker
                && ((com.jubitus.travellers.unit.EntityTravellerWorker) e).isHiredFollower();
    }

    static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
