package com.jubitus.travellers.utils.world.pathing.chunkmanager;

import com.jubitus.travellers.Reference;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerBoat;
import net.minecraft.entity.Entity;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod.EventBusSubscriber(modid = Reference.MOD_ID)
public class TravellerTrackingHandler {

    @SubscribeEvent
    public static void onStartTracking(net.minecraftforge.event.entity.player.PlayerEvent.StartTracking e) {
        Entity target = e.getTarget();
        if (target instanceof EntityTraveller) {
            ((EntityTraveller) target).incTracking();
        }
        if (target instanceof EntityTravellerBoat) ((EntityTravellerBoat) target).incTracking();

    }

    @SubscribeEvent
    public static void onStopTracking(net.minecraftforge.event.entity.player.PlayerEvent.StopTracking e) {
        Entity target = e.getTarget();
        if (target instanceof EntityTraveller) {
            ((EntityTraveller) target).decTracking();
        }
        if (target instanceof EntityTravellerBoat) ((EntityTravellerBoat) target).decTracking();
    }
}
