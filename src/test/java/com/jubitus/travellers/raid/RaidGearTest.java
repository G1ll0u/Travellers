package com.jubitus.travellers.raid;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaidGearTest {

    private enum Tier { NATURAL, NONE, LEATHER, CHAIN, IRON }

    @Test
    void readsSingleTiersAndRanges() {
        assertEquals(Collections.singletonList(Tier.IRON), RaidGear.tiers("iron", Tier.class, Tier.NATURAL));
        assertEquals(Arrays.asList(Tier.LEATHER, Tier.CHAIN, Tier.IRON),
                RaidGear.tiers(" Leather - IRON ", Tier.class, Tier.NATURAL));
        assertEquals(Arrays.asList(Tier.NONE, Tier.LEATHER), RaidGear.tiers("LEATHER-NONE", Tier.class, Tier.NATURAL));
        assertEquals(Collections.singletonList(Tier.NATURAL), RaidGear.tiers("NATURAL", Tier.class, Tier.NATURAL));
    }

    @Test
    void rejectsInvalidText() {
        assertTrue(RaidGear.tiers("NATURAL-IRON", Tier.class, Tier.NATURAL).isEmpty());
        assertTrue(RaidGear.tiers("GOLD", Tier.class, Tier.NATURAL).isEmpty());
        assertTrue(RaidGear.tiers("NONE-CHAIN-IRON", Tier.class, Tier.NATURAL).isEmpty());
        assertTrue(RaidGear.tiers("", Tier.class, Tier.NATURAL).isEmpty());
        assertTrue(RaidGear.tiers(null, Tier.class, Tier.NATURAL).isEmpty());
    }
}
