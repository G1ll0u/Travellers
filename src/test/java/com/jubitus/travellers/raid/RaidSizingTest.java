package com.jubitus.travellers.raid;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaidSizingTest {

    @Test
    void playerWithoutWorkersStillGetsASmallRaid() {
        int n = RaidStrength.raiderCount(0.0, 1.0, 40);
        assertTrue(n >= 2 && n <= 5, "got " + n);
    }

    @Test
    void aLegionOfWorkersSaturatesAtTheCap() {
        assertEquals(40, RaidStrength.raiderCount(400.0, 1.0, 40));
        assertTrue(RaidStrength.raiderCount(100.0, 1.0, 1000) < 40, "100 workers must not mean 100 raiders");
    }

    @Test
    void strengthGrowsSlowerThanLinear() {
        int ten = RaidStrength.raiderCount(10.0, 1.0, 1000);
        int forty = RaidStrength.raiderCount(40.0, 1.0, 1000);
        assertTrue(forty < ten * 4);
    }

    @Test
    void scaleMultipliesAndNeverGoesBelowOne() {
        assertTrue(RaidStrength.raiderCount(10.0, 2.0, 1000) > RaidStrength.raiderCount(10.0, 1.0, 1000));
        assertEquals(1, RaidStrength.raiderCount(0.0, 0.1, 40) >= 1 ? 1 : 0);
    }

    @Test
    void armorAndDamageRaisePower() {
        double bare = RaidStrength.power(3.0, 20.0, 0.0);
        assertTrue(RaidStrength.power(3.0, 20.0, 10.0) > bare);
        assertTrue(RaidStrength.power(6.0, 20.0, 0.0) > bare);
        assertEquals(1.0, RaidStrength.relativePower(bare, bare), 1e-9);
    }

    @Test
    void groupsAddUpAndAreNeverTinyUnlessTheRaidIs() {
        Random rng = new Random(7);
        for (int total = 1; total <= 80; total++) {
            List<Integer> sizes = RaidComposition.splitGroups(total, rng);
            int sum = 0;
            for (int s : sizes) {
                sum += s;
                if (total >= 4) assertTrue(s >= 4, "total " + total + " -> " + sizes);
                assertTrue(s <= 14, "total " + total + " -> " + sizes);
            }
            assertEquals(total, sum);
        }
    }
}
