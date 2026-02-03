package com.jubitus.travellers.utils.net.message;

import com.jubitus.travellers.client.sound.TravellerVoiceClientBus;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

public class MsgPlayTravellerVoice implements IMessage {
    public int entityId;
    public String soundKey; // e.g., "<modid>:entity.travellers.ambient"
    public float vol;
    public float pitch;
    public int duration;

    public MsgPlayTravellerVoice() {
    }

    public MsgPlayTravellerVoice(EntityTraveller e, SoundEvent evt, float vol, float pitch, int durationTicks) {
        this.entityId = e.getEntityId();
        this.soundKey = evt.getRegistryName().toString();
        this.vol = vol;
        this.pitch = pitch;
        this.duration = Math.max(1, durationTicks);
    }


    @Override
    public void fromBytes(io.netty.buffer.ByteBuf buf) {
        entityId = buf.readInt();
        soundKey = net.minecraftforge.fml.common.network.ByteBufUtils.readUTF8String(buf);
        vol = buf.readFloat();
        pitch = buf.readFloat();
        duration = buf.readInt();
    }

    @Override
    public void toBytes(io.netty.buffer.ByteBuf buf) {
        buf.writeInt(entityId);
        net.minecraftforge.fml.common.network.ByteBufUtils.writeUTF8String(buf, soundKey);
        buf.writeFloat(vol);
        buf.writeFloat(pitch);
        buf.writeInt(duration);
    }

    public static class Handler implements IMessageHandler<MsgPlayTravellerVoice, IMessage> {
        @Override
        public IMessage onMessage(MsgPlayTravellerVoice msg, MessageContext ctx) {
            Minecraft mc = Minecraft.getMinecraft();
            mc.addScheduledTask(() -> {
                World w = mc.world;
                if (w == null) return;
                Entity e = w.getEntityByID(msg.entityId);
                if (!(e instanceof EntityTraveller)) return;
                SoundEvent evt = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation(msg.soundKey));
                if (evt == null) return;
                TravellerVoiceClientBus.play((EntityTraveller) e, evt, msg.vol, msg.pitch);
            });

            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgPlayTravellerVoice, IMessage> {
        @Override
        public IMessage onMessage(MsgPlayTravellerVoice msg, MessageContext ctx) {
            return null;
        }
    }

}
