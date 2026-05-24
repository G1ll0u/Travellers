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
        return info;
    }
}
