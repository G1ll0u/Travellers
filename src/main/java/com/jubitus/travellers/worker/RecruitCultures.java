package com.jubitus.travellers.worker;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.raid.RaidWorldData;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerRaider;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.unit.UnitWorth;
import com.jubitus.travellers.village.MillCultures;
import com.jubitus.travellers.village.MillSettlements;
import com.jubitus.travellers.village.TravellerVillageRelations;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.IntSupplier;

/** Which cultures a player may recruit from, and what a recruiter would find around: for the recruit screen and the mission alike. */
public final class RecruitCultures {

    public enum Verdict { OK, LOW_REPUTATION, ENEMY_VILLAGE }

    /** One culture with at least one village in recruiting range. */
    public static final class Option {
        public final String cultureKey;
        public final int villages;
        public final int nearestDistance;
        /** Travellers and free workers of this culture within range, loaded or offscreen. */
        public final int freeUnits;
        public final int chancePercent;
        public final Verdict verdict;

        public Option(String cultureKey, int villages, int nearestDistance, int freeUnits, int chancePercent, Verdict verdict) {
            this.cultureKey = cultureKey;
            this.villages = villages;
            this.nearestDistance = nearestDistance;
            this.freeUnits = freeUnits;
            this.chancePercent = chancePercent;
            this.verdict = verdict;
        }
    }

    private RecruitCultures() {
    }

    public static Verdict verdict(WorldServer ws, UUID playerId, String cultureKey) {
        TravellersConfig.Recruitment cfg = TravellersConfig.RECRUITMENT;
        if (cfg.recruitEnemyVillageBlocksCulture
                && TravellerVillageRelations.get(ws).hasEnemyOfCulture(ws, playerId, cultureKey)) {
            return Verdict.ENEMY_VILLAGE;
        }
        return MillCultures.reputation(ws, playerId, cultureKey) < cfg.recruitMinCultureReputation
                ? Verdict.LOW_REPUTATION : Verdict.OK;
    }

    /** Chance a passer-by of this culture (or its whole group) agrees: the base at the lowest allowed reputation, the maximum at Stellar. */
    public static int chancePercent(WorldServer ws, UUID playerId, String cultureKey) {
        TravellersConfig.Recruitment cfg = TravellersConfig.RECRUITMENT;
        int rep = MillCultures.reputation(ws, playerId, cultureKey);
        int span = Math.max(1, MillCultures.STELLAR - cfg.recruitMinCultureReputation);
        double t = Math.max(0.0D, Math.min(1.0D, (rep - cfg.recruitMinCultureReputation) / (double) span));
        int base = cfg.recruitAcceptChancePercent;
        return (int) Math.round(base + (Math.max(base, cfg.recruitAcceptChanceMaxPercent) - base) * t);
    }

    /** A village a recruiting tour may visit from {@code origin}: not too close, not too far. */
    public static boolean inRange(BlockPos origin, BlockPos center) {
        int d = EntityTravellerWorker.dist2D(origin, center);
        return d >= TravellersConfig.RECRUITMENT.recruitMinVillageDistance && d <= TravellersConfig.RECRUITMENT.recruitMaxVillageRadius;
    }

    /** Every culture with a village in range, recruitable ones first, then nearest first. */
    public static List<Option> around(WorldServer ws, UUID playerId, BlockPos origin) {
        Map<String, int[]> villages = new TreeMap<>(); // culture -> {count, nearest}
        for (MillSettlements.Settlement s : MillSettlements.villages(ws)) {
            if (s.cultureKey == null || s.cultureKey.isEmpty() || !inRange(origin, s.center)) continue;
            int[] v = villages.computeIfAbsent(s.cultureKey, k -> new int[]{0, Integer.MAX_VALUE});
            v[0]++;
            v[1] = Math.min(v[1], EntityTravellerWorker.dist2D(origin, s.center));
        }
        Map<String, Integer> free = freeUnitsByCulture(ws, origin);
        List<Option> out = new ArrayList<>();
        for (Map.Entry<String, int[]> e : villages.entrySet()) {
            String key = e.getKey();
            out.add(new Option(key, e.getValue()[0], e.getValue()[1], free.getOrDefault(key, 0),
                    chancePercent(ws, playerId, key), verdict(ws, playerId, key)));
        }
        out.sort(Comparator.comparing((Option o) -> o.verdict != Verdict.OK).thenComparingInt(o -> o.nearestDistance));
        return out;
    }

    /** A unit that may be recruited: a traveller or a free worker, never a raider or a hired unit. */
    public static boolean isCandidate(WorldServer ws, VirtualTravellerRecord r) {
        return !"RAIDER".equals(r.entityClass) && !r.isHiredFollower && r.cultureId != null
                && !RaidWorldData.isRaider(ws, r.uuid);
    }

    public static boolean isCandidate(WorldServer ws, EntityTraveller e) {
        return !(e instanceof EntityTravellerRaider) && !e.isHiredFollower() && e.getCultureId() != null
                && !RaidWorldData.isRaider(ws, e.getUniqueID());
    }

    public interface CandidateVisitor {
        void visit(String cultureKey, IntSupplier hirePrice);
    }

    /** Every candidate within recruiting range of {@code origin}, loaded or offscreen. */
    public static void forEachCandidateAround(WorldServer ws, BlockPos origin, CandidateVisitor visitor) {
        double maxSq = (double) TravellersConfig.RECRUITMENT.recruitMaxVillageRadius * TravellersConfig.RECRUITMENT.recruitMaxVillageRadius;
        for (VirtualTravellerRecord r : VirtualTravellerManager.get(ws).getAllRecords()) {
            if (r.dimension != ws.provider.getDimension() || !isCandidate(ws, r) || distSq2D(origin, r.posX, r.posZ) > maxSq) continue;
            visitor.visit(r.cultureId, () -> UnitWorth.hirePrice(r));
        }
        for (EntityTraveller e : ws.getEntities(EntityTraveller.class, EntityTraveller::isEntityAlive)) {
            if (!isCandidate(ws, e) || distSq2D(origin, e.posX, e.posZ) > maxSq) continue;
            visitor.visit(e.getCultureId(), () -> UnitWorth.hirePrice(e));
        }
    }

    private static Map<String, Integer> freeUnitsByCulture(WorldServer ws, BlockPos origin) {
        Map<String, Integer> out = new TreeMap<>();
        forEachCandidateAround(ws, origin, (culture, price) -> out.merge(culture, 1, Integer::sum));
        return out;
    }

    private static double distSq2D(BlockPos origin, double x, double z) {
        double dx = x - (origin.getX() + 0.5D);
        double dz = z - (origin.getZ() + 0.5D);
        return dx * dx + dz * dz;
    }
}
