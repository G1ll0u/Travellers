package com.jubitus.travellers.server.net.message.chunks;

import com.jubitus.travellers.client.gui.debug.GuiTravellerChunks;
import com.jubitus.travellers.debug.TravellerChunkSnapshot;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.List;

public class PacketTravellerChunks implements IMessage {

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

        int virtualCount = buf.readInt();
        List<BlockPos> virtualWorkers = new ArrayList<>(virtualCount);
        for (int i = 0; i < virtualCount; i++) {
            virtualWorkers.add(new BlockPos(buf.readInt(), buf.readInt(), buf.readInt()));
        }

        int travCount = buf.readInt();
        List<BlockPos> virtualTravellers = new ArrayList<>(travCount);
        for (int i = 0; i < travCount; i++) {
            virtualTravellers.add(new BlockPos(buf.readInt(), buf.readInt(), buf.readInt()));
        }

        int raiderCount = buf.readInt();
        List<BlockPos> virtualRaiders = new ArrayList<>(raiderCount);
        for (int i = 0; i < raiderCount; i++) {
            virtualRaiders.add(new BlockPos(buf.readInt(), buf.readInt(), buf.readInt()));
        }

        this.snapshot = new TravellerChunkSnapshot(
                centerChunkX,
                centerChunkZ,
                radiusBlocks,
                radiusChunks,
                normal,
                villages,
                virtualWorkers,
                virtualTravellers,
                virtualRaiders
        );
    }

    @Override
    public void toBytes(io.netty.buffer.ByteBuf buf) {
        buf.writeInt(snapshot.centerX);
        buf.writeInt(snapshot.centerZ);
        buf.writeInt(snapshot.radiusBlocks);
        buf.writeInt(snapshot.radiusChunks);

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

        buf.writeInt(snapshot.virtualWorkers.size());
        for (BlockPos bp : snapshot.virtualWorkers) {
            buf.writeInt(bp.getX()); buf.writeInt(bp.getY()); buf.writeInt(bp.getZ());
        }

        buf.writeInt(snapshot.virtualTravellers.size());
        for (BlockPos bp : snapshot.virtualTravellers) {
            buf.writeInt(bp.getX()); buf.writeInt(bp.getY()); buf.writeInt(bp.getZ());
        }

        buf.writeInt(snapshot.virtualRaiders.size());
        for (BlockPos bp : snapshot.virtualRaiders) {
            buf.writeInt(bp.getX()); buf.writeInt(bp.getY()); buf.writeInt(bp.getZ());
        }
    }

    // Handler
    public static class Handler implements IMessageHandler<PacketTravellerChunks, IMessage> {
        @Override
        public IMessage onMessage(PacketTravellerChunks message, MessageContext ctx) {
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
        @Override
        public IMessage onMessage(PacketTravellerChunks msg, MessageContext ctx) {
            return null;
        }
    }
}
