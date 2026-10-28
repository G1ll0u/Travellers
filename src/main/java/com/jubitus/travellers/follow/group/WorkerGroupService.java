package com.jubitus.travellers.follow.group;

import com.jubitus.travellers.unit.WorkerLookup;
import com.jubitus.travellers.debug.trace.TravellerTracer;
import com.jubitus.travellers.follow.GroupSuccession;
import com.jubitus.travellers.worker.RecruitTour;
import com.jubitus.travellers.notify.NoticePlace;
import com.jubitus.travellers.follow.formations.WorkerFormationSavedData;
import com.jubitus.travellers.follow.formations.WorkerFormationScopeKey;
import com.jubitus.travellers.follow.formations.WorkerFormationScopeResolver;
import com.jubitus.travellers.follow.formations.WorkerFormationSettings;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.notify.MissionNotifications;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.follow.net.FormationLeaderInfo;
import com.jubitus.travellers.follow.net.GroupGearSummary;
import com.jubitus.travellers.follow.net.MsgSyncLeaderFollowerCount;
import com.jubitus.travellers.follow.net.MsgOpenFormationHubGui;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Single server-side entry point for every hired-worker group / leader reassignment operation
 * (create group, assign to leader, come back, merge, make-leader toggle, leader go-to).
 * <p>
 * Before this class existed each of those operations was implemented independently inside its own
 * network message handler, and every one of them handled the loaded / virtual / mixed cases
 * differently - only some reset the virtual formation state, only some forced an immediate goal
 * refresh, only some refreshed leader-count caches, and the "is this worker a leader?" question was
 * answered from loaded followers only (so a just-materialized leader whose followers were still
 * virtual would silently drop off the Hub list and the world-map). That divergence was the root
 * cause of the whole family of "the group freezes / the leader vanishes / the list doesn't update"
 * bugs. Everything now funnels through here so the loaded and virtual sides can never drift apart.
 * <p>
 * Each operation ends with a single mandatory {@link #epilogue}: mark the virtual store dirty,
 * force an immediate goal refresh on every one of that owner's virtual workers (so a reflow after a
 * split/merge is instant, and a group whose leader is idle still converges into formation once
 * instead of freezing), refresh the loaded leader-count caches, and push the freshly rebuilt,
 * authoritative Hub leader list back to the player (updating it in place if the Hub is open).
 */
public final class WorkerGroupService {

    private WorkerGroupService() {
    }

    private static VirtualTravellerManager vwm(MinecraftServer server) {
        return VirtualTravellerManager.get(server.worlds[0]);
    }

    // -------------------------------------------------------------------------
    // Operations
    // -------------------------------------------------------------------------

    /** Turn the given selection into a standalone group led by its best-equipped member. */
    /** Returns the new leader, or null when nobody could be grouped. */
    @Nullable
    public static UUID createGroup(EntityPlayerMP player, List<UUID> memberUuids) {
        MinecraftServer server = player.getServer();
        if (server == null || memberUuids == null || memberUuids.isEmpty()) return null;
        UUID owner = player.getUniqueID();
        Set<UUID> set = new HashSet<>(memberUuids);

        Map<UUID, EntityTravellerWorker> loaded = collectLoaded(server, owner, w -> set.contains(w.getUniqueID()));
        Map<UUID, VirtualTravellerRecord> virtual = collectVirtual(server, owner, r -> set.contains(r.uuid));
        int skipped = stripBusy(loaded, virtual);
        if (loaded.isEmpty() && virtual.isEmpty()) {
            notifySkipped(player, skipped);
            return null;
        }

        UUID leaderId = pickBestEquipped(loaded, virtual);
        if (leaderId == null) return null;

        for (EntityTravellerWorker w : loaded.values()) {
            if (leaderId.equals(w.getUniqueID())) becomeAnchoredLeaderLoaded(w);
            else assignToLeaderLoaded(w, leaderId);
        }
        for (VirtualTravellerRecord r : virtual.values()) {
            if (leaderId.equals(r.uuid)) becomeAnchoredLeaderVirtual(r);
            else assignToLeaderVirtual(r, leaderId);
        }

        epilogue(player, server, setOf(leaderId));
        notifySkipped(player, skipped);
        return leaderId;
    }

    /** New recruits fall in behind the recruiter that found them, busy on its tour or not. */
    public static void joinRecruiter(MinecraftServer server, UUID owner, List<UUID> recruits, UUID recruiterId) {
        if (recruits.isEmpty()) return;
        Set<UUID> set = new HashSet<>(recruits);
        for (EntityTravellerWorker w : collectLoaded(server, owner, w -> set.contains(w.getUniqueID())).values()) {
            assignToLeaderLoaded(w, recruiterId);
        }
        for (VirtualTravellerRecord r : collectVirtual(server, owner, r -> set.contains(r.uuid)).values()) {
            assignToLeaderVirtual(r, recruiterId);
        }
        epilogue(server, owner, setOf(recruiterId));
    }

    /** Assign the given selection as followers of an existing leader. */
    public static void assignToLeader(EntityPlayerMP player, List<UUID> memberUuids, UUID leaderId) {
        MinecraftServer server = player.getServer();
        if (server == null || leaderId == null || memberUuids == null || memberUuids.isEmpty()) return;
        UUID owner = player.getUniqueID();
        Set<UUID> set = new HashSet<>(memberUuids);

        if (leaderBusy(server, owner, leaderId)) {
            WorkerOrderGuard.notifyRefused(player, leaderId);
            return;
        }

        Set<UUID> affected = setOf(leaderId);
        Map<UUID, EntityTravellerWorker> loaded = collectLoaded(server, owner, w -> set.contains(w.getUniqueID()));
        Map<UUID, VirtualTravellerRecord> virtual = collectVirtual(server, owner, r -> set.contains(r.uuid));
        int skipped = stripBusy(loaded, virtual);
        if (loaded.isEmpty() && virtual.isEmpty()) {
            notifySkipped(player, skipped);
            return;
        }

        for (EntityTravellerWorker w : loaded.values()) {
            addIfPresent(affected, w.getEscortLeaderUUID());
            assignToLeaderLoaded(w, leaderId);
        }
        for (VirtualTravellerRecord r : virtual.values()) {
            addIfPresent(affected, r.escortLeaderUUID);
            assignToLeaderVirtual(r, leaderId);
        }

        epilogue(player, server, affected);
        notifySkipped(player, skipped);
    }

