package com.jubitus.travellers.unit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnitSpeedTest {

    // Steady ground speed of an AI mob: s^2 / (1 - 0.546) blocks per tick.
    private static double steadyBpt(double aiSpeed) {
        return aiSpeed * aiSpeed / (1.0D - 0.546D);
    }

    @Test
    void aiSpeedProducesTheRequestedPace() {
        for (double percent : new double[]{40, 92, 110, 115, 125, 130}) {
            assertEquals(UnitSpeed.blocksPerTick(percent), steadyBpt(UnitSpeed.aiSpeed(percent)), 1.0E-9);
        }
    }

    @Test
    void walkingPercentIsAWalkingPlayer() {
        assertEquals(4.317D, UnitSpeed.blocksPerTick(100) * 20.0D, 1.0E-9);
    }

    @Test
    void percentRoundTrips() {
        assertEquals(87.0D, UnitSpeed.percentOfBlocksPerTick(UnitSpeed.blocksPerTick(87.0D)), 1.0E-9);
    }

    @Test
    void navIsRelativeToTheFixedAttribute() {
        assertEquals(UnitSpeed.aiSpeed(92), UnitSpeed.nav(92) * UnitSpeed.ATTRIBUTE, 1.0E-12);
    }

    @Test
    void zeroPercentStandsStill() {
        assertTrue(UnitSpeed.aiSpeed(0) == 0.0D);
    }
}
