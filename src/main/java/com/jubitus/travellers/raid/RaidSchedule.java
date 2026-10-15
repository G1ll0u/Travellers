package com.jubitus.travellers.raid;

import java.util.Random;

/** Pure timing rules for raids: when the next one is due and in which part of the day. */
public final class RaidSchedule {

    public static final int DAY_TICKS = 24000;
    private static final int NIGHT_START = 13000;
    private static final int NIGHT_LENGTH = 10000;
    private static final int DAY_START = 23000;
    private static final int DAY_LENGTH = 14000;

    /** Interval spread around the configured average: 0.4 means 60%..140% of it. */
    private static final double INTERVAL_JITTER = 0.4;

    public enum Slot {
        DAY(DAY_START, DAY_LENGTH),
        NIGHT(NIGHT_START, NIGHT_LENGTH);

        final int start;
        final int length;

        Slot(int start, int length) {
            this.start = start;
            this.length = length;
        }
    }

    private RaidSchedule() {
    }

    /** Earliest tick of the next raid: now plus the average interval, randomised. */
    public static long rollEarliestTick(long now, double averageDays, Random rng) {
        double factor = 1.0 + INTERVAL_JITTER * (rng.nextDouble() * 2.0 - 1.0);
        return now + Math.max(1L, Math.round(averageDays * DAY_TICKS * factor));
    }

    /** Both weights at 0 count as equal weights, so a misconfiguration never silences raids silently. */
    public static Slot rollSlot(int nightWeight, int dayWeight, Random rng) {
        int n = Math.max(0, nightWeight);
        int d = Math.max(0, dayWeight);
        if (n + d == 0) return rng.nextBoolean() ? Slot.NIGHT : Slot.DAY;
        return rng.nextInt(n + d) < n ? Slot.NIGHT : Slot.DAY;
    }

    /** Random moment inside the slot, as ticks after the slot opens. */
    public static int rollOffset(Slot slot, Random rng) {
        return rng.nextInt(slot.length);
    }

    /** True once the earliest tick has passed and the day clock is inside the chosen slot, past its offset. */
    public static boolean isDue(long now, long earliestTick, Slot slot, int offset, long dayTime) {
        if (now < earliestTick) return false;
        int pos = (int) (((dayTime - slot.start) % DAY_TICKS + DAY_TICKS) % DAY_TICKS);
        return pos >= offset && pos < slot.length;
    }
}
