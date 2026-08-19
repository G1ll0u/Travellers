package com.jubitus.travellers.utils.world.mill;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * Remembers the last known Millénaire population for each village, so the global population
 * controller can still size travellers/raiders/workers for a village that hasn't been loaded
 * this session (a village's population is frozen while its chunks are unloaded, so the last
 * reading remains accurate until the village is visited again).
 */
public class VillagePopulationCache extends WorldSavedData {

    public static final String DATA_NAME = "TravellersVillagePopulationCache";

    private final Map<BlockPos, Integer> populationByVillage = new HashMap<>();

    public VillagePopulationCache() {
        super(DATA_NAME);
    }

    public VillagePopulationCache(String name) {
        super(name);
    }

    public static VillagePopulationCache get(WorldServer ws) {
        WorldServer overworld = ws.getMinecraftServer().getWorld(0);
        MapStorage storage = overworld.getPerWorldStorage();

        VillagePopulationCache data =
                (VillagePopulationCache) storage.getOrLoadData(VillagePopulationCache.class, DATA_NAME);

        if (data == null) {
            data = new VillagePopulationCache();
            storage.setData(DATA_NAME, data);
        }
        return data;
    }

    private static BlockPos norm(BlockPos village) {
        return new BlockPos(village.getX(), 0, village.getZ()).toImmutable();
    }

    /** Returns the last recorded population for this village, or -1 if never recorded. */
    public int getLastKnown(BlockPos village) {
        Integer v = populationByVillage.get(norm(village));
        return v != null ? v : -1;
    }

    public void recordPopulation(BlockPos village, int population) {
        BlockPos key = norm(village);
        Integer old = populationByVillage.get(key);
        if (old == null || old != population) {
            populationByVillage.put(key, population);
            markDirty();
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        populationByVillage.clear();
        NBTTagList list = nbt.getTagList("Villages", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound c = list.getCompoundTagAt(i);
            BlockPos pos = new BlockPos(c.getInteger("x"), 0, c.getInteger("z")).toImmutable();
            populationByVillage.put(pos, c.getInteger("pop"));
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();
        for (Map.Entry<BlockPos, Integer> e : populationByVillage.entrySet()) {
            NBTTagCompound c = new NBTTagCompound();
            c.setInteger("x", e.getKey().getX());
            c.setInteger("z", e.getKey().getZ());
            c.setInteger("pop", e.getValue());
            list.appendTag(c);
        }
        nbt.setTag("Villages", list);
        return nbt;
    }
}
