package com.jubitus.travellers.virtual;

import com.jubitus.travellers.camp.CampOccupant;
import com.jubitus.travellers.camp.CampsiteManager;
import com.jubitus.travellers.camp.CampsiteRecord;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerRaider;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.village.MillSettlements;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builds a record from a loaded unit when it virtualizes, and re-applies the record's live state when it materializes. */
final class VirtualSnapshot {

    private VirtualSnapshot() {
    }

    /** Everything a loaded worker hands to its record, up to the village-stay hand-over; the caller finishes and commits it. */
    static VirtualTravellerRecord captureWorker(WorldServer world, EntityTravellerWorker worker, long now) {
        // The sleep pose (palfrenier in its Stable bed at night) is a purely-loaded cosmetic state,
        // never simulated or persisted - clear it before snapshotting so entityNbt can't freeze a
        // 0.2-wide hitbox / pinned position. Re-derived on materialize (see materializeEntity).
        if (worker.sleep.isSleeping()) worker.sleep.wakeUp();

        NBTTagCompound entityNbt = new NBTTagCompound();
        worker.writeToNBT(entityNbt); // full NBT: name, culture, voice, equipment, etc.
        com.jubitus.travellers.debug.MissionDebugLog.record(worker.getUniqueID(), worker.getName(), "virtualize", entityNbt);

        VirtualTravellerRecord r = new VirtualTravellerRecord(
                worker.getUniqueID(),
                worker.posX, worker.posY, worker.posZ,
                worker.rotationYaw,
                world.provider.getDimension(),
                entityNbt
        );

        r.isHiredFollower = worker.isHiredFollower();
        r.name = worker.getName();
        r.ownerColor = worker.getOwnerMarkColor() & 0xFFFFFF;
        r.followOwnerUUID = worker.getFollowOwnerUUID();
        r.followOrderActive = worker.isFollowOrderActive();
        r.isCatchupMode = worker.isCatchupTravel();
        r.catchupTargetUUID = worker.catchup.getTargetId();
        r.workerMode = worker.getMode().name();
        r.jobGoal = worker.getJobGoal();
        r.recruitTour = worker.recruit.getTour();
        r.purse.set(worker.purse.get());
        r.goOrderActive = worker.trips.isGoOrderActive();
        // Duty assignment + personal self-travel (Go Home / Go To Duty): a loaded worker that
        // virtualizes mid-route would otherwise drop these and stall offscreen (nothing refreshes
        // its goal), only resuming when a player walks back into range.
        r.dutyKind = worker.getDutyKind();
        r.dutyZoneCenter = worker.getDutyZoneCenter();
        r.goingHome = worker.isGoingHome();
        r.goingToDuty = worker.trips.isGoingToDuty();
        r.personalTravelGoal = worker.trips.getActivePersonalTravelGoal();
        r.homeVillage = worker.getHomeVillage();
        r.escortLeaderUUID = worker.getEscortLeaderUUID();
        r.groupLeaderUUID = worker.getGroupLeaderUUID();
        r.groupFormationStyle = worker.getGroupFormationStyle().name();
        r.captureIdentityFromNbt();
        r.virtualizedTick = now;

        // Seed the persistent formation slot from the loaded coordinator's current ordinal (if any)
        // so VirtualFormationSimulator keeps the worker in the same slot instead of appending it
        // at the back of the line the moment it goes virtual.
        if (r.escortLeaderUUID != null && r.formationOrdinal < 0) {
            r.formationOrdinal = com.jubitus.travellers.follow.FollowFormationCoordinator
                    .snapshotOrdinal(r.escortLeaderUUID, world.provider.getDimension(), worker.getUniqueID());
        }

        // Compute initial goal
        BlockPos initialGoal = worker.getVirtualGoal();
        r.goal = initialGoal;
        r.health = worker.getHealth();

        // A non-recruited idle worker roams village-to-village offscreen exactly like a traveller:
        // capture its trip. Hired / escort / mission / personal-travel workers keep no trip.
        if (!r.isHiredFollower && "IDLE".equals(r.workerMode) && r.personalTravelGoal == null
                && r.escortLeaderUUID == null) {
            captureTrip(worker, r);
        }
        handOverItinerary(worker, r);
        handOverVisit(worker, r);
        return r;
    }

