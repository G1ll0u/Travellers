package com.jubitus.travellers.utils.world.pathing.offscreen;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class VirtualTravellerRecord {

    public final UUID uuid;
    public double posX;
    public double posY;
    public double posZ;
    public float rotYaw;
    public final int dimension;

    @Nullable public BlockPos goal;

    // Quick-access for goal refresh
    public boolean isHiredFollower;
    @Nullable public UUID followOwnerUUID;
    public boolean isCatchupMode;
    @Nullable public UUID catchupTargetUUID;

    // Worker mode name (IDLE / TO_VILLAGE / RETURNING / RECRUITING)
    public String workerMode = "IDLE";

    // True when a Go order is active (IDLE mode but heading to a specific location)
    public boolean goOrderActive;

    // Mirrors EntityTravellerWorker.followOrderActive: false = Follow OFF, worker stays put
    public boolean followOrderActive = true;

    // Non-null when this worker is following another worker as escort (not just the player)
    @Nullable public UUID escortLeaderUUID;

    // Destination used for forced materialization when the worker arrives
    @Nullable public BlockPos jobGoal;

    // Full entity state for restoration (non-final so it can be updated after virtual delivery)
    public NBTTagCompound entityNbt;

    // Home village, used to count virtual workers per village in the spawner
    @Nullable public BlockPos homeVillage;

    public long virtualizedTick;
    public long lastGoalRefreshTick;

    // Position at the moment of virtualization — never updated during virtual movement.
    public double virtualizedPosX;
    public double virtualizedPosY;
    public double virtualizedPosZ;

    // Set to true only when the worker has traveled more than 16 blocks from its virtualization point.
    // Formation-adjustment moves (a few blocks toward the leader) do not count.
    // Used at materialization to decide whether Y needs surface-clamping.
    public boolean hasTraveledFar;

    // Formation slot offset from the escort leader, captured at virtualization.
    // Used by MaterializePositionResolver to restore the worker to its exact slot
    // instead of placing it in a generic ring around the leader.
    public boolean hasEscortSlotOffset = false;
    public double escortSlotDx;
    public double escortSlotDz;

    // Entity class discriminator — determines which entity class to reconstruct at materialization.
    // "WORKER" (default), "TRAVELLER", or "RAIDER"
    public String entityClass = "WORKER";

    // Travel route for non-worker entities (captured from TravelCommon.route at virtualization).
    // Includes MPB waypoints. Used during virtual simulation to advance the goal.
    public List<BlockPos> travelRoute = new ArrayList<>();
    // Current position in travelRoute; -1 = no active route
    public int routeIndex = -1;

    // Non-null for traveller/raider group followers: UUID of the group leader they must track.
    @Nullable public UUID groupLeaderUUID;

    // Cached display data for the worldmap (set at virtualization)
    public String name = "";
    public int ownerColor = 0xFFFFFF;

    // Transient: world-tick when this worker last performed a virtual donation. Not persisted.
    public long lastDonationTick = 0L;

    public VirtualTravellerRecord(UUID uuid, double posX, double posY, double posZ, float rotYaw,
                               int dimension, NBTTagCompound entityNbt) {
        this.uuid = uuid;
        this.posX = posX;
        this.posY = posY;
        this.posZ = posZ;
        this.rotYaw = rotYaw;
        this.dimension = dimension;
        this.entityNbt = entityNbt;
        this.virtualizedPosX = posX;
        this.virtualizedPosY = posY;
        this.virtualizedPosZ = posZ;
    }

    public NBTTagCompound toNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("UUID", uuid.toString());
        tag.setDouble("PX", posX);
        tag.setDouble("PY", posY);
        tag.setDouble("PZ", posZ);
        tag.setFloat("Yaw", rotYaw);
        tag.setInteger("Dim", dimension);
        tag.setTag("EntityData", entityNbt);
        tag.setBoolean("Hired", isHiredFollower);
        if (followOwnerUUID != null) tag.setString("OwnerUUID", followOwnerUUID.toString());
        tag.setBoolean("Catchup", isCatchupMode);
        if (catchupTargetUUID != null) tag.setString("CatchupUUID", catchupTargetUUID.toString());
        tag.setString("Mode", workerMode);
        tag.setBoolean("GoOrder", goOrderActive);
        tag.setBoolean("FollowOrder", followOrderActive);
        if (escortLeaderUUID != null) tag.setString("EscortLeader", escortLeaderUUID.toString());
        if (goal != null) {
            tag.setInteger("GX", goal.getX());
            tag.setInteger("GY", goal.getY());
            tag.setInteger("GZ", goal.getZ());
        }
        if (jobGoal != null) {
            tag.setInteger("JGX", jobGoal.getX());
            tag.setInteger("JGY", jobGoal.getY());
            tag.setInteger("JGZ", jobGoal.getZ());
        }
        if (homeVillage != null) {
            tag.setInteger("HVX", homeVillage.getX());
            tag.setInteger("HVZ", homeVillage.getZ());
        }
        tag.setLong("VT", virtualizedTick);
        tag.setDouble("VPX", virtualizedPosX);
        tag.setDouble("VPY", virtualizedPosY);
        tag.setDouble("VPZ", virtualizedPosZ);
        tag.setBoolean("TraveledFar", hasTraveledFar);
        tag.setString("Name", name != null ? name : "");
        tag.setInteger("OwnerColor", ownerColor);
        if (hasEscortSlotOffset) {
            tag.setBoolean("HasSlotOff", true);
            tag.setDouble("SlotDX", escortSlotDx);
            tag.setDouble("SlotDZ", escortSlotDz);
        }
        tag.setString("EC", entityClass);
        tag.setInteger("RI", routeIndex);
        if (groupLeaderUUID != null) tag.setString("GroupLeader", groupLeaderUUID.toString());
        if (!travelRoute.isEmpty()) {
            NBTTagList routeList = new NBTTagList();
            for (BlockPos p : travelRoute) {
                routeList.appendTag(new NBTTagIntArray(new int[]{p.getX(), p.getY(), p.getZ()}));
            }
            tag.setTag("TR", routeList);
        }
        return tag;
    }

    public static VirtualTravellerRecord fromNbt(NBTTagCompound tag) {
        UUID uuid = UUID.fromString(tag.getString("UUID"));
        double px = tag.getDouble("PX");
        double py = tag.getDouble("PY");
        double pz = tag.getDouble("PZ");
        float yaw = tag.getFloat("Yaw");
        int dim = tag.getInteger("Dim");
        NBTTagCompound entityData = tag.getCompoundTag("EntityData");

        VirtualTravellerRecord r = new VirtualTravellerRecord(uuid, px, py, pz, yaw, dim, entityData);
        r.isHiredFollower = tag.getBoolean("Hired");
        if (tag.hasKey("OwnerUUID")) r.followOwnerUUID = UUID.fromString(tag.getString("OwnerUUID"));
        // Backwards-compat: old records didn't save Hired/OwnerUUID at the record level.
        // Fall back to the entity NBT which has always stored these fields.
        if (!r.isHiredFollower && entityData != null) {
            r.isHiredFollower = entityData.getBoolean("HiredFollower");
        }
        if (r.followOwnerUUID == null && entityData != null && entityData.hasKey("FollowOwnerIdLeast")) {
            r.followOwnerUUID = entityData.getUniqueId("FollowOwnerId");
        }
        r.isCatchupMode = tag.getBoolean("Catchup");
        if (tag.hasKey("CatchupUUID")) r.catchupTargetUUID = UUID.fromString(tag.getString("CatchupUUID"));
        r.workerMode = tag.hasKey("Mode") ? tag.getString("Mode") : "IDLE";
        r.goOrderActive = tag.getBoolean("GoOrder");
        r.followOrderActive = !tag.hasKey("FollowOrder") || tag.getBoolean("FollowOrder");
        if (tag.hasKey("EscortLeader")) r.escortLeaderUUID = UUID.fromString(tag.getString("EscortLeader"));
        if (tag.hasKey("GX")) r.goal = new BlockPos(tag.getInteger("GX"), tag.getInteger("GY"), tag.getInteger("GZ"));
        if (tag.hasKey("JGX")) r.jobGoal = new BlockPos(tag.getInteger("JGX"), tag.getInteger("JGY"), tag.getInteger("JGZ"));
        if (tag.hasKey("HVX")) r.homeVillage = new BlockPos(tag.getInteger("HVX"), 0, tag.getInteger("HVZ"));
        r.virtualizedTick = tag.getLong("VT");
        // Backward-compat: if not saved yet, treat as not moved (use current pos as origin)
        r.virtualizedPosX = tag.hasKey("VPX") ? tag.getDouble("VPX") : px;
        r.virtualizedPosY = tag.hasKey("VPY") ? tag.getDouble("VPY") : py;
        r.virtualizedPosZ = tag.hasKey("VPZ") ? tag.getDouble("VPZ") : pz;
        r.hasTraveledFar = tag.getBoolean("TraveledFar");
        r.name = tag.hasKey("Name") ? tag.getString("Name") : "";
        r.ownerColor = tag.hasKey("OwnerColor") ? tag.getInteger("OwnerColor") : 0xFFFFFF;
        r.hasEscortSlotOffset = tag.getBoolean("HasSlotOff");
        if (r.hasEscortSlotOffset) {
            r.escortSlotDx = tag.getDouble("SlotDX");
            r.escortSlotDz = tag.getDouble("SlotDZ");
        }
        r.entityClass = tag.hasKey("EC") ? tag.getString("EC") : "WORKER";
        r.routeIndex = tag.hasKey("RI") ? tag.getInteger("RI") : -1;
        if (tag.hasKey("GroupLeader")) r.groupLeaderUUID = UUID.fromString(tag.getString("GroupLeader"));
        if (tag.hasKey("TR", 9)) {
            NBTTagList routeList = tag.getTagList("TR", 11);
            for (int i = 0; i < routeList.tagCount(); i++) {
                int[] arr = routeList.getIntArrayAt(i);
                if (arr.length == 3) r.travelRoute.add(new BlockPos(arr[0], arr[1], arr[2]));
            }
        }
        return r;
    }
}
