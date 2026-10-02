package com.jubitus.travellers.entity.entityai.travel.travelcommon;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.culture.CultureRegistry;
import com.jubitus.travellers.world.mill.MillRelations;
import com.jubitus.travellers.world.mill.MillSettlements;
import com.jubitus.travellers.pathing.MPBIntegration;
import com.jubitus.travellers.pathing.terrain.TerrainProfile;
import com.jubitus.travellers.pathing.terrain.TerrainProfileGrid;
import com.jubitus.travellers.pathing.terrain.TerrainWorldState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The single destination authority for loaded <b>and</b> virtual travellers/raiders/roaming workers
 * (mirrors the {@code WorkerGroupService} "one service" rule). Given an origin and a traveller's
 * identity it returns which settlement to head to next, as a soft config-scalable bias over a
 * still-dominant distance term - see {@code docs/regionalized-travel-B-destination-choice.md}.
 *
 * <p>Server thread, cheap, rare (once per traveller per completed leg). Caches are bounded by
 * settlement <i>pairs</i>, never by traveller count.
 */
public final class TravelDestinationChooser {

    /** Mirrors {@code org.millenaire.common.village.Building.RELATION_OPENCONFLICT}. */
    private static final int OPEN_CONFLICT = -90;
    /** Culture bonus coefficient - the free constant §9 says to lower if the distance invariant breaks. */
    private static final double K_CULTURE = 0.6;
    private static final int CORRIDOR_SAMPLES = 10;

    public enum EntityKind {TRAVELLER, RAIDER, WORKER}

    private TravelDestinationChooser() {}

    // ---- Public entry -------------------------------------------------------

    public static final class DestinationContext {
        public BlockPos originCentre;
        @Nullable public BlockPos homeVillage;
        @Nullable public String cultureId;
        public EntityKind kind = EntityKind.TRAVELLER;
        public float wanderlust;
        public float exploreChance;
        public long[] recentDestinations = new long[0];
        public Random rng;
        /** Loaded side with MPB present restricts candidates to road-connected villages, as today. */
        public boolean applyRoadRestriction;
        /** Loaded: TravelCommon.badVillages. Virtual: null (the ring + stuck detection cover it). */
        @Nullable public Predicate<BlockPos> badVillages;
        /** For {@code /travellersdebug destination} - the last breakdown is stored under this id. */
        @Nullable public UUID debugId;
        public boolean loaded;
    }

    /** One shortlist candidate as scored, for the analysis log / {@code /travellersdebug destination}. */
    public static final class Row {
        public BlockPos centre;
        public double dist;
        public boolean cultureKnown, sameCulture;
        @Nullable public Integer relation;
        public int population;
        public boolean roadConnected;
        public double homeDist = -1;
        public float corridor = -1f;
        public Breakdown terms;   // prefilter multipliers (before corridor)
        public double score;      // final score used for the weighted pick
        public boolean picked;
    }

    public static final class ChosenDestination {
        public final BlockPos dest;
        public final String debug;
        // Structured breakdown for the analysis log; may be empty for an epsilon / no-candidate pick.
        public BlockPos origin;
        public EntityKind kind;
        public String originCulture = "";
        public boolean epsilon;
        public boolean raiderInverted;
        public String ladder = "full";
        public double epsilonThreshold;
        public java.util.List<Row> shortlist = java.util.Collections.emptyList();
        // Aggregates over the whole hard-filtered candidate pool (the base rate to compare picks against).
        public int poolCount;
        public double poolSameCultureFrac, poolMeanDist, poolMeanPop, poolMeanRelation, poolRoadFrac;
        public long tick;
        @Nullable public java.util.UUID unit;
        public double originHomeDist = -1;
        public boolean loaded;

        ChosenDestination(BlockPos dest, String debug) {
            this.dest = dest;
            this.debug = debug;
        }
    }

