package com.jubitus.travellers.server.net.message.zone;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class MsgSyncWorkerHome implements IMessage {
    private int workerId;
    private boolean hasHome;
    private BlockPos pos;
    private int dim;
    private String name;

    public MsgSyncWorkerHome() {
    }

    public MsgSyncWorkerHome(EntityTravellerWorker w) {
        this.workerId = w.getEntityId();
        this.hasHome = w.getAssignedHomePos() != null;
        this.pos = w.getAssignedHomePos();
        this.dim = w.getAssignedHomeDim();
        this.name = w.getAssignedHomeName();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        workerId = buf.readInt();
        hasHome = buf.readBoolean();
        pos = hasHome ? BlockPos.fromLong(buf.readLong()) : null;
        dim = buf.readInt();
        int len = buf.readUnsignedShort();
        name = buf.readCharSequence(len, java.nio.charset.StandardCharsets.UTF_8).toString();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(workerId);
        buf.writeBoolean(hasHome);
        if (hasHome) {
            buf.writeLong(pos.toLong());
        }
        buf.writeInt(dim);
        byte[] b = (name == null ? "" : name).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buf.writeShort(b.length);
        buf.writeBytes(b);
    }

    public static class ClientHandler implements IMessageHandler<MsgSyncWorkerHome, IMessage> {
        @Override
        public IMessage onMessage(MsgSyncWorkerHome msg, MessageContext ctx) {
            net.minecraft.client.Minecraft.getMinecraft().addScheduledTask(() -> {
                World w = net.minecraft.client.Minecraft.getMinecraft().world;
                if (w == null) return;

                Entity e = w.getEntityByID(msg.workerId);
                if (e instanceof EntityTravellerWorker) {
                    ((EntityTravellerWorker) e).setClientHome(msg.hasHome ? msg.pos : null, msg.dim, msg.name);
                }
            });
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgSyncWorkerHome, IMessage> {
        @Override
        public IMessage onMessage(MsgSyncWorkerHome msg, MessageContext ctx) {
            return null;
        }
    }
}