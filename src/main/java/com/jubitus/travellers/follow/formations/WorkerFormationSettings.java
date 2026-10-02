package com.jubitus.travellers.follow.formations;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.MathHelper;

public final class WorkerFormationSettings {

    public static final double MIN_FOLLOW_DISTANCE = 0.2D;
    public static final double MAX_FOLLOW_DISTANCE = 32.0D;

    public static final double MIN_SPACING = 0.50D;
    public static final double MAX_SPACING = 4.00D;

    public static final int MIN_WIDTH = 1;
    public static final int MAX_WIDTH = 64;

    public static final int MIN_TURN_SMOOTHNESS = 10;
    public static final int MAX_TURN_SMOOTHNESS = 100;

    /** Number of close-escort workers always kept 2-3 blocks from the leader (0 to 16). */
    public static final int MIN_ESCORTS = 0;
    public static final int MAX_ESCORTS = 16;

    public static final double MIN_PLAYER_COHESION = 0.0D;
    public static final double MAX_PLAYER_COHESION = 1.0D;

    public WorkerFormationStyle style = WorkerFormationStyle.ADAPTIVE;
    public double followDistance = 2.5D;
    public double spacing = 1.0D;
    public int width = 8;
    public int turnSmoothness = 60;
    public boolean adaptiveCave = true;
    /** How many workers are kept as close escorts near the leader regardless of formation. */
    public int numEscorts = 2;
    /** Combat posture for the whole group - see {@link WorkerAttitude}. */
    public WorkerAttitude attitude = WorkerAttitude.ATTACK;
    /**
     * How eagerly followers stick to their leader while it's moving (0 = today's looser trailing
     * behaviour, 1 = tight escort that fills in beside/ahead of the leader and reacts to the
     * smallest movement). See FormationGeometry#computeSurfaceOffset and
     * EntityAIFollowLeader's wake-up thresholds for where this is applied.
     */
    public double playerCohesion = 0.4D;

    public WorkerFormationSettings copy() {
        WorkerFormationSettings s = new WorkerFormationSettings();
        s.style = this.style;
        s.followDistance = this.followDistance;
        s.spacing = this.spacing;
        s.width = this.width;
        s.turnSmoothness = this.turnSmoothness;
        s.adaptiveCave = this.adaptiveCave;
        s.numEscorts = this.numEscorts;
        s.attitude = this.attitude;
        s.playerCohesion = this.playerCohesion;
        return s;
    }

    public WorkerFormationSettings clamp() {
        if (style == null) {
            style = WorkerFormationStyle.ADAPTIVE;
        }
        // COLUMN is no longer a selectable surface style (see WorkerFormationStyle) - migrate
        // pre-existing saves/settings that still have it to the closest remaining style.
        if (style == WorkerFormationStyle.COLUMN) {
            style = WorkerFormationStyle.LINE;
        }
        if (attitude == null) {
            attitude = WorkerAttitude.ATTACK;
        }

        followDistance = roundHalf(MathHelper.clamp(followDistance, MIN_FOLLOW_DISTANCE, MAX_FOLLOW_DISTANCE));
        spacing = roundQuarter(MathHelper.clamp(spacing, MIN_SPACING, MAX_SPACING));
        width = MathHelper.clamp(width, MIN_WIDTH, MAX_WIDTH);
        turnSmoothness = MathHelper.clamp(turnSmoothness, MIN_TURN_SMOOTHNESS, MAX_TURN_SMOOTHNESS);
        numEscorts = MathHelper.clamp(numEscorts, MIN_ESCORTS, MAX_ESCORTS);
        playerCohesion = roundQuarter(MathHelper.clamp(playerCohesion, MIN_PLAYER_COHESION, MAX_PLAYER_COHESION));
        return this;
    }

    public float getTurnSmoothingFactor() {
        float t = (turnSmoothness - MIN_TURN_SMOOTHNESS)
                / (float) (MAX_TURN_SMOOTHNESS - MIN_TURN_SMOOTHNESS);
        return 0.12F + (0.60F * t);
    }

    public NBTTagCompound toNbt() {
        clamp();
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("Style", style.name());
        tag.setDouble("FollowDistance", followDistance);
        tag.setDouble("Spacing", spacing);
        tag.setInteger("Width", width);
        tag.setInteger("TurnSmoothness", turnSmoothness);
        tag.setBoolean("AdaptiveCave", adaptiveCave);
        tag.setInteger("NumEscorts", numEscorts);
        tag.setString("Attitude", attitude.name());
        tag.setDouble("PlayerCohesion", playerCohesion);
        return tag;
    }

    public static WorkerFormationSettings fromNbt(NBTTagCompound tag) {
        WorkerFormationSettings s = defaults();

        if (tag.hasKey("Style")) {
            try {
                s.style = WorkerFormationStyle.valueOf(tag.getString("Style"));
            } catch (IllegalArgumentException ignored) {
                s.style = WorkerFormationStyle.ADAPTIVE;
            }
        }

        if (tag.hasKey("FollowDistance")) {
            s.followDistance = tag.getDouble("FollowDistance");
        }
        if (tag.hasKey("Spacing")) {
            s.spacing = tag.getDouble("Spacing");
        }
        if (tag.hasKey("Width")) {
            s.width = tag.getInteger("Width");
        }
        if (tag.hasKey("TurnSmoothness")) {
            s.turnSmoothness = tag.getInteger("TurnSmoothness");
        }
        if (tag.hasKey("AdaptiveCave")) {
            s.adaptiveCave = tag.getBoolean("AdaptiveCave");
        }
        if (tag.hasKey("NumEscorts")) {
            s.numEscorts = tag.getInteger("NumEscorts");
        }
        if (tag.hasKey("Attitude")) {
            try {
                s.attitude = WorkerAttitude.valueOf(tag.getString("Attitude"));
            } catch (IllegalArgumentException ignored) {
                s.attitude = WorkerAttitude.ATTACK;
            }
        }
        if (tag.hasKey("PlayerCohesion")) {
            s.playerCohesion = tag.getDouble("PlayerCohesion");
        }

        return s.clamp();
    }

    public static WorkerFormationSettings defaults() {
        return new WorkerFormationSettings().clamp();
    }

    private static double roundHalf(double v) {
        return Math.round(v * 2.0D) / 2.0D;
    }

    private static double roundQuarter(double v) {
        return Math.round(v * 4.0D) / 4.0D;
    }
}