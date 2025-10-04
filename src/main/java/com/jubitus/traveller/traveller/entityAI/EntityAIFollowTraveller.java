package com.jubitus.traveller.traveller.entityAI;

import com.jubitus.traveller.TravellersModConfig;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;

public class EntityAIFollowTraveller extends net.minecraft.entity.ai.EntityAIBase {
    private static final double DEFAULT_RANGE = TravellersModConfig.followDefaultRange;     // detect meet-up radius
    private static final double DESIRED_DIST = TravellersModConfig.followDesiredDist;      // stand ~3 blocks behind
    private static final double MAX_DIST = TravellersModConfig.followMaxDist;         // stop if too far
    private static final int REPUSH_EVERY = TravellersModConfig.followRepushEvery;          // ticks
    private static final int DURATION_MIN = TravellersModConfig.followDurationMin * 20;     // 2 minutes
    private static final int DURATION_MAX = TravellersModConfig.followDurationMax * 20;     // 4 minutes
    private static final float ANGLE_ALIGN_DEG = TravellersModConfig.followAngleAlignDeg;    // roughly same direction
    private static final float START_CHANCE = TravellersModConfig.followStartChance;     // 33% chance when meeting to follow

    private final EntityTraveller mob;
    private final double angleAlignCos;
    private final double speed;
    private final double rangeSq;

    private EntityTraveller leader;
    private int repathTicker;

    private Vec3d smoothedFwd = Vec3d.ZERO;
    private Vec3d lastLeaderPos = Vec3d.ZERO;
    private double lateralOffset = 0.0;     // constant per follow session
    private Vec3d lastBackPoint = null;     // last issued target

    private int glanceCooldown = 0;
    private int glanceTicks = 0;

    private static final double LEADER_MOVE_EPS = 1e-4;     // "moving" threshold (squared units from delta)
    private static final int IDLE_STILL_TICKS = 25;         // ~1.25s before we consider leader idle
    private static final int RANDOM_GAZE_MIN = 20;          // 1.0s
    private static final int RANDOM_GAZE_MAX = 50;          // 2.5s

    private int leaderStillTicks = 0;       // how long leader has been effectively still
    private int nextRandomGazeTick = 0;     // when to pick the next idle/random gaze
    private Vec3d currentGaze = null;       // current random gaze target

    // --- NEW: follow gating state ---
    /**
     * Next tick at/after which we’re allowed to *attempt* starting to follow.
     */
    private int nextFollowTryTick = 0;
    /**
     * Track whether we had a valid candidate last tick, to edge-trigger “meeting”.
     */
    private boolean hadCandidateLastTick = false;

    public EntityAIFollowTraveller(EntityTraveller mob, double angleAlignCos, double speed) {
        this.mob = mob;
        this.angleAlignCos = Math.cos(Math.toRadians(ANGLE_ALIGN_DEG));
        this.speed = speed;
        this.rangeSq = DEFAULT_RANGE * DEFAULT_RANGE;
        this.setMutexBits(1);
    }

    private static Vec3d lastResortFacing(EntityTraveller t) {
        return new Vec3d(-MathHelper.sin(t.rotationYaw * 0.017453292F), 0,
                MathHelper.cos(t.rotationYaw * 0.017453292F)).normalize();
    }

    @Override
    public boolean shouldExecute() {
        if (mob.world.isRemote) return false;
        if (mob.isInCombat() || mob.isAutoEating()) return false;
        if (mob.getTargetVillage() == null) return false;
        if (mob.hasFollowLeader()) return false;

        // Cooldown: don't even look for a leader until it elapses
        if (mob.ticksExisted < nextFollowTryTick) return false;

        // Find best candidate (same as your code, minus the angle bug)
        EntityTraveller cand = null;
        double best = Double.NEGATIVE_INFINITY;

        Vec3d myDir = travelDir(mob);
        List<EntityTraveller> nearby = mob.world.getEntitiesWithinAABB(
                EntityTraveller.class,
                mob.getEntityBoundingBox().grow(Math.sqrt(rangeSq), 2.0, Math.sqrt(rangeSq)),
                e -> e != null && e.isEntityAlive() && e != mob && !e.isInCombat()
        );

        for (EntityTraveller t : nearby) {
            if (t.getTargetVillage() == null) continue;
            double dSq = mob.getDistanceSq(t);
            if (dSq > rangeSq) continue;
            if (compareIds(mob, t) <= 0) continue;

            double align = dot2D(myDir, travelDir(t));
            double score = align - dSq * 0.02;
            if (score > best && align >= angleAlignCos) {
                best = score;
                cand = t;
            }
        }

        boolean hasCandidate = (cand != null);

        // Only roll the dice when we transition from no-candidate -> has-candidate (i.e., when they "meet")
        boolean justMet = hasCandidate && !hadCandidateLastTick;

        // Update edge-detection memory for next tick
        hadCandidateLastTick = hasCandidate;

        if (!justMet) return false;

        // Random start
        if (mob.getRNG().nextFloat() > START_CHANCE) {
            // Failed the roll: back off for a while before trying again
            nextFollowTryTick = mob.ticksExisted + (20 * 60 + mob.getRNG().nextInt(10 * 120)); // 60–120sc
            return false;
        }

        // Success: start following this candidate
        this.leader = cand;
        return true;
    }

