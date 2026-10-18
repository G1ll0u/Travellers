package com.jubitus.travellers.worldmap.client;

import com.jubitus.travellers.Tags;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
@Mod.EventBusSubscriber(modid = Tags.MOD_ID, value = Side.CLIENT)
public class ClientMapCacheEvents {

    // These fire on the network thread; the caches and GL textures belong to the client thread.
    @SubscribeEvent
    public static void onClientConnect(FMLNetworkEvent.ClientConnectedToServerEvent e) {
        Minecraft.getMinecraft().addScheduledTask(ClientMapCacheEvents::clearAll);
    }

    @SubscribeEvent
    public static void onClientDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent e) {
        Minecraft.getMinecraft().addScheduledTask(ClientMapCacheEvents::clearAll);
    }

    private static void clearAll() {
        ClientMapDiscovery.clear();
        ClientTileCache.clear();
        ClientTileTextures.clear();
        ClientMapRoads.clear();
    }
}
