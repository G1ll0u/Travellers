package com.jubitus.travellers.follow;

/** Small angle helpers shared by the follow AI. */
final class AngleMath {

    private AngleMath() {
    }

    static double wrapToPi(double angle) {
        while (angle > Math.PI) angle -= Math.PI * 2.0D;
        while (angle < -Math.PI) angle += Math.PI * 2.0D;
        return angle;
    }
}
