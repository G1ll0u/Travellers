package com.jubitus.travellers.raid;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Pure split of a raid's raiders into warbands. */
public final class RaidComposition {

    private static final int MIN_GROUP = 4;
    private static final int MAX_GROUP = 10;

    private RaidComposition() {
    }

    /** Sizes that add up to {@code total}; no band is smaller than {@link #MIN_GROUP} unless the whole raid is. */
    public static List<Integer> splitGroups(int total, Random rng) {
        List<Integer> sizes = new ArrayList<>();
        int left = Math.max(0, total);
        while (left > 0) {
            if (left <= MAX_GROUP) {
                sizes.add(left);
                break;
            }
            int size = MIN_GROUP + rng.nextInt(MAX_GROUP - MIN_GROUP + 1);
            if (left - size < MIN_GROUP) size = left;
            sizes.add(size);
            left -= size;
        }
        return sizes;
    }
}
