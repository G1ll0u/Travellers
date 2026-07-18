package com.jubitus.travellers.entity.entityAI.choir;

import com.jubitus.travellers.Tags;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class ChoirWorldTicker {

    private ChoirWorldTicker() {}

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.world.isRemote) return;
        long now = event.world.getTotalWorldTime();
        // Session management (rejoin, spread checks) runs every second.
        // Auto-choir throttling is handled inside the coordinator.
        if (now % 20 != 0) return;
        ChoirCoordinator.get(event.world).tick(event.world);
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        if (!event.getWorld().isRemote) {
            ChoirCoordinator.remove(event.getWorld().provider.getDimension());
        }
    }
}
