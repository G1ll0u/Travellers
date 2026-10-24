package com.jubitus.travellers.virtual;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.notify.MissionNotifications;
import com.jubitus.travellers.unit.DeliveryJob;
import com.jubitus.travellers.zone.ZoneRef;
import javax.annotation.Nullable;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.village.MillSettlements;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.WorldServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Offscreen hired-worker missions (Go, delivery, recruiting, Get Equipment): resolved on arrival without loading the worker. */
final class VirtualWorkerMissions {

    // Within this distance of its mission target an offscreen worker resolves the mission.
    static final double JOB_ARRIVAL_FORCE_DIST = 48.0;

    private final VirtualTravellerManager manager;
    private final Map<UUID, VirtualTravellerRecord> records;

    VirtualWorkerMissions(VirtualTravellerManager manager, Map<UUID, VirtualTravellerRecord> records) {
        this.manager = manager;
        this.records = records;
    }

    void checkArrivals(WorldServer world) {
        if (records.isEmpty()) return;

        List<VirtualTravellerRecord> hiredArrivals = null;
        List<UUID> recruitArrivals = null;
        List<UUID> getEquipArrivals = null;
        List<UUID> goArrivals = null;

        int dim = world.provider.getDimension();
        for (VirtualTravellerRecord r : records.values()) {
            if (r.dimension != dim) continue;
            if (r.battleId != null) continue; // no job arrivals mid-battle

            // Hired workers on TO_VILLAGE missions: virtual delivery (inventory trade, no chunk load)
            if (r.isHiredFollower && r.jobGoal != null && "TO_VILLAGE".equals(r.workerMode)) {
                double dx = r.jobGoal.getX() + 0.5 - r.posX;
                double dz = r.jobGoal.getZ() + 0.5 - r.posZ;
                if (dx * dx + dz * dz <= JOB_ARRIVAL_FORCE_DIST * JOB_ARRIVAL_FORCE_DIST) {
                    if (hiredArrivals == null) hiredArrivals = new ArrayList<>();
                    hiredArrivals.add(r);
                }
                continue;
            }

            // GET_EQUIPMENT missions: virtual exchange when near barracks (no chunk loading for pathfinding)
            if (r.isHiredFollower && r.goal != null && "GET_EQUIPMENT".equals(r.workerMode)) {
                double dx = r.goal.getX() + 0.5 - r.posX;
                double dz = r.goal.getZ() + 0.5 - r.posZ;
                if (dx * dx + dz * dz <= JOB_ARRIVAL_FORCE_DIST * JOB_ARRIVAL_FORCE_DIST) {
                    if (getEquipArrivals == null) getEquipArrivals = new ArrayList<>();
                    getEquipArrivals.add(r.uuid);
                }
                continue;
            }

            // Recruiting tours, outbound and return legs: processed after iteration (recruits change the record map)
            if (r.isHiredFollower && r.recruitTour != null) {
                if (recruitArrivals == null) recruitArrivals = new ArrayList<>();
                recruitArrivals.add(r.uuid);
                continue;
            }

            // Hired workers on a plain Go order: arrival is resolved offscreen too, so the player
            // gets the same notice and the worker anchors exactly as the loaded arrival does.
            if (r.isHiredFollower && r.goOrderActive && r.goal != null && "IDLE".equals(r.workerMode)) {
                double dx = r.goal.getX() + 0.5 - r.posX;
                double dz = r.goal.getZ() + 0.5 - r.posZ;
                double rad = TravellersConfig.WORKER.workerGoArrivalRadius;
                if (dx * dx + dz * dz <= rad * rad) {
                    if (goArrivals == null) goArrivals = new ArrayList<>();
                    goArrivals.add(r.uuid);
                }
                continue;
            }

        }

        if (goArrivals != null) {
            for (UUID id : goArrivals) {
                VirtualTravellerRecord r = records.get(id);
                if (r != null) finishVirtualGoOrder(world, r);
            }
        }

        // GET_EQUIPMENT arrivals: virtual exchange (force-loads only chest chunks, no entity spawn/pathfinding)
        if (getEquipArrivals != null) {
            for (UUID id : getEquipArrivals) {
                VirtualTravellerRecord r = records.get(id);
                if (r != null) tryVirtualEquipExchange(world, r);
            }
        }

        // Hired workers: virtual delivery (chest chunks borrowed for the trade, worker stays virtual then RETURNING)
        if (hiredArrivals != null) {
            for (VirtualTravellerRecord r : hiredArrivals) {
                tryHiredVirtualDelivery(world, r);
            }
        }

        if (recruitArrivals != null) {
            for (UUID id : recruitArrivals) {
                VirtualTravellerRecord r = records.get(id);
                if (r == null || r.recruitTour == null) continue;
                if ("RECRUITING".equals(r.workerMode)) tickVirtualTour(world, r);
                com.jubitus.travellers.worker.RecruitEncounters.tick(world, r.recruitTour, r.uuid,
                        r.followOwnerUUID, r.posX, r.posZ);
            }
        }

    }

