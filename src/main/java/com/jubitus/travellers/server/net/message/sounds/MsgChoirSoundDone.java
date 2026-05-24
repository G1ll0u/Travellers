package com.jubitus.travellers.server.net.message.sounds;

import com.jubitus.travellers.entity.travellers.EntityTraveller;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class MsgChoirSoundDone implements IMessage {
    public int entityId;

    public MsgChoirSoundDone() {}

    public MsgChoirSoundDone(int entityId) {
        this.entityId = entityId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        entityId = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(entityId);
    }

    public static class Handler implements IMessageHandler<MsgChoirSoundDone, IMessage> {
        @Override
        public IMessage onMessage(MsgChoirSoundDone msg, MessageContext ctx) {
            ctx.getServerHandler().player.getServerWorld().addScheduledTask(() -> {
                WorldServer world = FMLCommonHandler.instance().getMinecraftServerInstance()
                        .getWorld(ctx.getServerHandler().player.dimension);
                Entity e = world.getEntityByID(msg.entityId);
                if (e instanceof EntityTraveller) {
                    ((EntityTraveller) e).interruptChoirVoiceLock();
                }
            });
            return null;
        }
    }
}
