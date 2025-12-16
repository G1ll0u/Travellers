package com.jubitus.travellers.utils.net.packet;

import com.jubitus.travellers.client.gui.GuiTravellerChunks;
import com.jubitus.travellers.utils.debug.TravellerChunkSnapshot;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;
import java.util.List;

public class PacketTravellerChunks implements net.minecraftforge.fml.common.network.simpleimpl.IMessage {

    private TravellerChunkSnapshot snapshot;

    public PacketTravellerChunks() {
    }

    public PacketTravellerChunks(TravellerChunkSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public TravellerChunkSnapshot getSnapshot() {
        return snapshot;
    }

    @Override
    public void fromBytes(io.netty.buffer.ByteBuf buf) {
        int centerChunkX = buf.readInt();
        int centerChunkZ = buf.readInt();
        int radiusBlocks = buf.readInt();
        int radiusChunks = buf.readInt();

        int merchantCount = buf.readInt();
        List<ChunkPos> merchant = new ArrayList<>(merchantCount);
        for (int i = 0; i < merchantCount; i++) {
            int x = buf.readInt();
            int z = buf.readInt();
            merchant.add(new ChunkPos(x, z));
        }

        int normalCount = buf.readInt();
        List<ChunkPos> normal = new ArrayList<>(normalCount);
        for (int i = 0; i < normalCount; i++) {
            int x = buf.readInt();
            int z = buf.readInt();
            normal.add(new ChunkPos(x, z));
        }

        int villageCount = buf.readInt();
        List<BlockPos> villages = new ArrayList<>(villageCount);
        for (int i = 0; i < villageCount; i++) {
            int x = buf.readInt();
            int y = buf.readInt();
            int z = buf.readInt();
            villages.add(new BlockPos(x, y, z));
        }

        this.snapshot = new TravellerChunkSnapshot(
                centerChunkX,
                centerChunkZ,
                radiusBlocks,
                radiusChunks,
                merchant,
                normal,
                villages
        );
    }

    @Override
    public void toBytes(io.netty.buffer.ByteBuf buf) {
        buf.writeInt(snapshot.centerX);
        buf.writeInt(snapshot.centerZ);
        buf.writeInt(snapshot.radiusBlocks);
        buf.writeInt(snapshot.radiusChunks);

        buf.writeInt(snapshot.merchantChunks.size());
        for (ChunkPos cp : snapshot.merchantChunks) {
            buf.writeInt(cp.x);
            buf.writeInt(cp.z);
        }

        buf.writeInt(snapshot.normalChunks.size());
        for (ChunkPos cp : snapshot.normalChunks) {
            buf.writeInt(cp.x);
            buf.writeInt(cp.z);
        }

        buf.writeInt(snapshot.villages.size());
        for (BlockPos bp : snapshot.villages) {
            buf.writeInt(bp.getX());
            buf.writeInt(bp.getY());
            buf.writeInt(bp.getZ());
        }
    }

    // Handler
    public static class Handler implements net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler<PacketTravellerChunks, IMessage> {
        @Override
        public IMessage onMessage(PacketTravellerChunks message, net.minecraftforge.fml.common.network.simpleimpl.MessageContext ctx) {
            net.minecraft.client.Minecraft.getMinecraft().addScheduledTask(() -> {
                TravellerChunkSnapshot snapshot = message.getSnapshot();
                net.minecraft.client.Minecraft.getMinecraft().displayGuiScreen(
                        new GuiTravellerChunks(snapshot)
                );
            });
            return null;
        }
    }
    public static class NoopHandler implements IMessageHandler<PacketTravellerChunks, IMessage> {
        @Override public IMessage onMessage(PacketTravellerChunks msg, MessageContext ctx) { return null; }
    }
}
