package com.jubitus.travellers.follow;

import com.jubitus.travellers.follow.formations.FormationGeometry;
import com.jubitus.travellers.follow.formations.WorkerFormationSettings;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.*;

public final class FollowFormationCoordinator {

    private static final Map<LeaderKey, LeaderFormation> CONTEXTS = new HashMap<>();

    private static final int CLEANUP_INTERVAL = 200;



    private static long lastCleanupTick = Long.MIN_VALUE;

    private FollowFormationCoordinator() {
    }

    /**
     * A slot assignment for a follower whose position is a fixed function of its own identity, not
     * a rank in a pool (see {@link FormationGeometry#computeRingOffset}) - bypasses this class's
     * whole registration/dense-rank/heading-smoothing machinery entirely, which a plain traveller/
     * raider group follower has no use for (no owning player, no cave mode, no escort slots).
     * {@code leaderSpeed}/{@code leaderActuallyMoving} are computed fresh by the caller every tick -
     * no smoothing needed, since a RING offset does not depend on heading in the first place.
     */
    public static SlotAssignment buildFixedSlot(int groupSize,
                                                 double leaderSpeed,
                                                 boolean leaderActuallyMoving,
                                                 double idealX, double idealY, double idealZ,
                                                 FormationGeometry.SlotOffset off) {
        return buildFixedSlot(groupSize, leaderSpeed, leaderActuallyMoving, idealX, idealY, idealZ,
                off, 1.0D, 0.0D);
    }

    /**
     * @param leaderDirX/leaderDirZ the leader's current (unit) heading - matters for any style whose
     *                              geometry or front-penalty scoring is heading-relative (everything
     *                              except RING, see {@link FormationGeometry#computeRingOffset}).
     */
    public static SlotAssignment buildFixedSlot(int groupSize,
                                                 double leaderSpeed,
                                                 boolean leaderActuallyMoving,
                                                 double idealX, double idealY, double idealZ,
                                                 FormationGeometry.SlotOffset off,
                                                 double leaderDirX, double leaderDirZ) {
        return new SlotAssignment(FollowMode.SURFACE, 0, groupSize,
                0.5D, 0.5D, leaderDirX, leaderDirZ,
                leaderSpeed, leaderActuallyMoving,
                idealX, idealY, idealZ,
                off.preferredRadius, off.arriveRadius, off.frontPenaltyStrength,
                false, 0.0D, 0.0D,
                false, true, 1,
                false, off.dx, off.dz);
    }

    public static SlotAssignment getSlot(EntityTravellerWorker follower,
                                         EntityLivingBase leader,
                                         FollowMode mode,
                                         FollowRuntimeConfig cfg,
                                         double baseFollowDistance) {
        World world = follower.world;
        LeaderKey key = new LeaderKey(world.provider.getDimension(), leader.getUniqueID());

        long now = world.getTotalWorldTime();
        cleanup(now);

        LeaderFormation ctx = CONTEXTS.get(key);
        if (ctx == null) {
            ctx = new LeaderFormation(key);
            CONTEXTS.put(key, ctx);
        }

        // Resolved (and copied) at most once per leader per world tick, not once per follower:
        // with 200 followers all calling getSlot() the same tick, re-hitting WorldSavedData and
        // allocating a fresh WorkerFormationSettings copy 200x/tick was pure waste — the settings
        // can't meaningfully change faster than once per tick anyway.
        WorkerFormationSettings formationSettings = ctx.resolveFormationSettings(world, leader, now);

        ctx.registerMember(follower, now);
        ctx.ensureFresh(world, leader, follower.getFollowOwnerId(), now, mode, cfg, baseFollowDistance, formationSettings);

        SlotAssignment slot = ctx.assignments.get(follower.getUniqueID());
        if (slot == null) {
            // If this worker is completely new to this formation (never had a persistent ordinal),
            // do one immediate rebuild this tick so it gets the correct slot right away instead
            // of navigating to an incorrect fallback position for 3+ ticks.
            // Rate-limited to one forced rebuild per tick per context to avoid cascading rebuilds
            // when many workers join simultaneously (e.g., after server restart).
            if (!ctx.persistentOrdinals.containsKey(follower.getUniqueID())
                    && ctx.lastForcedRebuildTick < now) {
                ctx.lastForcedRebuildTick = now;
                ctx.rebuildAssignments(world, leader, follower.getFollowOwnerId(), now, mode, cfg, baseFollowDistance, formationSettings, false);
                slot = ctx.assignments.get(follower.getUniqueID());
            }
            if (slot == null) {
                slot = ctx.createFallbackSlot(follower, leader, mode, cfg, baseFollowDistance, formationSettings);
                ctx.nextRefreshTick = Math.min(ctx.nextRefreshTick, now + 3);
            }
        }

        return reanchorToLiveLeaderPosition(slot, leader);
    }

