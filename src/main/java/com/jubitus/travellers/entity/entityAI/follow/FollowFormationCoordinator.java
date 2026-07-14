package com.jubitus.travellers.entity.entityAI.follow;

import com.jubitus.travellers.entity.entityAI.follow.formations.*;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.*;

public final class FollowFormationCoordinator {

    private static final Map<LeaderKey, LeaderContext> CONTEXTS = new HashMap<>();

    private static final int REFRESH_INTERVAL_MOVING = 8;
    private static final int REFRESH_INTERVAL_MOVING_LARGE = 18; // for groups > 200
    private static final int REFRESH_INTERVAL_IDLE = 40;
    private static final int REFRESH_INTERVAL_IDLE_LARGE = 80;  // for groups > 200
    private static final int CLEANUP_INTERVAL = 200;
    // Self-registration: how many ticks without a call before a member is considered gone
    private static final long MEMBER_EXPIRY_TICKS = 40L;
    private static final double PLAYER_MOVE_EPSILON_SQ = 0.012D * 0.012D;
    private static final double WORKER_MOVE_EPSILON_SQ = 0.060D * 0.060D;
    private static final double ESCORT_HYSTERESIS_DIST = 2.2D;


    private static final double HEADING_REVERSE_DOT_LIMIT = -0.82D;

    private static long lastCleanupTick = Long.MIN_VALUE;

    private FollowFormationCoordinator() {
    }

    public static SlotAssignment getSlot(EntityTravellerWorker follower,
                                         EntityLivingBase leader,
                                         FollowMode mode,
                                         FollowRuntimeConfig cfg,
                                         double baseFollowDistance) {
        World world = follower.world;
        LeaderKey key = new LeaderKey(world.provider.getDimension(), leader.getUniqueID());

        WorkerFormationScopeKey formationScope = WorkerFormationScopeResolver.resolveRuntimeScope(leader);
        WorkerFormationSettings formationSettings = formationScope == null
                ? WorkerFormationSettings.defaults()
                : WorkerFormationSavedData.get(world).getSettings(formationScope);

        long now = world.getTotalWorldTime();
        cleanup(now);

        LeaderContext ctx = CONTEXTS.get(key);
        if (ctx == null) {
            ctx = new LeaderContext(key);
            CONTEXTS.put(key, ctx);
        }

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

        return slot;
    }

    /**
     * Captures the current slot offset from the leader for the given worker.
     * Called at virtualization so MaterializePositionResolver can restore the
     * worker to its formation slot on re-materialization.
     *
     * @param out2 receives [dx, dz] — slot offset from leader in world coords
     * @return true if valid offset was stored in out2
     */
    public static boolean snapshotSlotOffset(UUID leaderId, int dimension, UUID workerId, double[] out2) {
        LeaderKey key = new LeaderKey(dimension, leaderId);
        LeaderContext ctx = CONTEXTS.get(key);
        if (ctx == null) return false;
        SlotAssignment slot = ctx.assignments.get(workerId);
        if (slot == null || slot.projectedGroundTarget) return false;
        double dx = slot.leaderOffsetDx;
        double dz = slot.leaderOffsetDz;
        if (Math.abs(dx) < 0.05 && Math.abs(dz) < 0.05) return false; // no meaningful offset
        out2[0] = dx;
        out2[1] = dz;
        return true;
    }

    public static void notifyWorkerRejoined(UUID workerId) {
        for (LeaderContext ctx : CONTEXTS.values()) {
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

        Iterator<Map.Entry<LeaderKey, LeaderContext>> it = CONTEXTS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<LeaderKey, LeaderContext> e = it.next();
            LeaderContext ctx = e.getValue();
            if (ctx == null || (now - ctx.lastSeenTick) > 20L * 30L) {
                it.remove();
            }
        }
    }

    private static final class LeaderKey {
        private final int dimension;
        private final UUID leaderId;

