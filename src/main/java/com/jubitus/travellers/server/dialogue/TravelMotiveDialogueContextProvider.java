package com.jubitus.travellers.server.dialogue;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.culture.TravellerAppearance;
import com.jubitus.travellers.travel.TravelCommon;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.TravelMotive;
import com.jubitus.travellers.world.mill.MillRelations;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Travel motive (identity-memory plan, Part L): a grounded, entity-local, deterministic reason for
 * being on the road - never an LLM call. {@link #rollMotive} is the one-time weighted pick, called
 * from {@link EntityTraveller} at spawn (and lazily on first read for an old save with no motive
 * persisted yet); {@link #contribute} only ever phrases whatever was already rolled, it never
 * re-rolls or overrides it.
 */
public final class TravelMotiveDialogueContextProvider implements DialogueContextProvider {

    @Override
    public void contribute(EntityTraveller traveller, EntityPlayer player, DialogueContext.Builder builder) {
        if (!TravellersConfig.DIALOGUE.travelMotiveEnabled) return;
        TravelMotive motive = traveller.getTravelMotive();
        if (motive == null) return;
        builder.travelMotivePhrase(phraseFor(motive));
    }

    /**
     * Re-derives home village, target village and their relation independently rather than reading
     * another provider's already-built state - same precedent as {@link RecentTravelHistoryDialogueContextProvider}'s
     * 2026-09-22 fix (see CLAUDE.md's dialogue section). {@code target} may still be null this early
     * (no destination chosen yet at spawn time) - that simply reads as "unknown" to the weighting below.
     */
    public static TravelMotive rollMotive(EntityTraveller traveller) {
        World world = traveller.world;
        BlockPos home = traveller.getHomeVillage();
        TravelCommon travelCommon = traveller.getTravelCommon();
        BlockPos target = travelCommon != null ? travelCommon.getTargetVillage() : null;

        Map<TravelMotive, Double> weights = new EnumMap<>(TravelMotive.class);
        for (TravelMotive m : TravelMotive.values()) weights.put(m, 1.0);

        boolean targetIsHome = home != null && target != null
                && home.getX() == target.getX() && home.getZ() == target.getZ();
        if (targetIsHome) {
            multiply(weights, TravelMotive.HOMEWARD_RETURN, 6.0);
        } else if (home != null && target != null) {
            Integer relation = MillRelations.getRelation(world, home, target, world.getTotalWorldTime());
            if (relation != null && relation < -15) {
                // Hostile/tense relation to the destination - bias away from upbeat motives, toward
                // one that explains travelling there despite it (maintainer's call, reads better than
                // just suppressing travel-worthy motives outright).
                multiply(weights, TravelMotive.PILGRIMAGE, 0.3);
                multiply(weights, TravelMotive.FAMILY_VISIT, 0.3);
                multiply(weights, TravelMotive.WANDERLUST, 0.3);
                multiply(weights, TravelMotive.DELIVERING_NEWS, 3.0);
            }
        }

        String jobId = jobIdOf(traveller);
        if (jobId != null) {
            if (jobId.contains("merchant") || jobId.contains("trader")) {
                multiply(weights, TravelMotive.TRADE_RUN, 4.0);
            }
            if (jobId.contains("priest") || jobId.contains("monk") || jobId.contains("pilgrim")
                    || jobId.contains("abbot") || jobId.contains("vicar")) {
                multiply(weights, TravelMotive.PILGRIMAGE, 4.0);
            }
            if (jobId.contains("boy") || jobId.contains("girl") || jobId.contains("apprentice")) {
                multiply(weights, TravelMotive.APPRENTICESHIP, 3.0);
                multiply(weights, TravelMotive.SEEKING_WORK, 2.0);
            }
        }

        return weightedPick(weights, traveller.getRNG());
    }

    private static void multiply(Map<TravelMotive, Double> weights, TravelMotive motive, double factor) {
        weights.merge(motive, factor, (a, b) -> a * b);
    }

    private static String jobIdOf(EntityTraveller traveller) {
        TravellerAppearance appearance = traveller.getActiveAppearance();
        String jobId = appearance == null ? null : appearance.jobId;
        return jobId == null ? null : jobId.toLowerCase(Locale.ROOT);
    }

    private static TravelMotive weightedPick(Map<TravelMotive, Double> weights, Random rand) {
        double total = 0;
        for (double w : weights.values()) total += Math.max(0, w);
        if (total <= 0) return TravelMotive.WANDERLUST;

        double r = rand.nextDouble() * total;
        for (Map.Entry<TravelMotive, Double> e : weights.entrySet()) {
            double w = Math.max(0, e.getValue());
            if (w <= 0) continue;
            r -= w;
            if (r < 0) return e.getKey();
        }
        return TravelMotive.WANDERLUST;
    }

    private static String phraseFor(TravelMotive motive) {
        switch (motive) {
            case TRADE_RUN:
                return "You are on a trade run, carrying goods to sell or trade along the way.";
            case PILGRIMAGE:
                return "You are on a pilgrimage, travelling to visit a place of faith or meaning to you.";
            case FAMILY_VISIT:
                return "You are travelling to visit family.";
            case SEEKING_WORK:
                return "You are looking for work, going wherever there might be some.";
            case HOMEWARD_RETURN:
                return "You are on your way back home.";
            case DELIVERING_NEWS:
                return "You are carrying news that needs delivering.";
            case APPRENTICESHIP:
                return "You are travelling to learn a trade under someone else's guidance.";
            case WANDERLUST:
            default:
                return "You are simply following your own restlessness, with no particular errand in mind.";
        }
    }
}
