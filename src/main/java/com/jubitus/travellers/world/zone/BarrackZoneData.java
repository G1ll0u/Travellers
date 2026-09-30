package com.jubitus.travellers.world.zone;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Barrack-only extension state: which worker (if any) has claimed which bed, for respawn.
 * {@link PlayerZoneRecord#pickFreeBed()} is the one place that combines this with the zone's own
 * bed positions - kept there since it needs both. */
public final class BarrackZoneData implements ZoneTypeData, ZoneBedData {

    public final Map<UUID, BlockPos> bedByWorker = new HashMap<>();

    @Override
    public Map<UUID, BlockPos> bedByWorker() {
        return bedByWorker;
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        ZoneBedData.writeBeds(tag, bedByWorker);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        ZoneBedData.readBeds(tag, bedByWorker);
    }
}
