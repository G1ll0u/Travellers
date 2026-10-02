package com.jubitus.travellers.zone;

import net.minecraft.nbt.NBTTagCompound;

/** Extra, per-zone-type state attached to a {@link PlayerZoneRecord} - one implementation per
 * {@link ZoneType} (e.g. a Barrack's bed assignments, a Stable's horse pool). */
public interface ZoneTypeData {
    void writeToNBT(NBTTagCompound tag);

    void readFromNBT(NBTTagCompound tag);
}
