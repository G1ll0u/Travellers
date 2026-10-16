package com.jubitus.travellers.follow;

import com.jubitus.travellers.follow.formations.FormationGeometry;
import com.jubitus.travellers.follow.formations.WorkerFormationSavedData;
import com.jubitus.travellers.follow.formations.WorkerFormationScopeKey;
import com.jubitus.travellers.follow.formations.WorkerFormationScopeResolver;
import com.jubitus.travellers.follow.formations.WorkerFormationSettings;
import com.jubitus.travellers.follow.formations.WorkerFormationStyle;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.*;
import static com.jubitus.travellers.follow.FormationSpace.*;
import com.jubitus.travellers.follow.FollowFormationCoordinator.SlotAssignment;

/** Per-leader formation state: registered members, persistent ordinals, and the slot rebuild. */
final class LeaderFormation {

    static final long MEMBER_EXPIRY_TICKS = 40L; // ticks without a call before a member is considered gone
    static final int REFRESH_INTERVAL_MOVING = 8;
    static final int REFRESH_INTERVAL_MOVING_LARGE = 18; // for groups > 200
    static final int REFRESH_INTERVAL_IDLE = 40;
    static final int REFRESH_INTERVAL_IDLE_LARGE = 80;  // for groups > 200

    final FollowFormationCoordinator.LeaderKey key;

    long lastSeenTick;
    long nextRefreshTick;
    long lastHeadingTick = Long.MIN_VALUE;
    /**
     * Tick of the last heading-triggered rebuild. Rate-limits rapid heading rebuilds
     * caused by erratic worker-leader pathfinding (waypoint jumps).
     */
    long lastHeadingRebuildTick = Long.MIN_VALUE;

    final HeadingTracker heading = new HeadingTracker();
    final CrowdGrid crowd = new CrowdGrid();

    /** Heading direction at the last full rebuildAssignments() call. Used to detect significant rotations. */
    double lastRebuildDirX = Double.NaN;
    double lastRebuildDirZ = Double.NaN;
    /** Minimum heading angle change (radians) that forces an early rebuild. */
    static final double HEADING_REBUILD_THRESHOLD_RAD_PLAYER = Math.toRadians(4.0D);
    static final double HEADING_REBUILD_THRESHOLD_RAD_WORKER = Math.toRadians(11.0D);

    /** Hash of the last WorkerFormationSettings used for rebuild — detects GUI changes. */
    int lastFormationSettingsHash = 0;

    long lastForcedRebuildTick = Long.MIN_VALUE;

    WorkerFormationSettings cachedFormationSettings;
    long formationSettingsTick = Long.MIN_VALUE;

    int groupSize = 1;
    SpaceProfile profile = new SpaceProfile(false, 1.0D, 1.0D, 1, 0.0D, 1.0D);
    GroundAnchor anchor = new GroundAnchor(0.0D, 0.0D, 0.0D, false);

    final List<EntityTravellerWorker> members = new ArrayList<>();
    final Map<UUID, SlotAssignment> assignments = new HashMap<>();
    final Map<UUID, Integer> previousOrdinals = new HashMap<>();

    static final class RegisteredMember {
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
    final Map<UUID, RegisteredMember> registered = new LinkedHashMap<>();

    /**
     * Persistent ordinal registry: workers keep their slot index even between rebuild cycles.
     */
    final Map<UUID, Integer> persistentOrdinals = new HashMap<>();
    /**
     * Persistent formation-slot index (adj ordinal, independent of escort count).
     * Workers keep this across rebuilds so that changing numEscorts doesn't shuffle the formation.
     */
    final Map<UUID, Integer> persistentFormOrdinals = new HashMap<>();
    /** Total known workforce size. */
    int lastKnownGroupSize = 1;
    /** Eligible member count at last heading-update tick. -1 = never computed. */
    int lastEligibleCount = -1;

    private static final double[] ROUTE_HEADING = new double[2];

    LeaderFormation(FollowFormationCoordinator.LeaderKey key) {
        this.key = key;
    }

    List<EntityTravellerWorker> crowdNeighbors(double x, double z, long now, EntityTravellerWorker self) {
        return crowd.neighbors(registered.values(), x, z, now, self);
    }

