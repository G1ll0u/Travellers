package com.jubitus.travellers.debug.autotest;

import com.jubitus.travellers.village.MillSettlements;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Picks where a test run forces chunks in a world with far too many villages to load: the busiest few, small radius.
 * The scenario arena sits on the busiest one. The whole layout stays within {@link #CHUNK_BUDGET}.
 */
public final class AutotestLayout {

    private static final int BUSY_VILLAGES = 4;
    private static final int VILLAGE_RADIUS_CHUNKS = 2;
    private static final int ARENA_RADIUS_CHUNKS = 4;
    /** Hard ceiling on chunks a run forces at once, scenario-made zones included. */
    public static final int CHUNK_BUDGET = 250;

    private AutotestLayout() {
    }

    /** "arena x z r | x z r | x z r ..." : the first entry is the arena, the others are soak zones. */
    public static String compute(WorldServer ws) {
        Map<Long, Integer> unitsByVillage = new HashMap<>();
        for (VirtualTravellerRecord r : VirtualTravellerManager.get(ws).getAllRecords()) {
            if (r.homeVillage != null) unitsByVillage.merge(key(r.homeVillage), 1, Integer::sum);
        }
        List<BlockPos> villages = new ArrayList<>(MillSettlements.snapshot(ws).villageCenters);
        if (villages.isEmpty()) return "layout: no village known yet";
        villages.sort((a, b) -> unitsByVillage.getOrDefault(key(b), 0) - unitsByVillage.getOrDefault(key(a), 0));

        StringBuilder out = new StringBuilder("layout:");
        BlockPos arena = villages.get(0);
        out.append(' ').append(arena.getX()).append(' ').append(arena.getZ()).append(' ').append(ARENA_RADIUS_CHUNKS);
        for (int i = 0; i < Math.min(BUSY_VILLAGES, villages.size()); i++) {
            BlockPos v = villages.get(i);
            out.append(" | ").append(v.getX()).append(' ').append(v.getZ()).append(' ').append(VILLAGE_RADIUS_CHUNKS)
                    .append(" (").append(unitsByVillage.getOrDefault(key(v), 0)).append(" units)");
        }
        return out.toString();
    }

    private static long key(BlockPos p) {
        return ((long) p.getX() << 32) ^ (p.getZ() & 0xFFFFFFFFL);
    }
}
