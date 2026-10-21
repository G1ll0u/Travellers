package com.jubitus.travellers.raid;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaidPlanTest {

    @Test
    void waveCountStaysInsideMinMaxAndNeverExceedsRaiders() {
        Random rng = new Random(1);
        for (int total = 1; total <= 60; total++) {
            for (int i = 0; i < 20; i++) {
                int n = RaidPlan.waveCount(total, 2, 4, rng);
                assertTrue(n <= 4 && n <= total && (n >= 2 || total < 2), total + " -> " + n);
            }
        }
        assertEquals(3, RaidPlan.waveCount(40, 3, 3, rng), "min = max is a fixed count");
    }

    @Test
    void bigRaidsGetMoreWavesThanSmallOnes() {
        Random rng = new Random(2);
        int small = 0, big = 0;
        for (int i = 0; i < 200; i++) {
            small += RaidPlan.waveCount(5, 1, 4, rng);
            big += RaidPlan.waveCount(40, 1, 4, rng);
        }
        assertTrue(big > small * 2, small + " vs " + big);
    }

    @Test
    void splitAddsUpAndTheLastWaveIsTheBiggestOnAverage() {
        Random rng = new Random(3);
        int first = 0, last = 0;
        for (int i = 0; i < 200; i++) {
            List<Integer> sizes = RaidPlan.splitGrowing(30, 4, rng);
            assertEquals(30, sizes.stream().mapToInt(Integer::intValue).sum());
            assertTrue(sizes.stream().allMatch(n -> n >= 1));
            first += sizes.get(0);
            last += sizes.get(3);
        }
        assertTrue(last > first, first + " vs " + last);
    }

    @Test
    void kindsScaleWavesButTheRaidStaysUnderItsCap() {
        List<Integer> sizes = Arrays.asList(10, 12, 14);
        List<RaidPlan.WaveKind> hordes = Collections.nCopies(3, RaidPlan.WaveKind.HORDE);
        assertEquals(36, RaidPlan.applyKinds(sizes, hordes, 36).stream().mapToInt(Integer::intValue).sum());
        List<Integer> elite = RaidPlan.applyKinds(sizes, Collections.nCopies(3, RaidPlan.WaveKind.ELITE), 100);
        assertEquals(Arrays.asList(6, 7, 8), elite);
    }

    @Test
    void oneFrontKeepsEveryWaveOnTheBaseDirection() {
        for (double d : RaidPlan.offsets(RaidPlan.Approach.ONE_FRONT, 4, new Random(4))) assertEquals(0.0, d);
    }

    @Test
    void twoFrontsAlternateAndEncirclementSpreadsAround() {
        double[] two = RaidPlan.offsets(RaidPlan.Approach.TWO_FRONTS, 4, new Random(5));
        assertEquals(0.0, two[0]);
        assertEquals(two[1], two[3]);
        assertTrue(Math.abs(two[1]) >= 90.0);
        double[] around = RaidPlan.offsets(RaidPlan.Approach.ENCIRCLE, 4, new Random(6));
        assertEquals(0.0, around[0]);
        for (int i = 1; i < 4; i++) assertNotEquals(0.0, around[i]);
    }

    @Test
    void firstWaveIsAlwaysThePrimaryCulture() {
        List<String> all = RaidPlan.cultures(5, "norman", Collections.singletonList("seljuk"), 100, new Random(7));
        assertEquals(Arrays.asList("norman", "seljuk", "seljuk", "seljuk", "seljuk"), all);
        List<String> none = RaidPlan.cultures(5, "norman", Collections.singletonList("seljuk"), 0, new Random(7));
        assertTrue(none.stream().allMatch("norman"::equals));
    }

    @Test
    void allZeroWeightsStillPickSomething() {
        Random rng = new Random(8);
        for (int i = 0; i < 50; i++) {
            RaidPlan.Settings s = new RaidPlan.Settings();
            s.kindWeights = new int[]{0, 0, 0};
            s.approachWeights = new int[]{0, 0, 0};
            s.cavalryAllowed = false;
            RaidPlan plan = RaidPlan.build(30, "norman", Collections.emptyList(), s, rng);
            assertTrue(plan.waves.stream().noneMatch(w -> w.kind == RaidPlan.WaveKind.CAVALRY));
            assertTrue(plan.totalRaiders() <= s.maxRaiders);
        }
    }
}
