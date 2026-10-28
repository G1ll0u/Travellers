package com.jubitus.travellers.follow.net;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A Barrack or Stable of the player as the Formation Hub shows it: a list row (Barrack) and a map tooltip. */
public final class FormationZoneInfo {
    public UUID id = new UUID(0L, 0L);
    public boolean stable;
    public String name = "";
    public BlockPos center = BlockPos.ORIGIN;
    /** Beds (Barrack) or the pool's size limit (Stable). */
    public int capacity;
    /** Workers bound to this zone, anywhere in the world. */
    public int bound;
    /** Of those, the ones currently inside the zone. */
    public int here;
    /** Barrack only: bound workers with no group and no order, the ones a click on the row selects. */
    public final List<UUID> members = new ArrayList<>();
    /** Health of the bound workers. */
    public GroupGearSummary gear = new GroupGearSummary();
    /** Stable only: pool horses, tamed, saddled. */
    public int horses, tamed, saddled;

    public NBTTagCompound toNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setUniqueId("Id", id);
        tag.setBoolean("Stable", stable);
        tag.setString("Name", name);
        tag.setLong("Center", center.toLong());
        tag.setInteger("Cap", capacity);
        tag.setInteger("Bound", bound);
        tag.setInteger("Here", here);
        NBTTagList list = new NBTTagList();
        for (UUID u : members) {
            NBTTagCompound e = new NBTTagCompound();
            e.setUniqueId("U", u);
            list.appendTag(e);
        }
        tag.setTag("Members", list);
        tag.setTag("Gear", gear.toNbt());
        tag.setInteger("Horses", horses);
        tag.setInteger("Tamed", tamed);
        tag.setInteger("Saddled", saddled);
        return tag;
    }

    public static FormationZoneInfo fromNbt(NBTTagCompound tag) {
        FormationZoneInfo info = new FormationZoneInfo();
        info.id = tag.getUniqueId("Id");
        info.stable = tag.getBoolean("Stable");
        info.name = tag.getString("Name");
        info.center = BlockPos.fromLong(tag.getLong("Center"));
        info.capacity = tag.getInteger("Cap");
        info.bound = tag.getInteger("Bound");
        info.here = tag.getInteger("Here");
        NBTTagList list = tag.getTagList("Members", 10);
        for (int i = 0; i < list.tagCount(); i++) info.members.add(list.getCompoundTagAt(i).getUniqueId("U"));
        info.gear = GroupGearSummary.fromNbt(tag.getCompoundTag("Gear"));
        info.horses = tag.getInteger("Horses");
        info.tamed = tag.getInteger("Tamed");
        info.saddled = tag.getInteger("Saddled");
        return info;
    }
}
