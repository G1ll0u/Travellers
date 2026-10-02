package com.jubitus.travellers.virtual;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.entityai.follow.FollowRuntimeConfig;
import com.jubitus.travellers.entity.entityai.follow.formations.FormationGeometry;
import com.jubitus.travellers.entity.entityai.follow.formations.WorkerAttitude;
import com.jubitus.travellers.entity.entityai.follow.formations.WorkerFormationScopeKey;
import com.jubitus.travellers.entity.entityai.follow.formations.WorkerFormationSavedData;
import com.jubitus.travellers.entity.entityai.follow.formations.WorkerFormationSettings;
import com.jubitus.travellers.entity.entityai.follow.formations.WorkerFormationStyle;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Offscreen counterpart to {@code FollowFormationCoordinator}: gives virtual worker followers a
 * live, heading-aware formation slot instead of the one-time frozen offset previously captured at
 * virtualization. Shares {@link FormationGeometry}'s pure slot math with the loaded path, so shape
 * / spacing / style can never visually disagree between a materialized and a still-virtual
 * follower, and a Formation Hub settings change now makes virtual followers actually walk toward
 * their new slot instead of snapping into it only at materialization.
 * <p>
 * Always resolves surface geometry - cave/tunnel detection depends on world block scans that
 * offscreen simulation must never perform (zero chunk loading offscreen is a hard project rule).
 * <p>
 * <b>Slot indices are dense ranks, not raw ordinals.</b> {@code VirtualTravellerRecord.formationOrdinal}
 * is only a stable <i>ordering key</i> (assigned once, appended at the back, never reused). The slot
 * a follower actually renders in is the follower's <i>rank</i> among the current members of its
 * group, recomputed once per tick. When a group loses members (split into a new group, killed in a
 * virtual battle, etc.) everyone behind the gap shifts forward one slot automatically, so a group's
 * formation always fills tightly for its current size with no phantom/empty slots - the offscreen
 * equivalent of the loaded path's periodic {@code rebuildAssignments}. No explicit recompaction
 * call is ever needed anywhere.
 */
public final class VirtualFormationSimulator {

    private VirtualFormationSimulator() {
    }

    // Smoothed per-leader heading: [dirX, dirZ, lastLeaderX, lastLeaderZ]. Purely cosmetic and
    // in-memory - rebuilding from scratch after a restart just anchors on the current position
    // with no visible discontinuity.
    private static final Map<UUID, double[]> HEADINGS = new HashMap<>();
    private static final Map<UUID, Long> headingUpdatedTick = new HashMap<>();

    // Next ordinal (stable ordering key) to hand out per leader. Bumped immediately after each
    // assignment so several followers newly assigned to the same leader in the same tick (e.g. a
    // bulk "Create group" covering 100 workers at once) still get distinct, sequential ordering
    // keys instead of all tying on one value.
    private static final Map<UUID, Integer> NEXT_ORDINAL = new HashMap<>();

    // Per-tick dense-rank cache. For each leader (worker sub-leader UUID or owning-player UUID):
    //   RANKS[leader][memberUuid] = the member's 0-based rank among that group's virtual members
    //   VIRTUAL_COUNT[leader]     = number of virtual members currently in that group
    // Rebuilt at most once per world tick and shared by every follower resolved that tick, so a
    // large formation costs one O(n log n) sort per group per tick rather than an O(n) rescan per
    // follower. This is what makes holes impossible: rank always covers [0, VIRTUAL_COUNT).
    private static long denseCacheTick = Long.MIN_VALUE;
    private static final Map<UUID, Map<UUID, Integer>> RANKS = new HashMap<>();
    private static final Map<UUID, Integer> VIRTUAL_COUNT = new HashMap<>();

    // Physical (loaded) followers of a virtual leader self-register here every time they resolve a
    // slot (EntityTravellerWorker.tickVirtualLeaderFollow) - lets a mixed group of virtual and
    // physical followers share one dense slot space instead of colliding on the same indices.
    // Physical followers occupy the ranks just above the virtual members' range.
    private static final Map<UUID, Map<UUID, Long>> EXTERNAL_FOLLOWERS = new HashMap<>();
    private static final long EXTERNAL_FOLLOWER_EXPIRY_TICKS = 200L;