    private static com.jubitus.travellers.notify.NoticePlace placeOf(VirtualTravellerRecord r) {
        return new com.jubitus.travellers.notify.NoticePlace(new BlockPos(r.posX, r.posY, r.posZ), r.dimension, r.uuid);
    }

    private void finishVirtualGoOrder(WorldServer world, VirtualTravellerRecord r) {
        EntityTravellerWorker worker = new EntityTravellerWorker(world);
        worker.readFromNBT(r.entityNbt);
        worker.setUniqueId(r.uuid);
        worker.trips.finishGoOrderVirtually(r.goal);
        NBTTagCompound updatedNbt = new NBTTagCompound();
        worker.writeToNBT(updatedNbt);
        r.entityNbt = updatedNbt;
        r.invalidateNbtCaches();

        r.goOrderActive = false;
        r.followOrderActive = false;
        manager.markDirty();

        com.jubitus.travellers.notify.MissionNotifications.arrived(
                world.getMinecraftServer(), r.followOwnerUUID, r.uuid, placeOf(r));
    }

    /**
     * A RETURNING record's goal. Mirrors DeliveryJob's absent-employer rule for a delivered cargo: with
     * the owner away it heads home (else stays on its last goal), and turns back toward them once they return.
     */
    void tickReturnToOwner(WorldServer world, VirtualTravellerRecord r, @Nullable EntityPlayer owner) {
        NBTTagCompound nbt = r.entityNbt;
        boolean cargo = nbt != null && nbt.getBoolean("JobDelivered");
        if (!cargo) {
            if (owner != null) r.goal = owner.getPosition();
            return;
        }
        boolean absent = nbt.getBoolean(DeliveryJob.EMPLOYER_ABSENT_KEY);
        if (owner != null) {
            if (absent) {
                nbt.setBoolean(DeliveryJob.EMPLOYER_ABSENT_KEY, false);
                nbt.setBoolean("AwaitingPickup", false);
                double dx = owner.posX - r.posX;
                double dz = owner.posZ - r.posZ;
                if (DeliveryJob.hasWalkBackTo(dx * dx + dz * dz)) {
                    MissionNotifications.cargoComingBack(world.getMinecraftServer(), r.followOwnerUUID, r.name, placeOf(r));
                }
                manager.markDirty();
            }
            r.goal = owner.getPosition();
            return;
        }
        if (absent) return;
        nbt.setBoolean(DeliveryJob.EMPLOYER_ABSENT_KEY, true);
        BlockPos home = DeliveryJob.homeFor(ZoneRef.read(nbt), r.dimension);
        if (home != null) r.goal = home;
        MissionNotifications.cargoEmployerAbsent(world.getMinecraftServer(), r.followOwnerUUID, r.name, home != null,
                placeOf(r));
        manager.markDirty();
    }

