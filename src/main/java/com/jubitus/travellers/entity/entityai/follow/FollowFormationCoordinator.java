package com.jubitus.travellers.entity.entityai.follow;

import com.jubitus.travellers.entity.entityai.follow.formations.*;
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
    private static final double ESCORT_HYSTERESIS_DIST = 1.6D;


    private static final double HEADING_REVERSE_DOT_LIMIT = -0.82D;

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

        LeaderContext ctx = CONTEXTS.get(key);
        if (ctx == null) {
            ctx = new LeaderContext(key);
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
        LeaderContext ctx = CONTEXTS.get(key);
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
        LeaderContext ctx = CONTEXTS.get(key);
        if (ctx == null) return Collections.emptyList();

        return ctx.crowdNeighbors(x, z, world.getTotalWorldTime(), self);
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
        // Where the windowed heading logic below wants stableDir to end up. stableDir itself is
        // never snapped straight to this - it's slewed toward it a little every tick (see
        // slewStableDirTowardTarget) so a heading change plays out as a smooth turn instead of the
        // whole group's formation snapping sideways in one instant when a window closes.
        private double targetDirX = 0.0D;
        private double targetDirZ = 1.0D;
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

        private WorkerFormationSettings cachedFormationSettings;
        private long formationSettingsTick = Long.MIN_VALUE;

        private int groupSize = 1;
        private SpaceProfile profile = new SpaceProfile(false, 1.0D, 1.0D, 1, 0.0D, 1.0D);
        private GroundAnchor anchor = new GroundAnchor(0.0D, 0.0D, 0.0D, false);

        private final List<EntityTravellerWorker> members = new ArrayList<>();
        private final Map<UUID, SlotAssignment> assignments = new HashMap<>();
        private final Map<UUID, Integer> previousOrdinals = new HashMap<>();

        private static final class RegisteredMember {
            final EntityTravellerWorker worker;
            long lastSeen;

            RegisteredMember(EntityTravellerWorker worker, long lastSeen) {
                this.worker = worker;
                this.lastSeen = lastSeen;
            }
        }

        /**
         * Self-registration: workers register themselves in getSlot() each tick.
         * Replaces the expensive world.getEntitiesWithinAABB() scan in rebuildAssignments.
         * One map instead of two parallel UUID-keyed maps, to halve the lookups every place
         * that needs both the worker and its last-seen tick.
         */
        private final Map<UUID, RegisteredMember> registered = new LinkedHashMap<>();

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

        /**
         * Spatial bucket of registered members, rebuilt at most once per world tick.
         * Lets many followers query nearby group-mates for crowd-avoidance scoring
         * in O(1) instead of each running its own world.getEntitiesWithinAABB() scan.
         * Flat array indexed directly by cell offset rather than a HashMap: with a tightly
         * clustered group (hundreds of followers in a handful of cells), a Long-keyed HashMap
         * treeifies its buckets because Long.hashCode() XORs the packed cx/cz halves back
         * together, collapsing entropy for small nearby cell coordinates — array indexing has
         * no hash step at all.
         */
        private static final double CROWD_CELL_SIZE = 4.0D;
        /** Defensive cap on grid span (cells) so one far-away straggler can't blow up the array. */
        private static final int CROWD_GRID_MAX_SPAN = 160;

        private List<EntityTravellerWorker>[] crowdGridCells = emptyCrowdGrid();
        private int crowdGridMinCx, crowdGridMinCz, crowdGridWidth, crowdGridHeight;
        private long crowdGridTick = Long.MIN_VALUE;

        private LeaderContext(LeaderKey key) {
            this.key = key;
        }

        @SuppressWarnings("unchecked")
        private static List<EntityTravellerWorker>[] emptyCrowdGrid() {
            return new List[0];
        }

        @SuppressWarnings("unchecked")
        private void ensureCrowdGrid(long now) {
            if (crowdGridTick == now) return;
            crowdGridTick = now;

            int minCx = Integer.MAX_VALUE, maxCx = Integer.MIN_VALUE;
            int minCz = Integer.MAX_VALUE, maxCz = Integer.MIN_VALUE;
            List<EntityTravellerWorker> live = new ArrayList<>(registered.size());

            for (RegisteredMember m : registered.values()) {
                EntityTravellerWorker w = m.worker;
                if ((now - m.lastSeen) > MEMBER_EXPIRY_TICKS || w.isDead) continue;

                live.add(w);
                int cx = MathHelper.floor(w.posX / CROWD_CELL_SIZE);
                int cz = MathHelper.floor(w.posZ / CROWD_CELL_SIZE);
                if (cx < minCx) minCx = cx;
                if (cx > maxCx) maxCx = cx;
                if (cz < minCz) minCz = cz;
                if (cz > maxCz) maxCz = cz;
            }

            if (live.isEmpty()) {
                crowdGridWidth = 0;
                crowdGridHeight = 0;
                crowdGridCells = emptyCrowdGrid();
                return;
            }

            int width = Math.min(maxCx - minCx + 1, CROWD_GRID_MAX_SPAN);
            int height = Math.min(maxCz - minCz + 1, CROWD_GRID_MAX_SPAN);
            crowdGridMinCx = minCx;
            crowdGridMinCz = minCz;
            crowdGridWidth = width;
            crowdGridHeight = height;

            List<EntityTravellerWorker>[] cells = new List[width * height];

            for (EntityTravellerWorker w : live) {
                int cx = MathHelper.floor(w.posX / CROWD_CELL_SIZE) - minCx;
                int cz = MathHelper.floor(w.posZ / CROWD_CELL_SIZE) - minCz;
                if (cx < 0 || cx >= width || cz < 0 || cz >= height) continue; // outside capped span

                int idx = cx * height + cz;
                List<EntityTravellerWorker> bucket = cells[idx];
                if (bucket == null) {
                    bucket = new ArrayList<>(4);
                    cells[idx] = bucket;
                }
                bucket.add(w);
            }

            crowdGridCells = cells;
        }

        /**
         * Group-mates within the 3x3 cells around (x, z), excluding {@code self}. No owner check
         * needed: a worker only ever registers into this context when
         * {@code getEffectiveFollowTarget() == leader}, which itself requires a matching
         * follow-owner (see {@code EntityTravellerWorker.getEffectiveFollowTarget}) — every
         * registered member of this context is guaranteed to already share the same owner.
         */
        List<EntityTravellerWorker> crowdNeighbors(double x, double z, long now, EntityTravellerWorker self) {
            ensureCrowdGrid(now);
            if (crowdGridWidth == 0) return Collections.emptyList();

            int cx = MathHelper.floor(x / CROWD_CELL_SIZE) - crowdGridMinCx;
            int cz = MathHelper.floor(z / CROWD_CELL_SIZE) - crowdGridMinCz;

            List<EntityTravellerWorker> result = new ArrayList<>(8);
            for (int dx = -1; dx <= 1; dx++) {
                int gx = cx + dx;
                if (gx < 0 || gx >= crowdGridWidth) continue;
                for (int dz = -1; dz <= 1; dz++) {
                    int gz = cz + dz;
                    if (gz < 0 || gz >= crowdGridHeight) continue;

                    List<EntityTravellerWorker> bucket = crowdGridCells[gx * crowdGridHeight + gz];
                    if (bucket == null) continue;
                    for (EntityTravellerWorker w : bucket) {
                        if (w == self) continue;
                        result.add(w);
                    }
                }
            }
            return result;
        }

        private WorkerFormationSettings resolveFormationSettings(World world, EntityLivingBase leader, long now) {
            if (formationSettingsTick == now && cachedFormationSettings != null) {
                return cachedFormationSettings;
            }
            formationSettingsTick = now;

            WorkerFormationScopeKey formationScope = WorkerFormationScopeResolver.resolveRuntimeScope(leader);
            cachedFormationSettings = formationScope == null
                    ? WorkerFormationSettings.defaults()
                    : WorkerFormationSavedData.get(world).getSettings(formationScope);
            return cachedFormationSettings;
        }

        void registerMember(EntityTravellerWorker worker, long now) {
            UUID id = worker.getUniqueID();
            RegisteredMember existing = registered.get(id);

            if (existing == null) {
                registered.put(id, new RegisteredMember(worker, now));
                return;
            }

            // Worker was absent long enough to be considered gone: reset its slot so it rejoins
            // at the correct position for the current group size instead of using a stale ordinal.
            if ((now - existing.lastSeen) > MEMBER_EXPIRY_TICKS) {
                persistentOrdinals.remove(id);
                persistentFormOrdinals.remove(id);
            }
            existing.lastSeen = now;
        }

        private int countEligible(@Nullable UUID ownerId, EntityLivingBase leader, long now) {
            int count = 0;
            for (RegisteredMember m : registered.values()) {
                if ((now - m.lastSeen) > MEMBER_EXPIRY_TICKS || m.worker.isDead) continue;
                if (ownerMatches(m.worker, ownerId) && m.worker.getEffectiveFollowTarget() == leader)
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
                    // Needs a bit more distance to commit — formation is less sensitive to heading.
                    // Player minAccDist is calibrated below normal walk-speed accumulation over
                    // maxAccumTicks (~3.9 blocks at 4.3 blocks/s over 18 ticks) - it used to sit
                    // above that (4.5), which meant a player walking at normal speed never reached
                    // haveAccumulatedHeading and always fell through to the much weaker "drift
                    // nudge" fallback below, so the formation only ever re-oriented once the group
                    // fell behind and the leader "overshot" it (see minAccDist comments on the
                    // other cases for the same reasoning).
                    minAccDist      = workerLeader ? 3.0D : 2.2D;
                    minAngleRad     = Math.toRadians(12.0D);
                    maxAccumTicks   = 18;
                    headingBlendMin = 0.12D;
                    headingBlendMax = 0.32D;
                    break;
                case LINE:
                    // LINE rotation is dramatic (all workers move sideways) so needs clear direction.
                    minAccDist      = workerLeader ? 3.0D : 1.5D;
                    minAngleRad     = Math.toRadians(10.0D);
                    maxAccumTicks   = 10;
                    headingBlendMin = 0.20D;
                    headingBlendMax = 0.60D;
                    break;
                case WEDGE:
                    // Small group — respond more quickly to leader turns
                    minAccDist      = workerLeader ? 2.5D : 1.2D;
                    minAngleRad     = Math.toRadians(8.0D);
                    maxAccumTicks   = 8;
                    headingBlendMin = 0.22D;
                    headingBlendMax = 0.62D;
                    break;
                case COLUMN: // unreachable as a persisted style (WorkerFormationSettings.clamp migrates
                             // it to LINE), kept only so the switch stays exhaustive-by-intent
                case ADAPTIVE:
                default:
                    minAccDist      = workerLeader ? 2.5D : 1.8D;
                    minAngleRad     = Math.toRadians(8.0D);
                    maxAccumTicks   = 12;
                    headingBlendMin = 0.18D;
                    headingBlendMax = 0.52D;
                    break;
            }
            boolean haveAccumulatedHeading = accDistSq >= (minAccDist * minAccDist);
            boolean forceWindowClose = headingAccumTicks >= maxAccumTicks;

            // Below, only targetDirX/Z (where the heading is headed) is ever touched - stableDirX/Z
            // (what everything else actually reads) is slewed toward it a fixed small step per tick
            // at the bottom of this method, regardless of which branch below ran this tick. That's
            // what turns a window's heading decision into a smooth multi-tick turn instead of the
            // whole formation snapping sideways the instant a window closes.
            if (!haveAccumulatedHeading && !forceWindowClose) {
                slewStableDirTowardTarget(workerLeader);
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
                    double dot = targetDirX * nx + targetDirZ * nz;
                    // Only nudge if we're not going backwards (dot > -0.4)
                    if (dot > -0.4D) {
                        double nudge = 0.05D + (dot > 0.6D ? 0.03D : 0.0D); // stronger nudge when already aligned
                        double newDirX = targetDirX + nx * nudge;
                        double newDirZ = targetDirZ + nz * nudge;
                        double norm = Math.sqrt(newDirX * newDirX + newDirZ * newDirZ);
                        if (norm > 1.0E-6D) {
                            targetDirX = newDirX / norm;
                            targetDirZ = newDirZ / norm;
                        }
                    }
                }
                headingAnchorX = leader.posX;
                headingAnchorZ = leader.posZ;
                headingAccumTicks = 0;
                slewStableDirTowardTarget(workerLeader);
                return;
            }

            double len = Math.sqrt(accDistSq);
            double nx = accDx / len;
            double nz = accDz / len;

            double currentAngle = Math.atan2(targetDirZ, targetDirX);
            double targetAngle = Math.atan2(nz, nx);
            double delta = wrapToPi(targetAngle - currentAngle);
            double absDelta = Math.abs(delta);

            if (absDelta < minAngleRad) {
                lastMeaningfulHeadingSpeed = len / Math.max(1.0D, headingAccumTicks);
                headingAnchorX = leader.posX;
                headingAnchorZ = leader.posZ;
                headingAccumTicks = 0;
                slewStableDirTowardTarget(workerLeader);
                return;
            }

            double dot = targetDirX * nx + targetDirZ * nz;
            if (dot <= HEADING_REVERSE_DOT_LIMIT) {
                double leftDot = targetDirX * (-nz) + targetDirZ * nx;
                double rightDot = targetDirX * nz + targetDirZ * (-nx);
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

            targetDirX = Math.cos(newAngle);
            targetDirZ = Math.sin(newAngle);

            double sl = Math.sqrt(targetDirX * targetDirX + targetDirZ * targetDirZ);
            if (sl > 1.0E-6D) {
                targetDirX /= sl;
                targetDirZ /= sl;
            } else {
                targetDirX = nx;
                targetDirZ = nz;
            }

            lastMeaningfulHeadingSpeed = len / Math.max(1.0D, headingAccumTicks);
            headingAnchorX = leader.posX;
            headingAnchorZ = leader.posZ;
            headingAccumTicks = 0;
            slewStableDirTowardTarget(workerLeader);
        }

        /** Rate-limited per-tick turn of stableDir toward targetDir - see the field comment on
         * targetDirX for why this exists (smooth turning instead of per-window snapping). */
        private void slewStableDirTowardTarget(boolean workerLeader) {
            double maxStepRad = Math.toRadians(workerLeader ? 3.0D : 6.0D);

            double curAngle = Math.atan2(stableDirZ, stableDirX);
            double tgtAngle = Math.atan2(targetDirZ, targetDirX);
            double delta = wrapToPi(tgtAngle - curAngle);
            double step = MathHelper.clamp(delta, -maxStepRad, maxStepRad);
            double newAngle = curAngle + step;

            stableDirX = Math.cos(newAngle);
            stableDirZ = Math.sin(newAngle);
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
            Iterator<RegisteredMember> regIt = registered.values().iterator();
            while (regIt.hasNext()) {
                RegisteredMember m = regIt.next();
                EntityTravellerWorker w = m.worker;
                boolean stale = (now - m.lastSeen) > MEMBER_EXPIRY_TICKS;
                if (stale || w.isDead) {
                    regIt.remove();
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

            // Purge stale ordinals: workers absent from `registered`, OR present but no longer
            // following this leader (e.g. switched to another leader or back to player). Without this,
            // a worker rejoining carries its old ordinal from a larger group and gets placed far away.
            persistentOrdinals.keySet().removeIf(uuid -> {
                RegisteredMember m = registered.get(uuid);
                if (m == null) return true;
                return m.worker.isDead || !ownerMatches(m.worker, ownerId) || m.worker.getEffectiveFollowTarget() != leader;
            });
            persistentFormOrdinals.keySet().removeIf(uuid -> {
                RegisteredMember m = registered.get(uuid);
                if (m == null) return true;
                return m.worker.isDead || !ownerMatches(m.worker, ownerId) || m.worker.getEffectiveFollowTarget() != leader;
            });

            int totalKnownSize = groupSize;

            double anchorX = anchor.useProjectedGround ? anchor.x : leader.posX;
            double anchorY = mode == FollowMode.CAVE
                    ? leader.getEntityBoundingBox().minY
                    : (anchor.useProjectedGround ? anchor.y : leader.getEntityBoundingBox().minY);
            double anchorZ = anchor.useProjectedGround ? anchor.z : leader.posZ;
            boolean workerLeader = leader instanceof EntityTravellerWorker;
            double bodyScale = formationBodyScale(leader, ordered);

            // RING is a fixed per-follower personal offset (see FormationGeometry.computeRingOffset)
            // rather than an ordinal/rank slot - it does not fit the pool-then-nearest-match pipeline
            // below (which reassigns which worker occupies which ordinal for stability), so it is
            // resolved directly per follower identity and skips that pipeline entirely.
            if (formationSettings.style == WorkerFormationStyle.RING) {
                int ringGroupSize = ordered.size();
                for (int i = 0; i < ringGroupSize; i++) {
                    EntityTravellerWorker worker = ordered.get(i);
                    FormationGeometry.SlotOffset off = FormationGeometry.computeRingOffset(worker.getUniqueID());
                    SlotAssignment slot = new SlotAssignment(mode, i, ringGroupSize,
                            profile.compactness, profile.openness, stableDirX, stableDirZ,
                            leaderSpeed, leaderActuallyMoving,
                            anchorX + off.dx, anchorY, anchorZ + off.dz,
                            off.preferredRadius, off.arriveRadius, off.frontPenaltyStrength,
                            mode == FollowMode.CAVE, profile.tunnelStrength, profile.caveSpread,
                            anchor.useProjectedGround, workerLeader, profile.suggestedTunnelLanes,
                            false,
                            anchor.useProjectedGround ? 0.0D : off.dx,
                            anchor.useProjectedGround ? 0.0D : off.dz);
                    assignments.put(worker.getUniqueID(), slot);
                }
                return;
            }

            int numEscorts = Math.min(formationSettings.numEscorts, ordered.size());

            // Escort slots: always the first numEscorts entries in ordered (already proximity-sorted)
            for (int i = 0; i < numEscorts; i++) {
                EntityTravellerWorker worker = ordered.get(i);
                SlotOffset offset = computeSlotOffset(i, totalKnownSize, stableDirX, stableDirZ,
                        profile, mode, cfg, baseFollowDistance, key.leaderId, formationSettings, workerLeader, bodyScale);
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
                        profile, mode, cfg, baseFollowDistance, key.leaderId, formationSettings, workerLeader, bodyScale));
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
                    workerLeader,
                    formationBodyScale(leader, follower)
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

        BlockPos columnBase = new BlockPos(feet.getX(), 0, feet.getZ());
        BlockPos top = FollowRecoveryHelper.topSolidOrWaterSurface(world, columnBase);
        int verticalGap = feet.getY() - top.getY();

        if (airborne && verticalGap >= 5 && world.canSeeSky(top)) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos p = new BlockPos(top.getX() + dx, top.getY(), top.getZ() + dz);
                    if (com.jubitus.travellers.world.ground.Footing.canStandAt(world, p)) {
                        return new GroundAnchor(p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D, true);
                    }
                }
            }

            // Flying over open water: no dry standable tile below. Anchor to the water surface
            // itself instead of falling through to the leader's raw flight altitude, which would
            // otherwise pull followers up into the air trying to reach them.
            if (world.getBlockState(top).getMaterial().isLiquid()) {
                return new GroundAnchor(top.getX() + 0.5D, top.getY(), top.getZ() + 0.5D, true);
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

    /** Mounted formations need wider slots so the horses don't interpenetrate - true when the leader
     *  or any listed member is riding a bonded horse. */
    private static double formationBodyScale(EntityLivingBase leader,
                                             java.util.List<EntityTravellerWorker> members) {
        if (leader != null && com.jubitus.travellers.world.pathing.PathBody.isMounted(leader)) {
            return FormationGeometry.MOUNTED_SPACING_SCALE;
        }
        if (members != null) {
            for (EntityTravellerWorker m : members) {
                if (m != null && com.jubitus.travellers.world.pathing.PathBody.isMounted(m)) {
                    return FormationGeometry.MOUNTED_SPACING_SCALE;
                }
            }
        }
        return 1.0D;
    }

    private static double formationBodyScale(EntityLivingBase leader, EntityTravellerWorker member) {
        return formationBodyScale(leader, member == null ? null : java.util.Collections.singletonList(member));
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
                                                boolean workerLeader,
                                                double bodyScale) {
        // Close-escort slots, and surface formation geometry, are shared pure math (FormationGeometry)
        // so loaded and virtual/offscreen followers can never visually disagree on formation shape.
        if (mode == FollowMode.CAVE) {
            // Cave/tunnel geometry depends on world block scans (SpaceProfile) that only make sense
            // for a loaded leader — kept here rather than in the shared geometry class.
            int numEscortsCave = formationSettings.numEscorts;
            if (numEscortsCave > 0 && ordinal < numEscortsCave) {
                int effectiveEscorts = Math.min(numEscortsCave, groupSize);
                FormationGeometry.SlotOffset off =
                        FormationGeometry.computeEscortOffset(ordinal, effectiveEscorts, forwardX, forwardZ, bodyScale);
                return new SlotOffset(off.dx, off.dz, off.preferredRadius, off.arriveRadius, off.frontPenaltyStrength);
            }
            int adjOrdinalCave = ordinal - numEscortsCave;
            int adjGroupSizeCave = Math.max(1, groupSize - numEscortsCave);
            return computeCaveOffset(adjOrdinalCave, adjGroupSizeCave, forwardX, forwardZ, profile, cfg,
                    formationSettings, bodyScale);
        }

        FormationGeometry.SlotOffset off = FormationGeometry.computeSlotOffset(
                ordinal, groupSize, forwardX, forwardZ, baseFollowDistance, leaderId, formationSettings, workerLeader,
                bodyScale);
        // Higher player cohesion lets followers fill in beside/ahead of the leader instead of being
        // pushed strictly behind it - see WorkerFormationSettings.playerCohesion.
        double frontPenalty = off.frontPenaltyStrength * (1.0D - 0.6D * formationSettings.playerCohesion);
        return new SlotOffset(off.dx, off.dz, off.preferredRadius, off.arriveRadius, frontPenalty);
    }

    private static SlotOffset computeCaveOffset(int ordinal,
                                                int groupSize,
                                                double forwardX,
                                                double forwardZ,
                                                SpaceProfile profile,
                                                FollowRuntimeConfig cfg,
                                                WorkerFormationSettings settings,
                                                double bodyScale) {
        double rx = -forwardZ;
        double rz = forwardX;

        WorkerFormationStyle style = resolveCaveStyle(settings, profile);

        // Mirrors computeSurfaceOffset: only inter-slot spacing scales for a mounted body, never the
        // base leader distance. A mounted follower in a tunnel needs the same wider lane spacing a
        // surface formation gets, or it computes pedestrian-tight geometry sized for a ~0.6-wide body.
        double spacingMul = settings.spacing * bodyScale;
        double dist = Math.max(cfg.cave.baseLeaderDistance, settings.followDistance * 0.85D);

        double laneSpacing = Math.max(0.50D, cfg.cave.laneSpacing * spacingMul * (settings.adaptiveCave ? 0.72D : 0.92D));
        double rowSpacing = Math.max(0.85D, cfg.cave.rowSpacing * spacingMul * (settings.adaptiveCave ? 0.78D : 0.95D));

        int maxTunnelLanes = Math.max(1, profile.suggestedTunnelLanes);
        if (settings.adaptiveCave) {
            // Adaptive mode already limits lanes to what the tunnel physically fits
            // (profile.suggestedTunnelLanes); width just lets the player additionally narrow
            // that down, graded like every other style instead of a binary 1-vs-2 switch.
            maxTunnelLanes = Math.min(maxTunnelLanes, Math.max(1, settings.width));
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
                int lanes = Math.max(1, Math.min(maxTunnelLanes, 2));
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

        /** Copy with idealX/idealZ replaced - see {@link FollowFormationCoordinator#reanchorToLiveLeaderPosition}. */
        private SlotAssignment withLiveIdealXZ(double liveIdealX, double liveIdealZ) {
            return new SlotAssignment(mode, ordinal, groupSize, compactness, openness, leaderDirX, leaderDirZ,
                    leaderSpeed, leaderActuallyMoving, liveIdealX, idealY, liveIdealZ,
                    preferredRadius, arriveRadius, frontPenaltyStrength, underground, tunnelStrength, caveSpread,
                    projectedGroundTarget, workerLeader, laneCount, isEscort, leaderOffsetDx, leaderOffsetDz);
        }
    }
}