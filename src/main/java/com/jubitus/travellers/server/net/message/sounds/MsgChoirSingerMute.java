package com.jubitus.travellers.server.net.message.sounds;

import com.jubitus.travellers.client.sound.TravellerVoiceClientBus;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public class MsgChoirSingerMute implements IMessage {
    public int entityId;
    public int durationMs;

    public MsgChoirSingerMute() {}

    public MsgChoirSingerMute(int entityId, int durationMs) {
        this.entityId = entityId;
        this.durationMs = durationMs;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        entityId = buf.readInt();
        durationMs = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(entityId);
        buf.writeInt(durationMs);
    }

    @SideOnly(Side.CLIENT)
    public static class Handler implements IMessageHandler<MsgChoirSingerMute, IMessage> {
        @Override
        public IMessage onMessage(MsgChoirSingerMute msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() ->
                    TravellerVoiceClientBus.applyMute(msg.entityId, msg.durationMs));
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgChoirSingerMute, IMessage> {
        @Override
        public IMessage onMessage(MsgChoirSingerMute msg, MessageContext ctx) { return null; }
    }
}
