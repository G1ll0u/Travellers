package com.jubitus.travellers.entity.entityai.travel.travelcommon;

import com.jubitus.travellers.Tags;
import com.jubitus.travellers.unit.EntityTravellerBoat;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.Entity;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public class TravellerActivationHandler {

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onCanEntityUpdate(net.minecraftforge.event.entity.EntityEvent.CanUpdate event) {
        Entity e = event.getEntity();

        if (e instanceof EntityTravellerWorker
                || e instanceof EntityTravellerBoat) {
            event.setCanUpdate(true);
        }
    }
}

