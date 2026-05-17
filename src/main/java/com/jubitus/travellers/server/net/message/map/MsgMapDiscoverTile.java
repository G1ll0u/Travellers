package com.jubitus.travellers.server.net.message.map;

import com.jubitus.travellers.client.map.ClientMapDiscovery;
import com.jubitus.travellers.client.gui.worldmap.GuiWorldMapPanelHooks;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class MsgMapDiscoverTile implements IMessage {
    public int tileX;
    public int tileZ;

    public MsgMapDiscoverTile() {}
    public MsgMapDiscoverTile(int tileX, int tileZ) {
        this.tileX = tileX;
        this.tileZ = tileZ;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        tileX = buf.readInt();
        tileZ = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(tileX);
        buf.writeInt(tileZ);
    }

    // côté client
    public static class ClientHandler implements IMessageHandler<MsgMapDiscoverTile, IMessage> {
        @Override
        public IMessage onMessage(MsgMapDiscoverTile msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                ClientMapDiscovery.addTile(msg.tileX, msg.tileZ);
                GuiWorldMapPanelHooks.markDirty(); // <-- AJOUTE ÇA

            });
            return null;
        }
    }


    // dédié serveur : noop
    public static class NoopHandler implements IMessageHandler<MsgMapDiscoverTile, IMessage> {
        @Override public IMessage onMessage(MsgMapDiscoverTile msg, MessageContext ctx) { return null; }
    }
}
