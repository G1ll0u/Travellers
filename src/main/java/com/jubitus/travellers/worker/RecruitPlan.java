package com.jubitus.travellers.worker;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.unit.RecruitMission;
import com.jubitus.travellers.village.MillSettlements;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A recruiting tour worked out on the server from the wanted counts per culture: which villages, in which
 * order, and what it costs. The screen shows it, the start order recomputes it (the client is never trusted).
 */
public final class RecruitPlan {

    public static final class Stop {
        public final BlockPos center;
        public final String cultureKey;

        public Stop(BlockPos center, String cultureKey) {
            this.center = center;
            this.cultureKey = cultureKey;
        }
    }

    /** Wanted counts actually kept (unknown, blocked or out-of-range cultures dropped, total capped). */
    public final Map<String, Integer> wanted;
    public final List<Stop> stops;
    public final int travelFee;
    public final int purse;

    private RecruitPlan(Map<String, Integer> wanted, List<Stop> stops, int travelFee, int purse) {
        this.wanted = wanted;
        this.stops = stops;
        this.travelFee = travelFee;
        this.purse = purse;
    }

    public boolean isEmpty() {
        return stops.isEmpty();
    }

    public int total() {
        return travelFee + purse;
    }

    public static RecruitPlan build(WorldServer ws, UUID playerId, BlockPos origin, Map<String, Integer> requested) {
        TravellersConfig.Recruitment cfg = TravellersConfig.RECRUITMENT;
        Map<String, Integer> wanted = new LinkedHashMap<>();
        Map<String, List<BlockPos>> villagesByCulture = villagesInRange(ws, origin);
        int room = cfg.recruitMaxPerMission;
        for (Map.Entry<String, Integer> e : requested.entrySet()) {
            int n = Math.min(Math.max(0, e.getValue()), room);
            if (n == 0 || !villagesByCulture.containsKey(e.getKey())
                    || RecruitCultures.verdict(ws, playerId, e.getKey()) != RecruitCultures.Verdict.OK) continue;
            wanted.put(e.getKey(), n);
            room -= n;
        }

        List<Stop> unordered = new ArrayList<>();
        for (Map.Entry<String, Integer> e : wanted.entrySet()) {
            List<BlockPos> villages = villagesByCulture.get(e.getKey());
            villages.sort(Comparator.comparingInt(c -> EntityTravellerWorker.dist2D(origin, c)));
            int count = Math.min(villages.size(), (e.getValue() + cfg.recruitsPerPlannedVillage - 1) / cfg.recruitsPerPlannedVillage);
            for (int i = 0; i < count; i++) unordered.add(new Stop(villages.get(i), e.getKey()));
        }
        List<Stop> stops = nearestNext(origin, unordered);

        List<BlockPos> route = new ArrayList<>();
        for (Stop s : stops) route.add(s.center);
        int travelFee = stops.isEmpty() ? 0
                : RecruitMission.computeFee(RecruitMission.computeRouteDistance2D(origin, route, origin), stops.size());
        return new RecruitPlan(wanted, stops, travelFee, purse(ws, origin, wanted));
    }

    private static Map<String, List<BlockPos>> villagesInRange(WorldServer ws, BlockPos origin) {
        Map<String, List<BlockPos>> out = new HashMap<>();
        for (MillSettlements.Settlement s : MillSettlements.villages(ws)) {
            if (s.cultureKey == null || s.cultureKey.isEmpty() || !RecruitCultures.inRange(origin, s.center)) continue;
            out.computeIfAbsent(s.cultureKey, k -> new ArrayList<>()).add(s.center);
        }
        return out;
    }

    private static List<Stop> nearestNext(BlockPos origin, List<Stop> left) {
        List<Stop> out = new ArrayList<>(left.size());
        BlockPos at = origin;
        while (!left.isEmpty()) {
            final BlockPos from = at;
            Stop next = left.stream().min(Comparator.comparingInt(s -> EntityTravellerWorker.dist2D(from, s.center))).get();
            left.remove(next);
            out.add(next);
            at = next.center;
        }
        return out;
    }

    /** Wanted counts times the average hiring price of the free units of each culture around, plus the margin. */
    private static int purse(WorldServer ws, BlockPos origin, Map<String, Integer> wanted) {
        if (wanted.isEmpty()) return 0;
        Map<String, long[]> prices = new HashMap<>(); // culture -> {sum, count}
        RecruitCultures.forEachCandidateAround(ws, origin, (culture, price) -> {
            if (!wanted.containsKey(culture)) return;
            long[] p = prices.computeIfAbsent(culture, k -> new long[2]);
            p[0] += price.getAsInt();
            p[1]++;
        });
        double total = 0.0D;
        for (Map.Entry<String, Integer> e : wanted.entrySet()) {
            long[] p = prices.get(e.getKey());
            double avg = p == null ? TravellersConfig.MONEY.hireBaseDeniers : (double) p[0] / p[1];
            total += avg * e.getValue();
        }
        return (int) Math.ceil(total * (1.0D + TravellersConfig.RECRUITMENT.recruitPurseMarginPercent / 100.0D));
    }
}
