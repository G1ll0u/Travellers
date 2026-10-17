package com.jubitus.travellers.battle;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class BattleMergeTest {

    private static int next = 1;
    private static final String OWNER = BattleParticipant.OWNER_KEY_PREFIX + new UUID(9, 9);

    private static BattleRecord battle(Object... sideAndKey) {
        BattleRecord b = new BattleRecord(new UUID(1, next++), 0, BlockPos.ORIGIN, 0L);
        for (int i = 0; i < sideAndKey.length; i += 2) {
            BattleParticipant p = new BattleParticipant(new UUID(2, next++));
            p.side = (Integer) sideAndKey[i];
            p.groupKey = (String) sideAndKey[i + 1];
            b.participants.put(p.uuid, p);
        }
        return b;
    }

    @Test
    void mergeLinesUpSidesThroughASharedFaction() {
        BattleRecord dst = battle(0, OWNER, 1, "RAIDER");
        BattleRecord src = battle(1, OWNER, 0, "RAIDER", 1, "CIVIL");
        Map<UUID, Integer> mapping = BattleEncounterScanner.alignSides(dst, src);
        assertNotNull(mapping);
        for (BattleParticipant p : src.participants.values()) {
            int expected = "RAIDER".equals(p.groupKey) ? 1 : 0;
            assertEquals(expected, (int) mapping.get(p.uuid), p.groupKey);
        }
    }

    @Test
    void mergeIsRefusedWhenAFactionWouldFightOnBothSides() {
        // dst: your henchmen vs raiders. src: raiders vs travellers with your henchmen beside the raiders.
        BattleRecord dst = battle(0, OWNER, 1, "RAIDER");
        BattleRecord src = battle(0, "RAIDER", 0, OWNER, 1, "CIVIL");
        assertNull(BattleEncounterScanner.alignSides(dst, src));
    }

    @Test
    void zoneNeverGrowsPastTheCap() {
        List<double[]> positions = new ArrayList<>();
        positions.add(new double[]{10, 0});
        positions.add(new double[]{2000, 0});
        assertEquals(128, BattleRecord.zoneRadiusAround(0, 0, positions, 128));
        positions.remove(1);
        assertEquals(BattleRecord.MIN_ZONE_RADIUS, BattleRecord.zoneRadiusAround(0, 0, positions, 128));
    }
}
