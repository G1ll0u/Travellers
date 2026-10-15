package com.jubitus.travellers.raid;

/** Pure sizing of a raid from the strength of the player's hired workers. */
public final class RaidStrength {

    /** Raiders of a raid on a player with no worker at all (before the difficulty scale). */
    private static final double BASE_RAIDERS = 3.0;
    /** Raiders gained per square root of "bare-worker equivalents"; a legion saturates instead of exploding. */
    private static final double RAIDERS_PER_SQRT_STRENGTH = 2.5;
    /** Vanilla armor: each point removes about 4% of incoming damage, capped at 20 points. */
    private static final double ARMOR_REDUCTION_PER_POINT = 0.04;
    private static final double MAX_ARMOR_POINTS = 20.0;

    private RaidStrength() {
    }

    /** Damage dealt times damage absorbed, so a worker twice as deadly or twice as tough counts double. */
    public static double power(double damage, double maxHealth, double armor) {
        double reduction = Math.min(MAX_ARMOR_POINTS, Math.max(0.0, armor)) * ARMOR_REDUCTION_PER_POINT;
        double effectiveHealth = Math.max(0.0, maxHealth) / (1.0 - reduction);
        return Math.max(0.0, damage) * effectiveHealth;
    }

    /** One worker as a multiple of the bare reference worker. */
    public static double relativePower(double power, double referencePower) {
        return referencePower <= 0.0 ? 1.0 : power / referencePower;
    }

    public static int raiderCount(double totalStrength, double difficultyScale, int maxRaiders) {
        double raw = BASE_RAIDERS + RAIDERS_PER_SQRT_STRENGTH * Math.sqrt(Math.max(0.0, totalStrength));
        long n = Math.round(raw * Math.max(0.0, difficultyScale));
        return (int) Math.max(1, Math.min(Math.max(1, maxRaiders), n));
    }
}
