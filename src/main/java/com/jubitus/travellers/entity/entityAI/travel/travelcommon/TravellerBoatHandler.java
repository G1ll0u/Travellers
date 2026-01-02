package com.jubitus.travellers.entity.entityAI.travel.travelcommon;

import com.jubitus.travellers.Reference;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerMerchant;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityBoat;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod.EventBusSubscriber(modid = Reference.MOD_ID)
public class TravellerBoatHandler {

    @SubscribeEvent
    public static void onMount(net.minecraftforge.event.entity.EntityMountEvent event) {
        Entity rider = event.getEntityMounting();
        Entity mount = event.getEntityBeingMounted();

        if (!(rider instanceof EntityTravellerWorker || rider instanceof EntityTravellerMerchant)) return;
        if (!(mount instanceof EntityBoat)) return;

        // Only block when offscreen
        EntityTraveller t = (EntityTraveller) rider;
        if (!t.isInPlayerViewDistance()) {
            event.setCanceled(true);
        }
    }
}
