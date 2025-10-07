package com.jubitus.traveller.traveller.utils.sound;

import com.jubitus.traveller.traveller.entityAI.EntityTraveller;
import com.jubitus.traveller.traveller.sound.MsgStartTravellerChorus;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public class MsgPlayTravellerVoice implements net.minecraftforge.fml.common.network.simpleimpl.IMessage {
    public int entityId;
    public String soundKey; // e.g., "<modid>:entity.traveller.ambient"
    public float vol;
    public float pitch;
    public int duration;

    public MsgPlayTravellerVoice() {}
    public MsgPlayTravellerVoice(EntityTraveller e, SoundEvent evt, float vol, float pitch) {
        this.entityId = e.getEntityId();
        this.soundKey = evt.getRegistryName().toString();
        this.vol = vol; this.pitch = pitch;
    }

    @Override public void toBytes(io.netty.buffer.ByteBuf buf) {
        buf.writeInt(entityId);
        net.minecraftforge.fml.common.network.ByteBufUtils.writeUTF8String(buf, soundKey);
        buf.writeFloat(vol); buf.writeFloat(pitch); buf.writeInt(duration);
    }
    @Override public void fromBytes(io.netty.buffer.ByteBuf buf) {
        entityId = buf.readInt();
        soundKey = net.minecraftforge.fml.common.network.ByteBufUtils.readUTF8String(buf);
        vol = buf.readFloat(); pitch = buf.readFloat(); duration = buf.readInt();
    }

    public static class Handler implements net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler<MsgPlayTravellerVoice, IMessage> {
        @Override @SideOnly(Side.CLIENT)
        public IMessage onMessage(MsgPlayTravellerVoice msg, net.minecraftforge.fml.common.network.simpleimpl.MessageContext ctx) {
            Minecraft mc = Minecraft.getMinecraft();
            mc.addScheduledTask(() -> {
                World w = mc.world; if (w == null) return;
                Entity e = w.getEntityByID(msg.entityId);
                if (!(e instanceof EntityTraveller)) return;
                SoundEvent evt = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation(msg.soundKey));
                if (evt == null) return;
                TravellerVoiceClientBus.play((EntityTraveller) e, evt, msg.vol, msg.pitch);
            });

            return null;
        }
    }
    public static class NoopServer implements IMessageHandler<MsgStartTravellerChorus, IMessage> {
        @Override public IMessage onMessage(MsgStartTravellerChorus msg, MessageContext ctx) { return null; }
    }

}
