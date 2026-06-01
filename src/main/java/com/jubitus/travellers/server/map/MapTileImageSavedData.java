package com.jubitus.travellers.server.map;

import com.jubitus.travellers.Tags;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import java.util.HashMap;
import java.util.Map;

public class MapTileImageSavedData extends WorldSavedData {
    private static final String NAME = Tags.MOD_ID + "_map_tile_images";

    // key(long packed tileX,tileZ) -> byte[4096] (64x64)
    private final Map<Long, byte[]> tiles = new HashMap<>();

    public static final int TILE_PX = 64;
    public static final int TILE_BYTES = TILE_PX * TILE_PX;

    public MapTileImageSavedData() { super(NAME); }
    public MapTileImageSavedData(String name) { super(name); }

    public static MapTileImageSavedData get(World world) {
        MapStorage storage = world.getMapStorage();
        MapTileImageSavedData saved = (MapTileImageSavedData) storage.getOrLoadData(MapTileImageSavedData.class, NAME);
        if (saved == null) {
            saved = new MapTileImageSavedData();
            storage.setData(NAME, saved);
        }
        return saved;
    }

    public boolean has(long key) {
        return tiles.containsKey(key);
    }

    public byte[] get(long key) {
        return tiles.get(key);
    }

    public void put(long key, byte[] data) {
        tiles.put(key, data);
        markDirty();
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        tiles.clear();
        NBTTagList list = nbt.getTagList("tiles", 10); // compound
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound c = list.getCompoundTagAt(i);
            long key = c.getLong("k");
            byte[] data = c.getByteArray("d");
            if (data != null && data.length == TILE_BYTES) {
                tiles.put(key, data);
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();
        for (Map.Entry<Long, byte[]> e : tiles.entrySet()) {
            NBTTagCompound c = new NBTTagCompound();
            c.setLong("k", e.getKey());
            c.setByteArray("d", e.getValue());
            list.appendTag(c);
        }
        nbt.setTag("tiles", list);
        return nbt;
    }
}
