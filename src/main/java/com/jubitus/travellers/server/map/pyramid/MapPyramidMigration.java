package com.jubitus.travellers.server.map.pyramid;

import com.jubitus.travellers.Tags;
import net.minecraft.world.World;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.Map;

/**
 * One-time seed of pyramid level 0 from the legacy pre-pyramid detail store, so an existing
 * world's already-generated map data isn't thrown away by this rewrite. Runs once per world,
 * guarded by a version persisted on the level-0 store; the legacy low-res store is not
 * migrated — it's cheaply rebuilt by downsampling once level 0 is seeded.
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public class MapPyramidMigration {

    /**
     * Bump when a fix requires re-running this on already-migrated worlds. v1 only seeded
     * level 0 and lazily dirty-marked parents (too slow to fully cascade for a large legacy
     * store); v2 additionally does a synchronous full-pyramid rebuild right after seeding.
     */
    private static final int CURRENT_VERSION = 2;

    @SubscribeEvent
    public static void onWorldLoad(WorldEvent.Load e) {
        World world = e.getWorld();
        if (world.isRemote) return;

        MapPyramidStore level0 = MapPyramidStore.get(world, 0);
        if (level0.getMigrationVersion() >= CURRENT_VERSION) return;

        MapDetailTileImageSavedData legacy = MapDetailTileImageSavedData.get(world);

        for (Map.Entry<Long, byte[]> entry : legacy.view().entrySet()) {
            byte[] data = entry.getValue();
            if (data == null || data.length != MapTileGenerator.TILE_BYTES) continue;
            level0.put(entry.getKey(), data);
        }

        // Build every level above 0 right away, synchronously — a world with a large existing
        // detail store shouldn't have to wait minutes for the per-tick incremental propagator
        // to trickle the whole thing up to every zoom level.
        PyramidDirtyPropagator.rebuildAllLevelsSync(world);

        level0.setMigrationVersion(CURRENT_VERSION);
    }
}
