package com.jubitus.travellers.travel.destination;

import com.jubitus.travellers.travel.destination.TravelDestinationChooser.Breakdown;
import com.jubitus.travellers.travel.destination.TravelDestinationChooser.Cand;
import com.jubitus.travellers.travel.destination.TravelDestinationChooser.Terms;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-scoring-core tests for Plan B destination choice. No World / Millénaire - only the math the
 * §9 invariants pin down.
 */
class TravelDestinationChooserTest {

    /** Documented defaults from §7 (strength varies per test). */
    private static Terms defaults(double strength) {
        Terms t = new Terms();
        t.strength = strength;
        t.kCulture = 0.6;
        t.cultureMismatchPenalty = 1.0;
        t.minCultureWeight = 0.05;
        t.relationBiasLow = 0.5;
        t.relationBiasHigh = 0.5;
        t.populationBias = 0.5;
        t.populationRef = 15;
        t.maxPopMultiplier = 3.0;
        t.roadConnectivityBonus = 0.35;
        t.recentDestPenaltyFactor = 0.35;
        t.recentDestMemory = 3;
        t.corridorBiomeWeight = 0.4;
        t.raiderInvert = false;
        t.maxRadius = 4000;
        t.wanderlust = 0.5f;
        t.homePull = true;
        t.homePullWeight = 1.0;
        t.homePullFreeRadius = 1200;
        t.homePullFullRadius = 3000;
        return t;
    }

    private static Cand cand(double dist, boolean cultureKnown, boolean sameCulture,
                             Integer relation, int population, boolean road, int recentPos) {
        Cand c = new Cand();
        c.dist = dist;
        c.cultureKnown = cultureKnown;
        c.sameCulture = sameCulture;
        c.relation = relation;
        c.population = population;
        c.roadConnected = road;
        c.recentPos = recentPos;
        return c;
    }

    // ---- distance banding --------------------------------------------------

    @Test
    void distanceBandWeightDecaysWithDistance() {
        for (float wl : new float[]{0f, 0.5f, 1f}) {
            double near = TravelDestinationChooser.distanceBandWeight(200, 4000, wl);
            double mid = TravelDestinationChooser.distanceBandWeight(1000, 4000, wl);
            double far = TravelDestinationChooser.distanceBandWeight(3000, 4000, wl);
            assertTrue(near > mid && mid > far, "monotone decreasing at wl=" + wl);
        }
    }

    @Test
    void higherWanderlustFlattensTheDecay() {
        double lowWl = TravelDestinationChooser.distanceBandWeight(2000, 4000, 0f);
        double highWl = TravelDestinationChooser.distanceBandWeight(2000, 4000, 1f);
        assertTrue(highWl > lowWl, "a far village is relatively more attractive to a wanderer");
    }

    // ---- §9: distance stays the dominant term ----------------------------

    @Test
    void nearNeutralVillageBeatsFarMaxedVillage() {
        for (float wl : new float[]{0f, 0.5f, 1f}) {
            Terms t = defaults(1.0); // strength maxed
            t.wanderlust = wl;
            // far: same culture, relation +100, large (3x ref), on the road
            double far = TravelDestinationChooser.prefilterScore(
                    cand(1800, true, true, 100, 45, true, -1), t);
            // near: same culture, neutral relation, medium (== ref), off the road
            double near = TravelDestinationChooser.prefilterScore(
                    cand(300, true, true, null, 15, false, -1), t);
            assertTrue(near > far,
                    "near neutral village must outscore far maxed village (wl=" + wl
                            + " near=" + near + " far=" + far + ")");
        }
    }

    // ---- culture term ---------------------------------------------------

    @Test
    void sameCultureIsPreferredAndForeignIsFlooredNotZeroed() {
        Terms t = defaults(1.0);
        double same = TravelDestinationChooser.cultureTerm(cand(0, true, true, null, 0, false, -1), t);
        double foreign = TravelDestinationChooser.cultureTerm(cand(0, true, false, null, 0, false, -1), t);
        assertTrue(same > 1.0 && foreign < 1.0);
        assertTrue(foreign >= t.minCultureWeight, "an isolated culture still has reachable destinations");
    }

