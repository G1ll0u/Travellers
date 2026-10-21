package com.jubitus.travellers.raid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Pure shape of a raid: its approach and, per wave, how many raiders, how they fight, their culture, their side. */
public final class RaidPlan {

    public enum Approach { ONE_FRONT, TWO_FRONTS, ENCIRCLE }

    /** How a wave fights; the size factor keeps every kind on the same strength budget. */
    public enum WaveKind {
        HORDE(1.4), ELITE(0.6), CAVALRY(1.0);

        final double sizeFactor;

        WaveKind(double sizeFactor) {
            this.sizeFactor = sizeFactor;
        }
    }

    public static final class Wave {
        public final int size;
        public final WaveKind kind;
        public final String culture;
        /** Degrees from the raid's base direction (player toward the first wave's home village). */
        public final double offsetDegrees;

        public Wave(int size, WaveKind kind, String culture, double offsetDegrees) {
            this.size = size;
            this.kind = kind;
            this.culture = culture;
            this.offsetDegrees = offsetDegrees;
        }
    }

    /** Player-tunable inputs, read from the config by the caller. */
    public static final class Settings {
        public int minWaves = 1;
        public int maxWaves = 4;
        public int maxRaiders = 40;
        /** ONE_FRONT, TWO_FRONTS, ENCIRCLE. */
        public int[] approachWeights = {1, 1, 1};
        /** HORDE, ELITE, CAVALRY. */
        public int[] kindWeights = {1, 1, 1};
        public boolean cavalryAllowed = true;
        public int mixedCulturePercent;
    }

    /** Raiders per wave the wave count aims for, before its random step and the min/max clamp. */
    private static final int RAIDERS_PER_WAVE = 10;
    /** Each wave is this much bigger than the one before (as a share of the first). */
    private static final double WAVE_GROWTH = 0.25;
    private static final double SIZE_JITTER = 0.15;

    public final Approach approach;
    public final List<Wave> waves;

    public RaidPlan(Approach approach, List<Wave> waves) {
        this.approach = approach;
        this.waves = Collections.unmodifiableList(new ArrayList<>(waves));
    }

    public int totalRaiders() {
        int n = 0;
        for (Wave w : waves) n += w.size;
        return n;
    }

