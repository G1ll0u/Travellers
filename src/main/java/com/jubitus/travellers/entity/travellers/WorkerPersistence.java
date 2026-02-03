package com.jubitus.travellers.entity.travellers;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.util.Constants;

import javax.annotation.Nullable;
import java.util.UUID;

public final class WorkerPersistence {

    private final EntityTravellerWorker w;

    public WorkerPersistence(EntityTravellerWorker worker) {
        this.w = worker;
    }

    public void writeToNBT(NBTTagCompound nbt) {
        // IMPORTANT: keep super call in EntityTravellerWorker.writeEntityToNBT()

        if (w.employerId != null) nbt.setUniqueId("EmployerId", w.employerId);
        if (w.employerReturnPos != null) {
            nbt.setInteger("EmpX", w.employerReturnPos.getX());
            nbt.setInteger("EmpY", w.employerReturnPos.getY());
            nbt.setInteger("EmpZ", w.employerReturnPos.getZ());
        }

        if (w.jobVillageCenter != null) {
            nbt.setInteger("JobVX", w.jobVillageCenter.getX());
            nbt.setInteger("JobVY", w.jobVillageCenter.getY());
            nbt.setInteger("JobVZ", w.jobVillageCenter.getZ());
        }

        nbt.setInteger("WorkerMode", w.mode.ordinal());
        nbt.setBoolean("JobDelivered", w.jobDelivered);
        nbt.setBoolean("RouteLocked", w.routeLocked);
        nbt.setBoolean("DonationJob", w.donationJob);

        nbt.setTag("TravelInventory", w.travelInventory.serializeNBT());
        nbt.setTag("PersonalInventory", w.personalInventory.serializeNBT());

        nbt.setBoolean("AwaitingPickup", w.awaitingPickup);
        nbt.setBoolean("HasUnsoldGoods", w.hasUnsoldGoods);
        nbt.setBoolean("PostJobFarewellActive", w.postJobFarewellActive);
        nbt.setInteger("PostJobFarewellTicks", w.postJobFarewellTicks);

        if (w.homeVillageCenter != null) {
            nbt.setInteger("HomeVillageX", w.homeVillageCenter.getX());
            nbt.setInteger("HomeVillageY", w.homeVillageCenter.getY());
            nbt.setInteger("HomeVillageZ", w.homeVillageCenter.getZ());
        }

        if (w.followOwnerId != null) {
            nbt.setUniqueId("FollowOwnerId", w.followOwnerId);
        }

        if (!w.hostilePlayers.isEmpty()) {
            NBTTagList list = new NBTTagList();
            for (UUID id : w.hostilePlayers) {
                NBTTagCompound c = new NBTTagCompound();
                c.setUniqueId("Player", id);
                list.appendTag(c);
            }
            nbt.setTag("HostilePlayers", list);
        }

        nbt.setBoolean("FollowOrderActive", w.followOrderActive);

        if (w.idleAnchorPos != null) {
            nbt.setInteger("IdleAx", w.idleAnchorPos.getX());
            nbt.setInteger("IdleAy", w.idleAnchorPos.getY());
            nbt.setInteger("IdleAz", w.idleAnchorPos.getZ());
        }

        UUID leaderId = w.getEscortLeaderUUID(); // from DataManager
        if (leaderId != null) {
            nbt.setUniqueId("EscortLeaderUUID", leaderId);
        }

        nbt.setBoolean("HiredFollower", w.dataManager.get(EntityTravellerWorker.HIRED_FOLLOWER_DM));

        // Follow owner UUID stored via DataManager too (keep your existing behavior)
        java.util.Optional<UUID> o = w.dataManager.get(EntityTravellerWorker.FOLLOW_OWNER_DM);
        if (o.isPresent()) nbt.setUniqueId("FollowOwnerId", o.get());

        // Recruit mission state
        nbt.setBoolean("RecruitActive", w.recruitActive);
        nbt.setBoolean("RecruitReturning", w.recruitReturningToOwner);
        nbt.setInteger("RecruitIndex", w.recruitIndex);

        NBTTagList vlist = new NBTTagList();
        for (BlockPos p : w.recruitVillages) {
            NBTTagCompound c = new NBTTagCompound();
            c.setInteger("x", p.getX());
            c.setInteger("y", p.getY());
            c.setInteger("z", p.getZ());
            vlist.appendTag(c);
        }
        nbt.setTag("RecruitVillages", vlist);

        NBTTagList spawned = new NBTTagList();
        for (BlockPos p : w.recruitSpawnedAt) {
            NBTTagCompound c = new NBTTagCompound();
            c.setInteger("x", p.getX());
            c.setInteger("y", p.getY());
            c.setInteger("z", p.getZ());
            spawned.appendTag(c);
        }
        nbt.setTag("RecruitSpawnedAt", spawned);

        // Assigned home (server-side “real” copy)
        if (w.assignedHomePos != null) {
            nbt.setInteger("HomeX", w.assignedHomePos.getX());
            nbt.setInteger("HomeY", w.assignedHomePos.getY());
            nbt.setInteger("HomeZ", w.assignedHomePos.getZ());
            nbt.setInteger("HomeDim", w.assignedHomeDim);
            nbt.setString("HomeName", w.assignedHomeName);
        }
        nbt.setBoolean("GoingHome", w.goingHome);
    }