    @Test
    void raiderInversionFlipsCultureAndRelation() {
        Terms t = defaults(0.6);
        t.raiderInvert = true;
        double foreignBonus = TravelDestinationChooser.cultureTerm(cand(0, true, false, null, 0, false, -1), t);
        double samePenalty = TravelDestinationChooser.cultureTerm(cand(0, true, true, null, 0, false, -1), t);
        assertTrue(foreignBonus > 1.0 && samePenalty < 1.0, "raiders prefer foreign culture");

        double hostileBonus = TravelDestinationChooser.relationTerm(cand(0, true, false, -80, 0, false, -1), t);
        double friendlyPenalty = TravelDestinationChooser.relationTerm(cand(0, true, false, 80, 0, false, -1), t);
        assertTrue(hostileBonus > 1.0 && friendlyPenalty < 1.0, "raiders prefer low-relation targets");
    }

    @Test
    void cultureTermNeutralWhenCultureUnknown() {
        Terms t = defaults(1.0);
        assertEquals(1.0,
                TravelDestinationChooser.cultureTerm(cand(0, false, false, null, 0, false, -1), t));
    }

    // ---- relation term -----------------------------------------------

    @Test
    void relationTermTracksRelationSign() {
        Terms t = defaults(0.6);
        assertEquals(1.0, TravelDestinationChooser.relationTerm(cand(0, true, true, null, 0, false, -1), t),
                "null relation is neutral");
        assertTrue(TravelDestinationChooser.relationTerm(cand(0, true, true, 100, 0, false, -1), t) > 1.0);
        assertTrue(TravelDestinationChooser.relationTerm(cand(0, true, true, -100, 0, false, -1), t) < 1.0);
    }

    // ---- population term -------------------------------------------

    @Test
    void populationTermRewardsSizeAndIsCapped() {
        Terms t = defaults(1.0);
        double small = TravelDestinationChooser.populationTerm(cand(0, true, true, null, 3, false, -1), t);
        double ref = TravelDestinationChooser.populationTerm(cand(0, true, true, null, 15, false, -1), t);
        double huge = TravelDestinationChooser.populationTerm(cand(0, true, true, null, 100000, false, -1), t);
        assertTrue(small < ref && ref <= huge);
        assertTrue(huge <= t.maxPopMultiplier + 1e-9, "capped so a capital can't vacuum the map");
        assertEquals(1.0, ref, 1e-9, "a ref-sized village scores neutral");
    }

    // ---- anti ping-pong -------------------------------------------

    @Test
    void recentDestinationsArePenalisedAndDecayWithAge() {
        Terms t = defaults(0.6);
        double notRecent = TravelDestinationChooser.recentPenalty(cand(0, true, true, null, 0, false, -1), t);
        double justVisited = TravelDestinationChooser.recentPenalty(cand(0, true, true, null, 0, false, 0), t);
        double older = TravelDestinationChooser.recentPenalty(cand(0, true, true, null, 0, false, 2), t);
        assertEquals(1.0, notRecent);
        assertEquals(t.recentDestPenaltyFactor, justVisited, 1e-9);
        double cameFrom = TravelDestinationChooser.recentPenalty(cand(0, true, true, null, 0, false, 1), t);
        assertEquals(t.recentDestPenaltyFactor, cameFrom, 1e-9);
        assertTrue(older > justVisited && older <= 1.0, "the oldest slot is almost un-penalised");
    }

    // ---- home pull ---------------------------------------------

    private static Cand homeCand(double dist, double homeDistOrigin, double homeDistCand) {
        Cand c = cand(dist, false, false, null, 0, false, -1);
        c.homeDistOrigin = homeDistOrigin;
        c.homeDistCand = homeDistCand;
        return c;
    }

    @Test
    void noHomePullWithinTheFreeRadius() {
        Terms t = defaults(1.0);
        assertEquals(1.0, TravelDestinationChooser.homeTerm(homeCand(500, 1000, 0), t));
    }

