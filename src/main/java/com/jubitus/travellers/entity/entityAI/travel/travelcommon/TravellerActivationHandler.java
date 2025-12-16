package com.jubitus.travellers.entity.entityAI.travel.travelcommon;

import com.jubitus.travellers.Reference;
import com.jubitus.travellers.entity.travellers.EntityTravellerMerchant;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.entity.Entity;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod.EventBusSubscriber(modid = Reference.MOD_ID)
public class TravellerActivationHandler {

    @SubscribeEvent(
            priority = EventPriority.LOWEST,     // run AFTER all other handlers
            receiveCanceled = true               // still receive event even if someone cancelled
    )
    public static void onCanEntityUpdate(net.minecraftforge.event.entity.EntityEvent.CanUpdate event) {

        Entity e = event.getEntity();

        if (e instanceof EntityTravellerWorker || e instanceof EntityTravellerMerchant) {

            // Debug: see if someone tried to disable ticking
            if (!event.getCanUpdate()) {
                System.out.println("[ACTIVATION] Forcing CanUpdate=true for "
                        + e.getName() + " at " + e.getPosition());
            }

            // Force them to always tick
            event.setCanUpdate(true);
        }
    }
}