    /** Dismiss the given selection back to following the owner directly. */
    public static void comeBackMembers(EntityPlayerMP player, List<UUID> memberUuids) {
        MinecraftServer server = player.getServer();
        if (server == null || memberUuids == null || memberUuids.isEmpty()) return;
        UUID owner = player.getUniqueID();
        Set<UUID> set = new HashSet<>(memberUuids);

        Set<UUID> affected = new HashSet<>();
        Map<UUID, EntityTravellerWorker> loaded = collectLoaded(server, owner, w -> set.contains(w.getUniqueID()));
        Map<UUID, VirtualTravellerRecord> virtual = collectVirtual(server, owner, r -> set.contains(r.uuid));
        int skipped = stripBusy(loaded, virtual);
        if (loaded.isEmpty() && virtual.isEmpty()) {
            notifySkipped(player, skipped);
            return;
        }

        for (EntityTravellerWorker w : loaded.values()) {
            addIfPresent(affected, w.getEscortLeaderUUID());
            backToOwnerLoaded(w);
        }
        for (VirtualTravellerRecord r : virtual.values()) {
            addIfPresent(affected, r.escortLeaderUUID);
            backToOwnerVirtual(r);
        }

        epilogue(player, server, affected);
        notifySkipped(player, skipped);
    }

    /** Dismiss a whole group (the leader and all of its escorts) back to following the owner. */
    public static void comeBackGroup(EntityPlayerMP player, UUID leaderId) {
        MinecraftServer server = player.getServer();
        if (server == null || leaderId == null) return;
        UUID owner = player.getUniqueID();

        Map<UUID, EntityTravellerWorker> loaded = collectLoaded(server, owner,
                w -> leaderId.equals(w.getEscortLeaderUUID()) || leaderId.equals(w.getUniqueID()));
        Map<UUID, VirtualTravellerRecord> virtual = collectVirtual(server, owner,
                r -> leaderId.equals(r.escortLeaderUUID) || leaderId.equals(r.uuid));
        int skipped = stripBusy(loaded, virtual);

        for (EntityTravellerWorker w : loaded.values()) backToOwnerLoaded(w);
        for (VirtualTravellerRecord r : virtual.values()) backToOwnerVirtual(r);

        epilogue(player, server, setOf(leaderId));
        notifySkipped(player, skipped);
    }

    /** Merge a source group into a target leader (or dismiss it to the owner if target is null). */
    public static void merge(EntityPlayerMP player, UUID sourceLeaderId, @Nullable UUID targetLeaderId) {
        MinecraftServer server = player.getServer();
        if (server == null || sourceLeaderId == null) return;
        UUID owner = player.getUniqueID();

        if (targetLeaderId != null && leaderBusy(server, owner, targetLeaderId)) {
            WorkerOrderGuard.notifyRefused(player, targetLeaderId);
            return;
        }

        Map<UUID, EntityTravellerWorker> loaded = collectLoaded(server, owner,
                w -> sourceLeaderId.equals(w.getEscortLeaderUUID()) || sourceLeaderId.equals(w.getUniqueID()));
        Map<UUID, VirtualTravellerRecord> virtual = collectVirtual(server, owner,
                r -> sourceLeaderId.equals(r.escortLeaderUUID) || sourceLeaderId.equals(r.uuid));
        int skipped = stripBusy(loaded, virtual);

        for (EntityTravellerWorker w : loaded.values()) {
            if (targetLeaderId == null) backToOwnerLoaded(w);
            else assignToLeaderLoaded(w, targetLeaderId);
        }
        for (VirtualTravellerRecord r : virtual.values()) {
            if (targetLeaderId == null) backToOwnerVirtual(r);
            else assignToLeaderVirtual(r, targetLeaderId);
        }

        Set<UUID> affected = setOf(sourceLeaderId);
        addIfPresent(affected, targetLeaderId);
        epilogue(player, server, affected);
        notifySkipped(player, skipped);
    }

    /**
     * Toggle a worker's leadership: if it currently has any follower (loaded or virtual) release them
     * all back to the owner; otherwise gather every currently-free follower of the owner under it.
     */
    public static void makeLeader(EntityPlayerMP player, UUID leaderId) {
        MinecraftServer server = player.getServer();
        if (server == null || leaderId == null) return;
        UUID owner = player.getUniqueID();

        if (leaderBusy(server, owner, leaderId)) {
            WorkerOrderGuard.notifyRefused(player, leaderId);
            return;
        }

        boolean hasEscorts = anyLoaded(server, owner, w -> leaderId.equals(w.getEscortLeaderUUID()))
                || anyVirtual(server, owner, r -> leaderId.equals(r.escortLeaderUUID));

        if (hasEscorts) {
            Map<UUID, EntityTravellerWorker> loaded = collectLoaded(server, owner,
                    w -> leaderId.equals(w.getEscortLeaderUUID()));
            Map<UUID, VirtualTravellerRecord> virtual = collectVirtual(server, owner,
                    r -> leaderId.equals(r.escortLeaderUUID));
            int cleared = loaded.size() + virtual.size();
            for (EntityTravellerWorker w : loaded.values()) backToOwnerLoaded(w);
            for (VirtualTravellerRecord r : virtual.values()) backToOwnerVirtual(r);
            epilogue(player, server, setOf(leaderId));
            NotificationService.notify(player, NotificationDefs.LEADER_RELEASED_FOLLOWERS, String.valueOf(cleared));
            return;
        }

        Map<UUID, EntityTravellerWorker> loaded = collectLoaded(server, owner, w ->
                !leaderId.equals(w.getUniqueID())
                        && !WorkerOrderGuard.isBusy(w)
                        && !w.hasEscortLeader()
                        && w.isFollowOrderActive());
        Map<UUID, VirtualTravellerRecord> virtual = collectVirtual(server, owner, r ->
                !leaderId.equals(r.uuid)
                        && r.escortLeaderUUID == null
                        && r.followOrderActive
                        && !WorkerOrderGuard.isBusy(r));
        int assigned = loaded.size() + virtual.size();
        for (EntityTravellerWorker w : loaded.values()) assignToLeaderLoaded(w, leaderId);
        for (VirtualTravellerRecord r : virtual.values()) assignToLeaderVirtual(r, leaderId);

        epilogue(player, server, setOf(leaderId));
        if (assigned > 0) {
            NotificationService.notify(player, NotificationDefs.LEADER_NOW_LEADING, String.valueOf(assigned));
        } else {
            NotificationService.notify(player, NotificationDefs.LEADER_NO_FREE_FOLLOWERS);
        }
    }

