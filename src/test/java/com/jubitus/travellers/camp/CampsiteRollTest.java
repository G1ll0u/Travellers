package com.jubitus.travellers.camp;

import com.jubitus.travellers.config.TravellersConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CampsiteRollTest {

    @Test
    void rollMatchesConfiguredChanceAndIsStable() {
        double chance = TravellersConfig.CAMP_EVENTS.discoveryChancePerChunk;
        long seed = 123456789L;
        int hits = 0, n = 0;
        for (int x = -150; x < 150; x++) {
            for (int z = -150; z < 150; z++) {
                boolean a = CampsiteManager.rollsCampsite(seed, x, z);
                assertEquals(a, CampsiteManager.rollsCampsite(seed, x, z));
                if (a) hits++;
                n++;
            }
        }
        double rate = hits / (double) n;
        assertTrue(Math.abs(rate - chance) < 0.01, "rate " + rate + " vs chance " + chance);
    }

    @Test
    void neighbouringRowsAreNotCorrelated() {
        long seed = 42L;
        int same = 0, n = 0;
        for (int x = 0; x < 20000; x++) {
            if (CampsiteManager.rollsCampsite(seed, x, 0) == CampsiteManager.rollsCampsite(seed, x, 1)) same++;
            n++;
        }
        double p = TravellersConfig.CAMP_EVENTS.discoveryChancePerChunk;
        double expected = p * p + (1 - p) * (1 - p);
        assertTrue(Math.abs(same / (double) n - expected) < 0.02);
    }
}
