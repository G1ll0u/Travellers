package com.jubitus.travellers.worldmap.pyramid;

import com.jubitus.travellers.Tags;
import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.Level;

import java.util.LinkedHashSet;

/**
 * Builds every pyramid level above 0 lazily: whenever a level-0 (or lower-level) tile changes,
 * its parent tile is marked dirty here. This job just downsamples 4 children into 1 parent
 * (pure array math, no world access) and cascades the same mark to the parent's own parent —
 * so the whole pyramid above the world-sampled level 0 stays in sync for free, at a small,
 * budgeted cost per tick, regardless of how much of the world is explored.
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public class PyramidDirtyPropagator {

    /** 2^MAX_LEVEL blocks/pixel must be >= WorldMapController.MAX_ZOOM_BPP (64). */
    public static final int MAX_LEVEL = 6;

    private static final LinkedHashSet<PyramidTileRef> DIRTY = new LinkedHashSet<>();

    public static void markDirty(int dimension, int level, int tileX, int tileZ) {
        if (level < 1 || level > MAX_LEVEL) return;
        synchronized (DIRTY) {
            DIRTY.add(new PyramidTileRef(dimension, level, tileX, tileZ));
        }
    }

    public static void markParentDirty(int dimension, int childLevel, int childTileX, int childTileZ) {
        markDirty(dimension, childLevel + 1, Math.floorDiv(childTileX, 2), Math.floorDiv(childTileZ, 2));
    }

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        World world = event.world;
        if (world.isRemote) return;

        int budget = TravellersConfig.WORLDMAP.mapPyramidBudgetTilesPerTick;
        int dim = world.provider.getDimension();

        int processed = 0;
        java.util.Iterator<PyramidTileRef> it;
        synchronized (DIRTY) {
            it = new java.util.ArrayList<>(DIRTY).iterator();
        }
        while (it.hasNext() && processed < budget) {
            PyramidTileRef ref = it.next();
            if (ref.dimension != dim) continue;
            synchronized (DIRTY) {
                if (!DIRTY.remove(ref)) continue; // already handled this tick by another iterator pass
            }
            processed++;
            rebuildOne(world, ref);
        }
    }

    /**
     * Builds every pyramid level above 0 from whatever is currently in the level-0 store, all
     * levels, synchronously. Used once by {@link MapPyramidMigration} right after seeding level
     * 0 from the legacy detail store, so a world with a large pre-existing map doesn't have to
     * wait for the per-tick dirty queue above to trickle the whole thing up to every zoom level
     * (which, for tens of thousands of legacy tiles, could take a very long time at a per-tick
     * budget meant for incremental, ongoing updates).
     */
    public static void rebuildAllLevelsSync(World world) {
        MapPyramidStore childStore = MapPyramidStore.get(world, 0);
        java.util.Set<Long> childKeys = childStore.keySet();

        for (int level = 1; level <= MAX_LEVEL && !childKeys.isEmpty(); level++) {
            MapPyramidStore parentStore = MapPyramidStore.get(world, level);

            java.util.Set<Long> parentKeys = new java.util.HashSet<>();
            for (long childKey : childKeys) {
                int cx = TileKey.unpackX(childKey);
                int cz = TileKey.unpackZ(childKey);
                parentKeys.add(TileKey.pack(Math.floorDiv(cx, 2), Math.floorDiv(cz, 2)));
            }

            for (long parentKey : parentKeys) {
                int px = TileKey.unpackX(parentKey);
                int pz = TileKey.unpackZ(parentKey);
                int cx0 = px * 2;
                int cz0 = pz * 2;

                byte[] q00 = childStore.get(TileKey.pack(cx0, cz0));
                byte[] q10 = childStore.get(TileKey.pack(cx0 + 1, cz0));
                byte[] q01 = childStore.get(TileKey.pack(cx0, cz0 + 1));
                byte[] q11 = childStore.get(TileKey.pack(cx0 + 1, cz0 + 1));

                parentStore.put(parentKey, MapTileGenerator.downsample(q00, q10, q01, q11));
            }

            childStore = parentStore;
            childKeys = parentKeys;
        }
    }

    private static void rebuildOne(World world, PyramidTileRef ref) {
        int childLevel = ref.level - 1;
        MapPyramidStore childStore = MapPyramidStore.get(world, childLevel);
        MapPyramidStore parentStore = MapPyramidStore.get(world, ref.level);

        int cx0 = ref.tileX * 2;
        int cz0 = ref.tileZ * 2;

        byte[] q00 = childStore.get(TileKey.pack(cx0, cz0));
        byte[] q10 = childStore.get(TileKey.pack(cx0 + 1, cz0));
        byte[] q01 = childStore.get(TileKey.pack(cx0, cz0 + 1));
        byte[] q11 = childStore.get(TileKey.pack(cx0 + 1, cz0 + 1));

        if (q00 == null && q10 == null && q01 == null && q11 == null) return;

        byte[] merged = MapTileGenerator.downsample(q00, q10, q01, q11);
        parentStore.put(TileKey.pack(ref.tileX, ref.tileZ), merged);

        if (TravellersConfig.WORLDMAP.debugMap) {
            FMLLog.log(Level.INFO, "[Travellers/MapPyramid] dim=%d rebuilt: level=%d tile=%d,%d",
                    ref.dimension, ref.level, ref.tileX, ref.tileZ);
        }

        MapLoadedChunkResampler.pushTileUpdate(ref.dimension, ref.level, ref.tileX, ref.tileZ, merged);

        if (ref.level < MAX_LEVEL) {
            markDirty(ref.dimension, ref.level + 1, Math.floorDiv(ref.tileX, 2), Math.floorDiv(ref.tileZ, 2));
        }
    }
}