    private static Vec3d travelDir(EntityTraveller t) {
        // prefer waypoint direction if present, else motion, else look
        BlockPos wp = t.getCurrentWaypoint();
        if (wp != null) {
            Vec3d to = new Vec3d(wp.getX() + 0.5 - t.posX, 0, wp.getZ() + 0.5 - t.posZ);
            if (to.lengthSquared() > 1e-4) return to.normalize();
        }
        Vec3d mot = new Vec3d(t.motionX, 0, t.motionZ);
        if (mot.lengthSquared() > 1e-4) return mot.normalize();
        return new Vec3d(-MathHelper.sin(t.rotationYaw * 0.017453292F), 0, MathHelper.cos(t.rotationYaw * 0.017453292F)).normalize();
    }

    /**
     * Return negative if a<b; used to choose a deterministic leader.
     */
    private static int compareIds(Entity a, Entity b) {
        // entityId is fine in 1.12
        return Integer.compare(a.getEntityId(), b.getEntityId());
    }

    private static double dot2D(Vec3d a, Vec3d b) {
        return a.x * b.x + a.z * b.z;
    }

    // --- helpers ---

    private static double cosDeg(float deg) {
        // Want align >= cos(theta) where theta is the max allowed angle between headings.
        return Math.cos(Math.toRadians(deg));
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (leader == null || !leader.isEntityAlive()) return false;
        if (mob.isInCombat() || mob.isAutoEating()) return false;
        if (mob.getTargetVillage() == null) return false;
        if (!mob.hasFollowLeader() || mob.getFollowLeader() != leader) return false;

        boolean leaderNearVillage = leader.getNearestVillage() != null &&
                leader.getDistanceSq(leader.getNearestVillage()) < (EntityTraveller.VILLAGE_NEAR * EntityTraveller.VILLAGE_NEAR);
        boolean meNearVillage = mob.getNearestVillage() != null &&
                mob.getDistanceSq(mob.getNearestVillage()) < (EntityTraveller.VILLAGE_NEAR * EntityTraveller.VILLAGE_NEAR);
        if (leaderNearVillage || meNearVillage) return false;

        return mob.getDistanceSq(leader) <= (MAX_DIST * MAX_DIST);
    }

    @Override
    public void startExecuting() {
        this.repathTicker = 0;
        int dur = DURATION_MIN + mob.getRNG().nextInt(DURATION_MAX - DURATION_MIN + 1);
        mob.startFollowing(leader, dur);

        long mix = (31L * mob.getEntityId()) ^ (127L * leader.getEntityId());
        this.lateralOffset = ((mix & 1L) == 0L) ? 0.9 : -0.9;

        this.smoothedFwd = new Vec3d(
                -MathHelper.sin(leader.rotationYaw * 0.017453292F),
                0,
                MathHelper.cos(leader.rotationYaw * 0.017453292F)
        ).normalize();
        this.lastLeaderPos = new Vec3d(leader.posX, leader.posY, leader.posZ);

        this.lastBackPoint = computeBackPoint();
        pushFollowPathTo(lastBackPoint);
    }

