package com.jubitus.travellers.utils.net.message.map;

import com.jubitus.travellers.client.map.ClientMapDiscovery;
import com.jubitus.travellers.client.gui.worldmap.GuiWorldMapPanelHooks;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.Set;

public class MsgMapDiscoverySync implements IMessage {
    // on envoie une liste de longs (tile keys packées)
    public long[] keys;

    public MsgMapDiscoverySync() {}

    public MsgMapDiscoverySync(Set<Long> keysSet) {
        this.keys = new long[keysSet.size()];
        int i = 0;
        for (Long k : keysSet) keys[i++] = k;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int n = buf.readInt();
        keys = new long[n];
        for (int i = 0; i < n; i++) keys[i] = buf.readLong();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(keys.length);
        for (long k : keys) buf.writeLong(k);
    }

    public static class ClientHandler implements IMessageHandler<MsgMapDiscoverySync, IMessage> {
        @Override
        public IMessage onMessage(MsgMapDiscoverySync msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                ClientMapDiscovery.mergeAll(msg.keys);
                GuiWorldMapPanelHooks.markDirty();



            });
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgMapDiscoverySync, IMessage> {
        @Override public IMessage onMessage(MsgMapDiscoverySync msg, MessageContext ctx) { return null; }
    }
}
