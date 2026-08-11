package com.jubitus.travellers.server.net.message.formations;

import net.minecraft.nbt.NBTTagCompound;
import javax.annotation.Nullable;
import java.util.UUID;

public final class FormationLeaderInfo {
    @Nullable public UUID uuid;
    public String name = "";
    public int followerCount;
    public double posX, posZ;
    public boolean isPlayer;
    public boolean isVirtual;
    // True when this leader currently moves with the player/its target (follow ON or escorting
    // another leader); false when it is anchored in place. Drives the "Follow: ON/OFF" toggle label.
    public boolean followActive;
    // True when this leader is busy on a mission (transport/sale/recruit/get-equipment) and so can't
    // receive orders — drives client-side greying of the leader-order buttons.
    public boolean busy;

    // Display data for the leader list
    public String cultureId = "";
    public int textureIndex = 0;
    public int ownerColor = 0xFFFFFF;
    // -1 if virtual (no loaded entity)
    public int entityId = -1;

    public FormationLeaderInfo() {}

    public FormationLeaderInfo(@Nullable UUID uuid, String name, int followerCount,
                                double posX, double posZ, boolean isPlayer, boolean isVirtual) {
        this.uuid = uuid;
        this.name = name == null ? "" : name;
        this.followerCount = followerCount;
        this.posX = posX;
        this.posZ = posZ;
        this.isPlayer = isPlayer;
        this.isVirtual = isVirtual;
    }

    public NBTTagCompound toNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        if (uuid != null) tag.setString("UUID", uuid.toString());
        tag.setString("Name", name);
        tag.setInteger("FC", followerCount);
        tag.setDouble("PX", posX);
        tag.setDouble("PZ", posZ);
        tag.setBoolean("IsPlayer", isPlayer);
        tag.setBoolean("IsVirtual", isVirtual);
        tag.setBoolean("FollowActive", followActive);
        tag.setBoolean("Busy", busy);
        tag.setString("CultureId", cultureId);
        tag.setInteger("TextureIndex", textureIndex);
        tag.setInteger("OwnerColor", ownerColor);
        tag.setInteger("EntityId", entityId);
        return tag;
    }

    public static FormationLeaderInfo fromNbt(NBTTagCompound tag) {
        FormationLeaderInfo info = new FormationLeaderInfo();
        if (tag.hasKey("UUID")) info.uuid = UUID.fromString(tag.getString("UUID"));
        info.name = tag.getString("Name");
        info.followerCount = tag.getInteger("FC");
        info.posX = tag.getDouble("PX");
        info.posZ = tag.getDouble("PZ");
        info.isPlayer = tag.getBoolean("IsPlayer");
        info.isVirtual = tag.getBoolean("IsVirtual");
        info.followActive = tag.getBoolean("FollowActive");
        info.busy = tag.getBoolean("Busy");
        info.cultureId = tag.hasKey("CultureId") ? tag.getString("CultureId") : "";
        info.textureIndex = tag.hasKey("TextureIndex") ? tag.getInteger("TextureIndex") : 0;
        info.ownerColor = tag.hasKey("OwnerColor") ? tag.getInteger("OwnerColor") : 0xFFFFFF;
        info.entityId = tag.hasKey("EntityId") ? tag.getInteger("EntityId") : -1;
        return info;
    }
}
