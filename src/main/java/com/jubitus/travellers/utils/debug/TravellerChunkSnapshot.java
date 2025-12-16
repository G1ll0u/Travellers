package com.jubitus.travellers.utils.debug;

import com.jubitus.travellers.utils.world.pathing.chunkmanager.TravellerChunkManager;
import com.jubitus.travellers.utils.world.mill.MillVillageIndex;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TravellerChunkSnapshot {

    public final int centerX;
    public final int centerZ;
    public final int radiusBlocks;
    public final int radiusChunks;

    public final List<ChunkPos> merchantChunks;
    public final List<ChunkPos> normalChunks;
    public final List<BlockPos> villages;

    public TravellerChunkSnapshot(int centerX, int centerZ, int radiusBlocks, int radiusChunks,
                                  List<ChunkPos> merchantChunks,
                                  List<ChunkPos> normalChunks,
                                  List<BlockPos> villages) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radiusBlocks = radiusBlocks;
        this.radiusChunks = radiusChunks;
        this.merchantChunks = merchantChunks;
        this.normalChunks = normalChunks;
        this.villages = villages;
    }

    public static TravellerChunkSnapshot create(WorldServer world, BlockPos center, int radiusBlocks) {
        int radiusChunks = (radiusBlocks + 15) / 16;

        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;

        TravellerChunkManager.WorldState state = TravellerChunkManager.INSTANCE.getWorldState(world);
        Set<ChunkPos> merchantForced = (state != null)
                ? new HashSet<>(state.forced)
                : java.util.Collections.emptySet();

        // Collect loaded chunks
        Set<ChunkPos> normal = new HashSet<>();
        Set<ChunkPos> merchant = new HashSet<>();

        net.minecraft.world.gen.ChunkProviderServer cps =
                (net.minecraft.world.gen.ChunkProviderServer) world.getChunkProvider();

        // 1.12.2: getLoadedChunks() exists; if not in your mappings, adapt/reflection
        for (net.minecraft.world.chunk.Chunk chunk : cps.getLoadedChunks()) {
            ChunkPos cp = chunk.getPos();

            int dx = cp.x - centerChunkX;
            int dz = cp.z - centerChunkZ;
            if (Math.abs(dx) > radiusChunks || Math.abs(dz) > radiusChunks) {
                continue;
            }

            if (merchantForced.contains(cp)) {
                merchant.add(cp);
            } else {
                normal.add(cp);
            }
        }

        // Ensure we show travellers-forced chunks even if the chunk provider dropped them for some reason
        for (ChunkPos cp : merchantForced) {
            int dx = cp.x - centerChunkX;
            int dz = cp.z - centerChunkZ;
            if (Math.abs(dx) <= radiusChunks && Math.abs(dz) <= radiusChunks) {
                merchant.add(cp);
            }
        }

        // Village centers near the player
        List<BlockPos> allVillages = MillVillageIndex.getAllVillageCenters(world);
        List<BlockPos> villages = new ArrayList<>();
        int r2Blocks = radiusBlocks * radiusBlocks;

        for (BlockPos v : allVillages) {
            int dx = v.getX() - center.getX();
            int dz = v.getZ() - center.getZ();
            int d2 = dx * dx + dz * dz;
            if (d2 <= r2Blocks) {
                villages.add(v);
            }
        }

        return new TravellerChunkSnapshot(
                centerChunkX,
                centerChunkZ,
                radiusBlocks,
                radiusChunks,
                new ArrayList<>(merchant),
                new ArrayList<>(normal),
                villages
        );
    }
}