    @Test
    void farFromHomeFavoursHomewardCandidates() {
        Terms t = defaults(0.6);
        double homeward = TravelDestinationChooser.homeTerm(homeCand(800, 3000, 2200), t);
        double outward = TravelDestinationChooser.homeTerm(homeCand(800, 3000, 3800), t);
        assertTrue(homeward > 1.0 && outward < 1.0);
    }

    @Test
    void raidersFeelNoHomePull() {
        Terms t = defaults(1.0);
        t.homePull = false;
        assertEquals(1.0, TravelDestinationChooser.homeTerm(homeCand(800, 5000, 0), t));
    }

    @Test
    void homePullStaysSubDominantToDistance() {
        for (float wl : new float[]{0f, 0.5f, 1f}) {
            Terms t = defaults(1.0);
            t.wanderlust = wl;
            double farHome = TravelDestinationChooser.prefilterScore(homeCand(1800, 5000, 3200), t);
            double nearAway = TravelDestinationChooser.prefilterScore(homeCand(300, 5000, 5300), t);
            assertTrue(nearAway > farHome, "wl=" + wl + " near=" + nearAway + " far=" + farHome);
        }
    }

    // ---- road connectivity ------------------------------------

    @Test
    void roadConnectedVillagesGetABonus() {
        Terms t = defaults(0.6);
        double off = TravelDestinationChooser.roadTerm(cand(0, true, true, null, 0, false, -1), t);
        double on = TravelDestinationChooser.roadTerm(cand(0, true, true, null, 0, true, -1), t);
        assertEquals(1.0, off);
        assertTrue(on > 1.0);
    }

    @Test
    void breakdownProductEqualsScoreAndExposesEveryTerm() {
        Terms t = defaults(0.6);
        Cand c = cand(700, true, false, -40, 30, true, 1);
        Breakdown b = TravelDestinationChooser.prefilterBreakdown(c, t);
        assertEquals(b.distance * b.culture * b.relation * b.population * b.recent * b.road * b.home, b.total, 1e-12);
        assertEquals(b.total, TravelDestinationChooser.prefilterScore(c, t), 1e-12);
    }

    // ---- corridor contribution (relative to shortlist mean) --------------

    @Test
    void corridorContributionIsNeutralAtTheShortlistMean() {
        assertEquals(1.0, TravelDestinationChooser.corridorContribution(0.25, 0.25, 0.4, 1.0), 1e-12,
                "a trip at the shortlist average neither helps nor hurts");
    }

    @Test
    void corridorContributionRewardsAboveMeanAndPenalisesBelow() {
        double above = TravelDestinationChooser.corridorContribution(0.5, 0.3, 0.4, 1.0);
        double below = TravelDestinationChooser.corridorContribution(0.1, 0.3, 0.4, 1.0);
        assertTrue(above > 1.0, "a friendlier-than-average corridor is a bonus");
        assertTrue(below < 1.0, "a less-friendly-than-average corridor is a malus");
    }

    @Test
    void corridorContributionIsSymmetricAboutTheMean() {
        double mean = 0.3, d = 0.15;
        double above = TravelDestinationChooser.corridorContribution(mean + d, mean, 0.4, 0.7);
        double below = TravelDestinationChooser.corridorContribution(mean - d, mean, 0.4, 0.7);
        assertEquals(2.0, above + below, 1e-12, "equal and opposite departures average to neutral");
    }

    @Test
    void strengthZeroDisablesEveryRegionalBias() {
        Terms t = defaults(0.0);
        // Same distance, same (empty) recent ring: only the regional axes differ.
        Cand hostileForeignTiny = cand(500, true, false, -100, 1, false, -1);
        Cand friendlySameHuge = cand(500, true, true, 100, 50, true, -1);
        assertEquals(TravelDestinationChooser.prefilterScore(hostileForeignTiny, t),
                TravelDestinationChooser.prefilterScore(friendlySameHuge, t), 1e-9,
                "regionalizationStrength = 0 restores pure distance behaviour");
    }
}
