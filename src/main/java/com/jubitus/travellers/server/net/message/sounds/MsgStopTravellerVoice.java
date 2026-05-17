package com.jubitus.travellers.server.net.message.sounds;

import com.jubitus.travellers.client.sound.TravellerVoiceClientBus;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class MsgStopTravellerVoice implements IMessage {
    public int entityId;

    public MsgStopTravellerVoice() {
    }

    public MsgStopTravellerVoice(EntityTraveller e) {
        this.entityId = e.getEntityId();
    }

    @Override
    public void fromBytes(io.netty.buffer.ByteBuf buf) {
        entityId = buf.readInt();
    }

    @Override
    public void toBytes(io.netty.buffer.ByteBuf buf) {
        buf.writeInt(entityId);
    }

    public static class Handler implements IMessageHandler<MsgStopTravellerVoice, IMessage> {
        @Override
        public IMessage onMessage(MsgStopTravellerVoice msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() ->
                    TravellerVoiceClientBus.stopIfAny(msg.entityId));
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgStopTravellerVoice, IMessage> {
        @Override
        public IMessage onMessage(MsgStopTravellerVoice msg, MessageContext ctx) {
            return null;
        }
    }

}