    @Nullable
    public static ChosenDestination choose(World world, DestinationContext ctx) {
        long now = world.getTotalWorldTime();
        MillSettlements.Snapshot snap = MillSettlements.snapshot(world);
        if (snap.centers.isEmpty()) return null;

        WorldServer ws = world instanceof WorldServer ? (WorldServer) world : null;
        int maxRadius = Math.max(1, TravellersConfig.DESTINATIONS.villagePickRadius);
        long maxR2 = (long) maxRadius * maxRadius;
        boolean raider = ctx.kind == EntityKind.RAIDER
                && TravellersConfig.DESTINATIONS.raiderRegionInversion;

        List<BlockPos> roadVillages = (ws != null && MPBIntegration.PRESENT)
                ? cachedConnectedVillages(ws, now) : java.util.Collections.emptyList();

        // --- candidate set (§4.1) ---
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos c : snap.centers) {
            if (c.getX() == ctx.originCentre.getX() && c.getZ() == ctx.originCentre.getZ()) continue;
            if (ctx.badVillages != null && ctx.badVillages.test(c)) continue;
            long d2 = distSqXZ(ctx.originCentre, c);
            if (d2 > maxR2) continue;
            candidates.add(c);
        }
        if (ctx.applyRoadRestriction && MPBIntegration.PRESENT && !roadVillages.isEmpty()) {
            List<BlockPos> onRoad = new ArrayList<>();
            for (BlockPos c : candidates) if (isNearRoadVillage(c, roadVillages, 64)) onRoad.add(c);
            if (!onRoad.isEmpty()) candidates = onRoad;
        }
        if (candidates.isEmpty()) {
            BlockPos near = nearest(snap.centers, ctx.originCentre);
            if (near == null) return null;
            ChosenDestination cd = new ChosenDestination(near, "ladder=nearest(no-candidates)");
            cd.origin = ctx.originCentre;
            cd.kind = ctx.kind;
            cd.originCulture = ctx.cultureId == null ? "" : ctx.cultureId;
            cd.raiderInverted = raider;
            cd.ladder = "nearest(no-candidates)";
            cd.tick = now;
            return finish(world, ctx, cd);
        }

        // --- hard filter: open conflict (§4.2), non-raiders only ---
        Map<BlockPos, Integer> relations = new java.util.HashMap<>();
        List<BlockPos> filtered = new ArrayList<>();
        for (BlockPos c : candidates) {
            Integer rel = ctx.homeVillage == null ? null
                    : MillRelations.getRelation(world, ctx.homeVillage, c, now);
            relations.put(c, rel);
            if (!raider && rel != null && rel <= OPEN_CONFLICT) continue;
            filtered.add(c);
        }
        boolean openConflictRelaxed = filtered.isEmpty();
        if (openConflictRelaxed) filtered = candidates;

        Terms t = Terms.fromConfig(ctx.kind, ctx.wanderlust, maxRadius, raider);

        // --- score every hard-filtered candidate, accumulating pool aggregates as the base rate ---
        List<Scored> scored = new ArrayList<>(filtered.size());
        int poolSame = 0, poolRelN = 0, poolRoad = 0;
        double poolDistSum = 0, poolPopSum = 0, poolRelSum = 0;
        for (BlockPos c : filtered) {
            Cand cand = new Cand();
            cand.dist = Math.sqrt(distSqXZ(ctx.originCentre, c));
            String candCulture = snap.cultureAt(c);
            cand.cultureKnown = ctx.cultureId != null && !ctx.cultureId.isEmpty()
                    && candCulture != null && !candCulture.isEmpty();
            cand.sameCulture = cand.cultureKnown && candCulture.equalsIgnoreCase(ctx.cultureId);
            cand.relation = relations.get(c);
            cand.population = resolvePopulation(world, ws, c);
            cand.roadConnected = !roadVillages.isEmpty() && isNearRoadVillage(c, roadVillages, 64);
            cand.recentPos = RecentDestinationRing.indexOf(ctx.recentDestinations, c);
            if (ctx.homeVillage != null) {
                cand.homeDistOrigin = Math.sqrt(distSqXZ(ctx.originCentre, ctx.homeVillage));
                cand.homeDistCand = Math.sqrt(distSqXZ(c, ctx.homeVillage));
            }
            Breakdown bd = prefilterBreakdown(cand, t);
            Scored s = new Scored(c, cand, bd.total);
            s.terms = bd;
            scored.add(s);

            if (cand.sameCulture) poolSame++;
            if (cand.roadConnected) poolRoad++;
            poolDistSum += cand.dist;
            poolPopSum += cand.population;
            if (cand.relation != null) { poolRelSum += cand.relation; poolRelN++; }
        }

