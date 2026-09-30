package com.jubitus.travellers.server.dialogue;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerRaider;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What's immediately visible around the traveller right now — grazing animals, other people, other
 * travellers/workers/raiders — so a face-to-face conversation can naturally reference who or what
 * else is standing there. 2026-09-13 maintainer feedback. Deliberately a plain, unbudgeted AABB scan
 * (see the context-expansion plan's E-5: this runs once per GUI-open, not a hot loop — mirrors every
 * other one-shot scan already used elsewhere in this mod, e.g. {@code TravellerCombatLogic}'s crowd
 * scoring).
 */
public final class NearbyEntitiesDialogueContextProvider implements DialogueContextProvider {

    @Override
    public void contribute(EntityTraveller traveller, EntityPlayer player, DialogueContext.Builder builder) {
        World world = traveller.world;
        double radius = TravellersConfig.DIALOGUE.nearbyEntityRadius;
        AxisAlignedBB box = traveller.getEntityBoundingBox().grow(radius);

        Map<String, Integer> animalCounts = new LinkedHashMap<>();
        for (EntityAnimal animal : world.getEntitiesWithinAABB(EntityAnimal.class, box,
                a -> a != null && a.isEntityAlive())) {
            animalCounts.merge(animal.getName(), 1, Integer::sum);
        }
        int maxTypes = TravellersConfig.DIALOGUE.nearbyAnimalMaxTypes;
        int shown = 0;
        for (Map.Entry<String, Integer> entry : animalCounts.entrySet()) {
            if (shown >= maxTypes) break;
            builder.addNearbyAnimalDescription(
                    DialoguePhrasing.quantityWord(entry.getValue()) + " " + entry.getKey().toLowerCase(Locale.ROOT));
            shown++;
        }

        int otherPlayers = world.getEntitiesWithinAABB(EntityPlayer.class, box,
                p -> p != null && p.isEntityAlive() && !p.isSpectator() && p != player).size();
        builder.nearbyOtherPlayerCount(otherPlayers);

        int ownWorkers = 0;
        int otherTravellers = 0;
        boolean raiderPresent = false;
        for (EntityTraveller other : world.getEntitiesWithinAABB(EntityTraveller.class, box,
                t -> t != null && t.isEntityAlive() && t != traveller)) {
            if (other instanceof EntityTravellerRaider) {
                raiderPresent = true;
            } else if (other instanceof EntityTravellerWorker
                    && player.getUniqueID().equals(((EntityTravellerWorker) other).getFollowOwnerId())) {
                ownWorkers++;
            } else {
                otherTravellers++;
            }
        }
        builder.nearbyOwnWorkerCount(ownWorkers)
                .nearbyOtherTravellerCount(otherTravellers)
                .nearbyRaiderPresent(raiderPresent);
    }
}
