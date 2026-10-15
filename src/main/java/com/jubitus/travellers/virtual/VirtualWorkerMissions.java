package com.jubitus.travellers.virtual;

import com.jubitus.travellers.config.TravellersConfig;
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

            // Hired workers on RECRUITING missions: collect for post-iteration processing (records.put inside)
            if (r.isHiredFollower && r.jobGoal != null && "RECRUITING".equals(r.workerMode)) {
                double dx = r.jobGoal.getX() + 0.5 - r.posX;
                double dz = r.jobGoal.getZ() + 0.5 - r.posZ;
                if (dx * dx + dz * dz <= JOB_ARRIVAL_FORCE_DIST * JOB_ARRIVAL_FORCE_DIST) {
                    if (recruitArrivals == null) recruitArrivals = new ArrayList<>();
                    recruitArrivals.add(r.uuid);
                }
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

        // Hired workers on RECRUITING missions: processed after iteration to avoid ConcurrentModificationException
        if (recruitArrivals != null) {
            for (UUID id : recruitArrivals) {
                VirtualTravellerRecord r = records.get(id);
                if (r != null) tryVirtualRecruitment(world, r);
            }
        }

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
                world.getMinecraftServer(), r.followOwnerUUID, r.uuid);
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

    private void tryVirtualRecruitment(WorldServer world, VirtualTravellerRecord r) {
        BlockPos stop = r.jobGoal;
        if (stop == null) return;

        // Load recruiter temporarily to advance recruit state
        EntityTravellerWorker recruiter = new EntityTravellerWorker(world);
        recruiter.readFromNBT(r.entityNbt);
        recruiter.setUniqueId(r.uuid);
        recruiter.setPositionAndRotation(r.posX, r.posY, r.posZ, r.rotYaw, 0);

        int recruitCount = recruiter.recruit.advanceStopVirtually(stop);

        // Create virtual recruits with proper culture/name via onInitialSpawn
        EntityPlayer owner = r.followOwnerUUID != null
                ? world.getPlayerEntityByUUID(r.followOwnerUUID) : null;
        if (owner != null && recruitCount > 0) {
            DifficultyInstance diff = new DifficultyInstance(
                    world.getDifficulty(), world.getWorldTime(), 0, 0.5f);

            for (int i = 0; i < recruitCount; i++) {
                EntityTravellerWorker recruit = new EntityTravellerWorker(world);
                recruit.setPosition(stop.getX() + 0.5, stop.getY(), stop.getZ() + 0.5);
                recruit.onInitialSpawn(diff, null); // sets culture, name, equipment
                recruit.setHomeVillage(null);
                recruit.hireAsFollower(owner);
                recruit.setEscortLeaderUUID(r.uuid); // must be before writeToNBT so it persists

                NBTTagCompound recruitNbt = new NBTTagCompound();
                recruit.writeToNBT(recruitNbt);

                UUID recruitId = recruit.getUniqueID();
                VirtualTravellerRecord rec = new VirtualTravellerRecord(
                        recruitId, stop.getX(), stop.getY(), stop.getZ(),
                        recruit.rotationYaw, r.dimension, recruitNbt);
                rec.isHiredFollower = true;
                rec.name = recruit.getName();
                rec.ownerColor = recruit.getOwnerMarkColor() & 0xFFFFFF;
                rec.followOwnerUUID = r.followOwnerUUID;
                rec.escortLeaderUUID = r.uuid;
                rec.workerMode = "IDLE";
                rec.goal = new BlockPos(r.posX, r.posY, r.posZ);
                records.put(recruitId, rec);
            }

            org.millenaire.common.village.Building recruitTh = MillSettlements.townHall(world, stop);
            String villageName = recruitTh != null ? recruitTh.getVillageQualifiedName() : "a village";
            com.jubitus.travellers.notify.NotificationService.notify(owner,
                    com.jubitus.travellers.notify.NotificationDefs.RECRUIT_WORKERS_RECRUITED,
                    String.valueOf(recruitCount), villageName);
        }

        // Save recruiter updated state
        NBTTagCompound updatedNbt = new NBTTagCompound();
        recruiter.writeToNBT(updatedNbt);
        r.entityNbt = updatedNbt;
        r.invalidateNbtCaches();
        r.workerMode = recruiter.getMode().name();
        r.jobGoal = recruiter.getJobGoal(); // next stop, or null if returning
        BlockPos nextGoal = recruiter.getVirtualGoal();
        if (nextGoal != null) r.goal = nextGoal;

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

        if (kind == com.jubitus.travellers.unit.equipment.EquipTargetKind.STABLE) {
            tryVirtualHorsePickup(world, r, target, stay);
            return;
        }

        boolean useWholeVillage = kind == com.jubitus.travellers.unit.equipment.EquipTargetKind.CONTROLLED_VILLAGE;
        if (!com.jubitus.travellers.ground.chunk.LoadedChunks.tryAcquireJob(world)) return;

        // Load only the chest chunks, just for the swap, and release them right after.
        java.util.List<net.minecraft.world.chunk.Chunk> loadedChunks =
                com.jubitus.travellers.unit.equipment.WorkerEquipExchanger.loadChestChunks(world, target, useWholeVillage);

        // Temporary entity (not spawned) carries equipment state for the swap
        EntityTravellerWorker worker = new EntityTravellerWorker(world);
        worker.readFromNBT(r.entityNbt);
        worker.setUniqueId(r.uuid);
        worker.setPositionAndRotation(r.posX, r.posY, r.posZ, r.rotYaw, 0);

        int[] equip;
        try {
            equip = com.jubitus.travellers.unit.equipment.WorkerEquipExchanger
                    .exchangeWithFollowers(worker, world, target, useWholeVillage);
        } finally {
            com.jubitus.travellers.ground.chunk.LoadedChunks.release(world, loadedChunks);
        }
        com.jubitus.travellers.notify.MissionNotifications.equipped(
                world.getMinecraftServer(), r.followOwnerUUID, equip[0], equip[1]);

        finishVirtualEquip(world, r, worker, target, stay);
    }

    /**
     * STABLE branch of {@link #tryVirtualEquipExchange}: zero chunk loading, zero entity spawning.
     * The claimed horse's NBT goes straight into {@code r.mountNbt}/{@code r.mounted} - the same shape
     * {@code captureMountIfBonded} uses - so {@code restoreOrBondMount} spawns and bonds it on materialize.
     */
    private void tryVirtualHorsePickup(WorldServer world, VirtualTravellerRecord r, BlockPos target, boolean stay) {
        EntityTravellerWorker worker = new EntityTravellerWorker(world);
        if (kind == com.jubitus.travellers.unit.equipment.EquipTargetKind.STABLE_RETURN) {
            tryVirtualHorseReturn(world, r, target, stay);
            return;
        }

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

