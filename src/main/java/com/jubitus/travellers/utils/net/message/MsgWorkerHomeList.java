package com.jubitus.travellers.utils.net.message;

import com.jubitus.travellers.client.gui.GuiWorkerHomeList;
import com.jubitus.travellers.entity.entityAI.travel.travelcommon.TravellerHomeManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.List;

public class MsgWorkerHomeList implements IMessage {
    private int workerId;
    private final List<BlockPos> pos = new ArrayList<>();
    private final List<String> names = new ArrayList<>();

    public MsgWorkerHomeList() {
    }

    public MsgWorkerHomeList(int workerId, List<TravellerHomeManager.HomeChest> homes) {
        this.workerId = workerId;
        for (TravellerHomeManager.HomeChest h : homes) {
            pos.add(h.pos);
            names.add(h.name == null ? "" : h.name);
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        workerId = buf.readInt();
        int n = buf.readUnsignedShort();
        pos.clear();
        names.clear();
        for (int i = 0; i < n; i++) {
            pos.add(BlockPos.fromLong(buf.readLong()));
            int len = buf.readUnsignedShort();
            String s = buf.readCharSequence(len, java.nio.charset.StandardCharsets.UTF_8).toString();
            names.add(s);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(workerId);
        buf.writeShort(pos.size());
        for (int i = 0; i < pos.size(); i++) {
            buf.writeLong(pos.get(i).toLong());
            byte[] b = names.get(i).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            buf.writeShort(b.length);
            buf.writeBytes(b);
        }
    }

    public static class ClientHandler implements IMessageHandler<MsgWorkerHomeList, IMessage> {
        @Override
        public IMessage onMessage(MsgWorkerHomeList msg, MessageContext ctx) {
            net.minecraft.client.Minecraft.getMinecraft().addScheduledTask(() -> {
                if (net.minecraft.client.Minecraft.getMinecraft().currentScreen instanceof GuiWorkerHomeList) {
                    GuiWorkerHomeList gui = (GuiWorkerHomeList) net.minecraft.client.Minecraft.getMinecraft().currentScreen;
                    gui.receiveHomes(msg.pos, msg.names);
                }
            });
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgWorkerHomeList, IMessage> {
        @Override
        public IMessage onMessage(MsgWorkerHomeList msg, MessageContext ctx) {
            return null;
        }
    }


}
