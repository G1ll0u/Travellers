package com.jubitus.traveller.traveller.utils.sound;

import com.jubitus.traveller.traveller.entityAI.EntityTraveller;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

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
        @SideOnly(Side.CLIENT)
        public IMessage onMessage(MsgStopTravellerVoice msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() ->
                    TravellerVoiceClientBus.stopIfAny(msg.entityId));
            return null;
        }
    }
}
