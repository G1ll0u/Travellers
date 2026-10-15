package com.jubitus.travellers.combat;

import com.jubitus.travellers.config.TravellersConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AttackTargetLimiterCapTest {

    @Test
    void capGrowsWithFightDurationAndDeaths() {
        int base = TravellersConfig.COMBAT.maxAttackersPerTarget;
        assertEquals(base, AttackTargetLimiter.capFor(0, 0));
        assertEquals(base + 1, AttackTargetLimiter.capFor(5 * 20, 0));
        assertEquals(base + 2, AttackTargetLimiter.capFor(15 * 20, 0));
        assertEquals(base + 2 + 3, AttackTargetLimiter.capFor(15 * 20, 10));
    }
}
