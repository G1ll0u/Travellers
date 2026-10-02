package com.jubitus.travellers.worldmap.net;

import com.jubitus.travellers.worldmap.gui.GuiTravellerPopulationMap;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** Server -> client: tells the requesting player's client to open the population world map GUI. */
public class MsgOpenPopulationMap implements IMessage {

    public MsgOpenPopulationMap() {}

    @Override
    public void fromBytes(ByteBuf buf) {}

    @Override
    public void toBytes(ByteBuf buf) {}

    public static class Handler implements IMessageHandler<MsgOpenPopulationMap, IMessage> {
        @Override
        public IMessage onMessage(MsgOpenPopulationMap msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() ->
                    Minecraft.getMinecraft().displayGuiScreen(
                            new GuiTravellerPopulationMap(Minecraft.getMinecraft().player)));
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgOpenPopulationMap, IMessage> {
        @Override public IMessage onMessage(MsgOpenPopulationMap msg, MessageContext ctx) { return null; }
    }
}
