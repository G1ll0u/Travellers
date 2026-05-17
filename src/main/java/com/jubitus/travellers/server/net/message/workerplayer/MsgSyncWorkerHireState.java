package com.jubitus.travellers.server.net.message.workerplayer;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.UUID;

public class MsgSyncWorkerHireState implements IMessage {

    private int entityId;
    private boolean hired;
    private UUID ownerId;
    private boolean followOrderActive;

    public MsgSyncWorkerHireState() {
    }

    public MsgSyncWorkerHireState(EntityTravellerWorker worker) {
        this.entityId = worker.getEntityId();
        this.hired = worker.isHiredFollower();
        this.ownerId = worker.getFollowOwnerId();
        this.followOrderActive = worker.isFollowOrderActive();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.entityId = buf.readInt();
        this.hired = buf.readBoolean();

        boolean hasOwner = buf.readBoolean();
        if (hasOwner) {
            long msb = buf.readLong();
            long lsb = buf.readLong();
            this.ownerId = new UUID(msb, lsb);
        } else {
            this.ownerId = null;
        }

        this.followOrderActive = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(entityId);
        buf.writeBoolean(hired);

        if (ownerId != null) {
            buf.writeBoolean(true);
            buf.writeLong(ownerId.getMostSignificantBits());
            buf.writeLong(ownerId.getLeastSignificantBits());
        } else {
            buf.writeBoolean(false);
        }

        buf.writeBoolean(followOrderActive);
    }

    public static class Handler implements IMessageHandler<MsgSyncWorkerHireState, IMessage> {
        @Override
        public IMessage onMessage(MsgSyncWorkerHireState msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                World world = Minecraft.getMinecraft().world;
                if (world == null) return;

                Entity e = world.getEntityByID(msg.entityId);
                if (!(e instanceof EntityTravellerWorker)) return;

                EntityTravellerWorker w = (EntityTravellerWorker) e;

                // Update the client-side fields so your GUI sees the right state
                if (msg.hired && msg.ownerId != null) {
                    w.followOwnerId = msg.ownerId;
                    w.hiredFollower = true;
                } else {
                    w.followOwnerId = null;
                    w.hiredFollower = false;
                }

                w.setFollowOrderActive(msg.followOrderActive);
            });
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgSyncWorkerHireState, IMessage> {
        @Override
        public IMessage onMessage(MsgSyncWorkerHireState msg, MessageContext ctx) {
            return null;
        }
    }

}
