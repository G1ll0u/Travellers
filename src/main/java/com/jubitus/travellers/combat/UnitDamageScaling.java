package com.jubitus.travellers.combat;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerRaider;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Configured damage multipliers: units hurt by creatures, players hurt by units. Allied hits are scaled by friendly fire only. */
@Mod.EventBusSubscriber(modid = "travellers")
public final class UnitDamageScaling {

    private UnitDamageScaling() {
    }

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        Entity src = event.getSource().getTrueSource();
        // Environmental damage (fall, fire, drowning) has no source and stays vanilla.
        if (src == null || event.getEntityLiving().world.isRemote) return;
        if (TravellerFriendlyFireHandler.areAllies(src, event.getEntityLiving())) return;

        if (event.getEntityLiving() instanceof EntityTraveller) {
            if (src instanceof EntityPlayer || src instanceof EntityTraveller) return;
            event.setAmount(event.getAmount() * mobDamageScale((EntityTraveller) event.getEntityLiving()));
        } else if (event.getEntityLiving() instanceof EntityPlayer && src instanceof EntityTraveller) {
            event.setAmount(event.getAmount() * TravellersConfig.ATTRIBUTES.travellerPlayerDamageMultiplier);
        }
    }

    private static float mobDamageScale(EntityTraveller unit) {
        if (unit instanceof EntityTravellerRaider) return TravellersConfig.ATTRIBUTES.raiderMobDamageScale;
        if (unit instanceof EntityTravellerWorker) return TravellersConfig.ATTRIBUTES.workerMobDamageScale;
        return TravellersConfig.ATTRIBUTES.travellerMobDamageScale;
    }
}
