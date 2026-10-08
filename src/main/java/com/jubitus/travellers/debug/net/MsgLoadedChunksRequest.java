package com.jubitus.travellers.debug.net;

import com.jubitus.travellers.ground.chunk.LoadedChunks;
import com.jubitus.travellers.net.TravellersNetwork;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Debug: client asks which chunks of a rectangle the server has loaded, to highlight them on
 * {@code GuiTravellerPopulationMap}. Reply is {@link MsgLoadedChunksMap}.
 */
public class MsgLoadedChunksRequest implements IMessage {

    public static final int MAX_CHUNKS = 256;

    public int minChunkX;
    public int minChunkZ;
    public int wChunks;
    public int hChunks;

    public MsgLoadedChunksRequest() {
    }

    public MsgLoadedChunksRequest(int minChunkX, int minChunkZ, int wChunks, int hChunks) {
        this.minChunkX = minChunkX;
        this.minChunkZ = minChunkZ;
        this.wChunks = wChunks;
        this.hChunks = hChunks;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        minChunkX = buf.readInt();
        minChunkZ = buf.readInt();
        wChunks = buf.readInt();
        hChunks = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(minChunkX);
        buf.writeInt(minChunkZ);
        buf.writeInt(wChunks);
        buf.writeInt(hChunks);
    }

    public static class Handler implements IMessageHandler<MsgLoadedChunksRequest, IMessage> {
        @Override
        public IMessage onMessage(MsgLoadedChunksRequest msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            WorldServer world = player.getServerWorld();
            world.addScheduledTask(() -> {
                int w = Math.max(1, Math.min(MAX_CHUNKS, msg.wChunks));
                int h = Math.max(1, Math.min(MAX_CHUNKS, msg.hChunks));
                byte[] bits = new byte[(w * h + 7) / 8];
                for (int z = 0; z < h; z++) {
                    for (int x = 0; x < w; x++) {
                        if (LoadedChunks.isChunkLoaded(world, msg.minChunkX + x, msg.minChunkZ + z)) {
                            int i = z * w + x;
                            bits[i >> 3] |= (byte) (1 << (i & 7));
                        }
                    }
                }
                TravellersNetwork.CHANNEL.sendTo(new MsgLoadedChunksMap(msg.minChunkX, msg.minChunkZ, w, h, bits), player);
            });
            return null;
        }
    }
}