    WorkerFormationSettings resolveFormationSettings(World world, EntityLivingBase leader, long now) {
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

    int countEligible(@Nullable UUID ownerId, EntityLivingBase leader, long now) {
        int count = 0;
        for (RegisteredMember m : registered.values()) {
            if ((now - m.lastSeen) > MEMBER_EXPIRY_TICKS || m.worker.isDead) continue;
            if (ownerMatches(m.worker, ownerId) && m.worker.getEffectiveFollowTarget() == leader)
                count++;
        }
        return count;
    }

    void ensureFresh(World world,
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
            heading.update(leader, formationSettings);
            if (leader instanceof EntityTravellerWorker
                    && GroupFormationTracker.escortRouteHeading((EntityTravellerWorker) leader, ROUTE_HEADING)) {
                heading.steerToward(ROUTE_HEADING[0], ROUTE_HEADING[1]);
            }
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
            double dot = heading.stableDirX * lastRebuildDirX + heading.stableDirZ * lastRebuildDirZ;
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

    static int computeSettingsHash(WorkerFormationSettings s) {
        int h = 17;
        h = 31 * h + s.numEscorts;
        h = 31 * h + (s.style != null ? s.style.ordinal() : -1);
        h = 31 * h + Double.hashCode(s.followDistance);
        h = 31 * h + Double.hashCode(s.spacing);
        h = 31 * h + s.width;
        return h;
    }

    void rebuildAssignments(World world,
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
        profile = sampleSpaceProfile(world, leader, heading.stableDirX, heading.stableDirZ, mode, cfg);
        anchor = computeAnchor(world, leader);

        List<EntityTravellerWorker> ordered = SlotOrdering.order(
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
                        profile.compactness, profile.openness, heading.stableDirX, heading.stableDirZ,
                        heading.leaderSpeed, heading.leaderActuallyMoving,
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
            FormationGeometry.SlotOffset offset = computeSlotOffset(i, totalKnownSize, heading.stableDirX, heading.stableDirZ,
                    profile, mode, cfg, baseFollowDistance, key.leaderId, formationSettings, workerLeader, bodyScale);
            SlotAssignment slot = new SlotAssignment(mode, i, totalKnownSize,
                    profile.compactness, profile.openness, heading.stableDirX, heading.stableDirZ,
                    heading.leaderSpeed, heading.leaderActuallyMoving,
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

        List<FormationGeometry.SlotOffset> formSlots = new ArrayList<>(formSize);
        for (int i = 0; i < formSize; i++) {
            formSlots.add(computeSlotOffset(i + numEscorts, totalKnownSize, heading.stableDirX, heading.stableDirZ,
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
                FormationGeometry.SlotOffset off = formSlots.get(si);
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
            FormationGeometry.SlotOffset off = formSlots.get(adjOrd);
            SlotAssignment slot = new SlotAssignment(mode, ordinal, totalKnownSize,
                    profile.compactness, profile.openness, heading.stableDirX, heading.stableDirZ,
                    heading.leaderSpeed, heading.leaderActuallyMoving,
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
        lastRebuildDirX = heading.stableDirX;
        lastRebuildDirZ = heading.stableDirZ;

        boolean largeGroup = lastKnownGroupSize > 200;

        int movingInterval = largeGroup ? REFRESH_INTERVAL_MOVING_LARGE : REFRESH_INTERVAL_MOVING;
        int idleInterval = largeGroup ? REFRESH_INTERVAL_IDLE_LARGE : REFRESH_INTERVAL_IDLE;

        if (workerLeader) {
            movingInterval += largeGroup ? 24 : 14;
            idleInterval += largeGroup ? 40 : 25;
        }

        int interval = heading.leaderActuallyMoving ? movingInterval : idleInterval;
        nextRefreshTick = now + interval + Math.abs((leader.getEntityId() * 31) % 5);
    }

    SlotAssignment createFallbackSlot(EntityTravellerWorker follower,
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

        FormationGeometry.SlotOffset offset = computeSlotOffset(
                fallbackOrdinal,
                fallbackGroupSize,
                heading.stableDirX,
                heading.stableDirZ,
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
                heading.stableDirX,
                heading.stableDirZ,
                heading.leaderSpeed,
                heading.leaderActuallyMoving,
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

    static boolean ownerMatches(EntityTravellerWorker worker, @Nullable UUID ownerId) {
        UUID wid = worker.getFollowOwnerId();
        if (ownerId == null) {
            return wid == null;
        }
        return ownerId.equals(wid);
    }
}
