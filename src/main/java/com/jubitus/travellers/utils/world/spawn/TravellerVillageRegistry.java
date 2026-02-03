package com.jubitus.travellers.utils.world.spawn;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import javax.annotation.Nullable;
import java.util.*;

// New file: TravellerVillageRegistry.java
public class TravellerVillageRegistry extends WorldSavedData {

    public static final String DATA_NAME = "TravellersVillageRegistry";
    // 1 worker per village
    private final Map<BlockPos, UUID> workerByVillage = new HashMap<>();
    // merchants per village (can be > 1)
    private final Map<BlockPos, List<UUID>> merchantsByVillage = new HashMap<>();

    public TravellerVillageRegistry() {
        super(DATA_NAME);
    }

    public TravellerVillageRegistry(String name) {
        super(name);
    }

    public static TravellerVillageRegistry get(WorldServer ws) {
        WorldServer overworld = ws.getMinecraftServer().getWorld(0);
        MapStorage storage = overworld.getPerWorldStorage();

        TravellerVillageRegistry data =
                (TravellerVillageRegistry) storage.getOrLoadData(TravellerVillageRegistry.class, DATA_NAME);

        if (data == null) {
            data = new TravellerVillageRegistry();
            storage.setData(DATA_NAME, data);
        }
        return data;
    }

    public int getWorkerVillageCount() {
        return workerByVillage.size();
    }

    public java.util.Set<BlockPos> getWorkerVillages() {
        return new java.util.HashSet<>(workerByVillage.keySet());
    }

    @Nullable
    public UUID getWorkerFor(BlockPos village) {
        return workerByVillage.get(norm(village));
    }
    // ---------- Workers ----------

    private static BlockPos norm(BlockPos village) {
        // Treat villages as 2D (x,z) keys. Y is forced to 0.
        return new BlockPos(village.getX(), 0, village.getZ()).toImmutable();
    }

    public void setWorkerFor(BlockPos village, UUID id) {
        workerByVillage.put(norm(village), id);
        markDirty();
    }

    public void clearWorkerFor(BlockPos village, UUID id) {
        BlockPos key = norm(village);
        UUID current = workerByVillage.get(key);
        if (current != null && current.equals(id)) {
            workerByVillage.remove(key);
            markDirty();
        }
    }


    // ---------- Merchants ----------

    public List<UUID> getMerchantsFor(BlockPos village) {
        List<UUID> list = merchantsByVillage.get(norm(village));
        return list == null ? Collections.emptyList() : new ArrayList<>(list);
    }

    public void addMerchantFor(BlockPos village, UUID id) {
        BlockPos key = norm(village);
        List<UUID> list = merchantsByVillage.get(key);
        if (list == null) {
            list = new ArrayList<>();
            merchantsByVillage.put(key, list);
        }
        if (!list.contains(id)) {
            list.add(id);
            markDirty();
        }
    }

    public void removeMerchantFor(BlockPos village, UUID id) {
        BlockPos key = norm(village);
        List<UUID> list = merchantsByVillage.get(key);
        if (list != null && list.remove(id)) {
            if (list.isEmpty()) {
                merchantsByVillage.remove(key);
            }
            markDirty();
        }
    }

    // ---------- NBT ----------

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        workerByVillage.clear();
        merchantsByVillage.clear();

        // Workers
        NBTTagList wList = nbt.getTagList("Workers", 10);
        for (int i = 0; i < wList.tagCount(); i++) {
            NBTTagCompound c = wList.getCompoundTagAt(i);
            int x = c.getInteger("x");
            int y = c.getInteger("y");
            int z = c.getInteger("z");
            long most = c.getLong("most");
            long least = c.getLong("least");
            BlockPos pos = new BlockPos(x, 0, z); // normalize Y -> 0
            UUID id = new UUID(most, least);
            workerByVillage.put(pos.toImmutable(), id);
        }

        // Merchants
        NBTTagList mList = nbt.getTagList("Merchants", 10);
        for (int i = 0; i < mList.tagCount(); i++) {
            NBTTagCompound entry = mList.getCompoundTagAt(i);
            int x = entry.getInteger("x");
            int y = entry.getInteger("y");
            int z = entry.getInteger("z");
            BlockPos pos = new BlockPos(x, 0, z).toImmutable();

            NBTTagList ids = entry.getTagList("Ids", 10);
            List<UUID> list = new ArrayList<>();
            for (int j = 0; j < ids.tagCount(); j++) {
                NBTTagCompound c = ids.getCompoundTagAt(j);
                long most = c.getLong("most");
                long least = c.getLong("least");
                list.add(new UUID(most, least));
            }
            if (!list.isEmpty()) {
                merchantsByVillage.put(pos, list);
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        // Workers
        NBTTagList wList = new NBTTagList();
        for (Map.Entry<BlockPos, UUID> e : workerByVillage.entrySet()) {
            BlockPos pos = e.getKey();
            UUID id = e.getValue();
            NBTTagCompound c = new NBTTagCompound();
            c.setInteger("x", pos.getX());
            c.setInteger("y", 0); // keep 0, purely cosmetic now
            c.setInteger("z", pos.getZ());
            c.setLong("most", id.getMostSignificantBits());
            c.setLong("least", id.getLeastSignificantBits());
            wList.appendTag(c);
        }
        nbt.setTag("Workers", wList);

        // Merchants
        NBTTagList mList = new NBTTagList();
        for (Map.Entry<BlockPos, List<UUID>> e : merchantsByVillage.entrySet()) {
            BlockPos pos = e.getKey();
            List<UUID> ids = e.getValue();
            if (ids.isEmpty()) continue;

            NBTTagCompound entry = new NBTTagCompound();
            entry.setInteger("x", pos.getX());
            entry.setInteger("y", pos.getY());
            entry.setInteger("z", pos.getZ());

            NBTTagList idList = new NBTTagList();
            for (UUID id : ids) {
                NBTTagCompound c = new NBTTagCompound();
                c.setLong("most", id.getMostSignificantBits());
                c.setLong("least", id.getLeastSignificantBits());
                idList.appendTag(c);
            }
            entry.setTag("Ids", idList);
            mList.appendTag(entry);
        }
        nbt.setTag("Merchants", mList);

        return nbt;
    }
}