    private void tryHiredVirtualDelivery(WorldServer world, VirtualTravellerRecord r) {
        if (r.jobGoal == null) return;
        if (!com.jubitus.travellers.ground.chunk.LoadedChunks.tryAcquireJob(world)) return;

        // Build a temporary entity (not spawned) to run the delivery logic
        EntityTravellerWorker worker = new EntityTravellerWorker(world);
        worker.readFromNBT(r.entityNbt);
        worker.setUniqueId(r.uuid);
        worker.setPositionAndRotation(r.posX, r.posY, r.posZ, r.rotYaw, 0);

        // Loads the village's chest chunks only for the trade, then releases them
        worker.delivery.deliverCargo(r.jobGoal);

        // Persist updated state (empty cargo, money added, mode=RETURNING)
        NBTTagCompound updatedNbt = new NBTTagCompound();
        worker.writeToNBT(updatedNbt);
        com.jubitus.travellers.debug.MissionDebugLog.record(r.uuid, r.name, "virtual-trade-after", updatedNbt);
        r.entityNbt = updatedNbt;
        r.invalidateNbtCaches();

        // Update record to RETURNING so the virtual worker heads back to the employer
        r.workerMode = "RETURNING";
        r.jobGoal = null;
        BlockPos returnGoal = worker.getVirtualGoal(); // returns employerReturnPos when RETURNING
        if (returnGoal != null) r.goal = returnGoal;

        manager.markDirty();
    }

    /** The offscreen side of RecruitMission.tickTour: same tour decisions, arrival judged at the mission radius. */
    private void tickVirtualTour(WorldServer world, VirtualTravellerRecord r) {
        com.jubitus.travellers.worker.RecruitTour tour = r.recruitTour;
        long now = world.getTotalWorldTime();
        com.jubitus.travellers.worker.RecruitPlan.Stop stop = tour.currentStop();
        if (stop != null && !tour.isStaying()) {
            double dx = stop.center.getX() + 0.5 - r.posX;
            double dz = stop.center.getZ() + 0.5 - r.posZ;
            if (dx * dx + dz * dz <= JOB_ARRIVAL_FORCE_DIST * JOB_ARRIVAL_FORCE_DIST) {
                tour.arrive(now);
                manager.markDirty();
            }
            return;
        }
        if (stop != null && !tour.isStayOver(now)) return;
        com.jubitus.travellers.worker.RecruitPlan.Stop next = stop == null || r.followOwnerUUID == null
                ? null : tour.leave(world, r.followOwnerUUID);
        if (next != null) {
            r.jobGoal = next.center;
            r.goal = next.center;
        } else {
            tour.turnBack();
            r.workerMode = "RETURNING";
            r.jobGoal = null;
            EntityPlayer owner = r.followOwnerUUID != null ? world.getPlayerEntityByUUID(r.followOwnerUUID) : null;
            if (owner != null) r.goal = owner.getPosition();
        }
        manager.markDirty();
    }

    private void tryVirtualEquipExchange(WorldServer world, VirtualTravellerRecord r) {
        BlockPos target = r.goal;
        if (target == null) return;

        com.jubitus.travellers.unit.equipment.EquipTargetKind kind = r.entityNbt.hasKey("GetEquipTargetKind")
                ? com.jubitus.travellers.unit.equipment.EquipTargetKind.fromNbt(r.entityNbt.getString("GetEquipTargetKind"))
                // Legacy records stored a boolean instead - true meant CONTROLLED_VILLAGE.
                : (r.entityNbt.getBoolean("GetEquipWholeVillage")
                    ? com.jubitus.travellers.unit.equipment.EquipTargetKind.CONTROLLED_VILLAGE
                    : com.jubitus.travellers.unit.equipment.EquipTargetKind.LONE_BARRACK);
        boolean stay = r.entityNbt.getBoolean("GetEquipStay");

        if (kind == com.jubitus.travellers.unit.equipment.EquipTargetKind.STABLE_RETURN) {
            tryVirtualHorseReturn(world, r, target, stay);
            return;
        }

        if (kind == com.jubitus.travellers.unit.equipment.EquipTargetKind.STABLE) {
            tryVirtualHorsePickup(world, r, target, stay);
            return;
        }

        if (!com.jubitus.travellers.ground.chunk.LoadedChunks.tryAcquireJob(world)) return;

        // Temporary entity (not spawned) carries equipment state for the swap; the chest chunks are borrowed inside.
        EntityTravellerWorker worker = new EntityTravellerWorker(world);
        worker.readFromNBT(r.entityNbt);
        worker.setUniqueId(r.uuid);
        worker.setPositionAndRotation(r.posX, r.posY, r.posZ, r.rotYaw, 0);

        com.jubitus.travellers.unit.equipment.WorkerEquipExchanger.run(worker, world, target, kind);

        finishVirtualEquip(world, r, worker, target, stay);
    }

