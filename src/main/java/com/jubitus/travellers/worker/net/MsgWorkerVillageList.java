package com.jubitus.travellers.worker.net;

import com.jubitus.travellers.worker.gui.GuiWorker;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;
import java.util.List;

// NEW CLASS
public class MsgWorkerVillageList implements IMessage {

    private int workerId;
    private List<BlockPos> centers;
    private List<String> labels;
    private List<Integer> distances;

    public MsgWorkerVillageList() {
        // needed for Forge
    }

    public MsgWorkerVillageList(int workerId,
                                List<BlockPos> centers,
                                List<String> labels,
                                List<Integer> distances) {
        this.workerId = workerId;
        this.centers = centers;
        this.labels = labels;
        this.distances = distances;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.workerId = buf.readInt();
        int count = buf.readInt();

        this.centers = new ArrayList<>(count);
        this.labels = new ArrayList<>(count);
        this.distances = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            int x = buf.readInt();
            int y = buf.readInt();
            int z = buf.readInt();
            BlockPos center = new BlockPos(x, y, z);
            String label = ByteBufUtils.readUTF8String(buf);
            int dist = buf.readInt();

            centers.add(center);
            labels.add(label);
            distances.add(dist);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(workerId);
        int count = (centers != null) ? centers.size() : 0;
        buf.writeInt(count);

        for (int i = 0; i < count; i++) {
            BlockPos center = centers.get(i);
            String label = labels.get(i);
            int dist = distances.get(i);

            buf.writeInt(center.getX());
            buf.writeInt(center.getY());
            buf.writeInt(center.getZ());
            ByteBufUtils.writeUTF8String(buf, label);
            buf.writeInt(dist);
        }
    }

    // ---------- CLIENT HANDLER ----------
    public static class Handler implements IMessageHandler<MsgWorkerVillageList, IMessage> {

        @Override
        public IMessage onMessage(MsgWorkerVillageList msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> handle(msg));
            return null;
        }

        @SideOnly(Side.CLIENT)
        private void handle(MsgWorkerVillageList msg) {
            Minecraft mc = Minecraft.getMinecraft();
            World world = mc.world;
            if (world == null) return;

            Entity e = world.getEntityByID(msg.workerId);
            if (!(e instanceof EntityTravellerWorker)) {

                return;
            }

            GuiScreen screen = mc.currentScreen;
            if (screen instanceof GuiWorker) {
                ((GuiWorker) screen).receiveVillageList(msg.centers, msg.labels, msg.distances);
            }
        }
    }

}