        // --- exploration epsilon (§4.5): skip all bias, uniform among hard-filtered ---
        double eps = Math.max(TravellersConfig.DESTINATIONS.explorationEpsilonFloor,
                Math.min(1.0, ctx.exploreChance));
        boolean epsilonRoll = ctx.rng.nextDouble() < eps;

        List<Scored> shortlist;
        BlockPos pick;
        String ladder;
        if (epsilonRoll) {
            pick = filtered.get(ctx.rng.nextInt(filtered.size()));
            shortlist = java.util.Collections.emptyList();
            ladder = "epsilon";
        } else {
            scored.sort(Comparator.comparingDouble((Scored s) -> s.score).reversed());
            int topN = Math.max(1, TravellersConfig.DESTINATIONS.topNCandidates);
            shortlist = scored.size() > topN ? new ArrayList<>(scored.subList(0, topN)) : scored;

            boolean corridorOn = !raider && t.corridorBiomeWeight > 0.0
                    && ctx.cultureId != null && !ctx.cultureId.isEmpty();
            if (corridorOn) {
                // Sample the whole shortlist first; the neutral point is the shortlist's own mean,
                // not an absolute 0.5 - a globally thin biome table would otherwise make this a
                // blanket penalty. corridor < 0 == no biome data for that trip.
                double corridorSum = 0;
                int corridorN = 0;
                for (Scored s : shortlist) {
                    s.corridor = corridorFriendliness(world, ctx.originCentre, s.centre, ctx.cultureId, now);
                    if (s.corridor >= 0f) { corridorSum += s.corridor; corridorN++; }
                }
                if (corridorN >= 3) {
                    double meanF = corridorSum / corridorN;
                    for (Scored s : shortlist) {
                        if (s.corridor < 0f) continue;
                        s.score *= corridorContribution(s.corridor, meanF, t.corridorBiomeWeight, t.strength);
                    }
                }
            }

            pick = weightedPick(shortlist, ctx.rng);
            ladder = "full";
            if (pick == null) { // relaxation - only reachable if every term collapsed to 0
                pick = shortlist.isEmpty() ? nearest(filtered, ctx.originCentre)
                        : nearest(centresOf(shortlist), ctx.originCentre);
                ladder = "nearest(zero-weight)";
            }
        }
        if (pick == null) return null;

        ChosenDestination cd = new ChosenDestination(pick,
                buildDebug(ctx, t, shortlist, ladder, openConflictRelaxed, eps));
        cd.origin = ctx.originCentre;
        cd.kind = ctx.kind;
        cd.originCulture = ctx.cultureId == null ? "" : ctx.cultureId;
        cd.epsilon = epsilonRoll;
        cd.epsilonThreshold = eps;
        cd.raiderInverted = raider;
        cd.ladder = openConflictRelaxed ? ladder + "+openConflictRelaxed" : ladder;
        cd.tick = now;
        cd.poolCount = filtered.size();
        cd.poolSameCultureFrac = filtered.isEmpty() ? 0 : (double) poolSame / filtered.size();
        cd.poolMeanDist = filtered.isEmpty() ? 0 : poolDistSum / filtered.size();
        cd.poolMeanPop = filtered.isEmpty() ? 0 : poolPopSum / filtered.size();
        cd.poolMeanRelation = poolRelN == 0 ? 0 : poolRelSum / poolRelN;
        cd.poolRoadFrac = filtered.isEmpty() ? 0 : (double) poolRoad / filtered.size();

