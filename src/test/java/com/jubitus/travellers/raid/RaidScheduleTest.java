package com.jubitus.travellers.raid;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaidScheduleTest {

    @Test
    void intervalStaysWithinJitterBand() {
        Random rng = new Random(1);
        for (int i = 0; i < 1000; i++) {
            long t = RaidSchedule.rollEarliestTick(1000, 5.0, rng);
            long delta = t - 1000;
            assertTrue(delta >= 0.6 * 5 * 24000 - 1 && delta <= 1.4 * 5 * 24000 + 1);
        }
    }

    @Test
    void weightsDecideTheSlot() {
        Random rng = new Random(2);
        for (int i = 0; i < 200; i++) {
            assertEquals(RaidSchedule.Slot.NIGHT, RaidSchedule.rollSlot(100, 0, rng));
            assertEquals(RaidSchedule.Slot.DAY, RaidSchedule.rollSlot(0, 100, rng));
        }
        int night = 0;
        for (int i = 0; i < 2000; i++) {
            if (RaidSchedule.rollSlot(0, 0, rng) == RaidSchedule.Slot.NIGHT) night++;
        }
        assertTrue(night > 800 && night < 1200, "zero weights count as equal");
    }

    @Test
    void notDueBeforeEarliestTick() {
        assertFalse(RaidSchedule.isDue(99, 100, RaidSchedule.Slot.NIGHT, 0, 15000));
    }

    @Test
    void nightSlotMatchesNightClockOnly() {
        assertTrue(RaidSchedule.isDue(500, 100, RaidSchedule.Slot.NIGHT, 0, 15000));
        assertFalse(RaidSchedule.isDue(500, 100, RaidSchedule.Slot.NIGHT, 0, 6000));
        assertFalse(RaidSchedule.isDue(500, 100, RaidSchedule.Slot.NIGHT, 0, 23500));
    }

    @Test
    void daySlotWrapsAroundMidnightOfTheClock() {
        assertTrue(RaidSchedule.isDue(500, 100, RaidSchedule.Slot.DAY, 0, 23500));
        assertTrue(RaidSchedule.isDue(500, 100, RaidSchedule.Slot.DAY, 0, 500));
        assertFalse(RaidSchedule.isDue(500, 100, RaidSchedule.Slot.DAY, 0, 15000));
    }

    @Test
    void offsetDelaysTheStartInsideTheSlot() {
        assertFalse(RaidSchedule.isDue(500, 100, RaidSchedule.Slot.NIGHT, 3000, 14000));
        assertTrue(RaidSchedule.isDue(500, 100, RaidSchedule.Slot.NIGHT, 3000, 16500));
    }
}
