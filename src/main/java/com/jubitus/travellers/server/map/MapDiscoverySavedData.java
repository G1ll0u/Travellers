package com.jubitus.travellers.server.map;

import com.jubitus.travellers.Tags;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import java.util.*;

public class MapDiscoverySavedData extends WorldSavedData {
    private static final String NAME = Tags.MOD_ID + "_map_discovery";

    // joueur UUID -> tiles
    private final Map<UUID, Set<Long>> data = new HashMap<>();

    public MapDiscoverySavedData() { super(NAME); }
    public MapDiscoverySavedData(String name) { super(name); }

    public static MapDiscoverySavedData get(World world) {
        MapStorage storage = world.getMapStorage();
        MapDiscoverySavedData saved = (MapDiscoverySavedData) storage.getOrLoadData(MapDiscoverySavedData.class, NAME);
        if (saved == null) {
            saved = new MapDiscoverySavedData();
            storage.setData(NAME, saved);
        }
        return saved;
    }

    public Set<Long> getTiles(UUID playerId) {
        return data.computeIfAbsent(playerId, k -> new HashSet<>());
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        data.clear();
        NBTTagList players = nbt.getTagList("players", 10);
        for (int i = 0; i < players.tagCount(); i++) {
            NBTTagCompound ptag = players.getCompoundTagAt(i);
            UUID id = ptag.getUniqueId("id");
            NBTTagList tiles = ptag.getTagList("tiles", 4); // long
            Set<Long> set = new HashSet<>();
            for (int t = 0; t < tiles.tagCount(); t++) set.add(((NBTTagLong) tiles.get(t)).getLong());
            data.put(id, set);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList players = new NBTTagList();
        for (Map.Entry<UUID, Set<Long>> e : data.entrySet()) {
            NBTTagCompound ptag = new NBTTagCompound();
            ptag.setUniqueId("id", e.getKey());

            NBTTagList tiles = new NBTTagList();
            for (Long k : e.getValue()) tiles.appendTag(new NBTTagLong(k));
            ptag.setTag("tiles", tiles);

            players.appendTag(ptag);
        }
        nbt.setTag("players", players);
        return nbt;
    }
}