    /**
     * STABLE branch of {@link #tryVirtualEquipExchange}: zero chunk loading, zero entity spawning.
     * The claimed horse's NBT goes straight into {@code r.mountNbt}/{@code r.mounted} - the same shape
     * {@code captureMountIfBonded} uses - so {@code restoreOrBondMount} spawns and bonds it on materialize.
     */
    private void tryVirtualHorsePickup(WorldServer world, VirtualTravellerRecord r, BlockPos target, boolean stay) {
        EntityTravellerWorker worker = new EntityTravellerWorker(world);
        worker.readFromNBT(r.entityNbt);
        worker.setUniqueId(r.uuid);
        worker.setPositionAndRotation(r.posX, r.posY, r.posZ, r.rotYaw, 0);

        com.jubitus.travellers.zone.PlayerZoneRecord zone =
                com.jubitus.travellers.zone.PlayerZoneManager.get(world)
                        .getByOwnerAndCenter(r.followOwnerUUID, target);
        if (zone != null) {
            boolean wantsHorse = worker.getMountUUID() == null && r.mountNbt == null;
            com.jubitus.travellers.zone.stable.WorkerHorsePickup.equipAtStable(world, zone, r.uuid,
                    r.followOwnerUUID, wantsHorse, horseNbt -> {
                        r.mountNbt = horseNbt;
                        r.mounted = true;
                        r.mountMode = com.jubitus.travellers.mount.MountMode.MOUNTED.name();
                        return true;
                    });
        }

        finishVirtualEquip(world, r, worker, target, stay);
    }

    /** STABLE_RETURN branch of {@link #tryVirtualEquipExchange}: the horses go back to the pool as data. */
    private void tryVirtualHorseReturn(WorldServer world, VirtualTravellerRecord r, BlockPos target, boolean stay) {
        com.jubitus.travellers.zone.PlayerZoneRecord zone =
                com.jubitus.travellers.zone.PlayerZoneManager.get(world)
                        .getByOwnerAndCenter(r.followOwnerUUID, target);
        boolean given = zone != null
                && com.jubitus.travellers.zone.stable.WorkerHorseReturn.returnVirtual(world, zone, r);
        com.jubitus.travellers.zone.stable.WorkerHorseReturn.finishAtStable(world, zone, r.followOwnerUUID,
                r.uuid, given);

        // Built after the return, so the temporary entity no longer carries the horse's bond.
        EntityTravellerWorker worker = new EntityTravellerWorker(world);
        worker.readFromNBT(r.entityNbt);
        worker.setUniqueId(r.uuid);
        worker.setPositionAndRotation(r.posX, r.posY, r.posZ, r.rotYaw, 0);
        finishVirtualEquip(world, r, worker, target, stay);
    }

    /** Shared tail of both Get-Equipment branches: finalize mission state on the temp entity, persist
     * it back into the record, and mirror the finished state onto the record itself. */
    private void finishVirtualEquip(WorldServer world, VirtualTravellerRecord r, EntityTravellerWorker worker,
                                     BlockPos target, boolean stay) {
        worker.trips.finishGetEquipVirtually(stay, target);
        NBTTagCompound updatedNbt = new NBTTagCompound();
        worker.writeToNBT(updatedNbt);
        r.entityNbt = updatedNbt;
        r.invalidateNbtCaches();

        r.workerMode = "IDLE";
        r.goOrderActive = false;
        if (stay) {
            r.followOrderActive = false;
            r.goal = target;
        } else {
            r.followOrderActive = true;
            // Seed the return goal; refreshGoal will keep it current
            if (r.followOwnerUUID != null) {
                net.minecraft.entity.player.EntityPlayer owner = world.getPlayerEntityByUUID(r.followOwnerUUID);
                if (owner != null && !owner.isDead) r.goal = owner.getPosition();
            }
        }

        manager.markDirty();
    }
}
