package com.jubitus.travellers.battle;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * Equipment/inventory of a pawn that died virtually, waiting to be dropped as items
 * when a player comes near. Surface Y is resolved at deposit time (chunk is loaded then).
 */
public class PendingLootDrop {

    public final int dimension;
    public final double x;
    public final double z;
    public final double approxY;
    /** List of ItemStack compounds. */
    public final NBTTagList items;
    public final long createdTick;
    public final String victimName;

    public PendingLootDrop(int dimension, double x, double z, double approxY,
                           NBTTagList items, long createdTick, String victimName) {
        this.dimension = dimension;
        this.x = x;
        this.z = z;
        this.approxY = approxY;
        this.items = items;
        this.createdTick = createdTick;
        this.victimName = victimName != null ? victimName : "";
    }

    public NBTTagCompound toNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("Dim", dimension);
        tag.setDouble("X", x);
        tag.setDouble("Z", z);
        tag.setDouble("Y", approxY);
        tag.setTag("Items", items);
        tag.setLong("Created", createdTick);
        tag.setString("Victim", victimName);
        return tag;
    }

    public static PendingLootDrop fromNbt(NBTTagCompound tag) {
        return new PendingLootDrop(
                tag.getInteger("Dim"),
                tag.getDouble("X"),
                tag.getDouble("Z"),
                tag.getDouble("Y"),
                tag.getTagList("Items", 10),
                tag.getLong("Created"),
                tag.getString("Victim"));
    }
}