    @Override
    public void resetTask() {
        mob.stopFollowing();
        leader = null;

        // After finishing a session, wait before trying to latch onto someone again
        nextFollowTryTick = mob.ticksExisted + (200 + mob.getRNG().nextInt(200)); // ~10–20s
        // Also require a fresh meeting edge next time
        hadCandidateLastTick = true;
    }
    @Override
    public void updateTask() {
        if (leader == null) return;

        // --- Update leader forward (EMA) & motion state ---
        Vec3d now = new Vec3d(leader.posX, leader.posY, leader.posZ);
        Vec3d delta = now.subtract(lastLeaderPos);
        lastLeaderPos = now;

        boolean leaderMoving = isMovingHoriz(delta);
        if (leaderMoving) {
            leaderStillTicks = 0;
            // refresh smoothed heading from instantaneous direction
            Vec3d inst = new Vec3d(delta.x, 0, delta.z).normalize();
            double alpha = 0.25; // lower = smoother
            this.smoothedFwd = new Vec3d(
                    smoothedFwd.x * (1 - alpha) + inst.x * alpha,
                    0,
                    smoothedFwd.z * (1 - alpha) + inst.z * alpha
            ).normalize();
        } else {
            leaderStillTicks++;
            // keep previous smoothedFwd (gives continuity)
        }

        // --- Glance timers (unchanged behavior) ---
        if (glanceCooldown > 0) glanceCooldown--;
        if (glanceTicks > 0) glanceTicks--;

        if (glanceCooldown == 0 && mob.getRNG().nextFloat() < 0.02f) { // ~1 in 50 ticks
            glanceTicks = 20 + mob.getRNG().nextInt(20);         // 1–2s
            glanceCooldown = 100 + mob.getRNG().nextInt(100);    // 5–10s
        }

        // --- Head/Look behavior ---
        if (glanceTicks > 0) {
            // Sometimes look at the leader
            mob.getLookHelper().setLookPositionWithEntity(leader, 30.0F, 30.0F);
        } else if (!leaderMoving && leaderStillTicks >= IDLE_STILL_TICKS) {
            // Leader is idle: do human-like idle random gazes
            randomGazeTick();
        } else {
            // Leader moving (or short stop): look where we're going, not at the leader
            Vec3d fwd = (smoothedFwd.lengthSquared() > 1e-6) ? smoothedFwd : lastResortFacing(leader);
            // gaze 4–6 blocks ahead with a tiny vertical bias
            double ahead = 4.0 + mob.getRNG().nextDouble() * 2.0;
            Vec3d target = new Vec3d(
                    mob.posX + fwd.x * ahead,
                    mob.posY + mob.getEyeHeight() * (0.9 + mob.getRNG().nextDouble() * 0.15),
                    mob.posZ + fwd.z * ahead
            );
            lookAt(target, 15.0F, 15.0F);
            // decay random gaze so it gets re-picked next idle
            currentGaze = null;
        }

        // --- Distance gating to avoid micro-corrections ---
        double d = mob.getDistance(leader);
        boolean tucked = d < (DESIRED_DIST + 0.6);

        // --- Single repath cadence point ---
        if (++repathTicker >= REPUSH_EVERY || mob.getNavigator().noPath()) {
            repathTicker = 0;

            Vec3d target = computeBackPoint();

            // Hysteresis: only repath if we meaningfully moved the target
            boolean bigShift = (lastBackPoint == null) ||
                    target.squareDistanceTo(lastBackPoint) > (0.8 * 0.8); // ~0.8 block

            if (bigShift || (!tucked && mob.getNavigator().noPath())) {
                if (!tucked) {
                    lastBackPoint = target;
                    pushFollowPathTo(target);
                } else {
                    lastBackPoint = target; // we're tucked; remember but don't push
                }
            }

            // If very close, stop entirely to reduce jitter
            if (d < (DESIRED_DIST + 0.4)) {
                mob.getNavigator().clearPath();
            }
        }
    }


    private Vec3d computeBackPoint() {
        Vec3d fwd = (smoothedFwd.lengthSquared() > 1e-6) ? smoothedFwd
                : new Vec3d(-MathHelper.sin(leader.rotationYaw * 0.017453292F), 0,
                MathHelper.cos(leader.rotationYaw * 0.017453292F)).normalize();

        Vec3d left = new Vec3d(-fwd.z, 0, fwd.x).normalize().scale(lateralOffset);
        double x = leader.posX - fwd.x * DESIRED_DIST + left.x;
        double z = leader.posZ - fwd.z * DESIRED_DIST + left.z;
        double y = leader.posY;

        return new Vec3d(x, y, z);
    }

    private void pushFollowPathTo(Vec3d p) {
        mob.getNavigator().tryMoveToXYZ(p.x, p.y, p.z, speed);
    }
    // ADD — is leader moving (horizontal)
    private static boolean isMovingHoriz(Vec3d delta) {
        double s = delta.x * delta.x + delta.z * delta.z;
        return s > LEADER_MOVE_EPS;
    }

    // ADD — set look to a point
    private void lookAt(Vec3d p, float yawSpeed, float pitchSpeed) {
        mob.getLookHelper().setLookPosition(p.x, p.y, p.z, yawSpeed, pitchSpeed);
    }

    // ADD — pick/maintain a natural random gaze around the mob (used for idle + "sometimes look elsewhere")
    private void randomGazeTick() {
        // refresh target periodically
        if (mob.ticksExisted >= nextRandomGazeTick || currentGaze == null) {
            double ang = mob.getRNG().nextDouble() * Math.PI * 2.0;
            double dist = 2.0 + mob.getRNG().nextDouble() * 4.0;     // 2–6 blocks away
            double gx = mob.posX + Math.cos(ang) * dist;
            double gz = mob.posZ + Math.sin(ang) * dist;
            double gy = mob.posY + mob.getEyeHeight() * (0.85 + mob.getRNG().nextDouble() * 0.25); // near eye height

            currentGaze = new Vec3d(gx, gy, gz);
            nextRandomGazeTick = mob.ticksExisted + RANDOM_GAZE_MIN + mob.getRNG().nextInt(RANDOM_GAZE_MAX - RANDOM_GAZE_MIN + 1);
        }
        // gently look at the current random target
        lookAt(currentGaze, 10.0F, 10.0F);
    }
}

