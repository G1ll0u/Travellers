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
    private static final int DURATION_MIN = TravellersModConfig.followDurationMin;     // 2 minutes
    private static final int DURATION_MAX = TravellersModConfig.followDurationMax;     // 4 minutes
    private static final float ANGLE_ALIGN_DEG = TravellersModConfig.followAngleAlignDeg;    // roughly same direction
    private static final float START_CHANCE = TravellersModConfig.followStartChance;     // 33% chance when meeting to follow

    private final EntityTraveller mob;
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

    // --- NEW: follow gating state ---
    /** Next tick at/after which we’re allowed to *attempt* starting to follow. */
    private int nextFollowTryTick = 0;
    /** Track whether we had a valid candidate last tick, to edge-trigger “meeting”. */
    private boolean hadCandidateLastTick = false;

    public EntityAIFollowTraveller(EntityTraveller mob, double speed) {
        this.mob = mob;
        this.speed = speed;
        this.rangeSq = DEFAULT_RANGE * DEFAULT_RANGE;
        this.setMutexBits(1);
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
            if (score > best && align >= cosDeg(ANGLE_ALIGN_DEG)) {
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
            nextFollowTryTick = mob.ticksExisted + (20*60 + mob.getRNG().nextInt(10*120)); // 5–15s
            return false;
        }

        // Success: start following this candidate
        this.leader = cand;
        return true;
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
// smooth the leader heading (EMA)
        Vec3d now = new Vec3d(leader.posX, leader.posY, leader.posZ);
        Vec3d delta = now.subtract(lastLeaderPos);
        lastLeaderPos = now;

        Vec3d inst = new Vec3d(delta.x, 0, delta.z);
        if (inst.lengthSquared() > 1e-4) {
            inst = inst.normalize();
            double alpha = 0.25; // 0..1 (lower = smoother)
            this.smoothedFwd = new Vec3d(
                    smoothedFwd.x * (1 - alpha) + inst.x * alpha,
                    0,
                    smoothedFwd.z * (1 - alpha) + inst.z * alpha
            ).normalize();
            if (++repathTicker >= REPUSH_EVERY || mob.getNavigator().noPath()) {
                repathTicker = 0;

                Vec3d target = computeBackPoint();

                // Hysteresis: don’t shove a new path unless target moved a lot
                boolean bigShift = (lastBackPoint == null)
                        || target.squareDistanceTo(lastBackPoint) > 0.8 * 0.8; // ~0.8 block

                // Also: if we’re already nicely tucked in, don’t repath at all
                double distToLeader = mob.getDistance(leader);
                boolean tucked = distToLeader < (DESIRED_DIST + 0.6);

                if (bigShift || (!tucked && mob.getNavigator().noPath())) {
                    lastBackPoint = target;
                    pushFollowPathTo(target);
                }
                if (glanceCooldown > 0) glanceCooldown--;
                if (glanceTicks > 0) glanceTicks--;

// Occasionally start a glance
                if (glanceCooldown == 0 && mob.getRNG().nextFloat() < 0.02f) { // ~1 in 50 ticks
                    glanceTicks = 20 + mob.getRNG().nextInt(20);   // look for 1–2s
                    glanceCooldown = 100 + mob.getRNG().nextInt(100); // then wait 5–10s
                }

                if (glanceTicks > 0) {
                    // temporarily glance at leader
                    mob.getLookHelper().setLookPositionWithEntity(leader, 30.0F, 30.0F);
                }

            }

// When close enough, stop moving to avoid micro-corrections
            double d = mob.getDistance(leader);
            if (d < (DESIRED_DIST + 0.4)) {
                mob.getNavigator().clearPath();
            }

// Always look at the leader (not the path node)
            mob.getLookHelper().setLookPositionWithEntity(leader, 20.0F, 20.0F);
        }
// if still too small (leader idle), keep last smoothedFwd as-is
        // keep path nudged to a spot *behind* the leader
        if (++repathTicker >= REPUSH_EVERY || mob.getNavigator().noPath()) {
            repathTicker = 0;
            pushFollowPath();
        }
    }

    // --- helpers ---

    private void pushFollowPath() {
        Vec3d back = computeBackPoint();
        if (back == null) return;
        mob.getNavigator().tryMoveToXYZ(back.x, back.y, back.z, speed);
        // make sure we look toward leader
        mob.getLookHelper().setLookPositionWithEntity(leader, 30.0F, 30.0F);
    }
    private void pushFollowPathTo(Vec3d p) {
        mob.getNavigator().tryMoveToXYZ(p.x, p.y, p.z, speed);
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

    private static Vec3d lastResortFacing(EntityTraveller t) {
        return new Vec3d(-MathHelper.sin(t.rotationYaw * 0.017453292F), 0,
                MathHelper.cos(t.rotationYaw * 0.017453292F)).normalize();
    }

    private static double dot2D(Vec3d a, Vec3d b) {
        return a.x * b.x + a.z * b.z;
    }
    private static double cosDeg(float deg) {
        // Want align >= cos(theta) where theta is the max allowed angle between headings.
        return Math.cos(Math.toRadians(deg));
    }

    /** Return negative if a<b; used to choose a deterministic leader. */
    private static int compareIds(Entity a, Entity b) {
        // entityId is fine in 1.12
        return Integer.compare(a.getEntityId(), b.getEntityId());
    }
}

