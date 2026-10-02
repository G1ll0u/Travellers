package com.jubitus.travellers.combat;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Scales damage dealt by any EntityTraveller (traveller/raider/worker) to a player, keyed off the
 * entity actually hit rather than the AI's intended target. Arrow damage in particular is baked
 * into the arrow at shot time against the intended target, which can differ from whoever the
 * arrow ends up hitting (e.g. the owner standing in the shot line) — doing the scaling here
 * instead guarantees it always matches the real victim.
 */
@Mod.EventBusSubscriber(modid = "travellers")
public final class TravellerFriendlyFireHandler {

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntityLiving() instanceof EntityPlayer)) return;
        EntityPlayer victim = (EntityPlayer) event.getEntityLiving();

        Entity src = event.getSource().getTrueSource();
        if (!(src instanceof EntityTraveller)) return;
        EntityTraveller attacker = (EntityTraveller) src;

        float multiplier;
        if (attacker instanceof EntityTravellerWorker && ((EntityTravellerWorker) attacker).isOwner(victim)) {
            multiplier = TravellersConfig.ATTRIBUTES.workerOwnerDamageMultiplier;
        } else {
            multiplier = TravellersConfig.ATTRIBUTES.travellerPlayerDamageMultiplier;
        }

        event.setAmount(event.getAmount() * multiplier);
    }
}
