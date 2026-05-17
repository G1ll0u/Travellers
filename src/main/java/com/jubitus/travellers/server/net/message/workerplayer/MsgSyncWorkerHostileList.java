package com.jubitus.travellers.server.net.message.workerplayer;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class MsgSyncWorkerHostileList implements IMessage {
    private int workerId;
    private java.util.List<String> names = new java.util.ArrayList<>();

    public MsgSyncWorkerHostileList() {
    }

    public MsgSyncWorkerHostileList(int workerId, java.util.List<String> names) {
        this.workerId = workerId;
        this.names = names;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        workerId = buf.readInt();
        int n = buf.readInt();
        names = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            names.add(ByteBufUtils.readUTF8String(buf));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(workerId);
        buf.writeInt(names.size());
        for (String s : names) {
            ByteBufUtils.writeUTF8String(buf, s);
        }
    }

    // CLIENT handler
    public static class Handler implements IMessageHandler<MsgSyncWorkerHostileList, IMessage> {
        @Override
        public IMessage onMessage(MsgSyncWorkerHostileList msg, MessageContext ctx) {
            net.minecraft.client.Minecraft.getMinecraft().addScheduledTask(() -> {
                World w = net.minecraft.client.Minecraft.getMinecraft().world;
                if (w == null) return;
                Entity e = w.getEntityByID(msg.workerId);
                if (!(e instanceof EntityTravellerWorker)) return;
                EntityTravellerWorker worker = (EntityTravellerWorker) e;
                worker.setHostileNamesClient(msg.names);
            });
            return null;
        }
    }

    // SERVER noop
    public static class NoopHandler implements IMessageHandler<MsgSyncWorkerHostileList, IMessage> {
        @Override
        public IMessage onMessage(MsgSyncWorkerHostileList msg, MessageContext ctx) {
            return null;
        }
    }
}
