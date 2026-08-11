package com.jubitus.travellers.server.net.message.map;

import com.jubitus.travellers.server.map.stream.WorkerMapStream;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class MsgWorkerMapView implements IMessage {
    public int dim;
    public int centerX;
    public int centerZ;
    public int rangeBlocks;

    public MsgWorkerMapView() {}

    public MsgWorkerMapView(int dim, int centerX, int centerZ, int rangeBlocks) {
        this.dim = dim;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.rangeBlocks = rangeBlocks;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dim = buf.readInt();
        centerX = buf.readInt();
        centerZ = buf.readInt();
        rangeBlocks = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dim);
        buf.writeInt(centerX);
        buf.writeInt(centerZ);
        buf.writeInt(rangeBlocks);
    }

    public static class Handler implements IMessageHandler<MsgWorkerMapView, IMessage> {
        @Override
        public IMessage onMessage(MsgWorkerMapView msg, MessageContext ctx) {
            EntityPlayerMP p = ctx.getServerHandler().player;
            p.getServerWorld().addScheduledTask(() -> {
                WorkerMapStream.updateView(p.getUniqueID(), msg.dim, msg.centerX, msg.centerZ, msg.rangeBlocks);
            });
            return null;
        }
    }
}