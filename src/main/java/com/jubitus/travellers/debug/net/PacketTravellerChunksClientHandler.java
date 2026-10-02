package com.jubitus.travellers.debug.net;

import com.jubitus.travellers.debug.gui.GuiTravellerChunks;
import com.jubitus.travellers.debug.TravellerChunkSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

// com.jubitus.travellers.server.chunks.message.net.PacketTravellerChunksClientHandler
@SideOnly(Side.CLIENT)
public class PacketTravellerChunksClientHandler
        implements IMessageHandler<PacketTravellerChunks, IMessage> {

    @Override
    public IMessage onMessage(PacketTravellerChunks message, MessageContext ctx) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            TravellerChunkSnapshot snap = message.getSnapshot();

            Minecraft mc = Minecraft.getMinecraft();
            GuiScreen cur = mc.currentScreen;

            // Optional: if already open, just update it
            if (cur instanceof GuiTravellerChunks) {
                ((GuiTravellerChunks) cur).updateSnapshot(snap);
            } else {
                mc.displayGuiScreen(new GuiTravellerChunks(snap));
            }
        });
        return null;
    }
}