        List<Row> rows = new ArrayList<>(shortlist.size());
        for (Scored s : shortlist) {
            Row r = new Row();
            r.centre = s.centre;
            r.dist = s.cand.dist;
            r.cultureKnown = s.cand.cultureKnown;
            r.sameCulture = s.cand.sameCulture;
            r.relation = s.cand.relation;
            r.population = s.cand.population;
            r.roadConnected = s.cand.roadConnected;
            r.homeDist = s.cand.homeDistCand;
            r.corridor = s.corridor;
            r.terms = s.terms;
            r.score = s.score;
            r.picked = s.centre.getX() == pick.getX() && s.centre.getZ() == pick.getZ();
            rows.add(r);
        }
        cd.shortlist = rows;
        return finish(world, ctx, cd);
    }

    private static ChosenDestination finish(World world, DestinationContext ctx, ChosenDestination cd) {
        cd.unit = ctx.debugId;
        if (ctx.homeVillage != null && cd.origin != null) cd.originHomeDist = Math.sqrt(distSqXZ(cd.origin, ctx.homeVillage));
        cd.loaded = ctx.loaded;
        if (ctx.debugId != null) recordDebug(ctx.debugId, cd.debug);
        if (TravellersConfig.DESTINATIONS.regionDebug) {
            System.out.println("[Travellers/Destination] " + ctx.kind + " -> "
                    + cd.dest.getX() + "," + cd.dest.getZ() + " | " + cd.debug);
        }
        if (TravellersConfig.DESTINATIONS.logChoices) {
            com.jubitus.travellers.debug.destination.DestinationChoiceLog.logChoice(world, cd);
        }
        return cd;
    }

    /**
     * Drops corridor mid-stops that are an open-conflict enemy of the home village (§4.6). Raiders
     * and a null home are pass-through. Server thread.
     */
    public static List<BlockPos> filterOpenConflictMids(World world, @Nullable BlockPos home,
                                                        boolean raider, List<BlockPos> mids) {
        if (raider || home == null || mids.isEmpty()) return mids;
        long now = world.getTotalWorldTime();
        List<BlockPos> out = new ArrayList<>(mids.size());
        for (BlockPos m : mids) {
            Integer rel = MillRelations.getRelation(world, home, m, now);
            if (rel != null && rel <= OPEN_CONFLICT) continue;
            out.add(m);
        }
        return out;
    }

    // ---- Distance banding (§3.3) -----------------------------------------------

    /**
     * A wanderlust-tuned weight peaked near the traveller's preferred trip length and decaying with
     * distance so distance stays the dominant term. {@code distBlocks}/{@code maxRadius} in blocks.
     */
    public static double distanceBandWeight(double distBlocks, double maxRadius, float wanderlust) {
        double d = clamp(distBlocks / Math.max(1.0, maxRadius), 0.0, 1.0);
        double wl = clamp(wanderlust, 0.0f, 1.0f);
        double dScale = 0.06 + 0.10 * wl; // low wanderlust: sharp short-trip preference; high: flatter
        double x = d / dScale;
        return 1.0 / (1.0 + x * x);
    }

    // ---- Pure scoring core (unit-tested directly) ----------------------------

    public static final class Cand {
        public double dist;
        public boolean cultureKnown;
        public boolean sameCulture;
        @Nullable public Integer relation;
        public int population;
        public boolean roadConnected;
        public int recentPos = -1;
        // Distances to the home village (XZ); -1 when there is no home.
        public double homeDistOrigin = -1, homeDistCand = -1;
    }

    public static final class Terms {
        public double strength;
        public double kCulture = K_CULTURE;
        public double cultureMismatchPenalty;
        public double minCultureWeight;
        public double relationBiasLow;
        public double relationBiasHigh;
        public double populationBias;   // already kind-resolved
        public int populationRef;
        public double maxPopMultiplier;
        public double roadConnectivityBonus;
        public double recentDestPenaltyFactor;
        public int recentDestMemory;
        public double corridorBiomeWeight;
        public boolean raiderInvert;
        public double maxRadius;
        public float wanderlust;
        public boolean homePull;
        public double homePullWeight;
        public double homePullFreeRadius;
        public double homePullFullRadius;

        public static Terms fromConfig(EntityKind kind, float wanderlust, double maxRadius, boolean raiderInvert) {
            TravellersConfig.Destinations d = TravellersConfig.DESTINATIONS;
            Terms t = new Terms();
            t.strength = d.regionalizationStrength;
            t.cultureMismatchPenalty = d.cultureMismatchPenalty;
            t.minCultureWeight = d.minCultureWeight;
            t.relationBiasLow = d.relationBiasLow;
            t.relationBiasHigh = d.relationBiasHigh;
            t.populationBias = kind == EntityKind.RAIDER ? d.raiderPopulationBias : d.populationBias;
            t.populationRef = d.populationRef;
            t.maxPopMultiplier = d.maxPopMultiplier;
            t.roadConnectivityBonus = d.roadConnectivityBonus;
            t.recentDestPenaltyFactor = d.recentDestPenaltyFactor;
            t.recentDestMemory = d.recentDestMemory;
            t.corridorBiomeWeight = d.corridorBiomeWeight;
            t.raiderInvert = raiderInvert;
            t.maxRadius = maxRadius;
            t.wanderlust = wanderlust;
            t.homePull = kind != EntityKind.RAIDER;
            t.homePullWeight = d.homePullWeight;
            t.homePullFreeRadius = d.homePullFreeRadius;
            t.homePullFullRadius = d.homePullFullRadius;
            return t;
        }
    }

    /** Per-term multipliers of the prefilter score, for the analysis log / report. */
    public static final class Breakdown {
        public double distance, culture, relation, population, recent, road, home, total;
    }

    public static Breakdown prefilterBreakdown(Cand c, Terms t) {
        Breakdown b = new Breakdown();
        b.distance = distanceBandWeight(c.dist, t.maxRadius, t.wanderlust);
        b.culture = cultureTerm(c, t);
        b.relation = relationTerm(c, t);
        b.population = populationTerm(c, t);
        b.recent = recentPenalty(c, t);
        b.road = roadTerm(c, t);
        b.home = homeTerm(c, t);
        b.total = b.distance * b.culture * b.relation * b.population * b.recent * b.road * b.home;
        return b;
    }

    public static double prefilterScore(Cand c, Terms t) {
        return prefilterBreakdown(c, t).total;
    }

    static double cultureTerm(Cand c, Terms t) {
        if (!c.cultureKnown) return 1.0;
        boolean bonusSide = t.raiderInvert != c.sameCulture; // normal: same=bonus; raider: foreign=bonus
        if (bonusSide) return 1.0 + t.kCulture * t.strength;
        return Math.max(t.minCultureWeight, 1.0 - t.kCulture * t.strength * t.cultureMismatchPenalty);
    }

    static double relationTerm(Cand c, Terms t) {
        if (c.relation == null) return 1.0;
        double norm = clamp(c.relation / 100.0, -1.0, 1.0);
        double slope = norm >= 0 ? t.relationBiasHigh : t.relationBiasLow;
        double delta = t.strength * slope * norm;
        double term = t.raiderInvert ? 1.0 - delta : 1.0 + delta;
        return Math.max(0.1, term);
    }

    static double populationTerm(Cand c, Terms t) {
        double pop = Math.max(1.0, c.population);
        double normPop = pop / Math.max(1.0, t.populationRef);
        double term = Math.pow(normPop, t.populationBias * t.strength);
        return clamp(term, 1.0 / Math.max(1.0, t.maxPopMultiplier), Math.max(1.0, t.maxPopMultiplier));
    }

    static double recentPenalty(Cand c, Terms t) {
        if (c.recentPos < 0) return 1.0;
        double f = clamp(t.recentDestPenaltyFactor, 0.0, 1.0);
        // Slot 0 is where the unit stands now; the place it came from (slot 1) gets the full penalty.
        double frac = t.recentDestMemory <= 1 ? 0.0
                : clamp((double) (c.recentPos - 1) / (t.recentDestMemory - 1), 0.0, 1.0);
        return f + (1.0 - f) * frac;
    }

    // The farther out past the free radius, the more a candidate that brings the unit closer to home is favoured.
    static double homeTerm(Cand c, Terms t) {
        if (!t.homePull || c.homeDistOrigin <= 0 || c.homeDistCand < 0) return 1.0;
        double span = Math.max(1.0, t.homePullFullRadius - t.homePullFreeRadius);
        double pressure = clamp((c.homeDistOrigin - t.homePullFreeRadius) / span, 0.0, 1.0);
        if (pressure <= 0.0) return 1.0;
        double gain = clamp((c.homeDistOrigin - c.homeDistCand) / c.homeDistOrigin, -1.0, 1.0);
        return Math.max(0.1, 1.0 + t.homePullWeight * t.strength * pressure * gain);
    }

    static double roadTerm(Cand c, Terms t) {
        return c.roadConnected ? 1.0 + t.roadConnectivityBonus * t.strength : 1.0;
    }

    // ---- Corridor friendliness (§4.4) --------------------------------------

    /**
     * Corridor term contribution as a multiplier on a candidate's score, relative to the shortlist's
     * own mean friendliness {@code meanF} so a thin per-culture biome table can't bias every trip the
     * same way. 1.0 == no effect (f at the mean); symmetric about it.
     */
    public static double corridorContribution(double f, double meanF, double weight, double strength) {
        return 1.0 + weight * strength * (f - meanF) * 2.0;
    }

    private static final class CorridorEntry {
        final float score;
        final long tick;

        CorridorEntry(float score, long tick) {
            this.score = score;
            this.tick = tick;
        }
    }

    private static final Map<String, CorridorEntry> CORRIDOR_CACHE =
            Cache.boundedLru(2048);

    static float corridorFriendliness(World world, BlockPos origin, BlockPos dest, String cultureId, long now) {
        String key = world.provider.getDimension() + "|" + (origin.getX() >> 6) + "," + (origin.getZ() >> 6)
                + ">" + (dest.getX() >> 6) + "," + (dest.getZ() >> 6) + "|" + cultureId;
        synchronized (CORRIDOR_CACHE) {
            CorridorEntry e = CORRIDOR_CACHE.get(key);
            if (e != null && now - e.tick < Math.max(1, TravellersConfig.DESTINATIONS.corridorCacheTtlTicks)) {
                return e.score;
            }
        }
        float score = computeCorridorFriendliness(world, origin, dest, cultureId);
        synchronized (CORRIDOR_CACHE) {
            CORRIDOR_CACHE.put(key, new CorridorEntry(score, now));
        }
        return score;
    }

    private static float computeCorridorFriendliness(World world, BlockPos origin, BlockPos dest, String cultureId) {
        WorldServer ws = world instanceof WorldServer ? (WorldServer) world : null;
        TerrainWorldState tws = ws != null ? TerrainWorldState.get(ws) : null;
        TerrainProfileGrid grid = tws != null ? tws.getGrid() : null;

        double sum = 0;
        int n = 0;
        for (int k = 1; k < CORRIDOR_SAMPLES; k++) {
            int x = origin.getX() + (dest.getX() - origin.getX()) * k / CORRIDOR_SAMPLES;
            int z = origin.getZ() + (dest.getZ() - origin.getZ()) * k / CORRIDOR_SAMPLES;
            String biomeName = null;
            if (grid != null && grid.isResidentForWorld(x, z)) {
                short ix = grid.getBiomeIx(x, z);
                if (ix != TerrainProfile.BIOME_IX_UNKNOWN) biomeName = grid.getBiomeIndex().nameFor(ix);
            }
            if (biomeName == null && world != null) {
                Biome b = world.getBiome(new BlockPos(x, 64, z));
                if (b != null && b.getRegistryName() != null) biomeName = b.getRegistryName().toString();
            }
            if (biomeName == null) continue;
            float aff = CultureRegistry.biomeCultureAffinity(biomeName, cultureId);
            if (aff < 0f) continue;
            sum += aff;
            n++;
        }
        return n == 0 ? -1f : (float) (sum / n); // -1 == no biome data; cached and treated as "no vote"
    }

    // ---- Road-connected snapshot -----------------------------------------

    private static final int ROAD_TTL_TICKS = 2400;
    private static final Map<Integer, List<BlockPos>> ROAD_BY_DIM = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Integer, Long> ROAD_STAMP = new java.util.concurrent.ConcurrentHashMap<>();

    private static List<BlockPos> cachedConnectedVillages(WorldServer ws, long now) {
        int dim = ws.provider.getDimension();
        Long stamp = ROAD_STAMP.get(dim);
        List<BlockPos> cached = ROAD_BY_DIM.get(dim);
        if (cached != null && stamp != null && now - stamp < ROAD_TTL_TICKS) return cached;
        List<BlockPos> fresh = MPBIntegration.getConnectedVillages(ws);
        ROAD_BY_DIM.put(dim, fresh);
        ROAD_STAMP.put(dim, now);
        return fresh;
    }

    private static boolean isNearRoadVillage(BlockPos candidate, List<BlockPos> roadVillages, int radius) {
        long r2 = (long) radius * radius;
        for (BlockPos rv : roadVillages) {
            long dx = rv.getX() - candidate.getX();
            long dz = rv.getZ() - candidate.getZ();
            if (dx * dx + dz * dz <= r2) return true;
        }
        return false;
    }

    // ---- Debug breakdown store ------------------------------------------

    private static final Map<UUID, String> LAST_DEBUG = Cache.boundedLru(64);

    static void recordDebug(UUID id, String s) {
        synchronized (LAST_DEBUG) {
            LAST_DEBUG.put(id, s);
        }
    }

    @Nullable
    public static String lastDebug(UUID id) {
        synchronized (LAST_DEBUG) {
            return LAST_DEBUG.get(id);
        }
    }

    // ---- helpers -------------------------------------------------------

    private static final class Scored {
        final BlockPos centre;
        final Cand cand;
        double score;
        float corridor = -1f;
        Breakdown terms;

        Scored(BlockPos centre, Cand cand, double score) {
            this.centre = centre;
            this.cand = cand;
            this.score = score;
        }
    }

    @Nullable
    private static BlockPos weightedPick(List<Scored> scored, Random rng) {
        double sum = 0;
        for (Scored s : scored) if (s.score > 0) sum += s.score;
        if (sum <= 0) return null;
        double r = rng.nextDouble() * sum;
        for (Scored s : scored) {
            if (s.score <= 0) continue;
            r -= s.score;
            if (r < 0) return s.centre;
        }
        return scored.get(scored.size() - 1).centre;
    }

    private static List<BlockPos> centresOf(List<Scored> scored) {
        List<BlockPos> out = new ArrayList<>(scored.size());
        for (Scored s : scored) out.add(s.centre);
        return out;
    }

    @Nullable
    private static BlockPos nearest(List<BlockPos> centres, BlockPos origin) {
        BlockPos best = null;
        long bestD = Long.MAX_VALUE;
        for (BlockPos c : centres) {
            if (c.getX() == origin.getX() && c.getZ() == origin.getZ()) continue;
            long d = distSqXZ(origin, c);
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        return best;
    }

    private static String buildDebug(DestinationContext ctx, Terms t, List<Scored> scored,
                                     String ladder, boolean openConflictRelaxed, double eps) {
        StringBuilder sb = new StringBuilder();
        sb.append("ladder=").append(ladder);
        sb.append(" strength=").append(fmt(t.strength));
        sb.append(" eps=").append(fmt(eps));
        if (openConflictRelaxed) sb.append(" [open-conflict filter relaxed: all candidates hostile]");
        sb.append(" shortlist:");
        for (Scored s : scored) {
            sb.append("\n  ").append(s.centre.getX()).append(',').append(s.centre.getZ())
                    .append(" d=").append((int) s.cand.dist)
                    .append(" cult=").append(s.cand.cultureKnown ? (s.cand.sameCulture ? "same" : "foreign") : "?")
                    .append(" rel=").append(s.cand.relation == null ? "-" : s.cand.relation)
                    .append(" pop=").append(s.cand.population)
                    .append(" road=").append(s.cand.roadConnected ? "y" : "n")
                    .append(" recent=").append(s.cand.recentPos)
                    .append(s.terms != null && s.terms.home != 1.0 ? " home=" + fmt(s.terms.home) : "")
                    .append(s.corridor >= 0 ? " corr=" + fmt(s.corridor) : "")
                    .append(" score=").append(fmt(s.score));
        }
        return sb.toString();
    }

    private static long distSqXZ(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX();
        long dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    /** Small bounded access-order LRU map factory (synchronize on the returned instance). */
    private static final class Cache {
        static <K, V> Map<K, V> boundedLru(int max) {
            return new LinkedHashMap<K, V>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                    return size() > max;
                }
            };
        }
    }

    private static int resolvePopulation(World world, @Nullable WorldServer ws, BlockPos centre) {
        return MillSettlements.population(world, centre);
    }

    /** Clears the per-dimension caches at server stop (two saves can share a dimension id). */
    public static void resetAll() {
        synchronized (CORRIDOR_CACHE) { CORRIDOR_CACHE.clear(); }
        synchronized (LAST_DEBUG) { LAST_DEBUG.clear(); }
        ROAD_BY_DIM.clear();
        ROAD_STAMP.clear();
        MillRelations.clear();
    }

    // ---- Dry-run validation report (/travellersdebug destination report) --------

    public static final class OriginStat {
        public BlockPos origin;
        public String culture;
        public EntityKind kind;
        public int trials, poolCount;
        public double pickSameCultureRate, poolSameCultureFrac;
        public double meanPickDistFrac, meanPoolDistFrac;
        public double meanPickPop, meanPoolPop;
        public double meanPickRelation, meanPoolRelation;
        public double pickRoadRate, poolRoadFrac;
        public double epsilonRate;
        public int ladderNonFull;
    }

    /**
     * Runs the chooser many times against synthetic contexts (a traveller / a raider of each
     * settlement's own culture, choosing from that settlement) and tabulates pick rates vs the
     * candidate-pool base rates - so "the bias actually does something" is measurable without
     * waiting for real legs to complete. Server thread; a one-shot admin command drives it.
     */
    public static List<OriginStat> dryRunReport(World world, BlockPos near, int maxOrigins, int trials) {
        MillSettlements.Snapshot snap = MillSettlements.snapshot(world);
        List<BlockPos> origins = new ArrayList<>(snap.centers);
        origins.sort(Comparator.comparingLong(c -> distSqXZ(near, c)));

        List<OriginStat> out = new ArrayList<>();
        int done = 0;
        for (BlockPos origin : origins) {
            if (done >= maxOrigins) break;
            String culture = snap.cultureAt(origin);
            if (culture == null || culture.isEmpty()) continue;
            done++;
            for (EntityKind kind : new EntityKind[]{EntityKind.TRAVELLER, EntityKind.RAIDER}) {
                out.add(runOriginTrials(world, snap, origin, culture, kind, trials));
            }
        }
        return out;
    }

    private static OriginStat runOriginTrials(World world, MillSettlements.Snapshot snap,
                                              BlockPos origin, String culture, EntityKind kind, int trials) {
        OriginStat st = new OriginStat();
        st.origin = origin;
        st.culture = culture;
        st.kind = kind;
        Random rng = new Random(((long) origin.getX() * 73428767L) ^ ((long) origin.getZ() << 21) ^ kind.ordinal());

        int same = 0, road = 0, relN = 0, eps = 0, ladderNonFull = 0;
        double distFracSum = 0, popSum = 0, relSum = 0;
        int n = 0;
        for (int i = 0; i < trials; i++) {
            DestinationContext ctx = new DestinationContext();
            ctx.originCentre = origin;
            ctx.homeVillage = origin;
            ctx.cultureId = culture;
            ctx.kind = kind;
            ctx.wanderlust = 0.5f;
            ctx.exploreChance = 0f;
            ctx.rng = rng;
            ChosenDestination cd = choose(world, ctx);
            if (cd == null) continue;
            n++;
            if (i == 0 || st.poolCount == 0) {
                st.poolCount = cd.poolCount;
                st.poolSameCultureFrac = cd.poolSameCultureFrac;
                st.meanPoolDistFrac = cd.poolMeanDist / Math.max(1, TravellersConfig.DESTINATIONS.villagePickRadius);
                st.meanPoolPop = cd.poolMeanPop;
                st.meanPoolRelation = cd.poolMeanRelation;
                st.poolRoadFrac = cd.poolRoadFrac;
            }
            if (cd.epsilon) eps++;
            if (!"full".equals(cd.ladder)) ladderNonFull++;
            String destCulture = snap.cultureAt(cd.dest);
            if (destCulture != null && destCulture.equalsIgnoreCase(culture)) same++;
            double dist = Math.sqrt(distSqXZ(origin, cd.dest));
            distFracSum += dist / Math.max(1, TravellersConfig.DESTINATIONS.villagePickRadius);
            popSum += resolvePopulation(world, world instanceof WorldServer ? (WorldServer) world : null, cd.dest);
            Integer rel = MillRelations.getRelation(world, origin, cd.dest, world.getTotalWorldTime());
            if (rel != null) { relSum += rel; relN++; }
            if (!cd.shortlist.isEmpty()) {
                for (Row r : cd.shortlist) if (r.picked && r.roadConnected) { road++; break; }
            }
        }
        st.trials = n;
        st.pickSameCultureRate = n == 0 ? 0 : (double) same / n;
        st.meanPickDistFrac = n == 0 ? 0 : distFracSum / n;
        st.meanPickPop = n == 0 ? 0 : popSum / n;
        st.meanPickRelation = relN == 0 ? 0 : relSum / relN;
        st.pickRoadRate = n == 0 ? 0 : (double) road / n;
        st.epsilonRate = n == 0 ? 0 : (double) eps / n;
        st.ladderNonFull = ladderNonFull;
        return st;
    }
}
