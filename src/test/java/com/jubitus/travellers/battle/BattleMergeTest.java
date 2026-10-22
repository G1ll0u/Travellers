package com.jubitus.travellers.battle;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BattleMergeTest {

    private static int next = 1;
    private static final String OWNER = BattleParticipant.OWNER_KEY_PREFIX + new UUID(9, 9);

    private static final String OTHER = BattleParticipant.OWNER_KEY_PREFIX + new UUID(8, 8);

    private static BattleRecord battle(String... keys) {
        BattleRecord b = new BattleRecord(new UUID(1, next++), 0, BlockPos.ORIGIN, 0L);
        for (String key : keys) {
            BattleParticipant p = new BattleParticipant(new UUID(2, next++));
            p.groupKey = key;
            b.participants.put(p.uuid, p);
        }
        return b;
    }

    @Test
    void raidersFightEveryoneOthersOnlyOnceSeenFighting() {
        BattleRecord b = battle(OWNER, "RAIDER", "CIVIL");
        assertTrue(b.areEnemies(OWNER, "RAIDER"));
        assertTrue(b.areEnemies("CIVIL", "RAIDER"));
        assertFalse(b.areEnemies(OWNER, "CIVIL"));
        b.addEnemies("CIVIL", OWNER);
        assertTrue(b.areEnemies(OWNER, "CIVIL"));
        assertFalse(b.areEnemies(OWNER, OWNER));
    }

    @Test
    void viewerCampHoldsItsFactionAndNonEnemies() {
        BattleRecord b = battle(OWNER, "RAIDER", "CIVIL", OTHER);
        b.addEnemies(OWNER, OTHER);
        Map<String, Integer> camps = b.camps(OWNER);
        assertEquals(0, (int) camps.get(OWNER));
        assertEquals(0, (int) camps.get("CIVIL"));
        assertEquals(1, (int) camps.get("RAIDER"));
        assertEquals(1, (int) camps.get(OTHER));
    }

    @Test
    void uninvolvedViewerSeesRaidersOnTheSecondCamp() {
        Map<String, Integer> camps = battle("CIVIL", "RAIDER", OWNER).camps(null);
        assertEquals(1, (int) camps.get("RAIDER"));
        assertEquals(0, (int) camps.get("CIVIL"));
        assertEquals(0, (int) camps.get(OWNER));
    }

    @Test
    void battleGoesOnWhileTwoEnemiesStand() {
        BattleRecord b = battle(OWNER, "CIVIL", "RAIDER");
        assertTrue(b.hasStandingEnemies());
        for (BattleParticipant p : b.participants.values()) {
            if ("RAIDER".equals(p.groupKey)) p.alive = false;
        }
        assertFalse(b.hasStandingEnemies());
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
