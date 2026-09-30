package com.jubitus.travellers.world.zone;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.UUID;

/**
 * Implemented by every {@link ZoneTypeData} whose zone type assigns one bed per worker (a Barrack's
 * residents, a Stable's palfreniers). Lets {@link PlayerZoneRecord#pickFreeBed()} and
 * {@link PlayerZoneManager}'s bed-break handling work off one shape instead of one branch per type.
 */
public interface ZoneBedData {

    /** NBT tag holding the worker-UUID -> bed-head map, shared by every {@link ZoneBedData}. */
    String BED_ASSIGNMENTS_TAG = "BedAssignments";

    /** Live map of worker UUID -> the HEAD block of the bed it has been assigned. Mutable; callers
     * add/remove directly. */
    Map<UUID, BlockPos> bedByWorker();

    /** Writes {@code map} under {@link #BED_ASSIGNMENTS_TAG} - one shared format for every bed zone. */
    static void writeBeds(NBTTagCompound tag, Map<UUID, BlockPos> map) {
        NBTTagList list = new NBTTagList();
        for (Map.Entry<UUID, BlockPos> e : map.entrySet()) {
            NBTTagCompound a = new NBTTagCompound();
            a.setUniqueId("WID", e.getKey());
            a.setInteger("X", e.getValue().getX());
            a.setInteger("Y", e.getValue().getY());
            a.setInteger("Z", e.getValue().getZ());
            list.appendTag(a);
        }
        tag.setTag(BED_ASSIGNMENTS_TAG, list);
    }

    /** Clears {@code map} and repopulates it from {@link #BED_ASSIGNMENTS_TAG}. */
    static void readBeds(NBTTagCompound tag, Map<UUID, BlockPos> map) {
        map.clear();
        NBTTagList list = tag.getTagList(BED_ASSIGNMENTS_TAG, 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound a = list.getCompoundTagAt(i);
            map.put(a.getUniqueId("WID"),
                    new BlockPos(a.getInteger("X"), a.getInteger("Y"), a.getInteger("Z")));
        }
    }
}
