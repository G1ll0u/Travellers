package com.jubitus.travellers.utils.net.message;

import com.jubitus.travellers.entity.entityAI.travel.travelcommon.TravellerHomeManager;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.net.TravellerFollowNet;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.List;

public class MsgWorkerRequestHomeList implements IMessage {
    private int workerId;

    public MsgWorkerRequestHomeList() {
    }

    public MsgWorkerRequestHomeList(int workerId) {
        this.workerId = workerId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        workerId = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(workerId);
    }

    public static class Handler implements IMessageHandler<MsgWorkerRequestHomeList, IMessage> {
        @Override
        public IMessage onMessage(MsgWorkerRequestHomeList msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;

            player.getServerWorld().addScheduledTask(() -> {
                Entity e = player.world.getEntityByID(msg.workerId);
                if (!(e instanceof EntityTravellerWorker)) return;
                EntityTravellerWorker w = (EntityTravellerWorker) e;

                // security: only owner can query homes for this worker
                if (!w.isHiredFollower() || w.getFollowOwnerId() == null ||
                        !w.getFollowOwnerId().equals(player.getUniqueID())) {
                    return;
                }

                TravellerHomeManager mgr = TravellerHomeManager.get(player.world);
                List<TravellerHomeManager.HomeChest> homes = mgr.getHomesOfPlayerFull(player);

                TravellerFollowNet.CHANNEL.sendTo(new MsgWorkerHomeList(w.getEntityId(), homes), player);
            });

            return null;
        }
    }
}
