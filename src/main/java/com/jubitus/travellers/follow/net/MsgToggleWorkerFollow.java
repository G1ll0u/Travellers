package com.jubitus.travellers.follow.net;

import com.jubitus.travellers.unit.WorkerLookup;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.worker.net.MsgSyncWorkerHireState;
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
                EntityTravellerWorker w = WorkerLookup.ownedById(player, msg.entityId);
                if (w == null) return;

                // Toggle on server
                w.setFollowOrderActive(!w.isFollowOrderActive());

                // 🔁 Sync new state back to this client so GUI updates
                TravellersNetwork.CHANNEL.sendTo(
                        new MsgSyncWorkerHireState(w, player),
                        player
                );
            });
            return null;
        }
    }


}
