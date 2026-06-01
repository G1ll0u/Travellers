package com.jubitus.travellers.client.map;

import com.jubitus.travellers.Tags;
import com.jubitus.travellers.client.gui.worldmap.GuiWorldMapPanelHooks;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
@Mod.EventBusSubscriber(modid = Tags.MOD_ID, value = Side.CLIENT)
public class ClientMapCacheEvents {

    @SubscribeEvent
    public static void onClientConnect(net.minecraftforge.fml.common.network.FMLNetworkEvent.ClientConnectedToServerEvent e) {
        ClientMapDiscovery.clear();
        ClientMapTileCache.clear();
        GuiWorldMapPanelHooks.markDirty();
    }

    @SubscribeEvent
    public static void onClientDisconnect(net.minecraftforge.fml.common.network.FMLNetworkEvent.ClientDisconnectionFromServerEvent e) {
        ClientMapDiscovery.clear();
        ClientMapTileCache.clear();
        GuiWorldMapPanelHooks.markDirty();
    }
}