        private LeaderKey(int dimension, UUID leaderId) {
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

    private static final class LeaderContext {
        private final LeaderKey key;

        private long lastSeenTick;
        private long nextRefreshTick;
        private long lastHeadingTick = Long.MIN_VALUE;
        /**
         * Tick of the last heading-triggered rebuild. Rate-limits rapid heading rebuilds
         * caused by erratic worker-leader pathfinding (waypoint jumps).
         */
        private long lastHeadingRebuildTick = Long.MIN_VALUE;
        private int leaderStoppedTicks = 0;

        private double stableDirX = 0.0D;
        private double stableDirZ = 1.0D;
        private double leaderSpeed = 0.0D;
        private boolean leaderActuallyMoving = false;

        /** Heading direction at the last full rebuildAssignments() call. Used to detect significant rotations. */
        private double lastRebuildDirX = Double.NaN;
        private double lastRebuildDirZ = Double.NaN;
        /** Minimum heading angle change (radians) that forces an early rebuild. */
        private static final double HEADING_REBUILD_THRESHOLD_RAD_PLAYER = Math.toRadians(4.0D);
        private static final double HEADING_REBUILD_THRESHOLD_RAD_WORKER = Math.toRadians(11.0D);

        private double headingAnchorX = Double.NaN;
        private double headingAnchorZ = Double.NaN;
        private int headingAccumTicks = 0;
        private double lastMeaningfulHeadingSpeed = 0.0D;

        /** Hash of the last WorkerFormationSettings used for rebuild — detects GUI changes. */
        private int lastFormationSettingsHash = 0;

        private long lastForcedRebuildTick = Long.MIN_VALUE;

        private int groupSize = 1;
        private SpaceProfile profile = new SpaceProfile(false, 1.0D, 1.0D, 1, 0.0D, 1.0D);
        private GroundAnchor anchor = new GroundAnchor(0.0D, 0.0D, 0.0D, false);

        private final List<EntityTravellerWorker> members = new ArrayList<>();
        private final Map<UUID, SlotAssignment> assignments = new HashMap<>();
        private final Map<UUID, Integer> previousOrdinals = new HashMap<>();

        /**
         * Self-registration: workers register themselves in getSlot() each tick.
         * Replaces the expensive world.getEntitiesWithinAABB() scan in rebuildAssignments.
         */
        private final Map<UUID, EntityTravellerWorker> registeredMembers = new LinkedHashMap<>();
        private final Map<UUID, Long> memberLastSeen = new HashMap<>();

        /**
         * Persistent ordinal registry: workers keep their slot index even between rebuild cycles.
         */
        private final Map<UUID, Integer> persistentOrdinals = new HashMap<>();
        /**
         * Persistent formation-slot index (adj ordinal, independent of escort count).
         * Workers keep this across rebuilds so that changing numEscorts doesn't shuffle the formation.
         */
        private final Map<UUID, Integer> persistentFormOrdinals = new HashMap<>();
        /** Total known workforce size. */
        private int lastKnownGroupSize = 1;
        /** Eligible member count at last heading-update tick. -1 = never computed. */
        private int lastEligibleCount = -1;

        private LeaderContext(LeaderKey key) {
            this.key = key;
        }

        void registerMember(EntityTravellerWorker worker, long now) {
            Long lastSeen = memberLastSeen.get(worker.getUniqueID());
            // Worker was absent long enough to be considered gone: reset its slot so it rejoins
            // at the correct position for the current group size instead of using a stale ordinal.
            if (lastSeen == null || (now - lastSeen) > MEMBER_EXPIRY_TICKS) {
                persistentOrdinals.remove(worker.getUniqueID());
                persistentFormOrdinals.remove(worker.getUniqueID());
            }
            registeredMembers.put(worker.getUniqueID(), worker);
            memberLastSeen.put(worker.getUniqueID(), now);
        }

        private int countEligible(@Nullable UUID ownerId, EntityLivingBase leader, long now) {
            int count = 0;
            for (Map.Entry<UUID, EntityTravellerWorker> e : registeredMembers.entrySet()) {
                EntityTravellerWorker w = e.getValue();
                Long lastSeen = memberLastSeen.get(e.getKey());
                if (lastSeen == null || (now - lastSeen) > MEMBER_EXPIRY_TICKS || w.isDead) continue;
                if (ownerMatches(w, ownerId) && w.getEffectiveFollowTarget() == leader)
                    count++;
            }
            return count;
        }

        private void ensureFresh(World world,
                                 EntityLivingBase leader,
                                 @Nullable UUID ownerId,
                                 long now,
                                 FollowMode mode,
                                 FollowRuntimeConfig cfg,
                                 double baseFollowDistance,
                                 WorkerFormationSettings formationSettings) {
            lastSeenTick = now;

            // Guard: heading update runs exactly once per world-tick per leader, not once per follower.
            // Without this, 100 followers calling getSlot() would apply the smoothing blend 100×/tick,
            // making the heading converge instantly and causing workers to spin.
            if (lastHeadingTick != now) {
                lastHeadingTick = now;
                updateStableHeading(leader, formationSettings);
                int eligible = countEligible(ownerId, leader, now);
                if (lastEligibleCount >= 0 && eligible != lastEligibleCount) {
                    nextRefreshTick = Math.min(nextRefreshTick, now);
                }
                lastEligibleCount = eligible;
            }

            // Also force a rebuild when the heading has rotated enough that slot positions are stale.
            // Rate-limited to avoid rapid-fire rebuilds from erratic worker-leader pathfinding.
            boolean workerLeaderNow = leader instanceof EntityTravellerWorker;
            double headingThreshold = workerLeaderNow
                    ? HEADING_REBUILD_THRESHOLD_RAD_WORKER
                    : HEADING_REBUILD_THRESHOLD_RAD_PLAYER;

            boolean headingShifted = false;
            if (!Double.isNaN(lastRebuildDirX)) {
                double dot = stableDirX * lastRebuildDirX + stableDirZ * lastRebuildDirZ;
                if (dot < Math.cos(headingThreshold)) {
                    int minHeadingRebuildInterval = workerLeaderNow ? 18 : 5;
                    if (now - lastHeadingRebuildTick >= minHeadingRebuildInterval) {
                        headingShifted = true;
                    }
                }
            }

            int settingsHash = computeSettingsHash(formationSettings);
            boolean settingsChanged = settingsHash != lastFormationSettingsHash;

            boolean refreshNow = now >= nextRefreshTick || assignments.isEmpty() || headingShifted || settingsChanged;
            if (refreshNow) {
                lastFormationSettingsHash = settingsHash;
                if (headingShifted) lastHeadingRebuildTick = now;
                rebuildAssignments(world, leader, ownerId, now, mode, cfg, baseFollowDistance, formationSettings, settingsChanged);
            }
        }

        private void updateStableHeading(EntityLivingBase leader, WorkerFormationSettings formationSettings) {
            boolean workerLeader = leader instanceof EntityTravellerWorker;

            double tickDx = leader.posX - leader.prevPosX;
            double tickDz = leader.posZ - leader.prevPosZ;
            double tickDistSq = tickDx * tickDx + tickDz * tickDz;

            double thresholdSq = workerLeader ? WORKER_MOVE_EPSILON_SQ : PLAYER_MOVE_EPSILON_SQ;
            boolean meaningfulTickMotion = tickDistSq > thresholdSq;

            if (Double.isNaN(headingAnchorX) || Double.isNaN(headingAnchorZ)) {
                headingAnchorX = leader.posX;
                headingAnchorZ = leader.posZ;
                headingAccumTicks = 0;
            }

            if (meaningfulTickMotion) {
                leaderSpeed = Math.sqrt(tickDistSq);
                leaderActuallyMoving = true;
                leaderStoppedTicks = 0;
            } else {
                leaderSpeed *= 0.88D;
                if (leaderSpeed < 0.02D) {
                    // Worker-leaders navigate around obstacles at variable speed, so require
                    // several consecutive slow ticks before declaring them stopped.
                    // Players stop almost instantly so 1 tick is enough.
                    int stopThreshold = workerLeader ? 8 : 1;
                    leaderStoppedTicks++;
                    if (leaderStoppedTicks >= stopThreshold) {
                        leaderActuallyMoving = false;
                    }
                } else {
                    leaderStoppedTicks = 0;
                }
            }

            headingAccumTicks++;

            double accDx = leader.posX - headingAnchorX;
            double accDz = leader.posZ - headingAnchorZ;
            double accDistSq = accDx * accDx + accDz * accDz;

            WorkerFormationStyle style = formationSettings.style != null ? formationSettings.style : WorkerFormationStyle.ADAPTIVE;
            double minAccDist;
            double minAngleRad;
            int maxAccumTicks;
            double headingBlendMin;
            double headingBlendMax;
            switch (style) {
                case COMPACT:
                    // Needs a bit more distance to commit — formation is less sensitive to heading
                    minAccDist      = workerLeader ? 3.0D : 4.5D;
                    minAngleRad     = Math.toRadians(12.0D);
                    maxAccumTicks   = 18;
                    headingBlendMin = 0.12D;
                    headingBlendMax = 0.32D;
                    break;
                case COLUMN:
                    minAccDist      = workerLeader ? 3.0D : 4.5D;
                    minAngleRad     = Math.toRadians(12.0D);
                    maxAccumTicks   = 12;
                    headingBlendMin = 0.14D;
                    headingBlendMax = 0.45D;
                    break;
                case LINE:
                    // LINE rotation is dramatic (all workers move sideways) so needs clear direction
                    minAccDist      = workerLeader ? 3.0D : 4.5D;
                    minAngleRad     = Math.toRadians(10.0D);
                    maxAccumTicks   = 10;
                    headingBlendMin = 0.20D;
                    headingBlendMax = 0.60D;
                    break;
                case WEDGE:
                    // Small group — respond more quickly to leader turns
                    minAccDist      = workerLeader ? 2.5D : 3.5D;
                    minAngleRad     = Math.toRadians(8.0D);
                    maxAccumTicks   = 8;
                    headingBlendMin = 0.22D;
                    headingBlendMax = 0.62D;
                    break;
                case ADAPTIVE:
                default:
                    minAccDist      = workerLeader ? 2.5D : 3.5D;
                    minAngleRad     = Math.toRadians(8.0D);
                    maxAccumTicks   = 12;
                    headingBlendMin = 0.18D;
                    headingBlendMax = 0.52D;
                    break;
            }
            boolean haveAccumulatedHeading = accDistSq >= (minAccDist * minAccDist);
            boolean forceWindowClose = headingAccumTicks >= maxAccumTicks;

            if (!haveAccumulatedHeading && !forceWindowClose) {
                return;
            }

            if (!haveAccumulatedHeading) {
                // Window timed out without enough net movement.
                // Apply a tiny continuous "drift nudge" toward whatever net direction there was,
                // so the heading slowly tracks the leader even during short, repeated movements.
                if (accDistSq > 0.4D * 0.4D && headingAccumTicks >= 3) {
                    double len = Math.sqrt(accDistSq);
                    double nx = accDx / len;
                    double nz = accDz / len;
                    double dot = stableDirX * nx + stableDirZ * nz;
                    // Only nudge if we're not going backwards (dot > -0.4)
                    if (dot > -0.4D) {
                        double nudge = 0.05D + (dot > 0.6D ? 0.03D : 0.0D); // stronger nudge when already aligned
                        double newDirX = stableDirX + nx * nudge;
                        double newDirZ = stableDirZ + nz * nudge;
                        double norm = Math.sqrt(newDirX * newDirX + newDirZ * newDirZ);
                        if (norm > 1.0E-6D) {
                            stableDirX = newDirX / norm;
                            stableDirZ = newDirZ / norm;
                        }
                    }
                }
                headingAnchorX = leader.posX;
                headingAnchorZ = leader.posZ;
                headingAccumTicks = 0;
                return;
            }

            double len = Math.sqrt(accDistSq);
            double nx = accDx / len;
            double nz = accDz / len;

            double currentAngle = Math.atan2(stableDirZ, stableDirX);
            double targetAngle = Math.atan2(nz, nx);
            double delta = wrapToPi(targetAngle - currentAngle);
            double absDelta = Math.abs(delta);

            if (absDelta < minAngleRad) {
                lastMeaningfulHeadingSpeed = len / Math.max(1.0D, headingAccumTicks);
                headingAnchorX = leader.posX;
                headingAnchorZ = leader.posZ;
                headingAccumTicks = 0;
                return;
            }

            double dot = stableDirX * nx + stableDirZ * nz;
            if (dot <= HEADING_REVERSE_DOT_LIMIT) {
                double leftDot = stableDirX * (-nz) + stableDirZ * nx;
                double rightDot = stableDirX * nz + stableDirZ * (-nx);
                if (leftDot >= rightDot) {
                    targetAngle = currentAngle + (Math.PI * 0.5D);
                } else {
                    targetAngle = currentAngle - (Math.PI * 0.5D);
                }
                delta = wrapToPi(targetAngle - currentAngle);
                absDelta = Math.abs(delta);
            }

            double blend = computeHeadingBlend(absDelta, formationSettings, workerLeader, len, headingAccumTicks, headingBlendMin, headingBlendMax);
            double newAngle = currentAngle + delta * blend;

            stableDirX = Math.cos(newAngle);
            stableDirZ = Math.sin(newAngle);

            double sl = Math.sqrt(stableDirX * stableDirX + stableDirZ * stableDirZ);
            if (sl > 1.0E-6D) {
                stableDirX /= sl;
                stableDirZ /= sl;
            } else {
                stableDirX = nx;
                stableDirZ = nz;
            }

            lastMeaningfulHeadingSpeed = len / Math.max(1.0D, headingAccumTicks);
            headingAnchorX = leader.posX;
            headingAnchorZ = leader.posZ;
            headingAccumTicks = 0;
        }

        private double computeHeadingBlend(double absDelta,
                                           WorkerFormationSettings formationSettings,
                                           boolean workerLeader,
                                           double accLen,
                                           int accTicks,
                                           double blendMin,
                                           double blendMax) {
            double smoothBase = formationSettings.getTurnSmoothingFactor();
            double range = blendMax - blendMin;

            double speed = accLen / Math.max(1.0D, accTicks);
            double speedFactor = MathHelper.clamp(speed * (workerLeader ? 5.2D : 4.4D), 0.0D, 1.0D);
            double angleFactor = MathHelper.clamp(absDelta / Math.toRadians(55.0D), 0.0D, 1.0D);

            double blend = blendMin
                    + (smoothBase * range * 0.72D)
                    + (speedFactor * range * 0.18D)
                    + (angleFactor * range * 0.10D);

            return MathHelper.clamp(blend, blendMin, blendMax);
        }

        private double wrapToPi(double angle) {
            while (angle > Math.PI) angle -= Math.PI * 2.0D;
            while (angle < -Math.PI) angle += Math.PI * 2.0D;
            return angle;
        }

        private static int computeSettingsHash(WorkerFormationSettings s) {
            int h = 17;
            h = 31 * h + s.numEscorts;
            h = 31 * h + (s.style != null ? s.style.ordinal() : -1);
            h = 31 * h + Double.hashCode(s.followDistance);
            h = 31 * h + Double.hashCode(s.spacing);
            h = 31 * h + s.width;
            return h;
        }

        private void rebuildAssignments(World world,
                                        EntityLivingBase leader,
                                        @Nullable UUID ownerId,
                                        long now,
                                        FollowMode mode,
                                        FollowRuntimeConfig cfg,
                                        double baseFollowDistance,
                                        WorkerFormationSettings formationSettings,
                                        boolean settingsChanged) {
            members.clear();

            // Build members from self-registered workers (no world AABB scan needed).
            // Workers register themselves in getSlot() every tick they are active.
            // Stale or dead entries are pruned here.
            Iterator<Map.Entry<UUID, EntityTravellerWorker>> regIt = registeredMembers.entrySet().iterator();
            while (regIt.hasNext()) {
                Map.Entry<UUID, EntityTravellerWorker> e = regIt.next();
                EntityTravellerWorker w = e.getValue();
                Long lastSeen = memberLastSeen.get(e.getKey());
                boolean stale = lastSeen == null || (now - lastSeen) > MEMBER_EXPIRY_TICKS;
                if (stale || w.isDead) {
                    regIt.remove();
                    memberLastSeen.remove(e.getKey());
                    continue;
                }
                if (ownerMatches(w, ownerId) && w.getEffectiveFollowTarget() == leader) {
                    members.add(w);
                }
            }

            groupSize = Math.max(1, members.size());
            profile = sampleSpaceProfile(world, leader, stableDirX, stableDirZ, mode, cfg);
            anchor = computeAnchor(world, leader);

            List<EntityTravellerWorker> ordered = buildOrderedMembers(
                    members, formationSettings.numEscorts, leader, previousOrdinals);

            assignments.clear();
            previousOrdinals.clear();

            // Purge stale ordinals: workers absent from registeredMembers, OR present but no longer
            // following this leader (e.g. switched to another leader or back to player). Without this,
            // a worker rejoining carries its old ordinal from a larger group and gets placed far away.
            persistentOrdinals.keySet().removeIf(uuid -> {
                if (!registeredMembers.containsKey(uuid)) return true;
                EntityTravellerWorker w = registeredMembers.get(uuid);
                return w.isDead || !ownerMatches(w, ownerId) || w.getEffectiveFollowTarget() != leader;
            });
            persistentFormOrdinals.keySet().removeIf(uuid -> {
                if (!registeredMembers.containsKey(uuid)) return true;
                EntityTravellerWorker w = registeredMembers.get(uuid);
                return w.isDead || !ownerMatches(w, ownerId) || w.getEffectiveFollowTarget() != leader;
            });

            int totalKnownSize = groupSize;

            double anchorX = anchor.useProjectedGround ? anchor.x : leader.posX;
            double anchorY = mode == FollowMode.CAVE
                    ? leader.getEntityBoundingBox().minY
                    : (anchor.useProjectedGround ? anchor.y : leader.getEntityBoundingBox().minY);
            double anchorZ = anchor.useProjectedGround ? anchor.z : leader.posZ;
            boolean workerLeader = leader instanceof EntityTravellerWorker;

            int numEscorts = Math.min(formationSettings.numEscorts, ordered.size());

            // Escort slots: always the first numEscorts entries in ordered (already proximity-sorted)
            for (int i = 0; i < numEscorts; i++) {
                EntityTravellerWorker worker = ordered.get(i);
                SlotOffset offset = computeSlotOffset(i, totalKnownSize, stableDirX, stableDirZ,
                        profile, mode, cfg, baseFollowDistance, key.leaderId, formationSettings, workerLeader);
                SlotAssignment slot = new SlotAssignment(mode, i, totalKnownSize,
                        profile.compactness, profile.openness, stableDirX, stableDirZ,
                        leaderSpeed, leaderActuallyMoving,
                        anchorX + offset.dx, anchorY, anchorZ + offset.dz,
                        offset.preferredRadius, offset.arriveRadius, offset.frontPenaltyStrength,
                        mode == FollowMode.CAVE, profile.tunnelStrength, profile.caveSpread,
                        anchor.useProjectedGround, workerLeader, profile.suggestedTunnelLanes,
                        true,
                        anchor.useProjectedGround ? 0.0D : offset.dx,
                        anchor.useProjectedGround ? 0.0D : offset.dz);
                assignments.put(worker.getUniqueID(), slot);
                previousOrdinals.put(worker.getUniqueID(), i);
                persistentOrdinals.put(worker.getUniqueID(), i);
                persistentFormOrdinals.remove(worker.getUniqueID());
            }

            // Formation slots: greedy nearest-slot assignment to avoid cascading reshuffles.
            // Workers keep their previous adj-ordinal (stability), unassigned workers find nearest slot.
            // On settings change the stability pass is skipped so workers redistribute optimally.
            List<EntityTravellerWorker> formList = ordered.subList(numEscorts, ordered.size());
            int formSize = formList.size();

            List<SlotOffset> formSlots = new ArrayList<>(formSize);
            for (int i = 0; i < formSize; i++) {
                formSlots.add(computeSlotOffset(i + numEscorts, totalKnownSize, stableDirX, stableDirZ,
                        profile, mode, cfg, baseFollowDistance, key.leaderId, formationSettings, workerLeader));
            }

            int[] workerToAdj = new int[formSize];
            Arrays.fill(workerToAdj, -1);
            boolean[] adjClaimed = new boolean[formSize];

            // Stability pass: workers reclaim their previous formation slot
            if (!settingsChanged) {
                for (int wi = 0; wi < formSize; wi++) {
                    Integer prevAdj = persistentFormOrdinals.get(formList.get(wi).getUniqueID());
                    if (prevAdj != null && prevAdj >= 0 && prevAdj < formSize && !adjClaimed[prevAdj]) {
                        workerToAdj[wi] = prevAdj;
                        adjClaimed[prevAdj] = true;
                    }
                }
            }

            // Greedy pass: unassigned workers claim the nearest unclaimed slot
            for (int wi = 0; wi < formSize; wi++) {
                if (workerToAdj[wi] != -1) continue;
                EntityTravellerWorker w = formList.get(wi);
                double bestDSq = Double.MAX_VALUE;
                int bestSi = -1;
                for (int si = 0; si < formSize; si++) {
                    if (adjClaimed[si]) continue;
                    SlotOffset off = formSlots.get(si);
                    double dx = w.posX - (anchorX + off.dx);
                    double dz = w.posZ - (anchorZ + off.dz);
                    double dSq = dx * dx + dz * dz;
                    if (dSq < bestDSq) { bestDSq = dSq; bestSi = si; }
                }
                if (bestSi < 0) bestSi = wi; // unreachable in valid state
                workerToAdj[wi] = bestSi;
                adjClaimed[bestSi] = true;
            }

            for (int wi = 0; wi < formSize; wi++) {
                EntityTravellerWorker w = formList.get(wi);
                int adjOrd = workerToAdj[wi];
                int ordinal = adjOrd + numEscorts;
                persistentFormOrdinals.put(w.getUniqueID(), adjOrd);
                previousOrdinals.put(w.getUniqueID(), ordinal);
                persistentOrdinals.put(w.getUniqueID(), ordinal);
                SlotOffset off = formSlots.get(adjOrd);
                SlotAssignment slot = new SlotAssignment(mode, ordinal, totalKnownSize,
                        profile.compactness, profile.openness, stableDirX, stableDirZ,
                        leaderSpeed, leaderActuallyMoving,
                        anchorX + off.dx, anchorY, anchorZ + off.dz,
                        off.preferredRadius, off.arriveRadius, off.frontPenaltyStrength,
                        mode == FollowMode.CAVE, profile.tunnelStrength, profile.caveSpread,
                        anchor.useProjectedGround, workerLeader, profile.suggestedTunnelLanes,
                        false,
                        anchor.useProjectedGround ? 0.0D : off.dx,
                        anchor.useProjectedGround ? 0.0D : off.dz);
                assignments.put(w.getUniqueID(), slot);
            }

            lastKnownGroupSize = Math.max(totalKnownSize, numEscorts + formSize);
            // Remember heading at rebuild time so we can detect rotation drift
            lastRebuildDirX = stableDirX;
            lastRebuildDirZ = stableDirZ;

            boolean largeGroup = lastKnownGroupSize > 200;

            int movingInterval = largeGroup ? REFRESH_INTERVAL_MOVING_LARGE : REFRESH_INTERVAL_MOVING;
            int idleInterval = largeGroup ? REFRESH_INTERVAL_IDLE_LARGE : REFRESH_INTERVAL_IDLE;

            if (workerLeader) {
                movingInterval += largeGroup ? 24 : 14;
                idleInterval += largeGroup ? 40 : 25;
            }

            int interval = leaderActuallyMoving ? movingInterval : idleInterval;
            nextRefreshTick = now + interval + Math.abs((leader.getEntityId() * 31) % 5);
        }

        private List<EntityTravellerWorker> buildOrderedMembers(
                List<EntityTravellerWorker> members,
                int numEscorts,
                EntityLivingBase leader,
                Map<UUID, Integer> prevOrdinals) {

            if (numEscorts <= 0 || members.size() <= numEscorts) {
                List<EntityTravellerWorker> r = new ArrayList<>(members);
                r.sort(new StableMemberComparator(prevOrdinals));
                return r;
            }

            final double lx = leader.posX, lz = leader.posZ;

            // Precompute a stable score per worker: dist minus a bonus for workers already in escort.
            // Sorting by a single scalar is always transitive — avoids the "violates contract" crash.
            List<EntityTravellerWorker> byDist = new ArrayList<>(members);
            final Map<UUID, Double> escortScore = new HashMap<>();
            for (EntityTravellerWorker w : members) {
                double dx = w.posX - lx, dz = w.posZ - lz;
                double dist = Math.sqrt(dx * dx + dz * dz);
                Integer prev = prevOrdinals.get(w.getUniqueID());
                double bonus = (prev != null && prev < numEscorts) ? ESCORT_HYSTERESIS_DIST : 0.0D;
                escortScore.put(w.getUniqueID(), dist - bonus);
            }
            byDist.sort((a, b) -> {
                int cmp = Double.compare(escortScore.get(a.getUniqueID()), escortScore.get(b.getUniqueID()));
                if (cmp != 0) return cmp;
                Integer oa = prevOrdinals.get(a.getUniqueID());
                Integer ob = prevOrdinals.get(b.getUniqueID());
                int ia = oa != null ? oa : Integer.MAX_VALUE;
                int ib = ob != null ? ob : Integer.MAX_VALUE;
                if (ia != ib) return Integer.compare(ia, ib);
                return a.getUniqueID().compareTo(b.getUniqueID());
            });

            Set<UUID> escortSet = new HashSet<>();
            List<EntityTravellerWorker> escortWorkers = new ArrayList<>(numEscorts);
            for (int i = 0; i < numEscorts; i++) {
                EntityTravellerWorker ew = byDist.get(i);
                escortWorkers.add(ew);
                escortSet.add(ew.getUniqueID());
            }
            escortWorkers.sort(new StableMemberComparator(prevOrdinals));

            List<EntityTravellerWorker> stableAll = new ArrayList<>(members);
            stableAll.sort(new StableMemberComparator(prevOrdinals));
            List<EntityTravellerWorker> formWorkers = new ArrayList<>(members.size() - numEscorts);
            for (EntityTravellerWorker w : stableAll) {
                if (!escortSet.contains(w.getUniqueID())) formWorkers.add(w);
            }

            List<EntityTravellerWorker> result = new ArrayList<>(members.size());
            result.addAll(escortWorkers);
            result.addAll(formWorkers);
            return result;
        }

        private SlotAssignment createFallbackSlot(EntityTravellerWorker follower,
                                                  EntityLivingBase leader,
                                                  FollowMode mode,
                                                  FollowRuntimeConfig cfg,
                                                  double baseFollowDistance,
                                                  WorkerFormationSettings formationSettings) {
            Integer registered = persistentOrdinals.get(follower.getUniqueID());
            int fallbackOrdinal;
            int fallbackGroupSize;
            if (registered != null) {
                fallbackOrdinal = registered;
                fallbackGroupSize = Math.max(lastKnownGroupSize, registered + 1);
            } else {
                // Use lastKnownGroupSize (updated each rebuild) as the ordinal, NOT
                // persistentOrdinals.size() which can be inflated by stale entries from
                // workers that previously followed this leader but changed their target.
                fallbackOrdinal = lastKnownGroupSize;
                persistentOrdinals.put(follower.getUniqueID(), fallbackOrdinal);
                lastKnownGroupSize = Math.max(lastKnownGroupSize, fallbackOrdinal + 1);
                fallbackGroupSize = lastKnownGroupSize;
            }

            boolean workerLeader = leader instanceof EntityTravellerWorker;

            SlotOffset offset = computeSlotOffset(
                    fallbackOrdinal,
                    fallbackGroupSize,
                    stableDirX,
                    stableDirZ,
                    profile,
                    mode,
                    cfg,
                    baseFollowDistance,
                    leader.getUniqueID(),
                    formationSettings,
                    workerLeader
            );

            double anchorX = anchor.useProjectedGround ? anchor.x : leader.posX;
            double anchorY = mode == FollowMode.CAVE
                    ? leader.getEntityBoundingBox().minY
                    : (anchor.useProjectedGround ? anchor.y : leader.getEntityBoundingBox().minY);
            double anchorZ = anchor.useProjectedGround ? anchor.z : leader.posZ;

            return new SlotAssignment(
                    mode,
                    fallbackOrdinal,
                    fallbackGroupSize,
                    profile.compactness,
                    profile.openness,
                    stableDirX,
                    stableDirZ,
                    leaderSpeed,
                    leaderActuallyMoving,
                    anchorX + offset.dx,
                    anchorY,
                    anchorZ + offset.dz,
                    offset.preferredRadius,
                    offset.arriveRadius,
                    offset.frontPenaltyStrength,
                    mode == FollowMode.CAVE,
                    profile.tunnelStrength,
                    profile.caveSpread,
                    anchor.useProjectedGround,
                    workerLeader,
                    profile.suggestedTunnelLanes,
                    false,
                    anchor.useProjectedGround ? 0.0D : offset.dx,
                    anchor.useProjectedGround ? 0.0D : offset.dz
            );
        }

        private static boolean ownerMatches(EntityTravellerWorker worker, @Nullable UUID ownerId) {
            UUID wid = worker.getFollowOwnerId();
            if (ownerId == null) {
                return wid == null;
            }
            return ownerId.equals(wid);
        }
    }

    private static GroundAnchor computeAnchor(World world, EntityLivingBase leader) {
        if (leader.isRiding() && leader.getRidingEntity() instanceof EntityBoat) {
            return new GroundAnchor(leader.posX, leader.getEntityBoundingBox().minY, leader.posZ, false);
        }

        BlockPos feet = new BlockPos(leader.posX, leader.getEntityBoundingBox().minY, leader.posZ);
        boolean airborne = !leader.onGround && !leader.isInWater() && leader.motionY > -0.08D;

        BlockPos top = world.getTopSolidOrLiquidBlock(new BlockPos(feet.getX(), 0, feet.getZ()));
        int verticalGap = feet.getY() - top.getY();

        if (airborne && verticalGap >= 5 && world.canSeeSky(top)) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos p = new BlockPos(top.getX() + dx, top.getY(), top.getZ() + dz);
                    if (FollowRecoveryHelper.isStandable(world, p)) {
                        return new GroundAnchor(p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D, true);
                    }
                }
            }
        }

