package com.jubitus.travellers.utils.world.camp.shelter;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.server.management.PlayerChunkMap;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

/**
 * Guarantees correct lighting after a bulk block change (shelter build or teardown):
 * 1) recomputes server-side block + sky light over the changed cuboid (flood-fill propagates
 *    lightening/darkening outside the cuboid — a removed firepit darkens up to ~14 blocks away);
 * 2) re-sends affected chunks plus a 2-chunk ring via PARTIAL section packets so the client
 *    updates lighting in-place without unloading the chunk or removing tracked entities.
 *
 * The bitmask must never equal 65535: SPacketChunkData(chunk, 65535) sets isFullChunk()=true,
 * which makes the client call doPreChunk(false)+doPreChunk(true) — that unloads the chunk and
 * removes all entities from the client world, causing the "entity disappears on structure place"
 * bug. A partial bitmask (isFullChunk()=false) updates block+light data in-place with no flicker.
 */
public final class CampLighting {

    private CampLighting() {
    }

    public static void refresh(WorldServer world, BlockPos origin, int sizeX, int sizeY, int sizeZ) {
        for (int dx = 0; dx < sizeX; dx++) {
            for (int dy = 0; dy < sizeY; dy++) {
                for (int dz = 0; dz < sizeZ; dz++) {
                    BlockPos p = origin.add(dx, dy, dz);
                    world.checkLightFor(EnumSkyBlock.BLOCK, p);
                    world.checkLightFor(EnumSkyBlock.SKY, p);
                }
            }
        }

        // Section bitmask covering the changed Y range + 1-section buffer above/below for light
        // spill. Cap at section 14 (never all 16 bits = 65535 = full-chunk = entity flicker).
        int minSection = Math.max(0, (origin.getY() >> 4) - 1);
        int maxSection = Math.min(14, ((origin.getY() + sizeY) >> 4) + 1);
        int sectionMask = 0;
        for (int s = minSection; s <= maxSection; s++) sectionMask |= (1 << s);

        // 2-chunk ring (32 blocks) covers max level-15 light spill even at chunk edges.
        int minCx = (origin.getX() - 32) >> 4;
        int maxCx = (origin.getX() + sizeX + 32) >> 4;
        int minCz = (origin.getZ() - 32) >> 4;
        int maxCz = (origin.getZ() + sizeZ + 32) >> 4;
        PlayerChunkMap pcm = world.getPlayerChunkMap();
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                PlayerChunkMapEntry entry = pcm.getEntry(cx, cz);
                if (entry == null) continue;
                Chunk chunk = entry.getChunk();
                if (chunk == null) continue;
                SPacketChunkData packet = new SPacketChunkData(chunk, sectionMask);
                for (EntityPlayerMP pl : world.getMinecraftServer().getPlayerList().getPlayers()) {
                    if (pl.world == world && entry.containsPlayer(pl)) {
                        pl.connection.sendPacket(packet);
                    }
                }
            }
        }
    }
}
