package com.jubitus.travellers.utils.world.pathing.offscreen;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.UUID;

public class VirtualWorkerRecord {

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

    // Cached display data for the worldmap (set at virtualization)
    public String name = "";
    public int ownerColor = 0xFFFFFF;

    public VirtualWorkerRecord(UUID uuid, double posX, double posY, double posZ, float rotYaw,
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
        return tag;
    }

    public static VirtualWorkerRecord fromNbt(NBTTagCompound tag) {
        UUID uuid = UUID.fromString(tag.getString("UUID"));
        double px = tag.getDouble("PX");
        double py = tag.getDouble("PY");
        double pz = tag.getDouble("PZ");
        float yaw = tag.getFloat("Yaw");
        int dim = tag.getInteger("Dim");
        NBTTagCompound entityData = tag.getCompoundTag("EntityData");

        VirtualWorkerRecord r = new VirtualWorkerRecord(uuid, px, py, pz, yaw, dim, entityData);
        r.isHiredFollower = tag.getBoolean("Hired");
        if (tag.hasKey("OwnerUUID")) r.followOwnerUUID = UUID.fromString(tag.getString("OwnerUUID"));
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
        return r;
    }
}