    /**
     * Mission launch (go / equip / transport / recruit): a worker still escorting another leader
     * leaves that group for good and follows the owner directly, so the mission and the return leg
     * never resolve against the old group. Its own followers (if it leads any) stay with it. Call
     * BEFORE the mission's start method - the mutation resets the go/follow flags.
     */
    public static void detachForMission(EntityPlayerMP player, UUID workerId) {
        MinecraftServer server = player.getServer();
        if (server == null || workerId == null) return;
        UUID owner = player.getUniqueID();

        UUID oldLeader;
        EntityTravellerWorker loaded = WorkerLookup.findLoadedOwned(server, owner, workerId);
        if (loaded != null) {
            oldLeader = loaded.getEscortLeaderUUID();
            if (oldLeader == null) return;
            backToOwnerLoaded(loaded);
        } else {
            VirtualTravellerRecord r = vwm(server).getRecord(workerId);
            if (r == null || !owner.equals(r.followOwnerUUID) || r.escortLeaderUUID == null) return;
            oldLeader = r.escortLeaderUUID;
            backToOwnerVirtual(r);
        }
        epilogue(player, server, setOf(oldLeader));
    }

    /** Send a leader (and therefore its whole group) to a destination. */
    public static void leaderGoTo(EntityPlayerMP player, UUID leaderId, BlockPos target) {
        MinecraftServer server = player.getServer();
        if (server == null || leaderId == null || target == null) return;
        UUID owner = player.getUniqueID();

        EntityTravellerWorker loadedLeader = WorkerLookup.findLoadedOwned(server, owner, leaderId);
        if (loadedLeader != null) {
            if (WorkerOrderGuard.isBusy(loadedLeader)) {
                WorkerOrderGuard.notifyRefused(player, loadedLeader);
                return;
            }
            loadedLeader.setFollowOrderActive(false);
            BlockPos from = loadedLeader.getPosition();
            loadedLeader.trips.startGoTo(target, loadedLeader.dimension);
            MissionNotifications.goOrdered(player, leaderId, from, target, NotificationDefs.GOTO_GOING_TO_POS);
        } else {
            VirtualTravellerRecord r = vwm(server).getRecord(leaderId);
            if (r == null || !owner.equals(r.followOwnerUUID)) return;
            if (WorkerOrderGuard.isBusy(r)) {
                WorkerOrderGuard.notifyRefused(player, r);
                return;
            }
            r.goOrderActive = true;
            r.followOrderActive = false;
            r.goal = target;
            MissionNotifications.goOrdered(player, leaderId,
                    new BlockPos((int) r.posX, (int) r.posY, (int) r.posZ), target, NotificationDefs.GOTO_GOING_TO_POS);
        }
        epilogue(player, server, setOf(leaderId));
    }

    /**
     * Convoy: make a whole group follow a target while keeping its own identity/formation. Only the
     * source <b>leader</b> is re-linked (to escort the target leader, or to follow the owner directly
     * when {@code targetId} is null) - its own followers stay pointing at it, so the group marches as
     * an intact unit behind the target rather than dissolving into it (that's what {@link #merge} does).
     */
    public static void followLeader(EntityPlayerMP player, UUID sourceLeaderId, @Nullable UUID targetId) {
        MinecraftServer server = player.getServer();
        if (server == null || sourceLeaderId == null) return;
        if (sourceLeaderId.equals(targetId)) return; // can't follow itself
        UUID owner = player.getUniqueID();

        if (leaderBusy(server, owner, sourceLeaderId)) {
            WorkerOrderGuard.notifyRefused(player, sourceLeaderId);
            return;
        }
        if (targetId != null) {
            if (leaderBusy(server, owner, targetId)) {
                WorkerOrderGuard.notifyRefused(player, targetId);
                return;
            }
            // Cycle guard: the target must not already (transitively) follow the source, or the two
            // groups would endlessly chase each other. Walk the target's escort chain upward.
            if (chainReaches(server, owner, targetId, sourceLeaderId)) {
                NotificationService.notify(player, NotificationDefs.ORDER_WORKER_BUSY);
                return;
            }
        }

        EntityTravellerWorker loaded = WorkerLookup.findLoadedOwned(server, owner, sourceLeaderId);
        if (loaded != null) {
            if (targetId == null) backToOwnerLoaded(loaded);
            else assignToLeaderLoaded(loaded, targetId);
        } else {
            VirtualTravellerRecord r = vwm(server).getRecord(sourceLeaderId);
            if (r == null || !owner.equals(r.followOwnerUUID)) return;
            if (targetId == null) backToOwnerVirtual(r);
            else assignToLeaderVirtual(r, targetId);
        }

        Set<UUID> affected = setOf(sourceLeaderId);
        addIfPresent(affected, targetId);
        epilogue(player, server, affected);
    }

    /**
     * "Resume marching" (follow = true): the leader goes back to following whoever it follows (its
     * owner, or the leader it convoys behind - escortLeaderUUID is kept). "Halt here" (false): it
     * anchors where it stands. Either way a pending Go order is dropped; missions are refused as busy.
     */
    public static void setLeaderFollow(EntityPlayerMP player, UUID leaderId, boolean follow) {
        MinecraftServer server = player.getServer();
        if (server == null || leaderId == null) return;
        UUID owner = player.getUniqueID();

        if (leaderBusy(server, owner, leaderId)) {
            WorkerOrderGuard.notifyRefused(player, leaderId);
            return;
        }

        EntityTravellerWorker loaded = WorkerLookup.findLoadedOwned(server, owner, leaderId);
        if (loaded != null) {
            loaded.setFollowOrderActive(follow); // also ends a Go order and its travel
            loaded.getNavigator().clearPath();
        } else {
            VirtualTravellerRecord r = vwm(server).getRecord(leaderId);
            if (r == null || !owner.equals(r.followOwnerUUID)) return;
            r.goOrderActive = false;
            r.followOrderActive = follow;
            clearVirtualCatchup(r);
            // resume: refreshGoal recomputes the live follow/escort slot; halt: hold this spot
            r.goal = follow ? null : new BlockPos((int) r.posX, (int) r.posY, (int) r.posZ);
        }
        epilogue(player, server, setOf(leaderId));
    }

