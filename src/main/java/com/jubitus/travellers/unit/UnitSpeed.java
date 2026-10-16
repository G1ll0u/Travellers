package com.jubitus.travellers.unit;

import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.util.math.MathHelper;

/**
 * The one authority on how fast a unit moves. Every speed is a percentage of a walking player
 * (100 = 4.3 blocks/s, a sprinting player is 130), read live from config on every call.
 *
 * <p>Ground physics of an AI mob: {@code EntityLiving.setAIMoveSpeed(s)} also sets moveForward to
 * {@code s}, so the per-tick push is {@code s * s} and the steady speed is {@code s^2 / (1 - 0.546)}
 * blocks per tick - quadratic in {@code s}, which is why raw multipliers were unreadable.
 */
public final class UnitSpeed {

    /** A walking player on default ground, blocks per tick. */
    public static final double WALK_BPT = 4.317D / 20.0D;
    /** Default-ground drag: 0.6 block slipperiness * 0.91. */
    private static final double GROUND_DRAG = 0.546D;
    /** Fixed MOVEMENT_SPEED attribute of every unit and bonded horse: navigator multipliers are relative to it. */
    public static final double ATTRIBUTE = 0.52D;
    /** Internal pace of camp life, ambience and other idle strolls. */
    public static final double STROLL_PERCENT = 70.0D;
    /** In-reach circling is slower than the chase. */
    public static final double FIGHT_RATIO = 0.84D;

    private static final double WALK_AI_SPEED = Math.sqrt(WALK_BPT * (1.0D - GROUND_DRAG));

    private UnitSpeed() {
    }

    /** The {@code setAIMoveSpeed} value that makes a unit cover ground at this percentage of a walking player. */
    public static double aiSpeed(double percent) {
        return WALK_AI_SPEED * Math.sqrt(Math.max(percent, 0.0D) / 100.0D);
    }

    /** Blocks per tick a unit covers at this percentage. */
    public static double blocksPerTick(double percent) {
        return WALK_BPT * percent / 100.0D;
    }

    /** Inverse of {@link #blocksPerTick}. */
    public static double percentOfBlocksPerTick(double bpt) {
        return bpt / WALK_BPT * 100.0D;
    }

    /** Navigator / move-helper multiplier that makes a unit move at this percentage. */
    public static double nav(double percent) {
        return aiSpeed(percent) / ATTRIBUTE;
    }

    public static double travelPercent() {
        return TravellersConfig.SPEED.travelPercent;
    }

    public static double travelNav() {
        return nav(travelPercent());
    }

    public static double attackPercent() {
        return TravellersConfig.SPEED.attackPercent;
    }

    /** How much faster a bonded horse makes its rider than walking (rides at mountedPercent while travelling). */
    public static double mountedFactor() {
        return Math.max(1.0D, TravellersConfig.SPEED.mountedPercent / Math.max(1.0D, travelPercent()));
    }

    /** How much faster than travel pace a follower may go to close a gap. */
    public static double followCatchUpFactor() {
        return Math.max(1.0D, TravellersConfig.SPEED.followMaxPercent / Math.max(1.0D, travelPercent()));
    }

    /** Speed (percent) a unit never exceeds in its current situation, as it moves on the ground. */
    public static double ceilingPercent(EntityTraveller unit) {
        if (unit.getAttackTarget() != null) return attackPercent();
        if (unit.isRouteLocked() || (unit.isHiredFollower() && !unit.isJobActive())) {
            return TravellersConfig.SPEED.followMaxPercent;
        }
        return unit.isMountedOnBondedMount() ? TravellersConfig.SPEED.mountedPercent : travelPercent();
    }

    /** Highest {@code setAIMoveSpeed} value allowed now. A mounted rider asks less: its horse multiplies the pace. */
    public static float maxAiSpeed(EntityTraveller unit) {
        double percent = ceilingPercent(unit);
        if (unit.isMountedOnBondedMount() && unit.getAttackTarget() == null) percent /= mountedFactor();
        // Slowdowns applied as attribute modifiers (raised shield) lower the ceiling with the attribute.
        double attrRatio = MathHelper.clamp(
                unit.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.MOVEMENT_SPEED).getAttributeValue()
                        / ATTRIBUTE, 0.0D, 1.0D);
        return (float) (aiSpeed(percent) * attrRatio);
    }
}
