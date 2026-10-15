package com.jubitus.travellers.debug.autotest.scenario;

import com.jubitus.travellers.ground.BlockRules;
import com.jubitus.travellers.ground.chunk.LoadedChunks;
import com.jubitus.travellers.unit.RecruitMission;
import com.jubitus.travellers.village.MillFootprints;
import com.jubitus.travellers.village.MillSettlements;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.Random;

/** Recruits appear on open ground: never inside a Millenaire building, on leaves, or inside blocks. */
public final class RecruitSpawnScenario extends AutotestScenario {

    private static final int SPOTS_PER_VILLAGE = 12;
    private static final double HALF_WIDTH = 0.3D;
    private static final double HEIGHT = 1.8D;

    @Override
    public String name() {
        return "recruit-spawn";
    }

    @Override
    public int budgetSeconds() {
        return 10;
    }

    @Nullable
    @Override
    public Outcome begin(AutotestArena arena, int attempt) {
        WorldServer ws = arena.world;
        Random rand = new Random(1234L + attempt);
        int villagesChecked = 0;
        for (BlockPos center : MillSettlements.snapshot(ws).villageCenters) {
            if (!LoadedChunks.isLoaded(ws, center)) continue;
            villagesChecked++;
            for (int i = 0; i < SPOTS_PER_VILLAGE; i++) {
                BlockPos spot = RecruitMission.findSpawnPos(ws, center, rand);
                String problem = problemAt(ws, spot);
                if (problem != null) {
                    return Outcome.fail(problem + " at " + spot.getX() + " " + spot.getY() + " " + spot.getZ()
                            + " (village " + center.getX() + " " + center.getZ() + ")");
                }
            }
        }
        return villagesChecked == 0 ? Outcome.skip("no village with loaded chunks") : Outcome.PASS;
    }

    @Nullable
    private static String problemAt(WorldServer ws, BlockPos spot) {
        if (MillFootprints.isForbiddenLanding(ws, spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D)) {
            return "recruit spot inside a Millenaire building";
        }
        BlockPos below = spot.down();
        IBlockState floor = ws.getBlockState(below);
        if (BlockRules.isLeaves(ws, below, floor) || BlockRules.isTreePart(ws, below, floor)) {
            return "recruit spot on " + floor.getBlock().getRegistryName();
        }
        AxisAlignedBB body = new AxisAlignedBB(spot.getX() + 0.5D - HALF_WIDTH, spot.getY(), spot.getZ() + 0.5D - HALF_WIDTH,
                spot.getX() + 0.5D + HALF_WIDTH, spot.getY() + HEIGHT, spot.getZ() + 0.5D + HALF_WIDTH);
        if (ws.collidesWithAnyBlock(body)) return "recruit spot inside blocks";
        return null;
    }

    @Override
    public Outcome poll(WorldServer ws, long ageTicks) {
        return Outcome.PENDING;
    }
}
