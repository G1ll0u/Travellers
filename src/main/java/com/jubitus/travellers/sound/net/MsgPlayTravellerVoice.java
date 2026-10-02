package com.jubitus.travellers.sound.net;

import com.jubitus.travellers.sound.client.EntityLinkedSound;
import com.jubitus.travellers.sound.client.TravellerVoiceClientBus;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class MsgPlayTravellerVoice implements IMessage {
    public int entityId;
    public String soundKey; // e.g., "<modid>:entity.travellers.ambient"
    public float vol;
    public float pitch;
    public int duration;
    public double x, y, z; // fallback position when entity is no longer in world (e.g. death sound)

    public MsgPlayTravellerVoice() {
    }

    public MsgPlayTravellerVoice(EntityTraveller e, SoundEvent evt, float vol, float pitch, int durationTicks) {
        this.entityId = e.getEntityId();
        this.soundKey = evt.getRegistryName().toString();
        this.vol = vol;
        this.pitch = pitch;
        this.duration = Math.max(1, durationTicks);
        this.x = e.posX;
        this.y = e.posY;
        this.z = e.posZ;
    }


    @Override
    public void fromBytes(io.netty.buffer.ByteBuf buf) {
        entityId = buf.readInt();
        soundKey = net.minecraftforge.fml.common.network.ByteBufUtils.readUTF8String(buf);
        vol = buf.readFloat();
        pitch = buf.readFloat();
        duration = buf.readInt();
        x = buf.readDouble();
        y = buf.readDouble();
        z = buf.readDouble();
    }

    @Override
    public void toBytes(io.netty.buffer.ByteBuf buf) {
        buf.writeInt(entityId);
        net.minecraftforge.fml.common.network.ByteBufUtils.writeUTF8String(buf, soundKey);
        buf.writeFloat(vol);
        buf.writeFloat(pitch);
        buf.writeInt(duration);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
    }

    public static class Handler implements IMessageHandler<MsgPlayTravellerVoice, IMessage> {
        private static final Logger LOGGER = LogManager.getLogger("TravellerSound");

        @Override
        public IMessage onMessage(MsgPlayTravellerVoice msg, MessageContext ctx) {
            Minecraft mc = Minecraft.getMinecraft();
            mc.addScheduledTask(() -> {
                World w = mc.world;
                if (w == null) return;
                SoundEvent evt = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation(msg.soundKey));
                if (evt == null) return;
                Entity e = w.getEntityByID(msg.entityId);
                if (e instanceof EntityTraveller) {
                    boolean isChoir = msg.soundKey.contains(".choir.");
                    TravellerVoiceClientBus.play((EntityTraveller) e, evt, msg.vol, msg.pitch, isChoir);
                } else {
                    // Entity already removed client-side by the time this arrived (e.g. a death
                    // sound whose speaker got cleaned up first) — play a plain one-shot at the
                    // last known position. Unlike TravellerVoiceClientBus.play(), this is a fire-
                    // and-forget vanilla sound with no per-tick correction afterward, so it must
                    // apply the same distance falloff itself, up front, instead of relying on
                    // engine/SoundPhysics attenuation alone — otherwise a mod-side one-time volume
                    // multiplier (e.g. SoundPhysics's) has nothing to correct it against later and
                    // it can play far louder than intended for however far away the player is.
                    EntityPlayer player = mc.player;
                    double maxDist = TravellersConfig.SOUND.voiceMaxDistance;
                    if (player != null) {
                        float dist = (float) player.getDistance(msg.x, msg.y, msg.z);
                        if (TravellersConfig.SOUND.soundDebugLog) {
                            LOGGER.info("[SoundBus] FALLBACK (entity gone) event={} dist={}", msg.soundKey, (int) dist);
                        }
                        if (dist > maxDist) return;
                        float gain = EntityLinkedSound.computeDistanceGain(dist);
                        if (gain <= 0.0f) return;
                        w.playSound(null, msg.x, msg.y, msg.z, evt, SoundCategory.NEUTRAL, msg.vol * gain, msg.pitch);
                    } else {
                        w.playSound(null, msg.x, msg.y, msg.z, evt, SoundCategory.NEUTRAL, msg.vol, msg.pitch);
                    }
                }
            });

            return null;
        }
    }

}
