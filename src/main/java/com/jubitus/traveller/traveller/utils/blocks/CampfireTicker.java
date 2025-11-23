package com.jubitus.traveller.traveller.utils.blocks;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod.EventBusSubscriber
public final class CampfireTicker {
    @SubscribeEvent
    public static void onServerTick(net.minecraftforge.fml.common.gameevent.TickEvent.ServerTickEvent e) {
        if (e.phase != net.minecraftforge.fml.common.gameevent.TickEvent.Phase.END) return;
        MinecraftServer server = net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;

        // Tick *each* dimension’s manager (your manager fetch uses a world instance, so loop worlds)
        for (WorldServer ws : server.worlds) {
            CampfireManager.get(ws).tickAll(server);
        }
    }
}