    /** UUID a worker record is actively tracking for formation purposes, or null if none applies. */
    @Nullable
    public static UUID effectiveLeaderId(VirtualTravellerRecord r) {
        if (r.escortLeaderUUID != null) return r.escortLeaderUUID;
        if (r.isHiredFollower && r.followOwnerUUID != null && r.followOrderActive
                && "IDLE".equals(r.workerMode) && !r.goOrderActive) {
            return r.followOwnerUUID;
        }
        return null;
    }

    private static void rebuildDenseCache(Iterable<VirtualTravellerRecord> records, long now) {
        if (denseCacheTick == now) return;
        denseCacheTick = now;
        RANKS.clear();
        VIRTUAL_COUNT.clear();

        Map<UUID, List<VirtualTravellerRecord>> byLeader = new HashMap<>();
        for (VirtualTravellerRecord rec : records) {
            if (!"WORKER".equals(rec.entityClass)) continue;
            UUID lid = effectiveLeaderId(rec);
            if (lid != null) byLeader.computeIfAbsent(lid, k -> new ArrayList<>()).add(rec);
        }

        for (Map.Entry<UUID, List<VirtualTravellerRecord>> e : byLeader.entrySet()) {
            List<VirtualTravellerRecord> members = e.getValue();
            // Stable order: by persistent ordinal (unassigned = -1 sorts to the back), UUID as tiebreak
            // so two members that momentarily share an ordinal still get distinct dense ranks.
            members.sort((a, b) -> {
                int oa = a.formationOrdinal < 0 ? Integer.MAX_VALUE : a.formationOrdinal;
                int ob = b.formationOrdinal < 0 ? Integer.MAX_VALUE : b.formationOrdinal;
                int c = Integer.compare(oa, ob);
                return c != 0 ? c : a.uuid.compareTo(b.uuid);
            });
            Map<UUID, Integer> ranks = new HashMap<>();
            for (int i = 0; i < members.size(); i++) ranks.put(members.get(i).uuid, i);
            RANKS.put(e.getKey(), ranks);
            VIRTUAL_COUNT.put(e.getKey(), members.size());
        }

        // Bound in-memory map growth: keep only leaders that still have a virtual or physical member.
        Map<UUID, Boolean> keep = new HashMap<>();
        for (UUID lid : VIRTUAL_COUNT.keySet()) keep.put(lid, Boolean.TRUE);
        for (UUID lid : EXTERNAL_FOLLOWERS.keySet()) keep.put(lid, Boolean.TRUE);
        HEADINGS.keySet().retainAll(keep.keySet());
        headingUpdatedTick.keySet().retainAll(keep.keySet());
        NEXT_ORDINAL.keySet().retainAll(keep.keySet());
    }

    private static int externalCount(UUID leaderId, long now) {
        Map<UUID, Long> ext = EXTERNAL_FOLLOWERS.get(leaderId);
        if (ext == null) return 0;
        ext.entrySet().removeIf(en -> now - en.getValue() > EXTERNAL_FOLLOWER_EXPIRY_TICKS);
        return ext.size();
    }