    /**
     * Would {@code follower} escorting {@code newLeaderId} close a loop? Walks loaded workers of the
     * follower's world only (an unloaded link ends the walk as "no loop"), unlike {@link #chainReaches}.
     */
    public static boolean wouldCreateLoadedEscortCycle(EntityTravellerWorker follower, @Nullable UUID newLeaderId) {
        if (newLeaderId == null) return false;

        // can't follow self
        if (newLeaderId.equals(follower.getUniqueID())) return true;

        // Walk up the leader chain: newLeader -> its leader -> ...
        // If we ever reach the follower, it would create a cycle.
        UUID cur = newLeaderId;

        // safety cap (prevents infinite loops if data is corrupted)
        for (int i = 0; i < 32 && cur != null; i++) {
            EntityTravellerWorker curLeader = follower.resolveEscortLeader(cur);
            if (curLeader == null || curLeader.isDead) return false;

            UUID next = curLeader.getEscortLeaderUUID();

            if (next == null) return false;
            if (next.equals(follower.getUniqueID())) return true;

            cur = next;
        }

        // If chain is absurdly long / weird, treat as unsafe
        return true;
    }

    /** True if {@code startId}'s escort chain (following escortLeaderUUID upward) reaches {@code targetId}. */
    private static boolean chainReaches(MinecraftServer server, UUID owner, UUID startId, UUID targetId) {
        UUID cur = startId;
        Set<UUID> seen = new HashSet<>();
        while (cur != null && seen.add(cur)) {
            if (cur.equals(targetId)) return true;
            UUID next;
            EntityTravellerWorker w = WorkerLookup.findLoadedOwned(server, owner, cur);
            if (w != null) next = w.getEscortLeaderUUID();
            else {
                VirtualTravellerRecord r = vwm(server).getRecord(cur);
                next = (r != null && owner.equals(r.followOwnerUUID)) ? r.escortLeaderUUID : null;
            }
            cur = next;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Leader succession
    // -------------------------------------------------------------------------

    /**
     * What a dead leader was doing, handed to its successor. The one mission inherited is a recruiting tour,
     * with the purse that pays for it (the recruits are the group that carries on).
     */
    public static final class LeaderOrder {
        static final LeaderOrder UNKNOWN = new LeaderOrder(null, true, null, null, 0);

        @Nullable final UUID convoyLeader;
        final boolean follow;
        @Nullable final BlockPos goTarget;
        @Nullable final RecruitTour recruitTour;
        final int purse;

        private LeaderOrder(@Nullable UUID convoyLeader, boolean follow, @Nullable BlockPos goTarget,
                            @Nullable RecruitTour recruitTour, int purse) {
            this.convoyLeader = convoyLeader;
            this.follow = follow;
            this.goTarget = goTarget;
            this.recruitTour = recruitTour;
            this.purse = purse;
        }
    }

    /** Read before the death code resets the leader's flags. */
    public static LeaderOrder captureOrder(EntityTravellerWorker leader) {
        if (leader.recruit.getTour() != null) return new LeaderOrder(null, true, null, leader.recruit.getTour(), leader.purse.get());
        if (WorkerOrderGuard.isBusy(leader)) return LeaderOrder.UNKNOWN;
        BlockPos go = leader.trips.isGoOrderActive() ? leader.trips.getGoOrderTargetPos() : null;
        return new LeaderOrder(leader.getEscortLeaderUUID(), leader.isFollowOrderActive(), go, null, 0);
    }

    public static LeaderOrder captureOrder(VirtualTravellerRecord leader) {
        if (leader.recruitTour != null) return new LeaderOrder(null, true, null, leader.recruitTour, leader.purse.get());
        if (WorkerOrderGuard.isBusy(leader)) return LeaderOrder.UNKNOWN;
        return new LeaderOrder(leader.escortLeaderUUID, leader.followOrderActive,
                leader.goOrderActive ? leader.goal : null, null, 0);
    }

    /**
     * A hired leader died: its best-equipped follower (armour + weapon) takes over the group, the leader's order
     * and its formation settings, and the owner is told. Members on a mission or leading a group of their own are
     * never elected; with no candidate every follower goes back to the owner.
     */
    /** Returns true when a successor took over the leader's recruiting tour and purse. */
    public static boolean onLeaderDied(MinecraftServer server, @Nullable UUID owner, UUID leaderId, LeaderOrder order) {
        return owner != null && elect(server, owner, leaderId, order) != null && order.recruitTour != null;
    }

    /**
     * Fallback for a follower whose leader is gone without a death hook (record removed by a command): same
     * election, the successor follows the owner. Returns the caller's escort leader afterwards (null = none).
     */
    @Nullable
    public static UUID onLeaderMissing(MinecraftServer server, @Nullable UUID owner, UUID leaderId, UUID callerId) {
        // Alive elsewhere (another dimension): not gone, the caller just unlinks as before.
        if (owner == null || WorkerLookup.findLoaded(server, leaderId) != null || vwm(server).isVirtual(leaderId)) return null;
        UUID successor = elect(server, owner, leaderId, LeaderOrder.UNKNOWN);
        return successor == null || successor.equals(callerId) ? null : successor;
    }

    @Nullable
    private static UUID elect(MinecraftServer server, UUID owner, UUID leaderId, LeaderOrder order) {
        Map<UUID, EntityTravellerWorker> loaded = collectLoaded(server, owner, w -> leaderId.equals(w.getEscortLeaderUUID()));
        Map<UUID, VirtualTravellerRecord> virtual = collectVirtual(server, owner, r -> leaderId.equals(r.escortLeaderUUID));
        if (loaded.isEmpty() && virtual.isEmpty()) return null;

        Set<UUID> leadingOthers = new HashSet<>();
        collectLoaded(server, owner, w -> true).values().forEach(w -> addIfPresent(leadingOthers, w.getEscortLeaderUUID()));
        collectVirtual(server, owner, r -> true).values().forEach(r -> addIfPresent(leadingOthers, r.escortLeaderUUID));
        Map<UUID, EntityTravellerWorker> loadedCandidates = new HashMap<>();
        Map<UUID, VirtualTravellerRecord> virtualCandidates = new HashMap<>();
        loaded.forEach((id, w) -> {
            if (!WorkerOrderGuard.isBusy(w) && !leadingOthers.contains(id)) loadedCandidates.put(id, w);
        });
        virtual.forEach((id, r) -> {
            if (!WorkerOrderGuard.isBusy(r) && !leadingOthers.contains(id)) virtualCandidates.put(id, r);
        });
        UUID successor = pickBestEquipped(loadedCandidates, virtualCandidates);

        if (successor == null) {
            for (EntityTravellerWorker w : loaded.values()) backToOwnerLoaded(w);
            for (VirtualTravellerRecord r : virtual.values()) backToOwnerVirtual(r);
            epilogue(server, owner, new HashSet<>());
            return null;
        }

        UUID convoy = successor.equals(order.convoyLeader) ? null : order.convoyLeader;
        EntityTravellerWorker newLeader = loaded.get(successor);
        String name;
        NoticePlace place;
        if (newLeader != null) {
            newLeader.catchup.stop();
            newLeader.getNavigator().clearPath();
            if (convoy != null) newLeader.setEscortLeaderUUID(convoy);
            else newLeader.clearEscortLeader();
            if (order.goTarget != null) {
                newLeader.setFollowOrderActive(false);
                newLeader.trips.startGoTo(order.goTarget, newLeader.dimension);
            } else {
                newLeader.setFollowOrderActive(order.follow);
            }
            TravellerTracer.event(newLeader, "follow", "group succession: now leads the group of " + leaderId);
            name = newLeader.getName();
            place = NoticePlace.of(newLeader);
        } else {
            VirtualTravellerRecord r = virtual.get(successor);
            r.escortLeaderUUID = convoy;
            r.formationOrdinal = -1;
            clearVirtualCatchup(r);
            r.goOrderActive = order.goTarget != null;
            r.followOrderActive = order.goTarget == null && order.follow;
            r.goal = order.goTarget != null ? order.goTarget
                    : order.follow ? null : new BlockPos((int) r.posX, (int) r.posY, (int) r.posZ);
            name = r.name;
            place = new NoticePlace(new BlockPos((int) r.posX, (int) r.posY, (int) r.posZ), r.dimension, r.uuid);
        }
        for (EntityTravellerWorker w : loaded.values()) {
            if (w != newLeader) assignToLeaderLoaded(w, successor);
        }
        for (VirtualTravellerRecord r : virtual.values()) {
            if (!r.uuid.equals(successor)) assignToLeaderVirtual(r, successor);
        }
        if (order.recruitTour != null) {
            if (newLeader != null) {
                newLeader.purse.add(order.purse);
                newLeader.recruit.start(order.recruitTour);
            } else {
                VirtualTravellerRecord r = virtual.get(successor);
                net.minecraft.world.WorldServer ws = net.minecraftforge.common.DimensionManager.getWorld(r.dimension);
                r.purse.add(order.purse);
                if (ws != null) VirtualTravellerManager.get(ws).giveRecruitTour(ws, r, order.recruitTour);
            }
        }

        WorkerFormationSavedData formations = WorkerFormationSavedData.get(server.worlds[0]);
        formations.setSettings(new WorkerFormationScopeKey(WorkerFormationScopeKey.Type.WORKER, successor),
                formations.getSettings(new WorkerFormationScopeKey(WorkerFormationScopeKey.Type.WORKER, leaderId)));

        Set<UUID> affected = setOf(successor);
        addIfPresent(affected, convoy);
        epilogue(server, owner, affected);
        NotificationService.notifyOfflineAt(server, owner, NotificationDefs.GROUP_NEW_LEADER, place, null,
                name == null || name.isEmpty() ? "?" : name);
        return successor;
    }

    // -------------------------------------------------------------------------
    // Mutation primitives (loaded / virtual)
    // -------------------------------------------------------------------------

    private static void assignToLeaderLoaded(EntityTravellerWorker w, UUID leaderId) {
        w.setEscortLeaderUUID(leaderId);
        w.setFollowOrderActive(true);
        w.catchup.stop();
        w.getNavigator().clearPath();
    }

    private static void backToOwnerLoaded(EntityTravellerWorker w) {
        w.clearEscortLeader();
        w.setFollowOrderActive(true);
        w.catchup.stop();
        w.getNavigator().clearPath();
    }

    private static void becomeAnchoredLeaderLoaded(EntityTravellerWorker w) {
        w.clearEscortLeader();
        w.setFollowOrderActive(false);
        w.catchup.stop();
        w.getNavigator().clearPath();
    }

    private static void assignToLeaderVirtual(VirtualTravellerRecord r, UUID leaderId) {
        r.escortLeaderUUID = leaderId;
        r.followOrderActive = true;
        r.goOrderActive = false;
        r.formationOrdinal = -1;
        r.goal = null;
        clearVirtualCatchup(r);
    }

    private static void backToOwnerVirtual(VirtualTravellerRecord r) {
        r.escortLeaderUUID = null;
        r.followOrderActive = true;
        r.goOrderActive = false;
        r.formationOrdinal = -1;
        r.goal = null;
        clearVirtualCatchup(r);
    }

    private static void becomeAnchoredLeaderVirtual(VirtualTravellerRecord r) {
        r.escortLeaderUUID = null;
        r.followOrderActive = false;
        r.goOrderActive = false;
        r.formationOrdinal = -1;
        r.goal = null;
        clearVirtualCatchup(r);
    }

    /**
     * {@code VirtualTravellerRecord.isCatchupMode}/{@code catchupTargetUUID} are captured once, at
     * virtualization, from the loaded worker's live catchup-travel state ({@code
     * VirtualTravellerManager.virtualizeWorker}) and are otherwise never cleared anywhere in the
     * virtual simulation. {@code refreshGoal}'s catchup branch takes priority over everything else
     * (mission, go-order, escort slot, follow-owner) and tracks {@code catchupTargetUUID}'s LIVE
     * position for as long as that entity stays valid - for a hired follower this is very often the
     * owner, since that's what a follower catches up to. Left uncleared, a record that happened to
     * virtualize mid-catchup keeps walking toward that captured target forever, silently overriding
     * any later leader reassignment: escortLeaderUUID changes, but the goal never even reaches the
     * escort branch to notice. This is what made some followers "teleport" to the owner on
     * materialization and only then walk off toward their actual new leader - they were being pulled
     * toward the owner the whole time it was virtual, invisibly. Every group/leader mutation must
     * clear it so a fresh assignment always wins.
     */
    private static void clearVirtualCatchup(VirtualTravellerRecord r) {
        r.isCatchupMode = false;
        r.catchupTargetUUID = null;
    }

    // -------------------------------------------------------------------------
    // Epilogue — always runs after any mutation
    // -------------------------------------------------------------------------

    private static void epilogue(EntityPlayerMP player, MinecraftServer server, Set<UUID> affectedLeaders) {
        refreshOwnerWorkers(server, player.getUniqueID(), affectedLeaders);

        // Push the authoritative (loaded + virtual) follower count too, for GuiWorkerHub's "Lead"
        // button — see MsgSyncLeaderFollowerCount's doc for why the DataParameter above isn't enough.
        for (UUID leaderId : affectedLeaders) {
            syncLeaderFollowerCount(player, leaderId);
        }

        // Push the authoritative Hub leader list; updates in place if the Hub is open, ignored if not.
        sendHub(player, true);
    }

    /** Epilogue of a change no player ordered (a leader's death): the player half only while the owner is online. */
    private static void epilogue(MinecraftServer server, UUID owner, Set<UUID> affectedLeaders) {
        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(owner);
        if (player != null) epilogue(player, server, affectedLeaders);
        else refreshOwnerWorkers(server, owner, affectedLeaders);
    }

    private static void refreshOwnerWorkers(MinecraftServer server, UUID owner, Set<UUID> affectedLeaders) {
        VirtualTravellerManager vwm = vwm(server);
        vwm.markDirty();

        WorldServer overworld = server.worlds[0];
        long now = overworld.getTotalWorldTime();

        // Immediate goal refresh for every one of this owner's virtual workers. Bounded by the
        // owner's worker count and robust: it can never miss a follower that needs to reflow into a
        // gap left by a split/merge, and a group whose new leader is idle still converges into
        // formation this instant instead of waiting for (or freezing until) the next periodic pass.
        for (VirtualTravellerRecord r : vwm.getRecordsView()) {
            if (!r.isHiredFollower || !owner.equals(r.followOwnerUUID)) continue;
            WorldServer w = server.getWorld(r.dimension);
            vwm.forceRefreshGoal(w != null ? w : overworld, r, now);
        }

        // Keep the loaded leader-count DataParameter honest for affected leaders (drives their own
        // idle AI; the Hub/map leader identity is computed independently below).
        for (UUID leaderId : affectedLeaders) {
            EntityTravellerWorker e = WorkerLookup.findLoadedOwned(server, owner, leaderId);
            if (e != null) e.forceRefreshLeaderCount();
        }
    }

    // -------------------------------------------------------------------------
    // Hub leader list (shared by MsgRequestFormationHub and the epilogue push)
    // -------------------------------------------------------------------------

    /**
     * Rebuilds and sends this player's Formation Hub leader list (standalone map hub).
     *
     * @param updateOnly when true the client only refreshes an already-open Hub and never opens one;
     *                   when false it opens the Hub if it isn't already showing (the request path).
     */
    public static void sendHub(EntityPlayerMP player, boolean updateOnly) {
        sendHub(player, true, updateOnly, null, null);
    }

    /**
     * Opens the Formation Hub attached to a specific right-clicked worker (non-standalone), with the
     * initial scope focused on that worker's own group.
     */
    public static void sendHubForWorker(EntityPlayerMP player, int representativeEntityId,
                                        WorkerFormationScopeKey initialScope) {
        sendHub(player, false, false, representativeEntityId, initialScope);
    }

    private static void sendHub(EntityPlayerMP player, boolean standalone, boolean updateOnly,
                                @Nullable Integer representativeOverride,
                                @Nullable WorkerFormationScopeKey initialScopeOverride) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        UUID owner = player.getUniqueID();

        VirtualTravellerManager vwm = vwm(server);
        WorkerFormationSavedData fsd = WorkerFormationSavedData.get(player.world);
        Map<String, WorkerFormationSettings> settingsByScope = new HashMap<>();

        // ---- Single pass: gather this owner's workers (loaded + virtual), compute the leader set
        //      and per-leader follower counts. A worker is a LEADER iff at least one worker (loaded
        //      OR virtual) escorts it - this is the fix for "materialized leader with virtual
        //      followers vanishes from the list", since it no longer depends on a loaded-only count.
        Set<UUID> leaderIds = new HashSet<>();
        Map<UUID, Integer> followerCount = new HashMap<>();
        Map<UUID, FormationLeaderInfo> meta = new HashMap<>();
        // Keyed by group: the leader's own UUID gets itself, its followers add to it too.
        Map<UUID, GroupGearSummary> gear = new HashMap<>();
        GroupGearSummary playerGear = new GroupGearSummary();
        List<HubWorker> hubWorkers = new ArrayList<>();
        int representativeId = -1;

        for (WorldServer ws : server.worlds) {
            for (EntityTravellerWorker w : ws.getEntities(EntityTravellerWorker.class, e ->
                    e != null && !e.isDead && e.isHiredFollower() && owner.equals(e.getFollowOwnerId()))) {
                UUID lead = w.getEscortLeaderUUID();
                if (lead != null) {
                    leaderIds.add(lead);
                    followerCount.merge(lead, 1, Integer::sum);
                    gear.computeIfAbsent(lead, k -> new GroupGearSummary()).addLoaded(w);
                }
                hubWorkers.add(new HubWorker(w.getUniqueID(), lead, w.isFollowOrderActive(), w.getZoneRef(),
                        w.dimension, w.getPosition(), g -> g.addLoaded(w)));
                gear.computeIfAbsent(w.getUniqueID(), k -> new GroupGearSummary()).addLoaded(w);
                FormationLeaderInfo info = new FormationLeaderInfo(w.getUniqueID(), w.getName(), 0,
                        w.posX, w.posZ, false, false);
                info.followActive = w.isFollowOrderActive();
                info.goActive = w.trips.isGoOrderActive();
                info.busy = WorkerOrderGuard.isBusy(w);
                info.recallable = com.jubitus.travellers.worker.MissionRecall.isRecallable(w);
                info.cultureId = w.getCultureId();
                info.textureIndex = w.getTextureIndex();
                info.ownerColor = w.getOwnerMarkColor();
                info.entityId = w.getEntityId();
                if (w.getEquipRole() != null) info.equipRole = w.getEquipRole().bits;
                meta.put(w.getUniqueID(), info);
            }
        }
        for (VirtualTravellerRecord r : vwm.getRecordsView()) {
            if (!r.isHiredFollower || !owner.equals(r.followOwnerUUID)) continue;
            if (r.escortLeaderUUID != null) {
                leaderIds.add(r.escortLeaderUUID);
                followerCount.merge(r.escortLeaderUUID, 1, Integer::sum);
                gear.computeIfAbsent(r.escortLeaderUUID, k -> new GroupGearSummary()).addVirtual(r);
            }
            hubWorkers.add(new HubWorker(r.uuid, r.escortLeaderUUID, r.followOrderActive,
                    r.entityNbt != null ? com.jubitus.travellers.zone.ZoneRef.read(r.entityNbt) : null,
                    r.dimension, new BlockPos(r.posX, r.posY, r.posZ), g -> g.addVirtual(r)));
            gear.computeIfAbsent(r.uuid, k -> new GroupGearSummary()).addVirtual(r);
            if (!meta.containsKey(r.uuid)) meta.put(r.uuid, virtualMeta(r));
        }

        HubZones hubZones = new HubZones(server, player, hubWorkers, leaderIds);
        // The player row counts only workers walking with the player: not leaders, not Barrack residents at rest.
        int directFollowers = 0;
        for (HubWorker hw : hubWorkers) {
            if (hw.escortLeader != null || leaderIds.contains(hw.uuid) || hubZones.idleResidents.contains(hw.uuid)) continue;
            directFollowers++;
            hw.addTo.accept(playerGear);
        }

        List<FormationLeaderInfo> leaders = new ArrayList<>();

        // Player row first (workers following the owner directly).
        WorkerFormationScopeKey playerScope = new WorkerFormationScopeKey(
                WorkerFormationScopeKey.Type.PLAYER, owner);
        FormationLeaderInfo playerRow = new FormationLeaderInfo(null, player.getName(), directFollowers,
                player.posX, player.posZ, true, false);
        playerRow.gear = playerGear;
        leaders.add(playerRow);
        settingsByScope.put(playerScope.asMapKey(), fsd.getSettings(playerScope));

        // One row per leader.
        for (UUID leaderId : leaderIds) {
            FormationLeaderInfo m = meta.get(leaderId);
            if (m == null) continue; // dangling escort ref (leader gone); self-heals within ~20 ticks
            m.followerCount = followerCount.getOrDefault(leaderId, 0);
            m.gear = gear.get(leaderId);
            if (!m.isVirtual && representativeId < 0) representativeId = m.entityId;
            leaders.add(m);
            WorkerFormationScopeKey ldrScope = new WorkerFormationScopeKey(
                    WorkerFormationScopeKey.Type.WORKER, leaderId);
            settingsByScope.put(ldrScope.asMapKey(), fsd.getSettings(ldrScope));
        }

        leaders.sort((a, b) -> {
            if (a.isPlayer) return -1;
            if (b.isPlayer) return 1;
            double da = dist2(a.posX, a.posZ, player.posX, player.posZ);
            double db = dist2(b.posX, b.posZ, player.posX, player.posZ);
            return Double.compare(da, db);
        });

        if (representativeOverride != null) representativeId = representativeOverride;
        WorkerFormationScopeKey initialScope = initialScopeOverride != null ? initialScopeOverride : playerScope;

        TravellersNetwork.CHANNEL.sendTo(
                new MsgOpenFormationHubGui(representativeId, standalone, updateOnly, initialScope, leaders, hubZones.zones, settingsByScope),
                player);
    }

    private static FormationLeaderInfo virtualMeta(VirtualTravellerRecord r) {
        String name = r.entityNbt != null && r.entityNbt.hasKey("CustomName")
                ? r.entityNbt.getString("CustomName") : "Henchman";
        FormationLeaderInfo info = new FormationLeaderInfo(r.uuid, name, 0, r.posX, r.posZ, false, true);
        info.followActive = r.followOrderActive;
        info.goActive = r.goOrderActive;
        info.busy = WorkerOrderGuard.isBusy(r);
        info.recallable = com.jubitus.travellers.worker.MissionRecall.isRecallable(r);
        info.cultureId = r.entityNbt != null && r.entityNbt.hasKey("CultureId")
                ? r.entityNbt.getString("CultureId") : "";
        info.textureIndex = r.entityNbt != null && r.entityNbt.hasKey("TextureIdx")
                ? r.entityNbt.getInteger("TextureIdx") : 0;
        info.ownerColor = r.ownerColor;
        info.entityId = -1;
        String roleKey = com.jubitus.travellers.unit.equipment.EquipLoadout.ROLE_NBT_KEY;
        if (r.entityNbt != null && r.entityNbt.hasKey(roleKey)) info.equipRole = r.entityNbt.getByte(roleKey);
        return info;
    }

    // -------------------------------------------------------------------------
    // Collection / query helpers
    // -------------------------------------------------------------------------

    /**
     * Authoritative (loaded + virtual) follower count for a leader, the same union `sendHub` counts
     * for the Formation Hub's list. Use this for anything player-facing — never
     * {@code EntityTravellerWorker.getLeaderFollowerCount()}, which is loaded-only by design.
     */
    public static int countFollowers(MinecraftServer server, UUID owner, UUID leaderId) {
        int count = 0;
        for (WorldServer ws : server.worlds) {
            for (EntityTravellerWorker w : ws.getEntities(EntityTravellerWorker.class, e ->
                    e != null && !e.isDead && e.isHiredFollower()
                            && owner.equals(e.getFollowOwnerId()) && leaderId.equals(e.getEscortLeaderUUID()))) {
                count++;
            }
        }
        for (VirtualTravellerRecord r : vwm(server).getRecordsView()) {
            if (r.isHiredFollower && owner.equals(r.followOwnerUUID) && leaderId.equals(r.escortLeaderUUID)) {
                count++;
            }
        }
        return count;
    }

    /** Loaded hired workers of the leader's world escorting it: the leader's own count DataParameter. */
    public static int countLoadedFollowers(EntityTravellerWorker leader) {
        UUID ownerId = leader.getFollowOwnerId();
        UUID myId = leader.getUniqueID();

        // Count loaded hired workers whose escort leader == me
        List<EntityTravellerWorker> all = leader.world.getEntities(EntityTravellerWorker.class, w ->
                w != null
                        && !w.isDead
                        && w != leader
                        && w.isHiredFollower()
                        && Objects.equals(w.getFollowOwnerId(), ownerId)
                        && myId.equals(w.getEscortLeaderUUID())
        );
        return all.size();
    }

    /** Pushes the authoritative follower count for {@code leaderId} to {@code player}, if it's loaded. */
    public static void syncLeaderFollowerCount(EntityPlayerMP player, UUID leaderId) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        EntityTravellerWorker leader = WorkerLookup.findLoadedOwned(server, player.getUniqueID(), leaderId);
        if (leader == null) return;
        int count = countFollowers(server, player.getUniqueID(), leaderId);
        TravellersNetwork.CHANNEL.sendTo(
                new MsgSyncLeaderFollowerCount(leader.getEntityId(), count),
                player);
    }