    /**
     * Tight single-slot escort for a worker coming back from a transport mission: ignores the
     * formation entirely and holds {@code dist} blocks from the owner, on the side it is already on
     * (so it never circles the player), falling back to directly behind when it is on top of them.
     */
    public static SlotAssignment getPickupEscortSlot(EntityTravellerWorker follower, EntityLivingBase leader, double dist) {
        double dx = follower.posX - leader.posX;
        double dz = follower.posZ - leader.posZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.5D) {
            double yaw = Math.toRadians(leader.rotationYaw);
            dx = Math.sin(yaw);
            dz = -Math.cos(yaw);
            len = 1.0D;
        }
        double offX = dx / len * dist;
        double offZ = dz / len * dist;
        double vx = leader.posX - leader.prevPosX;
        double vz = leader.posZ - leader.prevPosZ;
        double speed = Math.sqrt(vx * vx + vz * vz);
        return new SlotAssignment(FollowMode.SURFACE, 0, 1, 1.0D, 1.0D,
                -offX / dist, -offZ / dist, speed, speed > 0.04D,
                leader.posX + offX, leader.getEntityBoundingBox().minY, leader.posZ + offZ,
                dist, 1.2D, 0.0D, false, 0.0D, 0.0D, false, false, 1, true, offX, offZ);
    }

    /**
     * {@code assignments} caches a whole {@link SlotAssignment} (including {@code idealX/idealZ})
     * until the next {@code rebuildAssignments()} call, which for a worker-leader can be 20+ ticks
     * away (see the {@code workerLeader} interval padding in {@code rebuildAssignments}). Between
     * rebuilds the cached ideal point does not track the leader's actual walking at all - it is a
     * frozen snapshot of where the leader stood at the last rebuild - so every threshold that reads
     * {@code slot.idealX/idealZ} directly (shouldReevaluateGoal's idealShiftSq, the idle/wake drift
     * checks, the sticky-goal drift check) sees zero drift for the whole interval and then a single
     * large jump the instant a rebuild finally happens, which is what actually produced the
     * "stop, then sprint to catch up, then stop again" bursty motion for worker-led groups (plain
     * traveller/raider groups never had this because {@link GroupFormationTracker} recomputes its
     * equivalent offset fresh every tick from the leader's live position instead of caching it).
     * {@code leaderOffsetDx/leaderOffsetDz} already carry exactly what's needed to re-derive the
     * live point - {@link #resolveGoal} in {@code EntityAIFollowLeader} already does this locally
     * for the one goal it resolves, but every other direct reader of idealX/idealZ needs it too, so
     * doing it once here at the single return point fixes all of them at once.
     */
    private static SlotAssignment reanchorToLiveLeaderPosition(SlotAssignment slot, EntityLivingBase leader) {
        if (slot.projectedGroundTarget) return slot;
        double liveX = leader.posX + slot.leaderOffsetDx;
        double liveZ = leader.posZ + slot.leaderOffsetDz;
        if (liveX == slot.idealX && liveZ == slot.idealZ) return slot;
        return slot.withLiveIdealXZ(liveX, liveZ);
    }

    /**
     * Returns the worker's current persistent formation ordinal (slot index), or -1 if it never
     * had one. Called at virtualization so VirtualFormationSimulator can seed its own
     * formationOrdinal from the same value instead of appending the worker at the back of the
     * line the moment it goes virtual.
     */
    public static int snapshotOrdinal(UUID leaderId, int dimension, UUID workerId) {
        LeaderKey key = new LeaderKey(dimension, leaderId);
        LeaderFormation ctx = CONTEXTS.get(key);
        if (ctx == null) return -1;
        Integer ord = ctx.persistentOrdinals.get(workerId);
        return ord != null ? ord : -1;
    }

    /**
     * Group-mates of {@code self} (same follow-owner, following the same {@code leader}) within a
     * few blocks of (x, z), used for crowd-avoidance scoring. Reuses the per-leader
     * {@code registered} self-registration map (already maintained for slot assignment)
     * via a small spatial grid rebuilt at most once per world tick, instead of each follower
     * running its own world.getEntitiesWithinAABB() scan.
     */
    public static List<EntityTravellerWorker> getCrowdNeighbors(@Nullable EntityLivingBase leader,
                                                                 EntityTravellerWorker self,
                                                                 double x,
                                                                 double z) {
        if (leader == null) return Collections.emptyList();

        World world = self.world;
        LeaderKey key = new LeaderKey(world.provider.getDimension(), leader.getUniqueID());
        LeaderFormation ctx = CONTEXTS.get(key);
        if (ctx == null) return Collections.emptyList();

        return ctx.crowdNeighbors(x, z, world.getTotalWorldTime(), self);
    }

    public static void notifyWorkerRejoined(UUID workerId) {
        for (LeaderFormation ctx : CONTEXTS.values()) {
            if (ctx.persistentOrdinals.containsKey(workerId) || ctx.persistentFormOrdinals.containsKey(workerId)) {
                ctx.persistentOrdinals.remove(workerId);
                ctx.persistentFormOrdinals.remove(workerId);
                ctx.assignments.remove(workerId);
                ctx.nextRefreshTick = Long.MIN_VALUE;
            }
        }
    }

    private static void cleanup(long now) {
        if (lastCleanupTick != Long.MIN_VALUE && now - lastCleanupTick < CLEANUP_INTERVAL) {
            return;
        }
        lastCleanupTick = now;

        Iterator<Map.Entry<LeaderKey, LeaderFormation>> it = CONTEXTS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<LeaderKey, LeaderFormation> e = it.next();
            LeaderFormation ctx = e.getValue();
            if (ctx == null || (now - ctx.lastSeenTick) > 20L * 30L) {
                it.remove();
            }
        }
    }

    static final class LeaderKey {
        final int dimension;
        final UUID leaderId;

        LeaderKey(int dimension, UUID leaderId) {
            this.dimension = dimension;
            this.leaderId = leaderId;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof LeaderKey)) return false;
            LeaderKey other = (LeaderKey) obj;
            return dimension == other.dimension && leaderId.equals(other.leaderId);
        }

        @Override
        public int hashCode() {
            int result = dimension;
            result = 31 * result + leaderId.hashCode();
            return result;
        }
    }

    public static final class SlotAssignment {
        public final FollowMode mode;
        public final int ordinal;
        public final int groupSize;
        public final double compactness;
        public final double openness;
        public final double leaderDirX;
        public final double leaderDirZ;
        public final double leaderSpeed;
        public final boolean leaderActuallyMoving;
        public final double idealX;
        public final double idealY;
        public final double idealZ;
        public final double preferredRadius;
        public final double arriveRadius;
        public final double frontPenaltyStrength;
        public final boolean underground;
        public final double tunnelStrength;
        public final double caveSpread;
        public final boolean projectedGroundTarget;
        public final boolean workerLeader;
        public final int laneCount;
        /** True when this worker is in an escort slot (ordinal < numEscorts). */
        public final boolean isEscort;
        /**
         * Horizontal offset from the leader's position to this slot, in world coordinates.
         * Used to compute the slot's ideal position from the leader's CURRENT position each tick,
         * avoiding the stale-anchor lag that occurs between formation rebuilds.
         * Valid for non-projected-ground anchors only; zero when projectedGroundTarget is true.
         */
        public final double leaderOffsetDx;
        public final double leaderOffsetDz;

        SlotAssignment(FollowMode mode,
                               int ordinal,
                               int groupSize,
                               double compactness,
                               double openness,
                               double leaderDirX,
                               double leaderDirZ,
                               double leaderSpeed,
                               boolean leaderActuallyMoving,
                               double idealX,
                               double idealY,
                               double idealZ,
                               double preferredRadius,
                               double arriveRadius,
                               double frontPenaltyStrength,
                               boolean underground,
                               double tunnelStrength,
                               double caveSpread,
                               boolean projectedGroundTarget,
                               boolean workerLeader,
                               int laneCount,
                               boolean isEscort,
                               double leaderOffsetDx,
                               double leaderOffsetDz) {
            this.mode = mode;
            this.ordinal = ordinal;
            this.groupSize = groupSize;
            this.compactness = compactness;
            this.openness = openness;
            this.leaderDirX = leaderDirX;
            this.leaderDirZ = leaderDirZ;
            this.leaderSpeed = leaderSpeed;
            this.leaderActuallyMoving = leaderActuallyMoving;
            this.idealX = idealX;
            this.idealY = idealY;
            this.idealZ = idealZ;
            this.preferredRadius = preferredRadius;
            this.arriveRadius = arriveRadius;
            this.frontPenaltyStrength = frontPenaltyStrength;
            this.underground = underground;
            this.tunnelStrength = tunnelStrength;
            this.caveSpread = caveSpread;
            this.projectedGroundTarget = projectedGroundTarget;
            this.workerLeader = workerLeader;
            this.laneCount = laneCount;
            this.isEscort = isEscort;
            this.leaderOffsetDx = leaderOffsetDx;
            this.leaderOffsetDz = leaderOffsetDz;
        }

        /** Copy with idealX/idealZ replaced - see {@link FollowFormationCoordinator#reanchorToLiveLeaderPosition}. */
        private SlotAssignment withLiveIdealXZ(double liveIdealX, double liveIdealZ) {
            return new SlotAssignment(mode, ordinal, groupSize, compactness, openness, leaderDirX, leaderDirZ,
                    leaderSpeed, leaderActuallyMoving, liveIdealX, idealY, liveIdealZ,
                    preferredRadius, arriveRadius, frontPenaltyStrength, underground, tunnelStrength, caveSpread,
                    projectedGroundTarget, workerLeader, laneCount, isEscort, leaderOffsetDx, leaderOffsetDz);
        }
    }
}