    private static void updateHeadingOnce(UUID leaderId, double leaderX, double leaderZ, float leaderYaw,
                                          WorkerFormationSettings settings, long now) {
        Long last = headingUpdatedTick.get(leaderId);
        if (last != null && last == now) return;
        headingUpdatedTick.put(leaderId, now);

        // Seeded from the leader's own facing, never a fixed direction: this map is in-memory, so a
        // hardcoded seed silently rotated every anchored formation after a server restart, moving
        // each follower's slot away from where it actually stands - which then made it materialize
        // somewhere else entirely instead of exactly where the player left it.
        double[] heading = HEADINGS.computeIfAbsent(leaderId, k -> {
            double rad = Math.toRadians(leaderYaw);
            return new double[]{-Math.sin(rad), Math.cos(rad), leaderX, leaderZ};
        });
        double dx = leaderX - heading[2];
        double dz = leaderZ - heading[3];
        double distSq = dx * dx + dz * dz;
        if (distSq > 0.02D * 0.02D) {
            double len = Math.sqrt(distSq);
            double nx = dx / len;
            double nz = dz / len;
            float blend = settings.getTurnSmoothingFactor();
            double newX = heading[0] + (nx - heading[0]) * blend;
            double newZ = heading[1] + (nz - heading[1]) * blend;
            double norm = Math.sqrt(newX * newX + newZ * newZ);
            if (norm > 1.0E-6D) {
                heading[0] = newX / norm;
                heading[1] = newZ / norm;
            }
        }
        heading[2] = leaderX;
        heading[3] = leaderZ;
    }

    /** Assigns the next stable ordering key for {@code leaderId}, appending at the back of the group. */
    private static int nextOrdinalFor(Collection<VirtualTravellerRecord> records, UUID leaderId) {
        Integer cached = NEXT_ORDINAL.get(leaderId);
        if (cached == null) {
            int maxOrdinal = -1;
            for (VirtualTravellerRecord rec : records) {
                if (rec.formationOrdinal > maxOrdinal && leaderId.equals(effectiveLeaderId(rec))) {
                    maxOrdinal = rec.formationOrdinal;
                }
            }
            cached = maxOrdinal + 1;
        }
        NEXT_ORDINAL.put(leaderId, cached + 1);
        return cached;
    }

    /**
     * Sets {@code follower.goal} to its live formation slot relative to the leader's current
     * position and smoothed heading. The slot is the follower's dense rank within the group (holes
     * always filled); {@code formationOrdinal} is assigned once purely to keep the ordering stable.
     */
    static void applyFormationGoal(WorldServer world,
                                   Map<UUID, VirtualTravellerRecord> records,
                                   VirtualTravellerRecord follower,
                                   UUID leaderId,
                                   double leaderX, double leaderY, double leaderZ, float leaderYaw,
                                   boolean workerLeader,
                                   boolean mounted,
                                   long now) {
        WorkerFormationScopeKey scope = new WorkerFormationScopeKey(
                workerLeader ? WorkerFormationScopeKey.Type.WORKER : WorkerFormationScopeKey.Type.PLAYER, leaderId);
        WorkerFormationSettings settings = WorkerFormationSavedData.get(world).getSettings(scope);

        updateHeadingOnce(leaderId, leaderX, leaderZ, leaderYaw, settings, now);
        double[] heading = HEADINGS.get(leaderId);

        FormationGeometry.SlotOffset off;
        if (settings.style == WorkerFormationStyle.RING) {
            // Fixed per-follower offset - bypasses rank/groupSize entirely, see computeRingOffset.
            off = FormationGeometry.computeRingOffset(follower.uuid);
        } else {
            if (follower.formationOrdinal < 0) {
                follower.formationOrdinal = nextOrdinalFor(records.values(), leaderId);
            }

            rebuildDenseCache(records.values(), now);
            Map<UUID, Integer> ranks = RANKS.get(leaderId);
            int virtualCount = VIRTUAL_COUNT.getOrDefault(leaderId, 1);
            int rank = (ranks != null && ranks.containsKey(follower.uuid))
                    ? ranks.get(follower.uuid) : Math.max(0, virtualCount - 1);
            int groupSize = Math.max(1, virtualCount + externalCount(leaderId, now));

            FollowRuntimeConfig cfg = FollowRuntimeConfig.get();
            double baseFollowDistance = workerLeader
                    ? cfg.surface.baseLeaderDistanceWorker
                    : cfg.surface.baseLeaderDistancePlayer;
            double bodyScale = mounted ? FormationGeometry.MOUNTED_SPACING_SCALE : 1.0D;

            off = FormationGeometry.computeSlotOffset(
                    rank, groupSize, heading[0], heading[1], baseFollowDistance, leaderId, settings, workerLeader,
                    bodyScale);
        }

        follower.goal = new BlockPos(
                (int) Math.floor(leaderX + off.dx),
                (int) leaderY,
                (int) Math.floor(leaderZ + off.dz));
    }

