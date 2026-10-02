package com.jubitus.travellers.dialogue;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.pathing.terrain.TerrainProfile;
import com.jubitus.travellers.pathing.terrain.TerrainProfileGrid;
import com.jubitus.travellers.pathing.terrain.TerrainWorldState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/**
 * Cheap text terrain summary (roughness + nearby water) around the traveller, so it has some sense
 * of the ground around it without needing a vision-capable model. Reuses the same coarse per-chunk
 * grid ({@code pathing/terrain/}) Plan A route-planning already maintains — a chunk load is
 * never triggered by this: {@link TerrainProfileGrid#ensureEstimated} only fills cells from biome
 * estimates, mirroring how it's already used by the {@code terrain export} debug command.
 *
 * <p>A multimodal heightmap image was considered instead (2026-09-12 discussion) and shelved: it
 * needs a vision-capable local model, most players won't have one pulled, and a stylized elevation
 * render is a poor fit for models trained on natural photos anyway. This gets most of the value at a
 * fraction of the cost and works for every model.
 *
 * <p>Contributes nothing when {@code ROUTING.enablePlannedRoutes} is off ({@link TerrainWorldState#get}
 * returns null then — nothing else would be maintaining the grid) or when nothing is resident/
 * estimable nearby yet, rather than guessing.
 */
public final class TerrainSummaryDialogueContextProvider implements DialogueContextProvider {

    @Override
    public void contribute(EntityTraveller traveller, EntityPlayer player, DialogueContext.Builder builder) {
        if (!(traveller.world instanceof WorldServer)) return;
        WorldServer world = (WorldServer) traveller.world;
        TerrainWorldState state = TerrainWorldState.get(world);
        if (state == null) return;

        TerrainProfileGrid grid = state.getGrid();
        BlockPos pos = traveller.getPosition();
        int radius = TravellersConfig.DIALOGUE.terrainSummaryRadius;
        int minX = pos.getX() - radius, maxX = pos.getX() + radius;
        int minZ = pos.getZ() - radius, maxZ = pos.getZ() + radius;
        grid.ensureEstimated(world, minX, minZ, maxX, maxZ);

        int cMinX = TerrainProfile.cellCoord(minX), cMaxX = TerrainProfile.cellCoord(maxX);
        int cMinZ = TerrainProfile.cellCoord(minZ), cMaxZ = TerrainProfile.cellCoord(maxZ);

        long amplitudeSum = 0;
        int waterCells = 0;
        int knownCells = 0;
        boolean anyMountain = false;
        for (int cz = cMinZ; cz <= cMaxZ; cz++) {
            for (int cx = cMinX; cx <= cMaxX; cx++) {
                int wx = cx * TerrainProfile.CELL_SIZE + 8;
                int wz = cz * TerrainProfile.CELL_SIZE + 8;
                if (grid.getSource(wx, wz) == TerrainProfile.SOURCE_EMPTY) continue;
                knownCells++;
                amplitudeSum += grid.getAmplitude(wx, wz);
                if (grid.getWater(wx, wz) >= 128) waterCells++;
                if (grid.getBiomeIndex().isMountainBiome(grid.getBiomeIx(wx, wz))) anyMountain = true;
            }
        }
        if (knownCells == 0) return; // nothing scanned/estimated nearby yet - say nothing rather than guess

        double avgAmplitude = amplitudeSum / (double) knownCells;
        double waterFraction = waterCells / (double) knownCells;
        builder.terrainPhrase(DialoguePhrasing.terrainTier(avgAmplitude, waterFraction, anyMountain));
    }
}
