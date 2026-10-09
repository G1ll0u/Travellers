package com.jubitus.travellers.follow;

/** A follower's resolved formation goal (feet position). */
final class GoalCandidate {
    final double x;
    final double y;
    final double z;

    GoalCandidate(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }
}