        return new GroundAnchor(leader.posX, leader.getEntityBoundingBox().minY, leader.posZ, false);
    }

    private static SpaceProfile sampleSpaceProfile(World world,
                                                   EntityLivingBase leader,
                                                   double forwardX,
                                                   double forwardZ,
                                                   FollowMode mode,
                                                   FollowRuntimeConfig cfg) {
        BlockPos base = new BlockPos(leader.posX, leader.getEntityBoundingBox().minY, leader.posZ);

        int total = 0;
        int openForward = 0;
        int openSide = 0;

        double rx = -forwardZ;
        double rz = forwardX;

        for (int step = 1; step <= 4; step++) {
            BlockPos pf = new BlockPos(
                    MathHelper.floor(leader.posX + forwardX * step),
                    MathHelper.floor(leader.posY),
                    MathHelper.floor(leader.posZ + forwardZ * step)
            );
            BlockPos pb = new BlockPos(
                    MathHelper.floor(leader.posX - forwardX * step),
                    MathHelper.floor(leader.posY),
                    MathHelper.floor(leader.posZ - forwardZ * step)
            );
            BlockPos pl = new BlockPos(
                    MathHelper.floor(leader.posX + rx * step),
                    MathHelper.floor(leader.posY),
                    MathHelper.floor(leader.posZ + rz * step)
            );
            BlockPos pr = new BlockPos(
                    MathHelper.floor(leader.posX - rx * step),
                    MathHelper.floor(leader.posY),
                    MathHelper.floor(leader.posZ - rz * step)
            );

            if (isColumnTraversable(world, pf)) openForward++;
            if (isColumnTraversable(world, pb)) openForward++;
            if (isColumnTraversable(world, pl)) openSide++;
            if (isColumnTraversable(world, pr)) openSide++;

            total += 4;
        }

        double openness = total <= 0 ? 1.0D : (double) (openForward + openSide) / (double) total;
        boolean underground = mode == FollowMode.CAVE
                || FollowRecoveryHelper.isLeaderMeaningfullyUnderground(world, leader, cfg)
                || FollowRecoveryHelper.isEnclosedFollowSpace(world, leader, cfg);

        double compactness = underground ? 0.70D : (openness < 0.45D ? 0.88D : 1.0D);
        double tunnelStrength = underground
                ? MathHelper.clamp((openForward / 8.0D) - (openSide / 10.0D) + 0.35D, 0.0D, 1.0D)
                : 0.0D;
        double caveSpread = underground ? MathHelper.clamp(openSide / 8.0D, 0.0D, 1.0D) : 1.0D;

        int lanes;
        if (!underground) {
            lanes = 0;
        } else if (openSide >= 6) {
            lanes = Math.min(3, cfg.group.maxTunnelLanes);
        } else if (openSide >= 3) {
            lanes = Math.min(2, cfg.group.maxTunnelLanes);
        } else {
            lanes = 1;
        }

        return new SpaceProfile(underground, openness, compactness, lanes, tunnelStrength, caveSpread);
    }

    private static boolean isColumnTraversable(World world, BlockPos feet) {
        if (!world.isBlockLoaded(feet) || !world.isBlockLoaded(feet.up()) || !world.isBlockLoaded(feet.down())) {
            return false;
        }

        if (world.getBlockState(feet.down()).getMaterial().isLiquid()) {
            return false;
        }

        boolean freeFeet = world.getBlockState(feet).getCollisionBoundingBox(world, feet) == null;
        boolean freeHead = world.getBlockState(feet.up()).getCollisionBoundingBox(world, feet.up()) == null;
        return freeFeet && freeHead;
    }

    private static SlotOffset computeSlotOffset(int ordinal,
                                                int groupSize,
                                                double forwardX,
                                                double forwardZ,
                                                SpaceProfile profile,
                                                FollowMode mode,
                                                FollowRuntimeConfig cfg,
                                                double baseFollowDistance,
                                                UUID leaderId,
                                                WorkerFormationSettings formationSettings,
                                                boolean workerLeader) {
        // Close-escort slots: first numEscorts ordinals are kept 2-3 blocks from the leader.
        // When groupSize <= numEscorts all workers are escorts — use effective count for correct spread.
        int numEscorts = formationSettings.numEscorts;
        if (numEscorts > 0 && ordinal < numEscorts) {
            int effectiveEscorts = Math.min(numEscorts, groupSize);
            return computeEscortOffset(ordinal, effectiveEscorts, forwardX, forwardZ);
        }

        // Shift ordinal so the formation starts after the escort slots
        int adjOrdinal   = ordinal - numEscorts;
        int adjGroupSize = Math.max(1, groupSize - numEscorts);

        if (mode == FollowMode.CAVE) {
            return computeCaveOffset(adjOrdinal, adjGroupSize, forwardX, forwardZ, profile, cfg, formationSettings);
        }
        return computeSurfaceOffset(
                adjOrdinal,
                adjGroupSize,
                forwardX,
                forwardZ,
                profile,
                mode,
                cfg,
                baseFollowDistance,
                leaderId,
                formationSettings,
                workerLeader
        );
    }

    /**
     * Escort slot: places the worker close (2–3 blocks) behind the leader, slightly to the side.
     * These workers never join the main formation geometry and are always near enough to right-click.
     */
    private static SlotOffset computeEscortOffset(int escortIndex, int numEscorts, double forwardX, double forwardZ) {
        double rx = -forwardZ;
        double rz =  forwardX;

        // Spread escorts evenly across a small arc behind the leader
        // 1 escort  → directly behind
        // 2 escorts → behind-left and behind-right
        double side;
        double back = 2.5D;
        if (numEscorts == 1) {
            side = 0.0D;
        } else if (numEscorts == 2) {
            side = escortIndex == 0 ? -1.4D : 1.4D;
        } else {
            // Up to 4 per row; overflow goes to a second row 2 blocks further back
            int perRow = Math.min(numEscorts, 4);
            int row = escortIndex / perRow;
            int col = escortIndex % perRow;
            int rowSize = row == 0 ? Math.min(numEscorts, perRow) : (numEscorts - perRow);
            side = rowSize <= 1 ? 0.0D : (col - (rowSize - 1) * 0.5D) * 1.4D;
            back = 2.5D + row * 2.0D;
        }

        return new SlotOffset(
                -forwardX * back + rx * side,
                -forwardZ * back + rz * side,
                back,
                1.1D,
                0.0D
        );
    }

    private static SlotOffset computeCaveOffset(int ordinal,
                                                int groupSize,
                                                double forwardX,
                                                double forwardZ,
                                                SpaceProfile profile,
                                                FollowRuntimeConfig cfg,
                                                WorkerFormationSettings settings) {
        double rx = -forwardZ;
        double rz = forwardX;

        WorkerFormationStyle style = resolveCaveStyle(settings, profile);

        double spacingMul = settings.spacing;
        double dist = Math.max(cfg.cave.baseLeaderDistance, settings.followDistance * 0.85D);

        double laneSpacing = Math.max(0.50D, cfg.cave.laneSpacing * spacingMul * (settings.adaptiveCave ? 0.72D : 0.92D));
        double rowSpacing = Math.max(0.85D, cfg.cave.rowSpacing * spacingMul * (settings.adaptiveCave ? 0.78D : 0.95D));

        int maxTunnelLanes = Math.max(1, profile.suggestedTunnelLanes);
        if (settings.adaptiveCave) {
            maxTunnelLanes = Math.min(maxTunnelLanes, settings.width >= 5 ? 2 : 1);
        } else {
            maxTunnelLanes = Math.min(Math.max(1, settings.width), Math.max(1, cfg.group.maxTunnelLanes));
        }

        switch (style) {
            case LINE: {
                int perLine = Math.max(1, Math.min(maxTunnelLanes, Math.min(3, settings.width)));
                int rank = ordinal / perLine;
                int lineIndex = ordinal % perLine;

                double alongLine = (lineIndex - (perLine - 1) * 0.5D) * laneSpacing;
                double back = dist + rank * rowSpacing;

                return new SlotOffset(
                        -forwardX * back + rx * alongLine,
                        -forwardZ * back + rz * alongLine,
                        back,
                        cfg.cave.arriveRadius + Math.min(0.40D, rank * 0.05D),
                        0.08D
                );
            }

            case WEDGE:
            case COMPACT: {
                int lanes = Math.max(1, Math.min(maxTunnelLanes, 2));
                int row = ordinal / lanes;
                int lane = ordinal % lanes;
                double side = lanes <= 1 ? 0.0D : (lane == 0 ? -laneSpacing * 0.5D : laneSpacing * 0.5D);
                double back = dist + row * rowSpacing;
                return new SlotOffset(
                        -forwardX * back + rx * side,
                        -forwardZ * back + rz * side,
                        back,
                        cfg.cave.arriveRadius + Math.min(0.45D, row * 0.05D),
                        0.08D
                );
            }

            case COLUMN:
            default: {
                int lanes = settings.width >= 6 && maxTunnelLanes >= 2 ? 2 : 1;
                int row = ordinal / lanes;
                int lane = ordinal % lanes;
                double side = lanes == 1 ? 0.0D : (lane == 0 ? -laneSpacing * 0.42D : laneSpacing * 0.42D);
                double back = dist + row * rowSpacing * 0.95D;
                return new SlotOffset(
                        -forwardX * back + rx * side,
                        -forwardZ * back + rz * side,
                        back,
                        cfg.cave.arriveRadius + Math.min(0.45D, row * 0.05D),
                        0.08D
                );
            }
        }
    }

    private static SlotOffset computeSurfaceOffset(int ordinal,
                                                   int groupSize,
                                                   double forwardX,
                                                   double forwardZ,
                                                   SpaceProfile profile,
                                                   FollowMode mode,
                                                   FollowRuntimeConfig cfg,
                                                   double baseFollowDistance,
                                                   UUID leaderId,
                                                   WorkerFormationSettings settings,
                                                   boolean workerLeader) {
        double rx = -forwardZ;
        double rz = forwardX;

        WorkerFormationStyle style = resolveSurfaceStyle(settings.style, groupSize, workerLeader);

        double spacingMul = settings.spacing;
        double widthMul = 0.70D + settings.width * 0.20D;

        // No group-size back-off: the formation distance is exactly what the GUI says,
        // regardless of how many followers there are. Large groups simply extend further back.
        double baseDist = Math.max(baseFollowDistance, settings.followDistance);

        double ringSpacing = Math.max(1.00D, cfg.surface.ringSpacing * spacingMul);
        double rowSpacing = Math.max(1.15D, cfg.surface.rowSpacing * spacingMul);
        double laneSpacing = Math.max(1.05D, cfg.surface.laneSpacing * spacingMul * widthMul);
        double stagger = cfg.surface.staggerOffset * Math.min(1.65D, 0.70D + settings.width * 0.18D);

        switch (style) {
            case LINE: {
                // Perpendicular line: workers spread sideways (±right vector), stacked in ranks behind.
                // Row length is capped at maxSurfaceLanes (same cap COMPACT/COLUMN already use)
                // so very large groups grow backward (more ranks) instead of sideways past the
                // distance workers can reliably reach a slot. Spacing (laneSpacing, below) still
                // scales with the full width setting - only the row length is bounded.
                int perLine = Math.max(1, Math.min(settings.width, cfg.group.maxSurfaceLanes));
                int rank = ordinal / perLine;
                int lineIndex = ordinal % perLine;

                double alongLine = (lineIndex - (perLine - 1) * 0.5D) * laneSpacing;
                double back = baseDist + rank * rowSpacing;

                return new SlotOffset(
                        -forwardX * back + rx * alongLine,
                        -forwardZ * back + rz * alongLine,
                        back,
                        cfg.surface.arriveRadius + Math.min(0.35D, rank * 0.04D),
                        0.70D
                );
            }

            case COLUMN: {
                int lanes = settings.width >= 5 ? 2 : 1;
                int row = ordinal / lanes;
                int lane = ordinal % lanes;

                double side = lanes == 1 ? 0.0D : (lane == 0 ? -laneSpacing * 0.40D : laneSpacing * 0.40D);
                double back = baseDist + row * rowSpacing * 0.95D;

                return new SlotOffset(
                        -forwardX * back + rx * side,
                        -forwardZ * back + rz * side,
                        back,
                        cfg.surface.arriveRadius + Math.min(0.35D, row * 0.05D),
                        0.55D
                );
            }

            case WEDGE: {
                int row = 0;
                int remaining = ordinal;
                int rowCount = 1;

                while (remaining >= rowCount) {
                    remaining -= rowCount;
                    row++;
                    rowCount = Math.max(2, row * 2);
                }

                double back = baseDist + row * rowSpacing;
                double side = rowCount == 1 ? 0.0D : (remaining - (rowCount - 1) * 0.5D) * laneSpacing * 0.82D;

                return new SlotOffset(
                        -forwardX * back + rx * side,
                        -forwardZ * back + rz * side,
                        back,
                        cfg.surface.arriveRadius + Math.min(0.35D, row * 0.06D),
                        1.05D
                );
            }

            case COMPACT:
            default: {
                int firstRing = Math.min(Math.max(6, settings.width * 2), Math.max(6, Math.min(10, groupSize)));
                if (ordinal < firstRing) {
                    double t = firstRing <= 1 ? 0.5D : (double) ordinal / (double) (firstRing - 1);
                    double angleDeg = -135.0D + 270.0D * t;

                    if (Math.abs(angleDeg) < 24.0D) {
                        int sideSign = (((leaderId.hashCode() >> 1) + ordinal) & 1) == 0 ? -1 : 1;
                        angleDeg = 24.0D * sideSign;
                    }

                    double angle = Math.toRadians(angleDeg);
                    double radius = baseDist + (ordinal >= Math.max(4, firstRing / 2) ? ringSpacing * 0.75D : 0.0D);

                    double back = Math.cos(angle) * radius;
                    double side = Math.sin(angle) * radius;

                    return new SlotOffset(
                            -forwardX * back + rx * side,
                            -forwardZ * back + rz * side,
                            radius,
                            cfg.surface.arriveRadius,
                            1.10D
                    );
                }

                int idx = ordinal - firstRing;
                int lanes = Math.min(cfg.group.maxSurfaceLanes, Math.max(3, settings.width + 2));
                int row = 1 + idx / lanes;
                int col = idx % lanes;

                double side = (col - (lanes - 1) * 0.5D) * laneSpacing;
                if ((row & 1) == 1) {
                    int sign = (((leaderId.hashCode() >> row) + col) & 1) == 0 ? -1 : 1;
                    side += stagger * sign;
                }

                double back = baseDist + ringSpacing + rowSpacing * row;

                return new SlotOffset(
                        -forwardX * back + rx * side,
                        -forwardZ * back + rz * side,
                        back,
                        cfg.surface.arriveRadius + Math.min(0.40D, row * 0.08D),
                        0.80D
                );
            }
        }
    }

    private static WorkerFormationStyle resolveSurfaceStyle(WorkerFormationStyle requested,
                                                            int groupSize,
                                                            boolean workerLeader) {
        if (requested != WorkerFormationStyle.ADAPTIVE) {
            return requested;
        }

        if (workerLeader) {
            if (groupSize <= 6)  return WorkerFormationStyle.WEDGE;
            if (groupSize <= 18) return WorkerFormationStyle.COLUMN;
            return WorkerFormationStyle.LINE;
        }

        if (groupSize <= 8)  return WorkerFormationStyle.WEDGE;
        if (groupSize <= 60) return WorkerFormationStyle.COMPACT;
        return WorkerFormationStyle.LINE;
    }

    private static WorkerFormationStyle resolveCaveStyle(WorkerFormationSettings settings, SpaceProfile profile) {
        if (settings.style == WorkerFormationStyle.ADAPTIVE) {
            return profile.tunnelStrength >= 0.40D ? WorkerFormationStyle.COLUMN : WorkerFormationStyle.LINE;
        }

        if (!settings.adaptiveCave) {
            return settings.style;
        }

        if (profile.tunnelStrength >= 0.40D) {
            return WorkerFormationStyle.COLUMN;
        }

        if (settings.style == WorkerFormationStyle.WEDGE) {
            return WorkerFormationStyle.LINE;
        }

        return settings.style;
    }

    private static final class StableMemberComparator implements Comparator<EntityTravellerWorker> {
        private final Map<UUID, Integer> previousOrdinals;

        private StableMemberComparator(Map<UUID, Integer> previousOrdinals) {
            this.previousOrdinals = previousOrdinals;
        }

        @Override
        public int compare(EntityTravellerWorker a, EntityTravellerWorker b) {
            Integer pa = previousOrdinals.get(a.getUniqueID());
            Integer pb = previousOrdinals.get(b.getUniqueID());

            if (pa != null && pb != null && !pa.equals(pb)) return Integer.compare(pa, pb);
            if (pa != null && pb == null) return -1;
            if (pa == null && pb != null) return 1;

            int ha = a.getUniqueID().hashCode();
            int hb = b.getUniqueID().hashCode();
            if (ha != hb) return Integer.compare(ha, hb);

            return Integer.compare(a.getEntityId(), b.getEntityId());
        }
    }

    private static final class GroundAnchor {
        private final double x;
        private final double y;
        private final double z;
        private final boolean useProjectedGround;

        private GroundAnchor(double x, double y, double z, boolean useProjectedGround) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.useProjectedGround = useProjectedGround;
        }
    }

    private static final class SpaceProfile {
        private final boolean underground;
        private final double openness;
        private final double compactness;
        private final int suggestedTunnelLanes;
        private final double tunnelStrength;
        private final double caveSpread;

        private SpaceProfile(boolean underground,
                             double openness,
                             double compactness,
                             int suggestedTunnelLanes,
                             double tunnelStrength,
                             double caveSpread) {
            this.underground = underground;
            this.openness = openness;
            this.compactness = compactness;
            this.suggestedTunnelLanes = suggestedTunnelLanes;
            this.tunnelStrength = tunnelStrength;
            this.caveSpread = caveSpread;
        }
    }

    private static final class SlotOffset {
        private final double dx;
        private final double dz;
        private final double preferredRadius;
        private final double arriveRadius;
        private final double frontPenaltyStrength;

        private SlotOffset(double dx,
                           double dz,
                           double preferredRadius,
                           double arriveRadius,
                           double frontPenaltyStrength) {
            this.dx = dx;
            this.dz = dz;
            this.preferredRadius = preferredRadius;
            this.arriveRadius = arriveRadius;
            this.frontPenaltyStrength = frontPenaltyStrength;
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

        private SlotAssignment(FollowMode mode,
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
    }
}