    /** Everything a loaded traveller or raider hands to its record; the caller commits it. */
    static VirtualTravellerRecord captureTraveller(WorldServer world, EntityTraveller entity, long now) {
        NBTTagCompound entityNbt = new NBTTagCompound();
        entity.writeToNBT(entityNbt);

        VirtualTravellerRecord r = new VirtualTravellerRecord(
                entity.getUniqueID(),
                entity.posX, entity.posY, entity.posZ,
                entity.rotationYaw,
                world.provider.getDimension(),
                entityNbt
        );

        r.entityClass = entity instanceof EntityTravellerRaider ? "RAIDER" : "TRAVELLER";
        r.groupLeaderUUID = entity.getGroupLeaderUUID();
        r.groupFormationStyle = entity.getGroupFormationStyle().name();
        // The entity's own home identity is authoritative — TravellerSoftSpawner's population
        // accounting reads r.homeVillage directly, so this must stay in sync with it. Deliberately
        // NEVER overwritten by a "nearest village" proximity guess below: a traveller virtualizing
        // far from home (the normal case — that's the whole point of travelling) must keep counting
        // toward the village that actually spawned it, not get silently "adopted" by whichever
        // village it happens to be nearest to at this exact moment. An earlier version of this method
        // did exactly that unconditionally, re-labelling every traveller's home on every virtualize —
        // which fed the same undercounting/over-spawn bug this field was added to fix in the first
        // place (see population-overshoot fix notes), just one layer deeper.
        r.homeVillage = entity.getHomeVillage();
        r.captureIdentityFromNbt();

        deriveCampOccupation(world, entity.getUniqueID(), now, r);

        // Fallback only: an entity that never got a home identity at all (a save from before this
        // field existed) approximates one via nearest village, once, and the assignment is made
        // sticky on the entity itself so it doesn't need to keep guessing on every future virtualize.
        if (r.homeVillage == null) {
            MillSettlements.Settlement nearest = MillSettlements.nearest(world, entity.getPosition(), 3000);
            if (nearest != null) {
                r.homeVillage = new BlockPos(nearest.center.getX(), 0, nearest.center.getZ());
                entity.setHomeVillage(r.homeVillage);
            }
        }

        captureTrip(entity, r);
        handOverItinerary(entity, r);
        handOverVisit(entity, r);

        r.virtualizedTick = now;
        r.health = entity.getHealth();
        return r;
    }

    /** Re-applies the record-authoritative state of a traveller or raider right after readFromNBT. */
    static void applyToTraveller(VirtualTravellerRecord r, EntityTraveller entity) {
        applyGroup(r, entity);
        // Unconditional overwrite: the record is authoritative for population accounting
        // (TravellerSoftSpawner), entityNbt is just a frozen snapshot.
        entity.setHomeVillage(r.homeVillage);
    }

    /** Group link and style, shared by travellers, raiders and unhired worker escorts. */
    private static void applyGroup(VirtualTravellerRecord r, EntityTraveller entity) {
        // Unconditional: entityNbt may still carry a stale (e.g. dead-leader) UUID that r.groupLeaderUUID
        // has since cleared (see VirtualBattleManager.endBattle) — always overwrite, null included.
        entity.setGroupLeaderUUID(r.groupLeaderUUID);
        if (r.groupFormationStyle != null) {
            try {
                entity.setGroupFormationStyle(
                        com.jubitus.travellers.follow.formations.WorkerFormationStyle
                                .valueOf(r.groupFormationStyle));
            } catch (IllegalArgumentException ignored) {
                entity.setGroupFormationStyle(
                        com.jubitus.travellers.follow.formations.WorkerFormationStyle.RING);
            }
        }
    }