    /**
     * Formation slot for a physical (loaded) worker whose escort leader is virtual
     * ({@code EntityTravellerWorker.tickVirtualLeaderFollow}, the only movement AI available in that
     * state since the leader isn't a live entity for the loaded follow AI to target). Shares the
     * same per-leader heading state and {@link FormationGeometry} math as {@link #applyFormationGoal}.
     * Physical followers take the dense ranks just above the virtual members' range, recomputed live
     * (registration order, UUID-sorted) so that if the virtual part of the group shrinks the physical
     * followers shift down to stay contiguous rather than leaving a gap.
     */
    public static BlockPos resolvePhysicalFollowerGoal(WorldServer world,
                                                       Collection<VirtualTravellerRecord> allRecords,
                                                       UUID leaderId,
                                                       double leaderX, double leaderY, double leaderZ,
                                                       float leaderYaw,
                                                       UUID followerId,
                                                       boolean mounted,
                                                       long now) {
        WorkerFormationScopeKey scope = new WorkerFormationScopeKey(WorkerFormationScopeKey.Type.WORKER, leaderId);
        WorkerFormationSettings settings = WorkerFormationSavedData.get(world).getSettings(scope);

        updateHeadingOnce(leaderId, leaderX, leaderZ, leaderYaw, settings, now);
        double[] heading = HEADINGS.get(leaderId);

        if (settings.style == WorkerFormationStyle.RING) {
            // Fixed per-follower offset - bypasses rank/groupSize entirely, see computeRingOffset.
            FormationGeometry.SlotOffset off = FormationGeometry.computeRingOffset(followerId);
            return new BlockPos(
                    (int) Math.floor(leaderX + off.dx),
                    (int) leaderY,
                    (int) Math.floor(leaderZ + off.dz));
        }

        Map<UUID, Long> ext = EXTERNAL_FOLLOWERS.computeIfAbsent(leaderId, k -> new HashMap<>());
        ext.put(followerId, now);
        ext.entrySet().removeIf(e -> now - e.getValue() > EXTERNAL_FOLLOWER_EXPIRY_TICKS);

        rebuildDenseCache(allRecords, now);
        int virtualCount = VIRTUAL_COUNT.getOrDefault(leaderId, 0);

        // Dense rank of this physical follower among the currently-registered external followers.
        List<UUID> extIds = new ArrayList<>(ext.keySet());
        extIds.sort(UUID::compareTo);
        int extRank = Math.max(0, extIds.indexOf(followerId));

        int rank = virtualCount + extRank;
        int groupSize = Math.max(rank + 1, virtualCount + ext.size());

        FollowRuntimeConfig cfg = FollowRuntimeConfig.get();
        double bodyScale = mounted ? FormationGeometry.MOUNTED_SPACING_SCALE : 1.0D;
        FormationGeometry.SlotOffset off = FormationGeometry.computeSlotOffset(
                rank, groupSize, heading[0], heading[1], cfg.surface.baseLeaderDistanceWorker,
                leaderId, settings, true, bodyScale);

        return new BlockPos(
                (int) Math.floor(leaderX + off.dx),
                (int) leaderY,
                (int) Math.floor(leaderZ + off.dz));
    }

    // -------------------------------------------------------------------------
    // Attitude (offscreen counterpart of EntityTravellerWorker's cachedAttitude)
    // -------------------------------------------------------------------------