    public void readFromNBT(NBTTagCompound nbt) {
        // IMPORTANT: keep super call in EntityTravellerWorker.readEntityFromNBT()

        w.forceSpawn = true;

        w.employerId = nbt.hasUniqueId("EmployerId") ? nbt.getUniqueId("EmployerId") : null;

        if (nbt.hasKey("EmpX")) {
            w.employerReturnPos = new BlockPos(
                    nbt.getInteger("EmpX"),
                    nbt.getInteger("EmpY"),
                    nbt.getInteger("EmpZ"));
        } else {
            w.employerReturnPos = null;
        }

        if (nbt.hasKey("JobVX")) {
            w.jobVillageCenter = new BlockPos(
                    nbt.getInteger("JobVX"),
                    nbt.getInteger("JobVY"),
                    nbt.getInteger("JobVZ"));
        } else {
            w.jobVillageCenter = null;
        }

        int m = nbt.getInteger("WorkerMode");
        w.mode = (m >= 0 && m < EntityTravellerWorker.WorkerMode.values().length)
                ? EntityTravellerWorker.WorkerMode.values()[m]
                : EntityTravellerWorker.WorkerMode.IDLE;

        w.jobDelivered = nbt.getBoolean("JobDelivered");
        w.routeLocked = nbt.getBoolean("RouteLocked");
        w.donationJob = nbt.getBoolean("DonationJob");

        if (nbt.hasKey("TravelInventory")) {
            w.travelInventory.deserializeNBT(nbt.getCompoundTag("TravelInventory"));
        }
        if (nbt.hasKey("PersonalInventory")) {
            w.personalInventory.deserializeNBT(nbt.getCompoundTag("PersonalInventory"));
        }

        w.awaitingPickup = nbt.getBoolean("AwaitingPickup");
        w.hasUnsoldGoods = nbt.getBoolean("HasUnsoldGoods");
        w.postJobFarewellActive = nbt.getBoolean("PostJobFarewellActive");
        w.postJobFarewellTicks = nbt.getInteger("PostJobFarewellTicks");

        // Home village
        if (nbt.hasKey("HomeVillageX")) {
            w.homeVillageCenter = new BlockPos(
                    nbt.getInteger("HomeVillageX"),
                    nbt.getInteger("HomeVillageY"),
                    nbt.getInteger("HomeVillageZ"));
        } else {
            w.homeVillageCenter = null;
        }

        // Local cached followOwnerId (you also store it in DataManager)
        w.followOwnerId = nbt.hasUniqueId("FollowOwnerId") ? nbt.getUniqueId("FollowOwnerId") : null;
        w.hiredFollower = nbt.getBoolean("HiredFollower");

        // Hostile list
        w.hostilePlayers.clear();
        if (nbt.hasKey("HostilePlayers", Constants.NBT.TAG_LIST)) {
            NBTTagList list = nbt.getTagList("HostilePlayers", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < list.tagCount(); i++) {
                NBTTagCompound c = list.getCompoundTagAt(i);
                if (c.hasUniqueId("Player")) {
                    w.hostilePlayers.add(c.getUniqueId("Player"));
                }
            }
        }

        w.followOrderActive = nbt.getBoolean("FollowOrderActive");

        if (nbt.hasKey("IdleAx")) {
            w.idleAnchorPos = new BlockPos(
                    nbt.getInteger("IdleAx"),
                    nbt.getInteger("IdleAy"),
                    nbt.getInteger("IdleAz")
            );
        } else {
            w.idleAnchorPos = null;
        }

        // Escort leader UUID
        UUID leaderId = nbt.hasUniqueId("EscortLeaderUUID") ? nbt.getUniqueId("EscortLeaderUUID") : null;
        w.escortLeaderUUID = leaderId;

        // IMPORTANT: Only set DataManager on server
        if (!w.world.isRemote) {
            w.setEscortLeaderUUID(leaderId);
        }

        // Recruit mission
        w.recruitActive = nbt.getBoolean("RecruitActive");
        w.recruitReturningToOwner = nbt.getBoolean("RecruitReturning");
        w.recruitIndex = nbt.getInteger("RecruitIndex");

        w.recruitVillages.clear();
        if (nbt.hasKey("RecruitVillages", Constants.NBT.TAG_LIST)) {
            NBTTagList vlist = nbt.getTagList("RecruitVillages", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < vlist.tagCount(); i++) {
                NBTTagCompound c = vlist.getCompoundTagAt(i);
                w.recruitVillages.add(new BlockPos(c.getInteger("x"), c.getInteger("y"), c.getInteger("z")));
            }
        }

        w.recruitSpawnedAt.clear();
        if (nbt.hasKey("RecruitSpawnedAt", Constants.NBT.TAG_LIST)) {
            NBTTagList slist = nbt.getTagList("RecruitSpawnedAt", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < slist.tagCount(); i++) {
                NBTTagCompound c = slist.getCompoundTagAt(i);
                w.recruitSpawnedAt.add(new BlockPos(c.getInteger("x"), c.getInteger("y"), c.getInteger("z")));
            }
        }

        // Assigned home
        if (nbt.hasKey("HomeX")) {
            w.assignedHomePos = new BlockPos(
                    nbt.getInteger("HomeX"),
                    nbt.getInteger("HomeY"),
                    nbt.getInteger("HomeZ"));
            w.assignedHomeDim = nbt.getInteger("HomeDim");
            w.assignedHomeName = nbt.getString("HomeName");
        } else {
            w.assignedHomePos = null;
            w.assignedHomeDim = 0;
            w.assignedHomeName = "";
        }

        // Sync home DataManager on server
        if (!w.world.isRemote) {
            w.dataManager.set(EntityTravellerWorker.HOME_POS_DM,
                    w.assignedHomePos == null
                            ? com.google.common.base.Optional.absent()
                            : com.google.common.base.Optional.of(w.assignedHomePos));
            w.dataManager.set(EntityTravellerWorker.HOME_DIM_DM, w.assignedHomeDim);
            w.dataManager.set(EntityTravellerWorker.HOME_NAME_DM, w.assignedHomeName == null ? "" : w.assignedHomeName);
        }

        w.goingHome = nbt.getBoolean("GoingHome");

        // If farewell isn't active, normalize the counter
        if (!w.postJobFarewellActive) {
            w.postJobFarewellTicks = 0;
        }

        // Restore hired+owner DataManager state from NBT (your existing behavior)
        boolean hired = nbt.getBoolean("HiredFollower");
        java.util.Optional<UUID> owner = nbt.hasUniqueId("FollowOwnerId")
                ? java.util.Optional.of(nbt.getUniqueId("FollowOwnerId"))
                : java.util.Optional.empty();

        w.hiredFollower = hired;
        w.followOwnerId = owner.orElse(null);

        w.dataManager.set(EntityTravellerWorker.HIRED_FOLLOWER_DM, hired);
        w.dataManager.set(EntityTravellerWorker.FOLLOW_OWNER_DM, owner.isPresent()
                ? java.util.Optional.of(owner.get())
                : java.util.Optional.empty());

        if (w.recruitReturningToOwner && w.isHiredFollower()) {
            w.setFollowOrderActive(true);
        }
    }
}
