package com.jubitus.travellers.worldmap.pyramid;

import com.jubitus.travellers.Tags;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player fog of war, one bit per discovered chunk, grouped by level-0 tile (16x16 chunks = 4 longs).
 * Overworld only; vanilla shares one MapStorage across dimensions, so there is only one instance anyway.
 */
public class MapDiscoverySavedData extends WorldSavedData {
    private static final String NAME = Tags.MOD_ID + "_map_discovery";

    public static final int MASK_LONGS = 4;
    private static final int INTS_PER_TILE = 2 + MASK_LONGS * 2;

    private final Map<UUID, Long2ObjectOpenHashMap<long[]>> data = new HashMap<>();

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

    public static long[] fullMask() {
        return new long[]{-1L, -1L, -1L, -1L};
    }

    public static boolean isFull(long[] mask) {
        for (long l : mask) if (l != -1L) return false;
        return true;
    }

    /** Level-0 tile key -> 256-bit chunk mask (bit = localZ * 16 + localX). */
    public Long2ObjectOpenHashMap<long[]> getMasks(UUID playerId) {
        return data.computeIfAbsent(playerId, k -> new Long2ObjectOpenHashMap<>());
    }

    /** Marks one chunk discovered. Returns the tile key if its mask changed, else Long.MIN_VALUE. */
    public long discoverChunk(Long2ObjectOpenHashMap<long[]> masks, int chunkX, int chunkZ) {
        int tileX = Math.floorDiv(chunkX, WorldMapDiscovery.CHUNKS_PER_TILE);
        int tileZ = Math.floorDiv(chunkZ, WorldMapDiscovery.CHUNKS_PER_TILE);
        long key = TileKey.pack(tileX, tileZ);
        long[] mask = masks.get(key);
        if (mask == null) {
            mask = new long[MASK_LONGS];
            masks.put(key, mask);
        }
        int bit = (chunkZ - tileZ * 16) * 16 + (chunkX - tileX * 16);
        long b = 1L << (bit & 63);
        if ((mask[bit >> 6] & b) != 0) return Long.MIN_VALUE;
        mask[bit >> 6] |= b;
        markDirty();
        return key;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        data.clear();
        NBTTagList players = nbt.getTagList("players", 10);
        for (int i = 0; i < players.tagCount(); i++) {
            NBTTagCompound ptag = players.getCompoundTagAt(i);
            UUID id = ptag.getUniqueId("id");
            Long2ObjectOpenHashMap<long[]> masks = new Long2ObjectOpenHashMap<>();
            if (ptag.hasKey("chunkMasks")) {
                int[] a = ptag.getIntArray("chunkMasks");
                for (int o = 0; o + INTS_PER_TILE <= a.length; o += INTS_PER_TILE) {
                    long[] mask = new long[MASK_LONGS];
                    for (int m = 0; m < MASK_LONGS; m++) {
                        mask[m] = ((long) a[o + 2 + m * 2] << 32) | (a[o + 3 + m * 2] & 0xFFFFFFFFL);
                    }
                    masks.put(TileKey.pack(a[o], a[o + 1]), mask);
                }
            } else {
                // Old per-tile format: a discovered tile becomes fully discovered.
                NBTTagList tiles = ptag.getTagList("tiles", 4);
                for (int t = 0; t < tiles.tagCount(); t++) masks.put(((NBTTagLong) tiles.get(t)).getLong(), fullMask());
            }
            data.put(id, masks);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList players = new NBTTagList();
        for (Map.Entry<UUID, Long2ObjectOpenHashMap<long[]>> e : data.entrySet()) {
            NBTTagCompound ptag = new NBTTagCompound();
            ptag.setUniqueId("id", e.getKey());
            int[] a = new int[e.getValue().size() * INTS_PER_TILE];
            int o = 0;
            for (Long2ObjectMap.Entry<long[]> t : e.getValue().long2ObjectEntrySet()) {
                a[o] = TileKey.unpackX(t.getLongKey());
                a[o + 1] = TileKey.unpackZ(t.getLongKey());
                long[] mask = t.getValue();
                for (int m = 0; m < MASK_LONGS; m++) {
                    a[o + 2 + m * 2] = (int) (mask[m] >>> 32);
                    a[o + 3 + m * 2] = (int) mask[m];
                }
                o += INTS_PER_TILE;
            }
            ptag.setTag("chunkMasks", new NBTTagIntArray(a));
            players.appendTag(ptag);
        }
        nbt.setTag("players", players);
        return nbt;
    }
}