    /**
     * Virtual mirror of {@code EntityTravellerWorker.refreshCachedAttitude()}: a virtual worker that
     * leads its own group (loaded or virtual followers - reuses this class's existing leader caches,
     * the same union {@code WorkerGroupService.countFollowers} treats as "is a leader") resolves its
     * own group's settings; a follower resolves its escort leader's/owner's. Recomputed on demand
     * (cheap map lookups once {@link #rebuildDenseCache} has run for {@code now}), never persisted -
     * same choice as {@code BattleParticipant.loaded}.
     */
    public static WorkerAttitude resolveAttitude(WorldServer world, VirtualTravellerManager vtm, VirtualTravellerRecord r, long now) {
        if (!r.isHiredFollower) return WorkerAttitude.ATTACK;
        rebuildDenseCache(vtm.getRecordsView(), now);
        boolean isLeader = VIRTUAL_COUNT.containsKey(r.uuid) || externalCount(r.uuid, now) > 0;

        WorkerFormationScopeKey scope;
        if (isLeader) {
            scope = new WorkerFormationScopeKey(WorkerFormationScopeKey.Type.WORKER, r.uuid);
        } else {
            UUID leaderId = effectiveLeaderId(r);
            if (leaderId == null) return WorkerAttitude.ATTACK;
            scope = new WorkerFormationScopeKey(
                    r.escortLeaderUUID != null ? WorkerFormationScopeKey.Type.WORKER : WorkerFormationScopeKey.Type.PLAYER,
                    leaderId);
        }
        return WorkerFormationSavedData.get(world).getSettings(scope).attitude;
    }

    /**
     * Virtual mirror of {@code EntityAIAttackMeleeCustom.withinTether()}: true if {@code (targetX,
     * targetZ)} is within {@code r}'s attitude-appropriate leash. ATTACK is always true (free chase).
     * HOLD anchors to {@code r.goal} - frozen at the pre-battle formation slot while {@code
     * refreshGoal} is suspended for a battling record, exactly like the loaded follower's
     * {@code hasFollowerSlotGoal()} is frozen by the combat AI mutex. DEFEND anchors to the live
     * escort leader's/owner's position, resolved fresh every call.
     */
    public static boolean withinTether(WorldServer world, VirtualTravellerManager vtm, VirtualTravellerRecord r,
                                       double targetX, double targetZ, long now) {
        WorkerAttitude attitude = resolveAttitude(world, vtm, r, now);
        if (attitude == WorkerAttitude.ATTACK) return true;

        double anchorX, anchorZ, radius;
        if (attitude == WorkerAttitude.HOLD) {
            if (r.goal != null) {
                anchorX = r.goal.getX() + 0.5;
                anchorZ = r.goal.getZ() + 0.5;
            } else {
                anchorX = r.posX;
                anchorZ = r.posZ;
            }
            radius = TravellersConfig.COMBAT.holdBreakRadius;
        } else { // DEFEND
            double[] anchor = resolveDefendAnchor(world, vtm, r);
            anchorX = anchor[0];
            anchorZ = anchor[1];
            radius = TravellersConfig.COMBAT.defendTetherRadius;
        }
        double dx = targetX - anchorX;
        double dz = targetZ - anchorZ;
        return dx * dx + dz * dz <= radius * radius;
    }

    /** Live escort leader position (virtual record, else loaded entity), falling back to the owner,
     *  then to the record's own position - same fallback order as the loaded DEFEND branch. */
    private static double[] resolveDefendAnchor(WorldServer world, VirtualTravellerManager vtm, VirtualTravellerRecord r) {
        if (r.escortLeaderUUID != null) {
            VirtualTravellerRecord leaderRec = vtm.getRecord(r.escortLeaderUUID);
            if (leaderRec != null) return new double[]{leaderRec.posX, leaderRec.posZ};
            Entity leader = world.getEntityFromUuid(r.escortLeaderUUID);
            if (leader != null && !leader.isDead) return new double[]{leader.posX, leader.posZ};
        }
        if (r.isHiredFollower && r.followOwnerUUID != null) {
            EntityPlayer owner = world.getPlayerEntityByUUID(r.followOwnerUUID);
            if (owner != null && !owner.isDead) return new double[]{owner.posX, owner.posZ};
        }
        return new double[]{r.posX, r.posZ};
    }
}
