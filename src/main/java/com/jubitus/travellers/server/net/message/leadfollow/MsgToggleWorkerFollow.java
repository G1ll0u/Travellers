package com.jubitus.travellers.server.net.message.leadfollow;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.workerplayer.MsgSyncWorkerHireState;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

// MsgToggleWorkerFollow
public class MsgToggleWorkerFollow implements IMessage {
    private int entityId;

    public MsgToggleWorkerFollow() {
    }

    public MsgToggleWorkerFollow(int entityId) {
        this.entityId = entityId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.entityId = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(entityId);
    }

    public static class Handler implements IMessageHandler<MsgToggleWorkerFollow, IMessage> {
        @Override
        public IMessage onMessage(MsgToggleWorkerFollow msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                World world = player.world;
                Entity e = world.getEntityByID(msg.entityId);
                if (!(e instanceof EntityTravellerWorker)) return;

                EntityTravellerWorker w = (EntityTravellerWorker) e;

                // only hiring player may toggle
                if (!w.isHiredFollower()) return;
                if (!player.getUniqueID().equals(w.getFollowOwnerId())) return;

                // Toggle on server
                w.setFollowOrderActive(!w.isFollowOrderActive());

                // 🔁 Sync new state back to this client so GUI updates
                TravellerFollowNet.CHANNEL.sendTo(
                        new MsgSyncWorkerHireState(w),
                        player
                );
            });
            return null;
        }
    }


}
