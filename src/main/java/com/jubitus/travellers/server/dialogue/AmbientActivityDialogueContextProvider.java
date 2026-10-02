package com.jubitus.travellers.server.dialogue;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.entityai.travel.travelcommon.TravelCommon;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.world.mill.MillRelations;
import com.jubitus.travellers.world.mill.MillSettlements;
import com.jubitus.travellers.world.pathing.terrain.BiomeTerrainEstimator;
import com.jubitus.travellers.world.pathing.terrain.BiomeWaterClass;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Phase 1 context provider: identity (name/culture/personality), current activity (with the
 * destination/current village's own culture, size and standing with home — added in the
 * context-expansion plan following 2026-09-11 in-world feedback: a traveller couldn't say what
 * culture its own destination was), and nearby settlements. Deliberately reuses the exact same
 * signals {@code EntityTraveller.processInteract()} already reads today
 * ({@link EntityTraveller#isFollowingSomeone()}, {@link TravelCommon#isVillageIdling()},
 * {@link TravelCommon#getTargetVillage()}) rather than inventing a second way to ask "what is this
 * traveller doing right now" — see {@code announceHeadingTo} in that class for the same resolution
 * idiom applied to the destination village's name.
 */
public final class AmbientActivityDialogueContextProvider implements DialogueContextProvider {

    @Override
    public void contribute(EntityTraveller traveller, EntityPlayer player, DialogueContext.Builder builder) {
        builder.travellerDisplayName(traveller.getName())
                .cultureId(traveller.getCultureId())
                .personalityId(traveller.getVoicePersonalityId());

        World world = traveller.world;
        TravelCommon travelCommon = traveller.getTravelCommon();
        BlockPos home = traveller.getHomeVillage();

        BlockPos activityVillagePos = null;
        boolean travellingToVillage = false;
        if (travelCommon != null && travelCommon.isVillageIdling()) {
            builder.activity(DialogueContext.ActivityKind.IDLING_IN_VILLAGE);
            activityVillagePos = travelCommon.getNearestVillage();
        } else {
            BlockPos target = travelCommon != null ? travelCommon.getTargetVillage() : null;
            if (target != null) {
                builder.activity(DialogueContext.ActivityKind.TRAVELLING_TO_VILLAGE);
                activityVillagePos = target;
                travellingToVillage = true;
            } else {
                builder.activity(DialogueContext.ActivityKind.ROAMING);
            }
        }

        if (activityVillagePos != null) {
            MillSettlements.Settlement vi = MillSettlements.resolve(world, activityVillagePos);
            if (vi != null && !vi.name.isEmpty()) {
                builder.activityVillageName(vi.name)
                        .activityVillageCultureId(vi.cultureKey == null || vi.cultureKey.isEmpty() ? null : vi.cultureKey)
                        .activityVillageIsLoneBuilding(!vi.isVillage);
                if (vi.isVillage) {
                    builder.activityVillagePopulationTier(
                            DialoguePhrasing.populationTier(vi.population(world)));
                }
                if (home != null) {
                    builder.activityVillageRelationToHome(DialoguePhrasing.relationTier(
                            MillRelations.getRelation(world, home, vi.center, world.getTotalWorldTime())));
                }
                // Only worth stating while actually travelling there - a village already reached
                // (IDLING_IN_VILLAGE) has no meaningful distance left to mention.
                if (travellingToVillage) {
                    double dist = Math.sqrt(squaredPlanarDistance(vi.center, traveller.getPosition()));
                    builder.activityVillageDistanceTier(DialoguePhrasing.villageDistanceTier(dist, !vi.isVillage));
                }
            }
        }

        for (DialogueContext.NearbySettlementBlurb blurb : findNearbySettlements(traveller)) {
            builder.addNearbySettlement(blurb);
        }

        contributePhysicalLocation(traveller, builder);
    }

    /**
     * Physical proximity to a settlement, computed independently of travel-AI activity/destination
     * (2026-09-14 feedback: a traveller ROAMING or TRAVELLING_TO_VILLAGE toward somewhere else still
     * needs to know it's currently standing inside or right next to a *different* village than the one
     * it's heading to - {@code activityVillage*} above only ever names the destination/idling target).
     */
    private static void contributePhysicalLocation(EntityTraveller traveller, DialogueContext.Builder builder) {
        World world = traveller.world;
        BlockPos self = traveller.getPosition();
        BlockPos home = traveller.getHomeVillage();
        int adjacentRadius = TravellersConfig.DIALOGUE.villageAdjacentRadius;

        MillSettlements.Settlement vi = MillSettlements.nearest(world, self, adjacentRadius);
        if (vi == null) return;

        double dist = Math.sqrt(squaredPlanarDistance(vi.center, self));
        DialogueContext.NearbySettlementBlurb blurb = new DialogueContext.NearbySettlementBlurb(
                vi.name,
                vi.cultureKey == null ? "" : vi.cultureKey,
                bucket(vi.center.getX() - self.getX()),
                bucket(vi.center.getZ() - self.getZ()),
                vi.isVillage ? DialoguePhrasing.populationTier(vi.population(world)) : null,
                home == null ? null : DialoguePhrasing.relationTier(
                        MillRelations.getRelation(world, home, vi.center, world.getTotalWorldTime())),
                DialoguePhrasing.villageDistanceTier(dist, !vi.isVillage),
                crossesSea(world, self, vi.center));

        // A lone building's actual footprint is much smaller than a full village's - the same 120-block
        // "inside" radius used for a village would claim a traveller is standing inside a lone building
        // from 100 blocks away (2026-09-14 feedback).
        int insideRadius = vi.isVillage
                ? TravellersConfig.DIALOGUE.villageInsideRadius
                : TravellersConfig.DIALOGUE.loneBuildingInsideRadius;
        if (dist <= insideRadius) {
            builder.currentLocationVillage(blurb);
        } else {
            builder.adjacentVillage(blurb);
        }
    }

    private static List<DialogueContext.NearbySettlementBlurb> findNearbySettlements(EntityTraveller traveller) {
        World world = traveller.world;
        int radius = TravellersConfig.DIALOGUE.nearbySettlementRadius;
        int maxCount = TravellersConfig.DIALOGUE.nearbySettlementMaxCount;
        List<DialogueContext.NearbySettlementBlurb> result = new ArrayList<>();
        if (maxCount <= 0) return result;

        BlockPos self = traveller.getPosition();
        BlockPos home = traveller.getHomeVillage();
        long radiusSq = (long) radius * (long) radius;

        List<MillSettlements.Settlement> candidates = new ArrayList<>();
        for (MillSettlements.Settlement s : MillSettlements.all(world)) {
            long distSq = squaredPlanarDistance(s.center, self);
            if (distSq > 0 && distSq <= radiusSq) {
                candidates.add(s);
            }
        }
        candidates.sort(Comparator.comparingLong(s ->
                squaredPlanarDistance(s.center, self)));

        // Millénaire's procedural name pool can hand two unrelated settlements the same name - shown
        // twice under one name reads as a duplication bug to a player skimming the debug log, and
        // isn't useful context for the model either. Keep only the nearest of each name.
        Set<String> namesSeen = new HashSet<>();
        for (MillSettlements.Settlement vi : candidates) {
            if (result.size() >= maxCount) break;
            BlockPos centre = vi.center;
            if (vi.name.isEmpty()) continue;
            if (!namesSeen.add(vi.name)) continue;
            double dist = Math.sqrt(squaredPlanarDistance(centre, self));
            result.add(new DialogueContext.NearbySettlementBlurb(
                    vi.name,
                    vi.cultureKey == null ? "" : vi.cultureKey,
                    bucket(centre.getX() - self.getX()),
                    bucket(centre.getZ() - self.getZ()),
                    // A lone building isn't a "village" of any size - never give it a population tier.
                    vi.isVillage ? DialoguePhrasing.populationTier(vi.population(world)) : null,
                    home == null ? null : DialoguePhrasing.relationTier(
                            MillRelations.getRelation(world, home, centre, world.getTotalWorldTime())),
                    DialoguePhrasing.villageDistanceTier(dist, !vi.isVillage),
                    crossesSea(world, self, centre)
            ));
        }
        return result;
    }

    /**
     * Samples the straight line to a settlement for open sea (2026-09-17 feedback, same root cause as
     * {@link TerrainLandmarkScanner}: a bearing is a straight-line fact that silently implies a
     * walkable route). Fixed sample count regardless of distance - cheap, biome-only, no chunk load.
     * Requires two consecutive ocean samples so a single coastal-noise sample can't false-positive;
     * rivers are deliberately not checked - fords/bridges/boats make a river crossing an ordinary part
     * of any journey, not worth a caveat.
     */
    private static boolean crossesSea(World world, BlockPos from, BlockPos to) {
        int samples = 16;
        int consecutiveOcean = 0;
        for (int i = 1; i < samples; i++) {
            double t = i / (double) samples;
            int wx = (int) Math.round(from.getX() + (to.getX() - from.getX()) * t);
            int wz = (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * t);
            Biome b = world.getBiome(new BlockPos(wx, from.getY(), wz));
            if (b != null && BiomeTerrainEstimator.classify(b) == BiomeWaterClass.OCEAN) {
                if (++consecutiveOcean >= 2) return true;
            } else {
                consecutiveOcean = 0;
            }
        }
        return false;
    }

    private static long squaredPlanarDistance(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX();
        long dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    /** Rounds an offset to the nearest 100 blocks - a direction/rough-distance hint, never an exact figure (D-8). */
    private static int bucket(int offset) {
        int bucketSize = 100;
        return Math.round(offset / (float) bucketSize) * bucketSize;
    }
}
