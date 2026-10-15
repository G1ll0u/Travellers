package com.jubitus.travellers.unit.equipment;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.follow.group.WorkerGroupService;
import com.jubitus.travellers.follow.group.WorkerOrderGuard;
import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.notify.MissionNotifications;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.unit.WorkerLookup;
import com.jubitus.travellers.village.MillSettlements;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import com.jubitus.travellers.zone.PlayerZoneManager;
import com.jubitus.travellers.zone.PlayerZoneRecord;
import com.jubitus.travellers.zone.ZoneType;
import com.jubitus.travellers.zone.stable.WorkerHorsePickup;
import com.jubitus.travellers.zone.stable.WorkerHorseReturn;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.UUID;

/** Sends one of the player's workers, loaded or virtual, to fetch equipment at one of the player's places. */
public final class EquipOrders {

    private EquipOrders() {
    }

    public static void start(EntityPlayerMP player, UUID workerId, BlockPos dest, boolean stay,
                             EquipTargetKind kind, boolean detachFromGroup) {
        MinecraftServer srv = player.getServer();
        if (srv == null || !isValidTarget(player, dest, kind)) return;
        WorkerLookup.Owned owned = WorkerLookup.findOwned(srv, player.getUniqueID(), workerId);
        if (owned == null) return;

        // No trip for nothing: nobody in the group carries a horse.
        if (kind == EquipTargetKind.STABLE_RETURN && !WorkerHorseReturn.groupHasHorse(srv, workerId)) {
            NotificationService.notify(player, NotificationDefs.STABLE_NO_HORSE_TO_RETURN);
            return;
        }

        if (owned.live != null) {
            if (WorkerOrderGuard.isBusy(owned.live)) {
                WorkerOrderGuard.notifyRefused(player, owned.live);
                return;
            }
            if (detachFromGroup) WorkerGroupService.detachForMission(player, workerId);
            owned.live.trips.startGetEquip(dest, stay, kind);
            return;
        }

        VirtualTravellerRecord r = owned.record;
        if (WorkerOrderGuard.isBusy(r)) {
            WorkerOrderGuard.notifyRefused(player, r);
            return;
        }
        WorldServer overworld = srv.getWorld(0);
        EntityTravellerWorker temp = new EntityTravellerWorker(overworld);
        temp.readFromNBT(r.entityNbt);
        temp.setUniqueId(r.uuid);
        temp.setPositionAndRotation(r.posX, r.posY, r.posZ, r.rotYaw, 0);
        if (detachFromGroup) {
            WorkerGroupService.detachForMission(player, r.uuid);
            temp.clearEscortLeader();
        }
        temp.trips.startGetEquip(dest, stay, kind);

        NBTTagCompound updatedNbt = new NBTTagCompound();
        temp.writeToNBT(updatedNbt);
        r.entityNbt = updatedNbt;
        r.workerMode = "GET_EQUIPMENT";
        r.goOrderActive = true;
        r.goal = dest;
        VirtualTravellerManager.get(overworld).markDirty();
    }

    /** A loaded worker reached its Get Equipment target: it and its group followers are equipped there. */
    public static void onArrived(EntityTravellerWorker worker, BlockPos target, EquipTargetKind kind) {
        WorldServer world = (WorldServer) worker.world;
        if (kind == EquipTargetKind.STABLE_RETURN) {
            PlayerZoneRecord zone = PlayerZoneManager.get(world).getByOwnerAndCenter(worker.getFollowOwnerId(), target);
            boolean given = zone != null && WorkerHorseReturn.returnLoaded(world, zone, worker);
            WorkerHorseReturn.finishAtStable(world, zone, worker.getFollowOwnerId(), worker.getUniqueID(), given);
        } else if (kind == EquipTargetKind.STABLE) {
            PlayerZoneRecord zone = PlayerZoneManager.get(world).getByOwnerAndCenter(worker.getFollowOwnerId(), target);
            if (zone == null) return;
            WorkerHorsePickup.equipAtStable(world, zone, worker.getUniqueID(), worker.getFollowOwnerId(),
                    worker.getMountUUID() == null, horseNbt -> TravellerMountSupport.bondSpecificMount(worker, horseNbt));
        } else {
            boolean useWholeVillage = kind == EquipTargetKind.CONTROLLED_VILLAGE;
            int[] equip = WorkerEquipExchanger.exchangeWithFollowers(worker, world, target, useWholeVillage);
            MissionNotifications.equipped(world.getMinecraftServer(), worker.getFollowOwnerId(), equip[0], equip[1]);
        }
    }

    /** Only the player's own controlled villages, Barracks or Stables. */
    private static boolean isValidTarget(EntityPlayerMP player, @Nullable BlockPos pos, EquipTargetKind kind) {
        if (pos == null) return false;
        UUID ownerId = player.getUniqueID();
        switch (kind) {
            case CONTROLLED_VILLAGE:
                if (!TravellersConfig.CONTROLLED_VILLAGE.controlledVillageFeatureEnabled) return false;
                return MillSettlements.controlledBy(player.world, player).contains(pos);
            case STABLE_RETURN:
            case STABLE:
                return hasZoneAt(player, ownerId, ZoneType.STABLE, pos);
            case LONE_BARRACK:
            default:
                return hasZoneAt(player, ownerId, ZoneType.BARRACK, pos);
        }
    }

    private static boolean hasZoneAt(EntityPlayerMP player, UUID ownerId, ZoneType type, BlockPos pos) {
        for (PlayerZoneRecord r : PlayerZoneManager.get(player.world).getByOwnerAndType(ownerId, type)) {
            if (pos.equals(r.center)) return true;
        }
        return false;
    }
}
