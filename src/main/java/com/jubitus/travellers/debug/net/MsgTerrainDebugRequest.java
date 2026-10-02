package com.jubitus.travellers.debug.net;

import com.jubitus.travellers.net.TravellerFollowNet;
import com.jubitus.travellers.pathing.terrain.TerrainProfileImageExport;
import com.jubitus.travellers.pathing.terrain.TerrainWorldState;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Debug: client asks the server for a block of scanned terrain-grid cells to draw as an overlay on
 * {@code GuiTravellerPopulationMap}. Reply is {@link MsgTerrainDebugMap}.
 */
public class MsgTerrainDebugRequest implements IMessage {

    public static final int MAX_CELLS = 200;

    public int minCellX;
    public int minCellZ;
    public int wCells;
    public int hCells;
    public byte mode;

    public MsgTerrainDebugRequest() {
    }

    public MsgTerrainDebugRequest(int minCellX, int minCellZ, int wCells, int hCells, byte mode) {
        this.minCellX = minCellX;
        this.minCellZ = minCellZ;
        this.wCells = wCells;
        this.hCells = hCells;
        this.mode = mode;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        minCellX = buf.readInt();
        minCellZ = buf.readInt();
        wCells = buf.readInt();
        hCells = buf.readInt();
        mode = buf.readByte();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(minCellX);
        buf.writeInt(minCellZ);
        buf.writeInt(wCells);
        buf.writeInt(hCells);
        buf.writeByte(mode);
    }

    public static class Handler implements IMessageHandler<MsgTerrainDebugRequest, IMessage> {
        @Override
        public IMessage onMessage(MsgTerrainDebugRequest msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            WorldServer world = player.getServerWorld();
            world.addScheduledTask(() -> {
                TerrainWorldState state = TerrainWorldState.get(world);
                if (state == null) return;
                TerrainProfileImageExport.Mode[] modes = TerrainProfileImageExport.Mode.values();
                if (msg.mode < 0 || msg.mode >= modes.length) return;

                int w = Math.max(1, Math.min(MAX_CELLS, msg.wCells));
                int h = Math.max(1, Math.min(MAX_CELLS, msg.hCells));
                int[] argb = TerrainProfileImageExport.sampleArea(
                        state.getGrid(), modes[msg.mode], msg.minCellX, msg.minCellZ, w, h);

                TravellerFollowNet.CHANNEL.sendTo(
                        new MsgTerrainDebugMap(msg.minCellX, msg.minCellZ, w, h, msg.mode, argb), player);
            });
            return null;
        }
    }
}
