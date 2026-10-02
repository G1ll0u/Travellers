package com.jubitus.travellers.worldmap.pyramid;

import com.jubitus.travellers.Tags;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * Legacy pre-pyramid detail tile store. Read-only in current code: only
 * {@link MapPyramidMigration} still touches this, to seed pyramid level 0 once from whatever
 * detail data an existing world already has. Never written to anymore.
 */
public class MapDetailTileImageSavedData extends WorldSavedData {
    private static final String NAME = Tags.MOD_ID + "_map_detail_tile_images";

    // key(long packed tileX,tileZ) -> byte[256*256]
    private final Map<Long, byte[]> tiles = new HashMap<>();

    public MapDetailTileImageSavedData() { super(NAME); }
    public MapDetailTileImageSavedData(String name) { super(name); }

    public static MapDetailTileImageSavedData get(World world) {
        MapStorage storage = world.getMapStorage();
        MapDetailTileImageSavedData saved = (MapDetailTileImageSavedData) storage.getOrLoadData(MapDetailTileImageSavedData.class, NAME);
        if (saved == null) {
            saved = new MapDetailTileImageSavedData();
            storage.setData(NAME, saved);
        }
        return saved;
    }

    public Map<Long, byte[]> view() { return java.util.Collections.unmodifiableMap(tiles); }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        tiles.clear();
        NBTTagList list = nbt.getTagList("tiles", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound c = list.getCompoundTagAt(i);
            long key = c.getLong("k");
            byte[] data = c.getByteArray("d");
            if (data != null && data.length == MapTileGenerator.TILE_BYTES) {
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
