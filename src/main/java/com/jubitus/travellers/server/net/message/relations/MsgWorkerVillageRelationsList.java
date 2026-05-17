package com.jubitus.travellers.server.net.message.relations;

import com.jubitus.travellers.client.gui.bahavior.relations.GuiWorkerVillageRelations;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.*;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;
import java.util.List;

public class MsgWorkerVillageRelationsList implements IMessage {

    public int workerId;
    public List<BlockPos> centers;
    public List<String> labels;
    public List<Integer> distances;
    public List<Boolean> enemies;

    public MsgWorkerVillageRelationsList() {}

    public MsgWorkerVillageRelationsList(int workerId,
                                         List<BlockPos> centers,
                                         List<String> labels,
                                         List<Integer> distances,
                                         List<Boolean> enemies) {
        this.workerId = workerId;
        this.centers = centers;
        this.labels = labels;
        this.distances = distances;
        this.enemies = enemies;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        workerId = buf.readInt();
        int count = buf.readInt();

        centers = new ArrayList<>(count);
        labels = new ArrayList<>(count);
        distances = new ArrayList<>(count);
        enemies = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            BlockPos c = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
            String label = ByteBufUtils.readUTF8String(buf);
            int dist = buf.readInt();
            boolean enemy = buf.readBoolean();

            centers.add(c);
            labels.add(label);
            distances.add(dist);
            enemies.add(enemy);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(workerId);
        int count = centers == null ? 0 : centers.size();
        buf.writeInt(count);

        for (int i = 0; i < count; i++) {
            BlockPos c = centers.get(i);
            buf.writeInt(c.getX());
            buf.writeInt(c.getY());
            buf.writeInt(c.getZ());
            ByteBufUtils.writeUTF8String(buf, labels.get(i));
            buf.writeInt(distances.get(i));
            buf.writeBoolean(enemies.get(i));
        }
    }

    public static class Handler implements IMessageHandler<MsgWorkerVillageRelationsList, IMessage> {
        @Override
        public IMessage onMessage(MsgWorkerVillageRelationsList msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> handle(msg));
            return null;
        }

        @SideOnly(Side.CLIENT)
        private void handle(MsgWorkerVillageRelationsList msg) {
            Minecraft mc = Minecraft.getMinecraft();
            World world = mc.world;
            if (world == null) return;

            Entity e = world.getEntityByID(msg.workerId);
            if (!(e instanceof EntityTravellerWorker)) return;

            GuiScreen screen = mc.currentScreen;
            if (screen instanceof GuiWorkerVillageRelations) {
                ((GuiWorkerVillageRelations) screen).receiveVillageList(
                        msg.centers, msg.labels, msg.distances, msg.enemies
                );
            }
        }
    }
    public static class NoopHandler implements IMessageHandler<MsgWorkerVillageRelationsList, IMessage> {
        @Override
        public IMessage onMessage(MsgWorkerVillageRelationsList message, MessageContext ctx) {
            return null;
        }
    }
}