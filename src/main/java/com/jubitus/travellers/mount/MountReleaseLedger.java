package com.jubitus.travellers.mount;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bonded horses whose bond ended while the horse itself sat in an unloaded chunk (its rider died, or a
 * debug wipe): nobody could touch the horse then, so the verdict waits here until the horse loads again
 * and {@link MountBondLoadHandler} applies it - release it as an ordinary loose horse, or remove it.
 * Without this, an unknown owner can't be told apart from a rider that is simply not loaded yet.
 */
public final class MountReleaseLedger extends WorldSavedData {

    private static final String NAME = "TravellersMountReleaseLedger";
    private static final int MAX_ENTRIES = 4096;

    /** Horse UUID -> true to remove the horse, false to release it as a loose horse. */
    private final Map<UUID, Boolean> pending = new LinkedHashMap<>();

    public MountReleaseLedger() {
        super(NAME);
    }

    public MountReleaseLedger(String name) {
        super(name);
    }

    public static MountReleaseLedger get(World world) {
        MapStorage storage = world.getMapStorage();
        MountReleaseLedger data = (MountReleaseLedger) storage.getOrLoadData(MountReleaseLedger.class, NAME);
        if (data == null) {
            data = new MountReleaseLedger();
            storage.setData(NAME, data);
        }
        return data;
    }

    public void add(UUID horse, boolean remove) {
        pending.put(horse, remove);
        while (pending.size() > MAX_ENTRIES) pending.remove(pending.keySet().iterator().next());
        markDirty();
    }

    /** The verdict for this horse, removed from the ledger; null when there is none. */
    @Nullable
    public Boolean consume(UUID horse) {
        Boolean verdict = pending.remove(horse);
        if (verdict != null) markDirty();
        return verdict;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        pending.clear();
        NBTTagList list = nbt.getTagList("Horses", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound e = list.getCompoundTagAt(i);
            if (e.hasUniqueId("Id")) pending.put(e.getUniqueId("Id"), e.getBoolean("Remove"));
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();
        for (Map.Entry<UUID, Boolean> e : pending.entrySet()) {
            NBTTagCompound t = new NBTTagCompound();
            t.setUniqueId("Id", e.getKey());
            t.setBoolean("Remove", e.getValue());
            list.appendTag(t);
        }
        nbt.setTag("Horses", list);
        return nbt;
    }
}
