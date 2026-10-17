package com.jubitus.travellers.follow;

/**
 * Critically damped spring on one angle (degrees): glides to a moving target without overshoot and
 * without a hard speed jump when the target changes. Pure, driven by real frame time.
 */
public final class FollowCameraSpring {

    private final float smoothTimeSeconds;
    private final float maxSpeedDegPerSec;
    private float velocity;

    public FollowCameraSpring(float smoothTimeSeconds, float maxSpeedDegPerSec) {
        this.smoothTimeSeconds = smoothTimeSeconds;
        this.maxSpeedDegPerSec = maxSpeedDegPerSec;
    }

    public void reset() {
        velocity = 0f;
    }

    /** Next value of the angle after {@code dt} seconds; the shortest way round, result in (-180, 180]. */
    public float update(float current, float target, float dt) {
        if (dt <= 0f) return current;
        float omega = 2f / smoothTimeSeconds;
        float x = omega * dt;
        float decay = 1f / (1f + x + 0.48f * x * x + 0.235f * x * x * x);

        float change = wrap(current - target);
        float maxChange = maxSpeedDegPerSec * smoothTimeSeconds;
        change = Math.max(-maxChange, Math.min(maxChange, change));
        // A far target is approached as if it were only maxChange away, so the speed stays capped.
        float effectiveTarget = current - change;

        float temp = (velocity + omega * change) * dt;
        velocity = (velocity - omega * temp) * decay;
        return wrap(effectiveTarget + (change + temp) * decay);
    }

    private static float wrap(float deg) {
        deg %= 360f;
        if (deg > 180f) deg -= 360f;
        if (deg <= -180f) deg += 360f;
        return deg;
    }
}