    /**
     * After readFromNBT, the entity reflects state at virtualization time. This method
     * applies whatever the live record fields say, overriding stale NBT values for the
     * fields that commands can change while the worker is virtual (follow order, go order,
     * escort leader). Called once, immediately after readFromNBT in materializeWorker.
     */
    static void applyToWorker(VirtualTravellerRecord r, EntityTravellerWorker worker) {
        applyGroup(r, worker);
        // Unconditional: the record's tour went on offscreen, entityNbt holds the one it left with.
        worker.recruit.adoptTour(r.recruitTour);
        worker.purse.set(r.purse.get());
        // Escort leader may have changed while virtual (create/assign/merge/come-back commands).
        if (!java.util.Objects.equals(r.escortLeaderUUID, worker.getEscortLeaderUUID())) {
            worker.setEscortLeaderUUID(r.escortLeaderUUID);
        }

        // Go Home / Go To Duty travel was already re-armed from the frozen NBT by readEntityFromNBT.
        // That is legitimate self-travel toward a fixed destination, not a stale follow route - leave
        // it fully intact (cancelGoOrderAndTravel below would wipe the route/target/readyToDepart and
        // strand the worker where it materialized). startGoHome/startDutyTravel already cleared any
        // go order or escort link, so there is nothing else here to reconcile.
        if (worker.isGoingHome() || worker.trips.isGoingToDuty()) return;

        // D-2: a non-recruited idle worker roaming village-to-village. materializeEntity's worker
        // branch restores its final destination (restoreVirtualRoute) right after this call, and the
        // loaded roam AI then drives the leg - leave it alone here, exactly like Go Home / Go To Duty
        // above (the cancelGoOrderAndTravel paths below would strand it).
        if (!r.isHiredFollower && "IDLE".equals(r.workerMode) && r.personalTravelGoal == null
                && r.escortLeaderUUID == null) {
            return;
        }

        if (r.goOrderActive && r.goal != null) {
            // Go order active (possibly a destination issued while virtual): (re)issue it.
            // startGoToDestination rebuilds the travel route from scratch, so any stale route baked into
            // the frozen NBT is overwritten here.
            if (!worker.trips.isGoOrderActive() || !r.goal.equals(worker.trips.getGoOrderTargetPos())) {
                worker.trips.startGoTo(r.goal, r.dimension);
            }
            return;
        }

        // No go order in the record. A mission (workerMode != IDLE) or a non-hired roaming worker drives
        // its own travel from its mode, so leave that restored travel state intact - only drop a stale go
        // order the frozen NBT may still carry and re-enable follow if the record wants it.
        if (!"IDLE".equals(r.workerMode) || !r.isHiredFollower) {
            if (worker.trips.isGoOrderActive()) worker.trips.cancelGoOrder();
            if (r.followOrderActive && !worker.isFollowOrderActive()) worker.setFollowOrderActive(true);
            return;
        }

        // Hired + IDLE + no go order = following the owner/escort leader, or anchored in place. In every
        // such case the worker must NOT keep a stale travel route/target from the frozen NBT, or the
        // travel AI walks it off to that old location on materialize (the "just-promoted leader heads back
        // toward its old group / old location, oscillating at the view border" bug). cancelGoOrderAndTravel
        // clears the go order AND the leftover route/targetVillage/readyToDepart; follow/anchor then drive
        // movement from the record's followOrderActive.
        worker.trips.cancelGoOrder();
        worker.setFollowOrderActive(r.followOrderActive);
    }

    /**
     * Re-derives {@code r.campSiteId}/{@code campSlotIndex} from the campsite occupant (the source of
     * truth) at virtualize time, and resets the occupant's virtual build clock so loaded time spent
     * pacing/fighting between block placements doesn't retroactively convert into virtual progress.
     * Shared by traveller/raider and (non-hired, idle) worker virtualization.
     */
    static void deriveCampOccupation(WorldServer world, UUID uuid, long now, VirtualTravellerRecord r) {
        CampsiteRecord camp = CampsiteManager.get(world).campOf(uuid);
        if (camp == null) return;
        for (Map.Entry<Integer, CampOccupant> e : camp.occupants.entrySet()) {
            if (e.getValue().travellerUUID.equals(uuid)) {
                r.campSiteId = camp.id;
                r.campSlotIndex = e.getKey();
                CampOccupant occ = e.getValue();
                if (occ.buildState != CampOccupant.BuildState.BUILT) {
                    occ.lastProgressTick = now;
                }
                break;
            }
        }
    }

    /** Copies the loaded village trip (TravelCommon.route/routeIndex) onto the record. */
    private static void captureTrip(EntityTraveller entity, VirtualTravellerRecord r) {
        List<BlockPos> liveRoute = entity.getTravelRoute();
        int liveIndex = entity.getVillageRouteIndex();
        if (!liveRoute.isEmpty() && liveIndex >= 0 && liveIndex < liveRoute.size()) {
            r.setTrip(liveRoute, liveIndex);
            r.goal = liveRoute.get(liveIndex);
        } else if (entity.getTravelCommon().getTargetVillage() != null) {
            r.setTrip(java.util.Collections.singletonList(entity.getTravelCommon().getTargetVillage()), 0);
            r.goal = entity.getTravelCommon().getTargetVillage();
        }
    }

    /** Moves the entity's village stay onto the record as-is (never duplicated in entityNbt). */
    private static void handOverVisit(EntityTraveller entity, VirtualTravellerRecord r) {
        r.visit = entity.getVillageVisitState();
        entity.setVillageVisitState(null);
        r.entityNbt.removeTag(com.jubitus.travellers.village.visit.VillageVisitState.NBT_KEY);
        if (r.visit != null) r.visit.nextStepTick = 0L;
    }

    /** Moves the entity's itinerary + cursor onto the record as-is (never duplicated in entityNbt). */
    private static void handOverItinerary(EntityTraveller entity, VirtualTravellerRecord r) {
        r.itinerary = entity.getItineraryState();
        entity.setItineraryState(null);
        r.entityNbt.removeTag(EntityTraveller.ITINERARY_NBT_KEY);
    }
}
