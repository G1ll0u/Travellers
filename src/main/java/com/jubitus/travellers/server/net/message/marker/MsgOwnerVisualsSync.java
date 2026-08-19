package com.jubitus.travellers.server.net.message.marker;

import com.jubitus.travellers.client.marker.ClientOwnerVisualsCache;
import com.jubitus.travellers.client.render.marker.WorkerMarkStyle;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** Server-authoritative push of "your own mark color/style" — sent on login and right after a save, independent of any worker packet. */
public class MsgOwnerVisualsSync implements IMessage {

    private int color;
    private String styleId;

    public MsgOwnerVisualsSync() {
    }

    public MsgOwnerVisualsSync(int color, String styleId) {
        this.color = color & 0xFFFFFF;
        this.styleId = WorkerMarkStyle.byId(styleId).id;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.color = buf.readInt() & 0xFFFFFF;
        this.styleId = WorkerMarkStyle.byId(ByteBufUtils.readUTF8String(buf)).id;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(color & 0xFFFFFF);
        ByteBufUtils.writeUTF8String(buf, styleId);
    }

    public static class Handler implements IMessageHandler<MsgOwnerVisualsSync, IMessage> {
        @Override
        public IMessage onMessage(MsgOwnerVisualsSync msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> ClientOwnerVisualsCache.set(msg.color, msg.styleId));
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgOwnerVisualsSync, IMessage> {
        @Override
        public IMessage onMessage(MsgOwnerVisualsSync msg, MessageContext ctx) {
            return null;
        }
    }
}
