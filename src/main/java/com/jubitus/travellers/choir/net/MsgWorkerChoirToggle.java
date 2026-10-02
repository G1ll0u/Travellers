package com.jubitus.travellers.choir.net;

import com.jubitus.travellers.choir.ChoirCoordinator;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class MsgWorkerChoirToggle implements IMessage {
    public int workerEntityId;

    public MsgWorkerChoirToggle() {}

    public MsgWorkerChoirToggle(int workerEntityId) {
        this.workerEntityId = workerEntityId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        workerEntityId = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(workerEntityId);
    }

    public static class Handler implements IMessageHandler<MsgWorkerChoirToggle, IMessage> {
        @Override
        public IMessage onMessage(MsgWorkerChoirToggle msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                World world = player.world;
                Entity e = world.getEntityByID(msg.workerEntityId);
                if (!(e instanceof EntityTravellerWorker)) return;
                EntityTravellerWorker worker = (EntityTravellerWorker) e;
                if (!player.getUniqueID().equals(worker.getFollowOwnerId())) return;
                ChoirCoordinator.get(world).handleWorkerChoirToggle(world, worker, player);
            });
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgWorkerChoirToggle, IMessage> {
        @Override
        public IMessage onMessage(MsgWorkerChoirToggle msg, MessageContext ctx) {
            return null;
        }
    }
}