    public static RaidPlan build(int totalRaiders, String primaryCulture, List<String> otherCultures,
                                 Settings s, Random rng) {
        int count = waveCount(totalRaiders, s.minWaves, s.maxWaves, rng);
        Approach approach = count <= 1 ? Approach.ONE_FRONT : pick(Approach.values(), s.approachWeights, rng);
        List<Integer> sizes = splitGrowing(totalRaiders, count, rng);
        int[] kindWeights = s.kindWeights.clone();
        if (!s.cavalryAllowed) kindWeights[WaveKind.CAVALRY.ordinal()] = 0;
        List<WaveKind> kinds = new ArrayList<>();
        for (int i = 0; i < count; i++) kinds.add(pickKind(kindWeights, s.cavalryAllowed, rng));
        sizes = applyKinds(sizes, kinds, s.maxRaiders);
        List<String> cultures = cultures(count, primaryCulture, otherCultures, s.mixedCulturePercent, rng);
        double[] offsets = offsets(approach, count, rng);
        List<Wave> waves = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            waves.add(new Wave(sizes.get(i), kinds.get(i), cultures.get(i), offsets[i]));
        }
        return new RaidPlan(approach, waves);
    }

    /** About one wave per ten raiders, one more or less at random, inside [min, max] and never more than raiders. */
    static int waveCount(int totalRaiders, int min, int max, Random rng) {
        int lo = Math.max(1, Math.min(min, max));
        int hi = Math.max(lo, max);
        int n = (int) Math.ceil(totalRaiders / (double) RAIDERS_PER_WAVE) + rng.nextInt(3) - 1;
        n = Math.max(lo, Math.min(hi, n));
        return Math.max(1, Math.min(n, totalRaiders));
    }

    /** Sizes adding up to {@code total}, each at least one, growing from the first wave to the last. */
    static List<Integer> splitGrowing(int total, int waves, Random rng) {
        double[] weights = new double[waves];
        double sum = 0.0;
        for (int i = 0; i < waves; i++) {
            weights[i] = (1.0 + WAVE_GROWTH * i) * (1.0 + SIZE_JITTER * (rng.nextDouble() * 2.0 - 1.0));
            sum += weights[i];
        }
        int spare = total - waves;
        List<Integer> sizes = new ArrayList<>();
        int given = 0;
        for (int i = 0; i < waves; i++) {
            int extra = (int) Math.floor(spare * weights[i] / sum);
            sizes.add(1 + extra);
            given += extra;
        }
        // Rounding leftovers go to the last waves, which are the biggest anyway.
        for (int i = waves - 1; given < spare; i = (i - 1 + waves) % waves, given++) {
            sizes.set(i, sizes.get(i) + 1);
        }
        return sizes;
    }

    /** Scales each wave by its kind, then trims the biggest waves until the raid fits {@code maxRaiders}. */
    static List<Integer> applyKinds(List<Integer> sizes, List<WaveKind> kinds, int maxRaiders) {
        List<Integer> out = new ArrayList<>();
        int sum = 0;
        for (int i = 0; i < sizes.size(); i++) {
            int n = Math.max(1, (int) Math.round(sizes.get(i) * kinds.get(i).sizeFactor));
            out.add(n);
            sum += n;
        }
        int cap = Math.max(out.size(), maxRaiders);
        while (sum > cap) {
            int biggest = 0;
            for (int i = 1; i < out.size(); i++) if (out.get(i) > out.get(biggest)) biggest = i;
            out.set(biggest, out.get(biggest) - 1);
            sum--;
        }
        return out;
    }

    /** Degrees from the base direction per wave: all on one side, alternating two sides, or around the player. */
    static double[] offsets(Approach approach, int waves, Random rng) {
        double[] out = new double[waves];
        switch (approach) {
            case TWO_FRONTS: {
                double second = (rng.nextBoolean() ? 1 : -1) * (90.0 + rng.nextDouble() * 90.0);
                for (int i = 0; i < waves; i++) out[i] = i % 2 == 0 ? 0.0 : second;
                break;
            }
            case ENCIRCLE: {
                double step = 360.0 / waves;
                List<Double> rest = new ArrayList<>();
                for (int i = 1; i < waves; i++) rest.add(i * step + (rng.nextDouble() - 0.5) * step * 0.5);
                Collections.shuffle(rest, rng);
                for (int i = 1; i < waves; i++) out[i] = rest.get(i - 1);
                break;
            }
            default:
                break;
        }
        return out;
    }

    /** The first wave is always the primary culture; a later one switches to another at the given chance. */
    static List<String> cultures(int waves, String primary, List<String> others, int mixPercent, Random rng) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < waves; i++) {
            boolean mix = i > 0 && !others.isEmpty() && rng.nextInt(100) < mixPercent;
            out.add(mix ? others.get(rng.nextInt(others.size())) : primary);
        }
        return out;
    }

    private static WaveKind pickKind(int[] weights, boolean cavalryAllowed, Random rng) {
        int[] w = weights;
        if (sum(w) == 0) {
            // All weights at 0 count as equal weights, so a misconfiguration never silences raids.
            w = new int[WaveKind.values().length];
            for (int i = 0; i < w.length; i++) w[i] = cavalryAllowed || i != WaveKind.CAVALRY.ordinal() ? 1 : 0;
        }
        return pick(WaveKind.values(), w, rng);
    }

    /** Weighted pick; all weights at 0 count as equal weights. */
    static <E> E pick(E[] values, int[] weights, Random rng) {
        int total = sum(weights);
        if (total == 0) return values[rng.nextInt(values.length)];
        int roll = rng.nextInt(total);
        for (int i = 0; i < values.length; i++) {
            roll -= Math.max(0, i < weights.length ? weights[i] : 0);
            if (roll < 0) return values[i];
        }
        return values[values.length - 1];
    }

    private static int sum(int[] weights) {
        int n = 0;
        for (int w : weights) n += Math.max(0, w);
        return n;
    }
}
