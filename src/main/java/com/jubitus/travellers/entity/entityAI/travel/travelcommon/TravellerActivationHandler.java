package com.jubitus.travellers.entity.entityAI.travel.travelcommon;

import com.jubitus.travellers.Reference;
import com.jubitus.travellers.entity.travellers.others.EntityTravellerBoat;
import com.jubitus.travellers.entity.travellers.EntityTravellerMerchant;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.entity.Entity;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod.EventBusSubscriber(modid = Reference.MOD_ID)
public class TravellerActivationHandler {

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onCanEntityUpdate(net.minecraftforge.event.entity.EntityEvent.CanUpdate event) {
        Entity e = event.getEntity();

        if (e instanceof EntityTravellerWorker
                || e instanceof EntityTravellerMerchant
                || e instanceof EntityTravellerBoat) {   // ✅ add this
            event.setCanUpdate(true);
        }
    }
}