    private static Map<UUID, EntityTravellerWorker> collectLoaded(MinecraftServer server, UUID owner,
                                                                  Predicate<EntityTravellerWorker> filter) {
        Map<UUID, EntityTravellerWorker> out = new HashMap<>();
        for (WorldServer ws : server.worlds) {
            for (EntityTravellerWorker w : ws.getEntities(EntityTravellerWorker.class, e ->
                    e != null && !e.isDead && e.isHiredFollower()
                            && owner.equals(e.getFollowOwnerId()) && filter.test(e))) {
                out.put(w.getUniqueID(), w);
            }
        }
        return out;
    }

    private static Map<UUID, VirtualTravellerRecord> collectVirtual(MinecraftServer server, UUID owner,
                                                                    Predicate<VirtualTravellerRecord> filter) {
        Map<UUID, VirtualTravellerRecord> out = new HashMap<>();
        for (VirtualTravellerRecord r : vwm(server).getRecordsView()) {
            if (r.isHiredFollower && owner.equals(r.followOwnerUUID) && filter.test(r)) {
                out.put(r.uuid, r);
            }
        }
        return out;
    }

    private static boolean anyLoaded(MinecraftServer server, UUID owner, Predicate<EntityTravellerWorker> filter) {
        for (WorldServer ws : server.worlds) {
            if (!ws.getEntities(EntityTravellerWorker.class, e ->
                    e != null && !e.isDead && e.isHiredFollower()
                            && owner.equals(e.getFollowOwnerId()) && filter.test(e)).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyVirtual(MinecraftServer server, UUID owner, Predicate<VirtualTravellerRecord> filter) {
        for (VirtualTravellerRecord r : vwm(server).getRecordsView()) {
            if (r.isHiredFollower && owner.equals(r.followOwnerUUID) && filter.test(r)) return true;
        }
        return false;
    }

    /**
     * Removes every worker that is busy on a mission from the collected loaded/virtual maps so a group
     * operation only ever touches orderable (idle) workers, and returns how many were dropped. The
     * caller then applies the op to what remains and, if any were dropped, notifies the player once.
     */
    private static int stripBusy(Map<UUID, EntityTravellerWorker> loaded,
                                 Map<UUID, VirtualTravellerRecord> virtual) {
        int skipped = 0;
        for (java.util.Iterator<EntityTravellerWorker> it = loaded.values().iterator(); it.hasNext(); ) {
            if (WorkerOrderGuard.isBusy(it.next())) {
                it.remove();
                skipped++;
            }
        }
        for (java.util.Iterator<VirtualTravellerRecord> it = virtual.values().iterator(); it.hasNext(); ) {
            if (WorkerOrderGuard.isBusy(it.next())) {
                it.remove();
                skipped++;
            }
        }
        return skipped;
    }

    private static void notifySkipped(EntityPlayerMP player, int skipped) {
        if (skipped > 0) {
            NotificationService.notify(player, NotificationDefs.ORDER_SOME_BUSY_SKIPPED, String.valueOf(skipped));
        }
    }

    /** True if the given worker (loaded or virtual) is busy on a mission and can't lead/receive a group. */
    private static boolean leaderBusy(MinecraftServer server, UUID owner, UUID leaderId) {
        EntityTravellerWorker w = WorkerLookup.findLoadedOwned(server, owner, leaderId);
        if (w != null) return WorkerOrderGuard.isBusy(w);
        VirtualTravellerRecord r = vwm(server).getRecord(leaderId);
        return r != null && owner.equals(r.followOwnerUUID) && WorkerOrderGuard.isBusy(r);
    }

    /** Best armour + weapon (the same score as traveller-group succession); null when both maps are empty. */
    @Nullable
    private static UUID pickBestEquipped(Map<UUID, EntityTravellerWorker> loaded,
                                         Map<UUID, VirtualTravellerRecord> virtual) {
        Map<UUID, Double> scores = new HashMap<>();
        loaded.forEach((id, w) -> scores.put(id, GroupSuccession.gearScore(w)));
        virtual.forEach((id, r) -> scores.put(id, GroupSuccession.gearScore(r)));
        return scores.isEmpty() ? null : GroupSuccession.pickSuccessor(scores);
    }

    private static Set<UUID> setOf(UUID a) {
        Set<UUID> s = new HashSet<>();
        s.add(a);
        return s;
    }

    private static void addIfPresent(Set<UUID> set, @Nullable UUID id) {
        if (id != null) set.add(id);
    }

    private static double dist2(double ax, double az, double bx, double bz) {
        double dx = ax - bx, dz = az - bz;
        return dx * dx + dz * dz;
    }
}
