package com.jubitus.travellers.server.net.message.group;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.server.group.WorkerGroupService;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

// MsgMakeWorkerLeader
public class MsgMakeWorkerLeader implements IMessage {
    private int entityId;

    public MsgMakeWorkerLeader() {
    }

    public MsgMakeWorkerLeader(int entityId) {
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

    public static class Handler implements IMessageHandler<MsgMakeWorkerLeader, IMessage> {
        @Override
        public IMessage onMessage(MsgMakeWorkerLeader msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                World world = player.world;
                Entity e = world.getEntityByID(msg.entityId);
                if (!(e instanceof EntityTravellerWorker)) return;

                EntityTravellerWorker leader = (EntityTravellerWorker) e;
                if (!leader.isHiredFollower()) return;
                if (!player.getUniqueID().equals(leader.getFollowOwnerId())) return;

                WorkerGroupService.makeLeader(player, leader.getUniqueID());
            });
            return null;
        }
    }

}

